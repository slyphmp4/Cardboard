package io.papermc.paper.connection;

import java.util.function.Consumer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ConfigurationTask;
import org.cardboardpowered.bridge.server.network.ServerConfigurationPacketListenerImplBridge;

/** Hold after native/Fabric configuration, before the native prepare-spawn task. */
public final class PaperReconfigurationTask implements ConfigurationTask {
    public static final Type TYPE = new Type("paper_reconfiguration");
    private final ServerConfigurationPacketListenerImplBridge listener;
    private boolean released;

    public PaperReconfigurationTask(ServerConfigurationPacketListenerImplBridge listener) { this.listener = listener; }
    @Override public Type type() { return TYPE; }
    @Override public void start(Consumer<Packet<?>> sender) { this.listener.cardboard$beginReconfiguration(); }
    @Override public boolean tick() { return this.released; }
    public void release() { this.released = true; }
}
