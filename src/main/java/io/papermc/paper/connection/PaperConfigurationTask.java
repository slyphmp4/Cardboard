package io.papermc.paper.connection;

import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import org.bukkit.Bukkit;

/** Blocking plugin handlers run off-thread; native task completion stays on the server tick. */
public final class PaperConfigurationTask implements ConfigurationTask {
    public static final Type TYPE = new Type("paper_event_handling");
    private static final ConcurrentMap<MinecraftServer, Set<PaperConfigurationTask>> ACTIVE = new ConcurrentHashMap<>();
    private final MinecraftServer server;
    private final PaperPlayerConfigurationConnection connection;
    private volatile boolean done;
    private Thread worker;

    public PaperConfigurationTask(MinecraftServer server, PaperPlayerConfigurationConnection connection) {
        this.server = server;
        this.connection = connection;
    }

    @Override
    public synchronized void start(Consumer<Packet<?>> sender) {
        if (this.done || this.server.isStopped()
                || AsyncPlayerConnectionConfigureEvent.getHandlerList().getRegisteredListeners().length == 0) {
            this.done = true;
            return;
        }
        ACTIVE.compute(this.server, (server, tasks) -> {
            if (tasks == null) tasks = ConcurrentHashMap.newKeySet();
            tasks.add(this);
            return tasks;
        });
        this.worker = Thread.ofVirtual().name("Cardboard Configuration " + this.connection.getProfile().getId()).unstarted(() -> {
            try {
                if (!this.done) Bukkit.getPluginManager().callEvent(new AsyncPlayerConnectionConfigureEvent(this.connection));
            } finally {
                this.done = true;
                this.forget();
            }
        });
        this.worker.start();
    }

    @Override public boolean tick() { return this.done; }
    @Override public Type type() { return TYPE; }

    public synchronized void cancel() {
        this.done = true;
        if (this.worker != null) this.worker.interrupt();
        this.forget();
    }

    private void forget() {
        ACTIVE.computeIfPresent(this.server, (server, tasks) -> {
            tasks.remove(this);
            return tasks.isEmpty() ? null : tasks;
        });
    }

    public static void cancelAll(MinecraftServer server) {
        Set<PaperConfigurationTask> tasks = ACTIVE.remove(server);
        if (tasks != null) tasks.forEach(PaperConfigurationTask::cancel);
    }
}
