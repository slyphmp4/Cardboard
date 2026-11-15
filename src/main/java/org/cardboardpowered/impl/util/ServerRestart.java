package org.cardboardpowered.impl.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.spigotmc.SpigotConfig;

/** One restart request and optional JVM-exit launcher for one server lifecycle. */
public final class ServerRestart {
    private final MinecraftServer server;
    private final AtomicBoolean requested = new AtomicBoolean();
    private Thread launcher;

    public ServerRestart(MinecraftServer server) { this.server = server; }

    public synchronized boolean arm(String script) {
        if (!(this.server instanceof DedicatedServer) || this.server.isStopped()) return false;
        if (this.launcher != null) return true;
        try {
            var plan = RestartPlan.create(Path.of(""), script, System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
            if (plan.isEmpty()) return false;
            RestartPlan selected = plan.get();
            Thread hook = new Thread(() -> {
                try { selected.launch(); }
                catch (Exception failure) { failure.printStackTrace(); }
            }, "Cardboard restart launcher");
            Runtime.getRuntime().addShutdownHook(hook);
            this.launcher = hook;
            return true;
        } catch (RuntimeException failure) {
            if (CraftServer.INSTANCE != null) CraftServer.INSTANCE.getLogger().log(Level.WARNING, "Cannot prepare restart script; stopping without a script launcher", failure);
            return false;
        }
    }

    public void request() {
        if (this.server.isStopped() || !this.requested.compareAndSet(false, true)) return;
        boolean armed = arm(SpigotConfig.restartScript);
        if (CraftServer.INSTANCE != null) CraftServer.INSTANCE.getLogger().info(this.server instanceof DedicatedServer
                ? armed ? "Restart requested; startup script is armed for JVM shutdown" : "Restart requested; no startup script is available, stopping server"
                : "Restart requested; saving and closing the integrated world while keeping the client running");
        this.server.execute(() -> {
            try {
                if (this.server instanceof DedicatedServer) {
                    var message = CraftChatMessage.fromStringOrEmpty(SpigotConfig.restartMessage, true);
                    for (var player : List.copyOf(this.server.getPlayerList().getPlayers())) player.connection.disconnect(message);
                }
            } finally {
                org.spigotmc.WatchdogThread.doStop();
                this.server.halt(false);
            }
        });
    }
}
