package org.cardboardpowered.test;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.set.RegistrySet;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Identical public API contracts on Cardboard and the checksum-pinned Paper control. */
public final class DialogCallbackProbes {
    private static final Component TITLE = Component.text("Dialog probe");
    private DialogCallbackProbes() {}

    public static void verify(BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("dialog-inline-types-inputs-and-actions", () -> {
            var button = ActionButton.builder(TITLE).width(150).action(DialogAction.commandTemplate("say $(text)")).build();
            var base = DialogBase.builder(TITLE).pause(false).canCloseWithEscape(true)
                    .body(List.of(DialogBody.plainMessage(Component.text("Привет 世界"))))
                    .inputs(List.of(DialogInput.text("text", TITLE).initial("value").build(),
                            DialogInput.bool("flag", TITLE).initial(true).build(),
                            DialogInput.numberRange("value", TITLE, 0, 10).initial(3f).build())).build();
            var notice = Dialog.create(factory -> factory.empty().base(base).type(DialogType.notice(button)));
            var types = List.of(DialogType.confirmation(button, button), DialogType.multiAction(List.of(button), button, 1),
                    DialogType.dialogList(RegistrySet.valueSet(RegistryKey.DIALOG, List.of(notice)), button, 1, 150),
                    DialogType.serverLinks(button, 1, 150));
            for (var type : types) {
                var dialog = Dialog.create(factory -> factory.empty().base(base).type(type));
                require(ClickEvent.showDialog(dialog).action() == ClickEvent.Action.SHOW_DIALOG, "Show dialog metadata lost");
            }
            require(base.inputs().size() == 3 && !base.pause(), "Base values lost");
            var registered = io.papermc.paper.registry.RegistryAccess.registryAccess().getRegistry(RegistryKey.DIALOG).iterator();
            require(registered.hasNext(), "Native dialog registry is empty");
            var key = io.papermc.paper.registry.TypedKey.create(RegistryKey.DIALOG, registered.next().key());
            var copied = Dialog.create(factory -> factory.copyFrom(key).base(base).type(DialogType.notice(button)));
            require(ClickEvent.showDialog(copied).action() == ClickEvent.Action.SHOW_DIALOG, "Registered dialog copy failed");
        });
        check.accept("dialog-builder-validation-and-defensive-lists", () -> {
            var bodies = new java.util.ArrayList<DialogBody>();
            bodies.add(DialogBody.plainMessage(TITLE));
            var base = DialogBase.builder(TITLE).body(bodies).build();
            bodies.clear();
            require(base.body().size() == 1, "Body list aliased caller");
            rejects(() -> ActionButton.builder(TITLE).width(0).build());
            rejects(() -> DialogBody.plainMessage(TITLE, 1025));
            rejects(() -> DialogType.serverLinks(null, 0, 150));
            require(DialogAction.staticAction(ClickEvent.runCommand("/help")).value().action() == ClickEvent.Action.RUN_COMMAND, "Static action lost");
        });
        check.accept("click-callback-service-uses-expiry-and-reentrancy", () -> {
            var count = new AtomicInteger();
            var event = ClickEvent.callback(audience -> count.incrementAndGet(), options(2, Duration.ofMinutes(1)));
            require(event.action() == ClickEvent.Action.CUSTOM && event.payload() instanceof ClickEvent.Payload.Custom, "Callback service missing");
            var payload = (ClickEvent.Payload.Custom) event.payload();
            Object manager = manager("ADVENTURE_CLICK_MANAGER");
            pump(manager);
            run(manager, payload.key(), null);
            run(manager, Key.key("test:wrong"), payload.nbt());
            for (String invalid : List.of("{}", "{id:'bad'}", "{id:[I;1,2,3]}", "{id:[I;1,2,3,4,5]}", "[]")) {
                run(manager, payload.key(), BinaryTagHolder.binaryTagHolder(invalid));
            }
            require(count.get() == 0, "Malformed Adventure callback token was consumed");
            run(manager, payload.key(), payload.nbt()); run(manager, payload.key(), payload.nbt()); run(manager, payload.key(), payload.nbt());
            require(count.get() == 2, "Use count/replay contract: " + count);
            var expired = ClickEvent.callback(audience -> count.incrementAndGet(), options(1, Duration.ZERO));
            pump(manager);
            var dead = (ClickEvent.Payload.Custom) expired.payload(); run(manager, dead.key(), dead.nbt());
            require(count.get() == 2, "Expired callback ran");
            var recursive = new ClickEvent.Payload.Custom[1];
            var once = ClickEvent.callback(audience -> {
                count.incrementAndGet();
                try { run(manager, recursive[0].key(), recursive[0].nbt()); } catch (Exception e) { throw new RuntimeException(e); }
            }, options(1, Duration.ofMinutes(1)));
            recursive[0] = (ClickEvent.Payload.Custom) once.payload(); pump(manager);
            run(manager, recursive[0].key(), recursive[0].nbt());
            require(count.get() == 3, "Use was consumed after reentrant callback");
        });
        check.accept("dialog-callback-response-and-invalid-payload", () -> {
            var count = new AtomicInteger();
            var action = DialogAction.customClick((response, audience) -> {
                require(audience == Audience.empty(), "Wrong callback audience");
                require("Привет 世界".equals(response.getText("text")), "Unicode response lost");
                require(Boolean.TRUE.equals(response.getBoolean("flag")) && Float.valueOf(3.5f).equals(response.getFloat("value")), "Typed response lost");
                require(response.getText("absent") == null && response.getFloat("text") == null, "Missing/mismatched type contract");
                require(response.payload().string().contains("text"), "Raw response lost");
                count.incrementAndGet();
            }, options(1, Duration.ofMinutes(1)));
            Object manager = manager("DIALOG_CLICK_MANAGER"); pump(manager);
            run(manager, Key.key("test:wrong"), action.additions());
            run(manager, action.id(), null);
            for (String invalid : List.of("{}", "{id:'bad'}", "{id:[I;1,2,3]}", "{id:[I;1,2,3,4,5]}", "[]")) {
                run(manager, action.id(), BinaryTagHolder.binaryTagHolder(invalid));
            }
            require(count.get() == 0, "Invalid payload consumed callback");
            String payload = action.additions().string();
            var response = BinaryTagHolder.binaryTagHolder(payload.substring(0, payload.length() - 1) + ",text:'Привет 世界',flag:1b,value:3.5f}");
            run(manager, action.id(), response); run(manager, action.id(), response);
            require(count.get() == 1, "Dialog callback missing/replayed");
        });
    }

    public static void verifyTick(Plugin plugin, BiConsumer<String, WorldRayTraceProbes.Checked> check, Runnable next) {
        var count = new AtomicInteger();
        try {
            var chat = (ClickEvent.Payload.Custom) ClickEvent.callback(a -> count.incrementAndGet()).payload();
            var dialog = DialogAction.customClick((r, a) -> count.incrementAndGet(), options(1, Duration.ofMinutes(1)));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                check.accept("click-and-dialog-callback-native-tick-queue", () -> {
                    run(manager("ADVENTURE_CLICK_MANAGER"), chat.key(), chat.nbt());
                    run(manager("DIALOG_CLICK_MANAGER"), dialog.id(), dialog.additions());
                    require(count.get() == 2, "Native server tick did not activate both callback queues: " + count);
                });
                next.run();
            }, 3);
        } catch (Throwable failure) {
            check.accept("click-and-dialog-callback-native-tick-queue", () -> { throw new AssertionError("Callback registration failed", failure); });
            next.run();
        }
    }

    private static ClickCallback.Options options(int uses, Duration duration) { return ClickCallback.Options.builder().uses(uses).lifetime(duration).build(); }
    private static Object manager(String name) throws Exception { return Class.forName("io.papermc.paper.adventure.providers.ClickCallbackProviderImpl").getField(name).get(null); }
    private static void pump(Object manager) throws Exception { invoke(manager, "handleQueue", 1); }
    private static void run(Object manager, Key key, BinaryTagHolder payload) throws Exception {
        Object nativeKey = Class.forName("net.minecraft.resources.Identifier").getMethod("parse", String.class).invoke(null, key.asString());
        Object codec = Class.forName("io.papermc.paper.adventure.PaperAdventure").getField("NBT_CODEC").get(null);
        Object tag = payload == null ? null : invoke(codec, "decode", payload.string());
        invoke(manager, "tryRunCallback", Audience.empty(), nativeKey, Optional.ofNullable(tag));
    }
    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (var method : target.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterCount() == args.length) {
            method.setAccessible(true);
            try { return method.invoke(target, args); } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                if (e.getCause() instanceof Error cause) throw cause;
                throw e;
            }
        }
        throw new NoSuchMethodException(name);
    }
    private static void rejects(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Invalid builder input accepted"); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
