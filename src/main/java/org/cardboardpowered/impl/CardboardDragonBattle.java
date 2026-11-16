package org.cardboardpowered.impl;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.dimension.end.DragonRespawnStage;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.level.levelgen.feature.EndSpikeFeature;
import org.bukkit.Location;
import org.bukkit.boss.BossBar;
import org.bukkit.boss.DragonBattle;
import org.bukkit.craftbukkit.boss.CraftBossBar;
import org.bukkit.craftbukkit.entity.CraftEnderCrystal;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.entity.EnderDragon;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.cardboardpowered.bridge.world.level.dimension.end.EnderDragonFightBridge;
import io.papermc.paper.math.Position;

public class CardboardDragonBattle implements DragonBattle {
    private final EnderDragonFight handle;

    public CardboardDragonBattle(EnderDragonFight handle) {
        this.handle = handle;
    }

    private EnderDragonFightBridge bridge() {
        return (EnderDragonFightBridge) this.handle;
    }

    @Override
    public EnderDragon getEnderDragon() {
        var id = this.handle.dragonUUID();
        var entity = id == null ? null : this.handle.level.getEntity(id);
        return entity == null ? null : (EnderDragon) ((EntityBridge) entity).getBukkitEntity();
    }

    @Override
    public BossBar getBossBar() {
        return new CraftBossBar(this.handle.dragonEvent);
    }

    @Override
    public Location getEndPortalLocation() {
        var position = bridge().cardboard$getExitPortalLocation();
        return position == null ? null : CraftLocation.toBukkit(position, this.handle.level);
    }

    @Override
    public boolean generateEndPortal(boolean withPortals) {
        if (bridge().cardboard$getExitPortalLocation() != null || this.handle.findExitPortal() != null) return false;
        this.handle.spawnExitPortal(withPortals);
        return true;
    }

    @Override
    public boolean hasBeenPreviouslyKilled() {
        return this.handle.hasPreviouslyKilledDragon();
    }

    @Override
    public void setPreviouslyKilled(boolean killed) {
        bridge().cardboard$setPreviouslyKilled(killed);
        this.handle.setDirty();
    }

    @Override
    public void initiateRespawn() {
        this.handle.tryRespawn();
    }

    @Override
    public boolean initiateRespawn(Collection<org.bukkit.entity.EnderCrystal> crystals) {
        if (!hasBeenPreviouslyKilled() || getRespawnPhase() != RespawnPhase.NONE) return false;
        if (bridge().cardboard$getExitPortalLocation() == null && this.handle.findExitPortal() == null) {
            this.handle.spawnExitPortal(true);
        }
        List<EndCrystal> nativeCrystals = new ArrayList<>();
        if (crystals != null) {
            for (var crystal : crystals) {
                if (crystal != null && crystal.getWorld().equals(((org.cardboardpowered.bridge.world.level.LevelBridge) this.handle.level).cardboard$getWorld())) {
                    nativeCrystals.add(((CraftEnderCrystal) crystal).getHandle());
                }
            }
        }
        this.handle.respawnDragon(nativeCrystals);
        boolean started = bridge().cardboard$getRespawnStage() != null;
        if (started) bridge().cardboard$allowEmptyCrystalRespawn(nativeCrystals.isEmpty());
        return started;
    }

    @Override
    public RespawnPhase getRespawnPhase() {
        var stage = bridge().cardboard$getRespawnStage();
        return stage == null ? RespawnPhase.NONE : RespawnPhase.valueOf(stage.name());
    }

    @Override
    public boolean setRespawnPhase(RespawnPhase phase) {
        Preconditions.checkArgument(phase != null && phase != RespawnPhase.NONE, "Invalid respawn phase: %s", phase);
        if (bridge().cardboard$getRespawnStage() == null) return false;
        bridge().cardboard$setRespawnStage(DragonRespawnStage.valueOf(phase.name()));
        this.handle.setDirty();
        return true;
    }

    @Override
    public void resetCrystals() {
        this.handle.resetSpikeCrystals();
    }

    @Override
    public int getGatewayCount() {
        return EnderDragonFight.GATEWAY_COUNT - bridge().cardboard$getGateways().size();
    }

    @Override
    public boolean spawnNewGateway() {
        if (bridge().cardboard$getGateways().isEmpty()) return false;
        bridge().cardboard$spawnNewGateway();
        return true;
    }

    @Override
    public void spawnNewGateway(Position position) {
        Preconditions.checkArgument(position != null, "Position cannot be null");
        this.handle.spawnNewGateway(new BlockPos(position.blockX(), position.blockY(), position.blockZ()));
    }

    @Override
    public List<org.bukkit.entity.EnderCrystal> getRespawnCrystals() {
        List<org.bukkit.entity.EnderCrystal> crystals = new ArrayList<>();
        for (var reference : this.handle.respawnCrystals) {
            addCrystal(crystals, reference.getEntity(this.handle.level, EndCrystal.class));
        }
        return List.copyOf(crystals);
    }

    @Override
    public List<org.bukkit.entity.EnderCrystal> getHealingCrystals() {
        List<org.bukkit.entity.EnderCrystal> crystals = new ArrayList<>();
        for (var spike : EndSpikeFeature.getSpikesForLevel(this.handle.level)) {
            for (var crystal : this.handle.level.getEntitiesOfClass(EndCrystal.class, spike.getTopBoundingBox())) {
                addCrystal(crystals, crystal);
            }
        }
        return List.copyOf(crystals);
    }

    private static void addCrystal(List<org.bukkit.entity.EnderCrystal> result, EndCrystal crystal) {
        if (crystal != null && !crystal.isRemoved() && crystal.isAlive() && ((EntityBridge) crystal).isValidBF()) {
            result.add((org.bukkit.entity.EnderCrystal) ((EntityBridge) crystal).getBukkitEntity());
        }
    }

    @Override public int hashCode() { return this.handle.hashCode(); }
    @Override public boolean equals(Object other) {
        return other instanceof CardboardDragonBattle battle && battle.handle == this.handle;
    }
}
