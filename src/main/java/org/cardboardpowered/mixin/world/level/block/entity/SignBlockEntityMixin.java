package org.cardboardpowered.mixin.world.level.block.entity;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.cardboardpowered.bridge.world.level.block.entity.SignBlockEntityBridge;

/**
 * @implSpec https://github.com/PaperMC/Paper/blob/main/paper-server/patches/sources/net/minecraft/world/level/block/entity/SignBlockEntity.java.patch
 */
@Mixin(SignBlockEntity.class)
public class SignBlockEntityMixin implements SignBlockEntityBridge {

   // @Shadow
   // public Text[] texts;

    @Override
    public Component[] getTextBF() {
    	SignBlockEntity e = (SignBlockEntity)(Object)this;
        return e.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).toArray(Component[]::new);
    	
        //return texts;
    }
    
    // public boolean isPlayerFacingFront(PlayerEntity player) {
    //    return this.isFacingFrontText(player.getX(), player.getZ());
    //}

    @Override
    public boolean cardboard$isFacingFrontText(double x, double z) {
    	SignBlockEntity thiz = (SignBlockEntity) (Object) this;
    	
    	
        Block block = thiz.getBlockState().getBlock();
        if (block instanceof SignBlock) {
            SignBlock blocksign = (SignBlock)block;
            Vec3 vec3d = blocksign.getSignHitboxCenterPosition(thiz.getBlockState());
            double d0 = x - ((double)thiz.getBlockPos().getX() + vec3d.x);
            double d1 = z - ((double)thiz.getBlockPos().getZ() + vec3d.z);
            float f2 = blocksign.getYRotationDegrees(thiz.getBlockState());
            return Mth.degreesDifferenceAbs(f2, (float)(Mth.atan2(d1, d0) * 57.2957763671875) - 90.0f) <= 90.0f;
        }
        return false;
    }


    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
            method = "updateSignText",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/SignBlockEntity;updateText(Ljava/util/function/UnaryOperator;Lnet/minecraft/world/level/block/entity/SignTextSlot;)Z"))
    private boolean cardboard$signChangeEvent(
            SignBlockEntity sign,
            java.util.function.UnaryOperator<net.minecraft.world.level.block.entity.SignText> update,
            net.minecraft.world.level.block.entity.SignTextSlot slot,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Boolean> original,
            @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) net.minecraft.world.entity.player.Player player) {
        if (player.level().isClientSide()) {
            return original.call(sign, update, slot);
        }
        // This call is reached only after vanilla has checked wax and editor ownership.
        var proposed = update.apply(sign.getText(slot));
        var lines = proposed.getMessages(player.isTextFilteringEnabled()).stream()
                .map(io.papermc.paper.adventure.PaperAdventure::asAdventure).toList();
        var bukkitPlayer = (org.bukkit.entity.Player) ((org.cardboardpowered.bridge.world.entity.EntityBridge) player).getBukkitEntity();
        var side = slot == net.minecraft.world.level.block.entity.SignTextSlot.FRONT
                ? org.bukkit.block.sign.Side.FRONT : org.bukkit.block.sign.Side.BACK;
        var event = new org.bukkit.event.block.SignChangeEvent(
                bukkitPlayer.getWorld().getBlockAt(sign.getBlockPos().getX(), sign.getBlockPos().getY(), sign.getBlockPos().getZ()),
                bukkitPlayer, new java.util.ArrayList<>(lines), side);
        org.bukkit.Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                serverPlayer.connection.send(sign.getUpdatePacket());
            }
            return false;
        }
        var edited = proposed.asMutable();
        Component[] messages = org.bukkit.craftbukkit.block.CraftSign.sanitizeLines(event.lines());
        for (int line = 0; line < messages.length; line++) {
            if (!java.util.Objects.equals(lines.get(line), event.line(line))) {
                edited.setLine(line, messages[line]);
            }
        }
        return original.call(sign, (java.util.function.UnaryOperator<net.minecraft.world.level.block.entity.SignText>) ignored -> edited.asImmutable(), slot);
    }
}
