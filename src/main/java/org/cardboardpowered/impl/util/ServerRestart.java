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

/** One restart request and optional launcher after native server shutdown completes. */
public final class ServerRestart {
    private final MinecraftServer server;
    private final AtomicBoolean requested = new AtomicBoolean();
    private RestartPlan launcher;
    private boolean stopped;

    public ServerRestart(MinecraftServer server) { this.server = server; }

    public synchronized boolean arm(String script) {
        if (!(this.server instanceof DedicatedServer) || this.stopped || this.server.isStopped()) return false;
        if (this.launcher != null) return true;
        try {
            var plan = RestartPlan.create(Path.of(""), script, System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"));
            if (plan.isEmpty()) return false;
            this.launcher = plan.get();
            return true;
        } catch (RuntimeException failure) {
            if (CraftServer.INSTANCE != null) CraftServer.INSTANCE.getLogger().log(Level.WARNING, "Cannot prepare restart script; stopping without a script launcher", failure);
            return false;
        }
    }

    /** Called only when native stopServer returns, after world saves and listener close. */
    public void afterStop() {
        RestartPlan selected;
        synchronized (this) {
            if (this.stopped) return;
            this.stopped = true;
            selected = this.launcher;
            this.launcher = null;
        }
        if (selected == null) return;
        try {
            selected.launch();
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(ServerRestart.class).error("Cannot launch restart script after server shutdown", failure);
        }
    }

    public void request() {
        if (this.server.isStopped() || !this.requested.compareAndSet(false, true)) return;
        boolean armed = arm(SpigotConfig.restartScript);
        if (CraftServer.INSTANCE != null) CraftServer.INSTANCE.getLogger().info(this.server instanceof DedicatedServer
                ? armed ? "Restart requested; startup script is armed for native server shutdown completion" : "Restart requested; no startup script is available, stopping server"
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
