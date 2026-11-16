package org.cardboardpowered.mixin.server.network;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.papermc.paper.connection.PaperConfigurationTask;
import io.papermc.paper.connection.PaperPlayerConfigurationConnection;
import io.papermc.paper.connection.PaperReconfigurationTask;
import io.papermc.paper.event.connection.configuration.PlayerConnectionInitialConfigureEvent;
import io.papermc.paper.event.connection.configuration.PlayerConnectionReconfigureEvent;
import java.util.Queue;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.ServerResourcePackConfigurationTask;
import org.bukkit.Bukkit;
import org.cardboardpowered.bridge.network.ConnectionBridge;
import org.cardboardpowered.bridge.server.network.ServerConfigurationPacketListenerImplBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep native registry negotiation and world loading; insert Paper's configuration events. */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationPacketListenerImplMixin extends ServerCommonPacketListenerImpl
        implements ServerConfigurationPacketListenerImplBridge {
    @Shadow private ClientInformation clientInformation;
    @Shadow @Final private Queue<ConfigurationTask> configurationTasks;
    @Shadow private ConfigurationTask currentTask;
    @Shadow public abstract void returnToWorld();
    @Shadow protected abstract void finishCurrentTask(ConfigurationTask.Type type);
    @Unique private PaperPlayerConfigurationConnection cardboard$apiConnection;
    @Unique private PaperConfigurationTask cardboard$configurationTask;
    @Unique private PaperReconfigurationTask cardboard$reconfigurationTask;
    @Unique private boolean cardboard$reconfiguration;
    @Unique private boolean cardboard$returnRequested;
    @Unique private boolean cardboard$initialEventFired;

    protected ServerConfigurationPacketListenerImplMixin(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    @Override
    public PaperPlayerConfigurationConnection cardboard$configurationConnection() {
        if (this.cardboard$apiConnection == null) {
            this.cardboard$apiConnection = new PaperPlayerConfigurationConnection((ServerConfigurationPacketListenerImpl) (Object) this);
        }
        return this.cardboard$apiConnection;
    }

    @Override public ClientInformation cardboard$clientInformation() { return this.clientInformation; }

    @WrapMethod(method = "startConfiguration")
    private void cardboard$initialConfigure(Operation<Void> original) {
        // Fabric may defer this call until channel registration, on a Netty thread.
        // Serialize task creation and the initial event with the native server tick.
        if (!this.server.isSameThread()) {
            this.server.execute(((ServerConfigurationPacketListenerImpl) (Object) this)::startConfiguration);
            return;
        }
        // Fabric also calls startConfiguration again when its early tasks finish.
        if (!this.cardboard$initialEventFired) {
            this.cardboard$initialEventFired = true;
            if (!ServerConfigurationNetworking.isReconfiguring((ServerConfigurationPacketListenerImpl) (Object) this)) {
                Bukkit.getPluginManager().callEvent(new PlayerConnectionInitialConfigureEvent(this.cardboard$configurationConnection()));
            }
        }
        original.call();
    }

    @Inject(method = "startConfiguration", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;returnToWorld()V"))
    private void cardboard$asyncConfigure(CallbackInfo ci) {
        if (ServerConfigurationNetworking.isReconfiguring((ServerConfigurationPacketListenerImpl) (Object) this)) {
            if (((ConnectionBridge) this.connection).cardboard$hasPaperReconfiguration()) {
                this.cardboard$reconfigurationTask = new PaperReconfigurationTask(this);
                this.configurationTasks.add(this.cardboard$reconfigurationTask);
            }
            return;
        }
        this.cardboard$configurationTask = new PaperConfigurationTask(this.server, this.cardboard$configurationConnection());
        this.configurationTasks.add(this.cardboard$configurationTask);
    }

    @Override
    public void cardboard$beginReconfiguration() {
        this.cardboard$reconfiguration = true;
        Bukkit.getPluginManager().callEvent(new PlayerConnectionReconfigureEvent(this.cardboard$configurationConnection()));
    }

    @Override
    public void cardboard$completeReconfiguration() {
        if (!this.connection.isConnected() || this.connection.getPacketListener() != (Object) this) return;
        if (!this.server.isSameThread()) {
            this.server.execute(this::cardboard$completeReconfiguration);
            return;
        }
        if (!this.cardboard$reconfiguration || this.cardboard$returnRequested || this.cardboard$reconfigurationTask == null
                || this.currentTask != this.cardboard$reconfigurationTask
                || !this.connection.isConnected() || this.connection.getPacketListener() != (Object) this) return;
        this.cardboard$returnRequested = true;
        this.cardboard$reconfigurationTask.release();
    }

    @Inject(method = "handleResourcePackResponse", at = @At("HEAD"), cancellable = true)
    private void cardboard$resourcePackResponse(ServerboundResourcePackPacket packet, CallbackInfo ci) {
        PacketUtils.ensureRunningOnSameThread(packet, this, this.server.packetProcessor());
        this.cardboard$configurationConnection().handleResourcePackResponse(packet);
        // A plugin's optional pack must never finish the server's configuration task.
        boolean serverPack = this.server.getServerResourcePack().filter(pack -> pack.id().equals(packet.id())).isPresent();
        if (serverPack) {
            super.handleResourcePackResponse(packet);
            if (packet.action().isTerminal() && this.currentTask != null
                    && this.currentTask.type().equals(ServerResourcePackConfigurationTask.TYPE)) {
                this.finishCurrentTask(ServerResourcePackConfigurationTask.TYPE);
            }
        }
        ci.cancel();
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"))
    private void cardboard$cancelConfiguration(DisconnectionDetails details, CallbackInfo ci) {
        ((ConnectionBridge) this.connection).cardboard$setPaperReconfiguration(false);
        if (this.cardboard$configurationTask != null) this.cardboard$configurationTask.cancel();
        if (this.cardboard$apiConnection != null) this.cardboard$apiConnection.close();
    }
}
