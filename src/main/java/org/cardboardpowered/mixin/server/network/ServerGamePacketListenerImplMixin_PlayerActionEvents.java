package org.cardboardpowered.mixin.server.network;

import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.cardboardpowered.bridge.server.level.ServerPlayerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ServerGamePacketListenerImpl.class, priority = 800)
public abstract class ServerGamePacketListenerImplMixin_PlayerActionEvents {

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void cardboard$playerSwapHandItems(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (packet.getAction() != ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND) {
            return;
        }

        PacketUtils.ensureRunningOnSameThread(packet, (ServerGamePacketListenerImpl) (Object) this, this.player.level());
        this.player.resetLastActionTime();

        CraftPlayer craftPlayer = (CraftPlayer) ((ServerPlayerBridge) this.player).getBukkitEntity();
        org.bukkit.inventory.ItemStack newMainHand = CraftItemStack.asBukkitCopy(this.player.getItemInHand(InteractionHand.OFF_HAND));
        org.bukkit.inventory.ItemStack newOffHand = CraftItemStack.asBukkitCopy(this.player.getItemInHand(InteractionHand.MAIN_HAND));

        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(
                craftPlayer,
                newMainHand.clone(),
                newOffHand.clone()
        );
        Bukkit.getPluginManager().callEvent(event);

        if (event.isCancelled()) {
            this.player.containerMenu.sendAllDataToRemote();
            ci.cancel();
            return;
        }

        // Apply the event result ourselves so plugin modifications are not
        // overwritten by the vanilla swap immediately after the event.
        this.player.stopUsingItem();
        this.player.setItemInHand(InteractionHand.MAIN_HAND, CraftItemStack.asNMSCopy(event.getMainHandItem()));
        this.player.setItemInHand(InteractionHand.OFF_HAND, CraftItemStack.asNMSCopy(event.getOffHandItem()));
        this.player.containerMenu.sendAllDataToRemote();
        ci.cancel();
    }
}
