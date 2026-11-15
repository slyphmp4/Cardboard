package org.cardboardpowered.smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

/** Observe the decoded command tree after add, replace and restore in each actual client world. */
final class ServerAliasClientSmoke {
    private static final String FIRST = "p263client_alias", SECOND = "p263client_next";
    private static State active;

    static void begin(MinecraftServer server) {
        if (active != null) throw new AssertionError("Previous alias client fixture survived cleanup");
        try {
            active = new State(server);
            active.install(FIRST);
        } catch (Throwable failure) {
            State state = active; active = null;
            if (state != null) try { state.restore(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("Cannot begin alias client fixture", failure);
        }
    }

    static boolean finish(Minecraft client) {
        State state = active;
        if (state == null) return true;
        if (System.nanoTime() > state.deadline) throw new AssertionError("Client command tree did not follow alias reload phase=" + state.phase);
        if (state.pending != null) {
            if (!state.pending.isDone()) return false;
            state.pending.join(); state.pending = null;
        }
        if (client.player == null) return false;
        var root = client.player.connection.getCommands().getRoot();
        if (state.phase == 0 && root.getChild(FIRST) != null) {
            state.pending = CompletableFuture.runAsync(() -> state.install(SECOND), state.server);
            state.phase = 1;
        } else if (state.phase == 1 && root.getChild(FIRST) == null && root.getChild(SECOND) != null) {
            state.pending = CompletableFuture.runAsync(state::restore, state.server);
            state.phase = 2;
        } else if (state.phase == 2 && root.getChild(FIRST) == null && root.getChild(SECOND) == null && root.getChild("smokeworld") != null) {
            active = null;
            System.out.println("SERVER_ALIAS_CLIENT_PASS");
            return true;
        }
        return false;
    }

    private static final class State {
        final MinecraftServer server;
        final Path file = Path.of("commands.yml");
        final byte[] original;
        final long deadline = System.nanoTime() + 60_000_000_000L;
        CompletableFuture<Void> pending;
        int phase;
        State(MinecraftServer server) throws Exception {
            this.server = server;
            this.original = Files.exists(this.file) ? Files.readAllBytes(this.file) : null;
        }
        void install(String alias) {
            try {
                var yaml = new YamlConfiguration();
                yaml.set("aliases." + alias, java.util.List.of("smokeworld $1-"));
                yaml.save(this.file.toFile());
                if (!Bukkit.getServer().reloadCommandAliases()) throw new AssertionError("Valid client alias did not reload");
            } catch (Exception failure) { throw new IllegalStateException("Cannot install client alias", failure); }
        }
        void restore() {
            try {
                if (this.original == null) Files.deleteIfExists(this.file); else Files.write(this.file, this.original);
                if (!Bukkit.getServer().reloadCommandAliases()) throw new AssertionError("Original aliases did not reload");
            } catch (Exception failure) { throw new IllegalStateException("Cannot restore client aliases", failure); }
        }
    }
}
