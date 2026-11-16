package org.cardboardpowered.smoke;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Submit native dialog widgets even while the client has no player/world connection. */
final class ConfigurationDialogClientSmoke {
    private static State active;
    private static int submissions;
    private static long initialDeadline;
    private static boolean initialJoined;

    static void prepareWorld() {
        active = null; submissions = 0; initialJoined = false;
        initialDeadline = System.nanoTime() + 90_000_000_000L;
    }
    static void assertCleaned() {
        try {
            var field = io.papermc.paper.connection.PaperConfigurationTask.class.getDeclaredField("ACTIVE");
            field.setAccessible(true);
            require(((Map<?, ?>) field.get(null)).isEmpty(), "Configuration tasks retained a closed server");
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        System.out.println("CONFIGURATION_STOP_CLEANUP_PASS");
    }
    static void tick(Minecraft client) {
        if (!initialJoined && initialDeadline != 0) {
            require(System.nanoTime() < initialDeadline, "Initial configuration stalled before world entry; submissions="
                    + submissions + ", screen=" + (client.gui.screen() == null ? "none" : client.gui.screen().getTitle().getString()));
        }
        if (DialogClientSmoke.clickVisible(client, "p263-config")) submissions++;
    }
    static void begin(MinecraftServer server, UUID actor) {
        require(submissions == 3, "Initial configuration did not use the native form three times: " + submissions);
        initialJoined = true;
        active = new State(server, actor);
    }
    static boolean finish(Minecraft client) {
        State state = active;
        if (state == null) return true;
        require(System.nanoTime() < state.deadline, "Configuration client timeout: " + state.snapshot());
        var values = state.snapshot();
        require(!values.containsKey("failure"), "Configuration plugin failure: " + values);
        require("1".equals(values.get("initial")) && "1".equals(values.get("async"))
                && "1".equals(values.get("configured")), "Initial configuration events incomplete: " + values);
        if (state.request == null) {
            state.request = CompletableFuture.runAsync(() -> state.call("beginConfigurationReentry"), state.server);
            return false;
        }
        if (!state.request.isDone()) return false;
        state.request.join();
        if (!"1".equals(values.get("returned")) || client.player == null || client.level == null) return false;
        require("1".equals(values.get("held")) && "1".equals(values.get("reconfigure")), "Reconfiguration was not held exactly once: " + values);
        require(submissions == 6, "Expected three initial and three reconfiguration UI submissions: " + submissions);
        require("1".equals(values.get("cookie")) && "1".equals(values.get("initialAdventure"))
                && "1".equals(values.get("reentryAdventure")), "Configuration cookie/Adventure callbacks: " + values);
        if (++state.settle < 8) return false;
        if (state.fabricRequest == null) {
            state.fabricPlayer = client.player;
            state.fabricJoins = ConfigurationFabricSmoke.joinCount(state.server, state.actor);
            state.fabricRequest = CompletableFuture.runAsync(() -> {
                var player = state.server.getPlayerList().getPlayer(state.actor);
                require(player != null, "Missing player for Fabric reconfiguration");
                var paper = Bukkit.getPlayer(state.actor).getConnection();
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.reconfigure(player);
                // A concurrent Paper request must not replace Fabric's automatic flow.
                paper.reenterConfiguration();
            }, state.server);
            return false;
        }
        if (!state.fabricRequest.isDone()) return false;
        state.fabricRequest.join();
        if (ConfigurationFabricSmoke.joinCount(state.server, state.actor) != state.fabricJoins + 1
                || client.player == null || client.level == null || client.player == state.fabricPlayer) return false;
        if (++state.fabricSettle < 8) return false;
        active = null;
        System.out.println("CONFIGURATION_DIALOG_CLIENT_PASS actor=" + state.actor
                + " initial=1 async=1 reconfigure=1 fabricReconfigure=1 ui=6 dialogCallbacks=2 adventureCallbacks=2 cookie=roundtrip replay=ignored savedState=restored");
        return true;
    }

    private static final class State {
        final MinecraftServer server;
        final UUID actor;
        final Plugin plugin;
        final long deadline = System.nanoTime() + 60_000_000_000L;
        CompletableFuture<Void> request;
        CompletableFuture<Void> fabricRequest;
        Object fabricPlayer;
        int fabricJoins, fabricSettle;
        int settle;
        State(MinecraftServer server, UUID actor) {
            this.server = server; this.actor = actor;
            this.plugin = Bukkit.getPluginManager().getPlugin("IntegratedSmoke");
            require(this.plugin != null, "Missing configuration fixture");
        }
        Object call(String method) {
            try { return this.plugin.getClass().getMethod(method, UUID.class).invoke(this.plugin, this.actor); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        @SuppressWarnings("unchecked") Map<String, String> snapshot() { return (Map<String, String>) call("readConfiguration"); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
