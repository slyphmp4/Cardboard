package io.papermc.paper.connection;

import java.util.Set;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.cardboardpowered.bridge.server.level.ServerPlayerBridge;

public class PaperPlayerGameConnection extends PaperCommonConnection<ServerGamePacketListenerImpl> implements PlayerGameConnection {
    private boolean reconfigurationRequested;

    public PaperPlayerGameConnection(ServerGamePacketListenerImpl serverConfigurationPacketListenerImpl) {
        super(serverConfigurationPacketListenerImpl);
    }

    @Override
    public ClientInformation getClientInformation() {
        return ((ServerGamePacketListenerImpl)this.handle).player.clientInformation();
    }
    
    @Override
    public void reenterConfiguration() {
        if (!this.handle.connection.isConnected() || this.handle.connection.getPacketListener() != this.handle) return;
        var server = this.handle.player.level().getServer();
        if (!server.isSameThread()) {
            server.execute(this::reenterConfiguration);
            return;
        }
        if (this.reconfigurationRequested || !this.handle.connection.isConnected()
                || this.handle.connection.getPacketListener() != this.handle
                || ((org.cardboardpowered.bridge.server.network.ServerGamePacketListenerImplBridge) this.handle).cardboard$isWaitingForConfiguration()) return;
        this.reconfigurationRequested = true;
        ((org.cardboardpowered.bridge.network.ConnectionBridge) this.handle.connection).cardboard$setPaperReconfiguration(true);
        // Run Fabric's full registry/mod configuration pipeline before Paper holds the connection.
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.reconfigure(this.handle);
    }

    public Player getPlayer() {
        return (Player) ((ServerPlayerBridge) this.handle.player).getBukkitEntity();
    }

    public void sendPluginMessage(Plugin source, String channel, byte[] message) {
        this.getPlayer().sendPluginMessage(source, channel, message);
    }

    public Set<String> getListeningPluginChannels() {
    	return this.getPlayer().getListeningPluginChannels();
    }

	@Override
	public boolean isConnected() {
		return getPlayer().isConnected();
	}


    // 26.2: new on PlayerCommonConnection
    @Override
    public String getClientBrandName() {
        // captured from the minecraft:brand custom payload by ServerCommonPacketListenerImplMixin_Brand
        return ((org.cardboardpowered.bridge.server.network.ClientBrandBridge) this.handle).cardboard_getClientBrand();
    }

}
