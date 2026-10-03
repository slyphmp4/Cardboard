package org.bukkit.craftbukkit.block;

import com.google.common.base.Preconditions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.creaking.Creaking;
import net.minecraft.world.level.block.entity.CreakingHeartBlockEntity;
import org.bukkit.Location;
import org.bukkit.World;
import org.cardboardpowered.mixin.world.level.block.entity.CreakingHeartBlockEntityAccessor;

public class CraftCreakingHeart extends CraftBlockEntityState<CreakingHeartBlockEntity> implements org.bukkit.block.CreakingHeart {

    public CraftCreakingHeart(World world, CreakingHeartBlockEntity blockEntity) {
        super(world, blockEntity);
    }

    protected CraftCreakingHeart(CraftCreakingHeart state, Location location) {
        super(state, location);
    }

    @Override
    public CraftCreakingHeart copy() {
        return new CraftCreakingHeart(this, null);
    }

    @Override
    public CraftCreakingHeart copy(Location location) {
        return new CraftCreakingHeart(this, location);
    }

    @Override
    public org.bukkit.entity.Creaking getCreaking() {
        if (!this.isPlaced()) {
            return null;
        }

        return ((CreakingHeartBlockEntityAccessor) this.getBlockEntity())
                .cardboard$getCreakingProtector()
                .map(creaking -> (org.bukkit.entity.Creaking) creaking.getBukkitEntity())
                .orElse(null);
    }

    @Override
    public void setCreaking(org.bukkit.entity.Creaking creaking) {
        this.requirePlaced();

        if (creaking == null) {
            ((CreakingHeartBlockEntityAccessor) this.getBlockEntity()).cardboard$clearCreakingInfo();
            return;
        }

        Preconditions.checkArgument(
                this.getWorld().equals(creaking.getWorld()),
                "the location of the creaking must be in the same world as this CreakingHeart"
        );
        this.getBlockEntity().setCreakingInfo(
                (Creaking) ((org.bukkit.craftbukkit.entity.CraftEntity) creaking).getHandle()
        );
    }

    @Override
    public org.bukkit.entity.Creaking spawnCreaking() {
        this.requirePlaced();
        if (!(this.getBlockEntity().getLevel() instanceof ServerLevel serverLevel)) {
            return null;
        }

        Creaking creaking = CreakingHeartBlockEntityAccessor.cardboard$spawnProtector(serverLevel, this.getBlockEntity());
        if (creaking == null) {
            return null;
        }

        this.getBlockEntity().setCreakingInfo(creaking);
        return (org.bukkit.entity.Creaking) creaking.getBukkitEntity();
    }

    @Override
    public org.bukkit.Location spreadResin() {
        this.requirePlaced();
        if (!(this.getBlockEntity().getLevel() instanceof ServerLevel serverLevel)) {
            return null;
        }

        return ((CreakingHeartBlockEntityAccessor) this.getBlockEntity())
                .cardboard$spreadResin(serverLevel)
                .map(blockPos -> org.bukkit.craftbukkit.util.CraftLocation.toBukkit(blockPos, this.getWorld()))
                .orElse(null);
    }
}
