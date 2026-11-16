package org.cardboardpowered.smoke;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Real dialog screen -> native button action -> connection packet -> plugin callback. */
final class DialogClientSmoke {
    private static State active;
    private static Object submittedScreen;
    private static Object observedScreen;
    private static int observedFrames;
    private static final String TITLE = "p263-dialog";
    private DialogClientSmoke() {}

    static void begin(MinecraftServer server, UUID actor) {
        require(active == null, "Dialog state survived previous world");
        active = new State(server, actor);
    }

    static boolean finish(Minecraft client) {
        var state = active;
        if (state == null) return true;
        require(System.nanoTime() < state.deadline, "Dialog client timeout: " + state.snapshot());
        if (!state.pending.isDone()) return false;
        state.pending.join();
        if (state.phase == 0) {
            state.pending = CompletableFuture.runAsync(state::startRound, state.server);
            state.phase = 1;
            return false;
        }
        var values = state.snapshot();
        require(!values.containsKey("failure"), "Dialog plugin failure: " + values);
        if (state.phase == 1) {
            if (clickVisible(client)) state.submissions++;
            if (!state.chatSent) state.chatSent = clickDecodedChat(client, values.get("chatLabel"));
            if (!"3".equals(values.get("events")) || !"1".equals(values.get("chatCalls"))) return false;
            require("2".equals(values.get("calls")) && state.submissions == 3, "Use count/UI submission mismatch: " + values);
            // A replay from the real client must still be an event, but must not run
            // the exhausted callback. The payload deliberately has no form inputs.
            client.player.connection.send(new ServerboundCustomClickActionPacket(Identifier.parse(values.get("dialogKey")),
                    Optional.of(parse(values.get("dialogPayload")))));
            state.phase = 2;
            return false;
        }
        if (state.phase == 2) {
            if (!"4".equals(values.get("events"))) return false;
            require("2".equals(values.get("calls")), "Exhausted callback accepted a replay");
            if (state.round == 1) {
                state.submissions = 0; state.chatSent = false; submittedScreen = null;
                state.pending = CompletableFuture.supplyAsync(() -> {
                    state.call("closeDialogs");
                    return state.server.reloadResources(state.server.getPackRepository().getSelectedIds());
                }, state.server).thenCompose(future -> future).thenRunAsync(state::startRound, state.server);
                state.phase = 1;
            } else {
                state.pending = CompletableFuture.runAsync(() -> {
                    state.call("closeDialogs"); state.call("leaveDialogCallbacks");
                }, state.server);
                state.phase = 3;
            }
            return false;
        }
        if (client.gui.screen() != null && client.gui.screen().getTitle().getString().equals(TITLE)) return false;
        active = null; submittedScreen = null; observedScreen = null;
        System.out.println("DIALOG_CLIENT_UI_PASS actor=" + state.actor + " rounds=2 submissions=6 callbacks=4 chat=2 replay=ignored registry=fresh");
        return true;
    }

    /** Edit and submit the real native screen; no synthetic dialog response here. */
    static boolean clickVisible(Minecraft client) {
        return clickVisible(client, TITLE);
    }

    static boolean clickVisible(Minecraft client, String title) {
        if (client.gui.screen() == null || !client.gui.screen().getTitle().getString().equals(title) || client.gui.screen() == submittedScreen) return false;
        if (observedScreen != client.gui.screen()) { observedScreen = client.gui.screen(); observedFrames = 0; }
        // Let the actual dialog screen render before editing and submitting its widgets.
        if (++observedFrames < 3) return false;
        var children = new ArrayList<Object>();
        collect(client.gui.screen(), children);
        int edits = 0;
        for (Object child : children) if (child instanceof EditBox box) { box.setValue("Привет 世界"); edits++; }
        require(edits == 1, "Expected one native text input, got " + edits + " in " + children.stream().map(o -> o.getClass().getName()).toList());
        for (Object child : children) if (child instanceof AbstractButton button && button.getMessage().getString().equals("p263-submit")) {
            submittedScreen = client.gui.screen();
            press(button);
            return true;
        }
        throw new AssertionError("Native dialog submit button absent");
    }

    private static void collect(Object value, List<Object> result) {
        result.add(value);
        if (value instanceof ContainerEventHandler container) for (var child : container.children()) collect(child, result);
    }
    private static void press(AbstractButton button) {
        try {
            for (var method : button.getClass().getMethods()) if (method.getName().equals("onPress")) {
                if (method.getParameterCount() == 0) { method.invoke(button); return; }
                if (method.getParameterCount() == 1 && method.getParameterTypes()[0].isInterface()) {
                    Class<?> type = method.getParameterTypes()[0];
                    Object input = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, called, args) -> {
                        if (called.getReturnType() == boolean.class) return false;
                        if (called.getReturnType() == int.class) return 0;
                        if (called.getReturnType() == long.class) return 0L;
                        return null;
                    });
                    method.invoke(button, input); return;
                }
            }
            throw new AssertionError("Unsupported native button signature: " + java.util.Arrays.toString(button.getClass().getMethods()));
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native UI button action failed", failure); }
    }

    private static boolean clickDecodedChat(Minecraft client, String label) {
        try {
            Object chat = client.gui.hud.getChat();
            for (Field field : chat.getClass().getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                if (!(field.get(chat) instanceof List<?> messages)) continue;
                for (Object message : messages) {
                    for (var method : message.getClass().getMethods()) {
                        if (method.getParameterCount() != 0 || method.getReturnType() != Component.class) continue;
                        var text = (Component) method.invoke(message);
                        if (!text.getString().equals(label)) continue;
                        Object click = text.getStyle().getClickEvent();
                        require(click != null && click.getClass().getSimpleName().equals("Custom"), "Chat custom action metadata lost: " + click);
                        var key = (Identifier) click.getClass().getMethod("id").invoke(click);
                        @SuppressWarnings("unchecked") var payload = (Optional<net.minecraft.nbt.Tag>) click.getClass().getMethod("payload").invoke(click);
                        client.player.connection.send(new ServerboundCustomClickActionPacket(key, payload));
                        // Same decoded action twice: Adventure's default single use must hold.
                        client.player.connection.send(new ServerboundCustomClickActionPacket(key, payload));
                        return true;
                    }
                }
            }
            return false;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Decoded chat action failed", failure); }
    }

    private static net.minecraft.nbt.Tag parse(String value) {
        try { return io.papermc.paper.adventure.PaperAdventure.NBT_CODEC.decode(value); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { throw new IllegalArgumentException(failure); }
    }

    static void assertCleaned() {
        require(active == null, "Client dialog scope survived stop");
        try {
            Class<?> provider = Class.forName("io.papermc.paper.adventure.providers.ClickCallbackProviderImpl");
            for (String name : new String[] {"ADVENTURE_CLICK_MANAGER", "DIALOG_CLICK_MANAGER"}) {
                Object manager = provider.getField(name).get(null);
                for (String collection : new String[] {"callbacks", "queue"}) {
                    Field field = manager.getClass().getSuperclass().getDeclaredField(collection); field.setAccessible(true);
                    Object contents = field.get(manager);
                    require(contents instanceof Map<?, ?> map ? map.isEmpty() : ((java.util.Collection<?>) contents).isEmpty(), name + " retained " + collection);
                }
            }
            System.out.println("DIALOG_STOP_CLEANUP_PASS");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    static void prepareStop() {
        var plugin = Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
        try {
            plugin.getClass().getMethod("leaveDialogCallbacks").invoke(plugin);
            Class<?> provider = Class.forName("io.papermc.paper.adventure.providers.ClickCallbackProviderImpl");
            for (String name : new String[] {"ADVENTURE_CLICK_MANAGER", "DIALOG_CLICK_MANAGER"}) {
                Object manager = provider.getField(name).get(null);
                for (String collection : new String[] {"callbacks", "queue"}) {
                    Field field = manager.getClass().getSuperclass().getDeclaredField(collection); field.setAccessible(true);
                    Object contents = field.get(manager);
                    require(contents instanceof Map<?, ?> map ? !map.isEmpty() : !((java.util.Collection<?>) contents).isEmpty(),
                            "Stop fixture did not leave " + name + " " + collection + " populated");
                }
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void verifyCurrentRegistries(MinecraftServer server) {
        var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT,
                Identifier.parse("minecraft:sharpness"));
        var expected = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(key);
        var actual = io.papermc.paper.registry.data.util.Conversions.global().getReferenceHolder(key);
        require(actual == expected, "Global conversion context retained stale world/reload registries");
    }

    private static final class State {
        final MinecraftServer server;
        final UUID actor;
        final Plugin plugin;
        final long deadline = System.nanoTime() + 90_000_000_000L;
        CompletableFuture<?> pending;
        int phase, submissions, round;
        boolean chatSent;
        State(MinecraftServer server, UUID actor) {
            this.server = server; this.actor = actor; this.plugin = Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
            this.pending = CompletableFuture.completedFuture(null);
        }
        void startRound() {
            verifyCurrentRegistries(this.server);
            this.round++;
            call("beginDialogs", this.actor);
        }
        Object call(String method, Object... args) {
            try { return this.plugin.getClass().getMethod(method, args.length == 0 ? new Class<?>[0] : new Class<?>[] {UUID.class}).invoke(this.plugin, args); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        @SuppressWarnings("unchecked") Map<String, String> snapshot() { return (Map<String, String>) call("readDialogs"); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
