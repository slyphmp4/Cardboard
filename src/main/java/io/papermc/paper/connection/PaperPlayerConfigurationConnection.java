package io.papermc.paper.connection;

import com.destroystokyo.paper.profile.CraftPlayerProfile;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.PaperDialog;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.translation.Translator;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.configuration.ClientboundResetChatPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.cardboardpowered.bridge.network.DiscardedPayloadBridge;
import org.cardboardpowered.bridge.server.network.ClientBrandBridge;
import org.cardboardpowered.bridge.server.network.ServerConfigurationPacketListenerImplBridge;

/** Configuration has an audience and profile, but no Bukkit Player yet. */
public final class PaperPlayerConfigurationConnection extends PaperCommonConnection<ServerConfigurationPacketListenerImpl>
        implements PlayerConfigurationConnection, Audience, PluginMessageBridgeImpl {
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, ResourcePackCallback> packCallbacks = new ConcurrentHashMap<>();
    private final Pointers pointers;

    public PaperPlayerConfigurationConnection(ServerConfigurationPacketListenerImpl listener) {
        super(listener);
        this.pointers = Pointers.builder()
                .withDynamic(Identity.NAME, () -> this.handle.getOwner().name())
                .withDynamic(Identity.UUID, () -> this.handle.getOwner().id())
                .withDynamic(Identity.LOCALE, () -> Objects.requireNonNullElse(
                        Translator.parseLocale(this.getClientInformation().language()), Locale.US)).build();
    }

    private ServerConfigurationPacketListenerImplBridge bridge() {
        return (ServerConfigurationPacketListenerImplBridge) this.handle;
    }

    @Override public ClientInformation getClientInformation() { return this.bridge().cardboard$clientInformation(); }
    @Override public Audience getAudience() { return this; }
    @Override public PlayerProfile getProfile() { return CraftPlayerProfile.asBukkitCopy(this.handle.getOwner()); }
    @Override public Pointers pointers() { return this.pointers; }
    @Override public boolean isConnected() { return this.handle.connection.isConnected(); }
    @Override public String getClientBrandName() { return ((ClientBrandBridge) this.handle).cardboard_getClientBrand(); }

    @Override public void showDialog(DialogLike dialog) {
        this.handle.send(new ClientboundShowDialogPacket(PaperDialog.bukkitToMinecraftHolder((Dialog) dialog)));
    }
    @Override public void closeDialog() { this.handle.send(ClientboundClearDialogPacket.INSTANCE); }
    @Override public void clearChat() { this.handle.send(ClientboundResetChatPacket.INSTANCE); }
    @Override public void completeReconfiguration() { this.bridge().cardboard$completeReconfiguration(); }

    @Override public void sendResourcePacks(ResourcePackRequest request) {
        if (request.replace()) this.clearResourcePacks();
        var prompt = PaperAdventure.asVanilla(request.prompt());
        var iter = request.packs().iterator();
        while (iter.hasNext()) {
            var pack = iter.next();
            if (request.callback() != ResourcePackCallback.noOp()) this.packCallbacks.put(pack.id(), request.callback());
            this.handle.send(new ClientboundResourcePackPushPacket(pack.id(), pack.uri().toASCIIString(), pack.hash(),
                    request.required(), iter.hasNext() ? Optional.empty() : Optional.ofNullable(prompt)));
        }
    }
    @Override public void removeResourcePacks(UUID id, UUID... others) {
        this.handle.send(new ClientboundResourcePackPopPacket(Optional.of(id)));
        for (UUID other : others) this.handle.send(new ClientboundResourcePackPopPacket(Optional.of(other)));
    }
    @Override public void clearResourcePacks() { this.handle.send(new ClientboundResourcePackPopPacket(Optional.empty())); }

    public void handleResourcePackResponse(ServerboundResourcePackPacket packet) {
        var callback = packet.action().isTerminal() ? this.packCallbacks.remove(packet.id()) : this.packCallbacks.get(packet.id());
        if (callback != null) callback.packEventReceived(packet.id(), ResourcePackStatus.valueOf(packet.action().name()), this);
    }

    @Override public Set<String> channels() { return this.channels; }
    @Override public Set<String> getListeningPluginChannels() { return Set.copyOf(this.channels); }
    @Override public void sendPluginMessage(Plugin plugin, String channel, byte[] message) {
        StandardMessenger.validatePluginMessage(Bukkit.getMessenger(), plugin, channel, message);
        channel = StandardMessenger.validateAndCorrectChannel(channel);
        if (this.channels.contains(channel)) this.handle.send(new ClientboundCustomPayloadPacket(
                DiscardedPayloadBridge.create(Identifier.parse(channel), message)));
    }

    public void close() {
        this.packCallbacks.clear();
        this.channels.clear();
        this.cancelCookieRequests();
    }
}
