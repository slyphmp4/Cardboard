package org.bukkit.craftbukkit.entity;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.mojang.logging.LogUtils;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.entity.LookAnchor;
import io.papermc.paper.entity.TeleportFlag;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import me.isaiah.common.entity.IRemoveReason;
import net.kyori.adventure.pointer.PointersSupplier;
import net.kyori.adventure.util.TriState;
import net.md_5.bungee.api.chat.BaseComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.EntityEffect;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftSound;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry;
import org.bukkit.craftbukkit.util.CraftChatMessage;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.bukkit.craftbukkit.util.CraftSpawnCategory;
import org.bukkit.craftbukkit.util.CraftVector;
import org.bukkit.entity.EntitySnapshot;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.SpawnCategory;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.permissions.PermissibleBase;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.permissions.ServerOperator;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;
import org.cardboardpowered.bridge.world.entity.EntityBridge;
import org.cardboardpowered.bridge.world.item.ItemStackBridge;
import org.cardboardpowered.bridge.world.level.LevelBridge;
import org.cardboardpowered.impl.world.CraftWorld;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public abstract class CraftEntity implements org.bukkit.entity.Entity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static PermissibleBase perm;
    private static final CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new CraftPersistentDataTypeRegistry();
    static final PointersSupplier<org.bukkit.entity.Entity> POINTERS_SUPPLIER = PointersSupplier.<org.bukkit.entity.Entity>builder()
            .resolving(net.kyori.adventure.identity.Identity.DISPLAY_NAME, org.bukkit.entity.Entity::name)
            .resolving(net.kyori.adventure.identity.Identity.UUID, org.bukkit.entity.Entity::getUniqueId)
            .resolving(net.kyori.adventure.permission.PermissionChecker.POINTER, entity1 -> entity1::permissionValue)
            .build();

    protected final CraftServer server = CraftServer.INSTANCE;
    protected Entity entity;
    private final EntityType entityType;
    private EntityDamageEvent lastDamageEvent;
    private final CraftPersistentDataContainer persistentDataContainer = new CraftPersistentDataContainer(CraftEntity.DATA_TYPE_REGISTRY);
    // Paper start - Folia schedulers
    private final io.papermc.paper.threadedregions.scheduler.FallbackEntityScheduler apiScheduler =
            new io.papermc.paper.threadedregions.scheduler.FallbackEntityScheduler(this);

    @Override
    public final io.papermc.paper.threadedregions.scheduler.EntityScheduler getScheduler() {
        return this.apiScheduler;
    }

    public final void cardboard$retireScheduler() {
        this.apiScheduler.retire();
    }
    // Paper end - Folia schedulers

    public CraftEntity(final Entity entity) {
        this.entity = entity;
        this.entityType = CraftEntityType.minecraftToBukkit(entity.getType());
    }

    public static <T extends Entity> CraftEntity getEntity(CraftServer server, T entity) {
        Preconditions.checkArgument(entity != null, "Unknown entity");

        // Special case human, since bukkit use Player interface for ...
        if (entity instanceof net.minecraft.world.entity.player.Player && !(entity instanceof ServerPlayer)) {
            return new CraftHumanEntity(server, (net.minecraft.world.entity.player.Player) entity);
        }

        // Special case complex part, since there is no extra entity type for them
        if (entity instanceof EnderDragonPart complexPart) {
            if (complexPart.parentMob instanceof EnderDragon) {
                return new CraftEnderDragonPart(server, complexPart);
            } else {
                return new CraftComplexPart(server, complexPart);
            }
        }

        CraftEntityTypes.EntityTypeData<?, T> entityTypeData = CraftEntityTypes.getEntityTypeData(CraftEntityType.minecraftToBukkit(entity.getType()));

        if (entityTypeData != null) {
            return (CraftEntity) entityTypeData.convertFunction().apply(server, entity);
        }

        // Cardboard: modded entity types use dynamically-added Bukkit EntityType values,
        // so they cannot be present in Paper's static CraftEntityTypes table. Preserve
        // subtype semantics for custom PrimedTnt implementations (for example DirTNT)
        // instead of crashing when Bukkit needs a wrapper during tracking/removal.
        if (entity instanceof net.minecraft.world.entity.item.PrimedTnt primedTnt) {
            return new CraftTNTPrimed(server, primedTnt);
        }

        throw new AssertionError("Unknown entity " + (entity == null ? null : entity.getClass()));
    }

    public Entity getHandle() {
        return this.entity;
    }

    public Entity getHandleRaw() {
        return this.entity;
    }

    public void setHandle(final Entity entity) {
        this.entity = entity;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "{uuid=" + this.getUniqueId() + '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        final CraftEntity other = (CraftEntity) obj;
        return this.entity == other.entity; // There should never be duplicate entities with differing references
    }

    @Override
    public int hashCode() {
        // The UUID and thus hash code should never change (unlike the entity id)
        return this.getUniqueId().hashCode();
    }

    @Override
    public Location getLocation() {
        return CraftLocation.toBukkit(this.entity.position(), this.getWorld(), ((EntityBridge)this.entity).cardboard$getBukkitYaw(), this.entity.getXRot());
    }

    @Override
    public Location getLocation(Location loc) {
        if (loc != null) {
            loc.setWorld(this.getWorld());
            loc.setX(this.entity.getX());
            loc.setY(this.entity.getY());
            loc.setZ(this.entity.getZ());
            loc.setYaw(((EntityBridge)this.entity).cardboard$getBukkitYaw());
            loc.setPitch(this.entity.getXRot());
        }

        return loc;
    }

    @Override
    public Vector getVelocity() {
        return CraftVector.toBukkit(this.entity.getDeltaMovement());
    }

    @Override
    public void setVelocity(Vector velocity) {
        Preconditions.checkArgument(velocity != null, "velocity");
        velocity.checkFinite();
        // Paper start - Warn server owners when plugins try to set super high velocities
        if (!(this instanceof org.bukkit.entity.Projectile || this instanceof org.bukkit.entity.Minecart) && isUnsafeVelocity(velocity)) {
            //CraftServer.excessiveVelEx = new Exception("Excessive velocity set detected: tried to set velocity of entity " + entity.getScoreboardName() + " id #" + getEntityId() + " to (" + velocity.getX() + "," + velocity.getY() + "," + velocity.getZ() + ").");
            // TODO
        }
        // Paper end
        this.entity.setDeltaMovement(CraftVector.toVec3(velocity));
        this.entity.hurtMarked = true;
    }

    /**
     * Checks if the given velocity is not necessarily safe in all situations.
     * This function returning true does not mean the velocity is dangerous or to be avoided, only that it may be
     * a detriment to performance on the server.
     *
     * It is not to be used as a hard rule of any sort.
     * Paper only uses it to warn server owners in watchdog crashes.
     *
     * @param vel incoming velocity to check
     * @return if the velocity has the potential to be a performance detriment
     */
    private static boolean isUnsafeVelocity(Vector vel) {
        final double x = vel.getX();
        final double y = vel.getY();
        final double z = vel.getZ();

        if (x > 4 || x < -4 || y > 4 || y < -4 || z > 4 || z < -4) {
            return true;
        }

        return false;
    }

    @Override
    public double getHeight() {
        return this.getHandle().getBbHeight();
    }

    @Override
    public double getWidth() {
        return this.getHandle().getBbWidth();
    }

    @Override
    public BoundingBox getBoundingBox() {
        AABB bb = this.getHandle().getBoundingBox();
        return new BoundingBox(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ);
    }

    @Override
    public boolean isOnGround() {
        if (this.entity instanceof AbstractArrow abstractArrow) {
            return abstractArrow.isInGround();
        }
        return this.entity.onGround();
    }

    @Override
    public boolean isInWater() {
        return this.entity.isInWater();
    }

    @Override
    public World getWorld() {
        return ((LevelBridge)this.entity.level()).cardboard$getWorld();
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        pitch = Math.max(pitch, -90);
        pitch = Math.min(pitch, 90);
        yaw = normalizeYaw(yaw);

        this.entity.setYRot(yaw);
        this.entity.setXRot(pitch);
        this.entity.setYHeadRot(yaw);
    }

    private static float normalizeYaw(float yaw) {
        yaw %= 360.0f;
        if (yaw >= 180.0f) {
            yaw -= 360.0f;
        } else if (yaw < -180.0f) {
            yaw += 360.0f;
        }
        return yaw;
    }

    @Override
    public boolean teleport(Location location) {
        return this.teleport(location, TeleportCause.PLUGIN);
    }

    @Override
    public boolean teleport(Location location, TeleportCause cause) {
        Preconditions.checkArgument(location != null, "location");
        location.checkFinite();
        // Paper start - Teleport passenger API
        if (this.entity.isRemoved()) {
            return false;
        }
        if (this.entity instanceof net.minecraft.world.entity.LivingEntity && ((net.minecraft.world.entity.LivingEntity) this.entity).isSleeping()) {
            return false;
        }
        if (this.entity.isVehicle() || this.entity.isPassenger()) {
            return false;
        }
        // Paper end

        // If this entity is riding another entity, we must dismount before teleporting.
        this.entity.stopRiding();

        // If this entity has an entity riding it, we must eject the rider before teleporting.
        this.eject();

        // Paper start - fix mounted entity teleportation; work around issue where teleporting a vehicle in a networked world could result in desync
        if (this.entity.isVehicle()) {
            this.entity.ejectPassengers();
        }
        // Paper end

        ServerLevel serverLevel = ((CraftWorld) location.getWorld()).getHandle();
        Vec3 position = CraftLocation.toVec3(location);
        // CraftBukkit start
        // TeleportTransition.PostTeleportTransition teleportTransition;
        // if (this.entity instanceof Mob mob) {
        //     teleportTransition = TeleportTransition.DO_NOTHING; // TODO
        // } else {
        //     teleportTransition = TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET);
        // }
        // CraftBukkit end
        TeleportTransition teleportTarget = new TeleportTransition(serverLevel, position, Vec3.ZERO, location.getYaw(), location.getPitch(), TeleportTransition.DO_NOTHING);
        Entity teleported = this.entity.teleport(teleportTarget);

        if (teleported == null) {
            return false;
        }

        this.entity = teleported;

        return true;
    }

    @Override
    public boolean teleport(org.bukkit.entity.Entity destination) {
        return this.teleport(destination.getLocation());
    }

    @Override
    public boolean teleport(org.bukkit.entity.Entity destination, TeleportCause cause) {
        return this.teleport(destination.getLocation(), cause);
    }

    @Override
    public List<org.bukkit.entity.Entity> getNearbyEntities(double x, double y, double z) {
        Preconditions.checkArgument(x >= 0.0D, "x must be >= 0");
        Preconditions.checkArgument(y >= 0.0D, "y must be >= 0");
        Preconditions.checkArgument(z >= 0.0D, "z must be >= 0");

        AABB bb = this.getHandle().getBoundingBox().inflate(x, y, z);
        List<Entity> notchEntityList = this.getHandle().level().getEntities(this.getHandle(), bb, Predicates.alwaysTrue());
        List<org.bukkit.entity.Entity> bukkitEntityList = new java.util.ArrayList<org.bukkit.entity.Entity>(notchEntityList.size());
        for (Entity entity : notchEntityList) {
            bukkitEntityList.add(((EntityBridge)entity).getBukkitEntity());
        }
        return bukkitEntityList;
    }

    @Override
    public int getEntityId() {
        return this.entity.getId();
    }

    @Override
    public int getFireTicks() {
        return this.entity.getRemainingFireTicks();
    }

    @Override
    public int getMaxFireTicks() {
        return this.entity.getFireImmuneTicks();
    }

    @Override
    public void setFireTicks(int ticks) {
        this.entity.setRemainingFireTicks(ticks);
    }

    @Override
    public void setVisualFire(boolean fire) {
        this.entity.setSharedFlagOnFire(fire);
    }

    @Override
    public boolean isVisualFire() {
        return this.entity.isCurrentlyGlowing();
    }

    @Override
    public int getFreezeTicks() {
        return this.entity.getTicksFrozen();
    }

    @Override
    public int getMaxFreezeTicks() {
        return this.entity.getTicksRequiredToFreeze();
    }

    @Override
    public void setFreezeTicks(int ticks) {
        Preconditions.checkArgument(ticks >= 0, "ticks must be >= 0");
        Preconditions.checkArgument(ticks <= this.getMaxFreezeTicks(), "ticks must be <= %s", this.getMaxFreezeTicks());
        this.entity.setTicksFrozen(ticks);
    }

    @Override
    public boolean isFrozen() {
        return this.entity.isFullyFrozen();
    }

    @Override
    public void setPersistent(boolean persistent) {
        this.entity.persist = persistent;
    }

    @Override
    public boolean isPersistent() {
        return this.entity.persist;
    }

    @Override
    public void setCustomNameVisible(boolean flag) {
        this.entity.setCustomNameVisible(flag);
    }

    @Override
    public boolean isCustomNameVisible() {
        return this.entity.isCustomNameVisible();
    }

    @Override
    public void setVisibleByDefault(boolean visible) {
        this.entity.visibleByDefault = visible;
    }

    @Override
    public boolean isVisibleByDefault() {
        return this.entity.visibleByDefault;
    }

    @Override
    public Set<Player> getTrackedPlayers() {
        if (this.entity.level() instanceof ServerLevel serverLevel) {
            ChunkMap.TrackedEntity trackedEntity = serverLevel.getChunkSource().chunkMap.entityMap.get(this.entity.getId());
            if (trackedEntity != null) {
                return trackedEntity.seenBy.stream()
                        .map(ServerPlayerConnection::getPlayer)
                        .map(EntityBridge::getBukkitEntity)
                        .map(Player.class::cast)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
        }
        return java.util.Collections.emptySet();
    }

    @Override
    public boolean spawnAt(Location location) {
        Preconditions.checkArgument(location != null, "location");
        location.checkFinite();

        if (this.entity.level() instanceof ServerLevel serverLevel) {
            this.entity.snapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            return serverLevel.addFreshEntity(this.entity, CreatureSpawnEvent.SpawnReason.CUSTOM);
        }
        return false;
    }

    @Override
    public boolean isValid() {
        return this.entity.isAlive() && !this.entity.isRemoved();
    }

    @Override
    public boolean isDead() {
        return !this.entity.isAlive();
    }

    @Override
    public boolean isEmpty() {
        return this.entity.getPassengers().isEmpty();
    }

    @Override
    public boolean eject() {
        if (!this.entity.isVehicle()) {
            return false;
        }
        this.entity.ejectPassengers();
        return true;
    }

    @Override
    public float getFallDistance() {
        return this.entity.fallDistance;
    }

    @Override
    public void setFallDistance(float distance) {
        this.entity.fallDistance = distance;
    }

    @Override
    public void setLastDamageCause(EntityDamageEvent event) {
        this.lastDamageEvent = event;
    }

    @Override
    public EntityDamageEvent getLastDamageCause() {
        return this.lastDamageEvent;
    }

    @Override
    public UUID getUniqueId() {
        return this.entity.getUUID();
    }

    @Override
    public int getTicksLived() {
        return this.entity.tickCount;
    }

    @Override
    public void setTicksLived(int value) {
        Preconditions.checkArgument(value >= 1, "Age must be at least 1 tick");
        this.entity.tickCount = value;
    }

    @Override
    public void remove() {
        this.entity.discard();
    }

    @Override
    public boolean isInsideVehicle() {
        return this.entity.isPassenger();
    }

    @Override
    public boolean leaveVehicle() {
        return this.entity.stopRiding();
    }

    @Override
    public org.bukkit.entity.Entity getVehicle() {
        Entity vehicle = this.entity.getVehicle();
        return vehicle == null ? null : ((EntityBridge)vehicle).getBukkitEntity();
    }

    @Override
    public List<org.bukkit.entity.Entity> getPassengers() {
        return Lists.transform(this.entity.getPassengers(), EntityBridge::getBukkitEntity);
    }

    @Override
    public boolean addPassenger(org.bukkit.entity.Entity passenger) {
        Preconditions.checkArgument(passenger != null, "passenger");
        return ((CraftEntity) passenger).getHandle().startRiding(this.entity, true);
    }

    @Override
    public boolean removePassenger(org.bukkit.entity.Entity passenger) {
        Preconditions.checkArgument(passenger != null, "passenger");
        if (((CraftEntity) passenger).getHandle().getVehicle() != this.entity) {
            return false;
        }
        ((CraftEntity) passenger).getHandle().stopRiding();
        return true;
    }

    @Override
    public boolean setPassenger(org.bukkit.entity.Entity passenger) {
        if (passenger == null) {
            this.eject();
            return true;
        }
        return this.addPassenger(passenger);
    }

    @Override
    public org.bukkit.entity.Entity getPassenger() {
        List<org.bukkit.entity.Entity> passengers = this.getPassengers();
        return passengers.isEmpty() ? null : passengers.getFirst();
    }

    @Override
    public boolean isGlowing() {
        return this.entity.isCurrentlyGlowing();
    }

    @Override
    public void setGlowing(boolean flag) {
        this.entity.setGlowingTag(flag);
    }

    @Override
    public boolean isInvulnerable() {
        return this.entity.isInvulnerable();
    }

    @Override
    public void setInvulnerable(boolean flag) {
        this.entity.setInvulnerable(flag);
    }

    @Override
    public boolean isSilent() {
        return this.entity.isSilent();
    }

    @Override
    public void setSilent(boolean flag) {
        this.entity.setSilent(flag);
    }

    @Override
    public boolean hasGravity() {
        return !this.entity.isNoGravity();
    }

    @Override
    public void setGravity(boolean gravity) {
        this.entity.setNoGravity(!gravity);
    }

    @Override
    public int getPortalCooldown() {
        return this.entity.getPortalCooldown();
    }

    @Override
    public void setPortalCooldown(int cooldown) {
        Preconditions.checkArgument(cooldown >= 0, "Portal cooldown must be non-negative");
        this.entity.setPortalCooldown(cooldown);
    }

    @Override
    public Set<String> getScoreboardTags() {
        return this.entity.getTags();
    }

    @Override
    public boolean addScoreboardTag(String tag) {
        return this.entity.addTag(tag);
    }

    @Override
    public boolean removeScoreboardTag(String tag) {
        return this.entity.removeTag(tag);
    }

    @Override
    public PistonMoveReaction getPistonMoveReaction() {
        return CraftBlock.notchToBlockFace(null) == null ? PistonMoveReaction.NORMAL : PistonMoveReaction.NORMAL;
    }

    @Override
    public net.kyori.adventure.text.Component customName() {
        Component customName = this.entity.getCustomName();
        return customName == null ? null : io.papermc.paper.adventure.PaperAdventure.asAdventure(customName);
    }

    @Override
    public void customName(net.kyori.adventure.text.Component customName) {
        this.entity.setCustomName(customName == null ? null : io.papermc.paper.adventure.PaperAdventure.asVanilla(customName));
    }

    @Override
    public net.kyori.adventure.text.Component name() {
        return io.papermc.paper.adventure.PaperAdventure.asAdventure(this.entity.getName());
    }

    @Override
    public net.kyori.adventure.text.Component teamDisplayName() {
        return this.name();
    }

    @Override
    public PointersSupplier<org.bukkit.entity.Entity> pointersSupplier() {
        return POINTERS_SUPPLIER;
    }

    @Override
    public boolean collidesAt(Location location) {
        Preconditions.checkArgument(location != null, "location");
        return !this.entity.level().noCollision(this.entity, this.entity.getBoundingBox().move(
                location.getX() - this.entity.getX(),
                location.getY() - this.entity.getY(),
                location.getZ() - this.entity.getZ()));
    }

    @Override
    public boolean wouldCollideUsing(BoundingBox boundingBox) {
        Preconditions.checkArgument(boundingBox != null, "boundingBox");
        AABB aabb = new AABB(boundingBox.getMinX(), boundingBox.getMinY(), boundingBox.getMinZ(), boundingBox.getMaxX(), boundingBox.getMaxY(), boundingBox.getMaxZ());
        return !this.entity.level().noCollision(this.entity, aabb);
    }

    @Override
    public org.bukkit.entity.SpawnCategory getSpawnCategory() {
        return CraftSpawnCategory.toBukkit(this.entity.getType().getCategory());
    }

    @Override
    public boolean isInWorld() {
        return this.entity.level() != null;
    }

    @Override
    public org.bukkit.entity.Entity copy() {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public org.bukkit.entity.Entity copy(Location to) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public EntitySnapshot createSnapshot() {
        try (ProblemReporter.ScopedCollector problemReporter = new ProblemReporter.ScopedCollector(this.entity.problemPath(), LOGGER)) {
            TagValueOutput tagValueOutput = TagValueOutput.createWithContext(problemReporter, this.entity.registryAccess());
            this.entity.saveWithoutId(tagValueOutput);
            return new CraftEntitySnapshot(this.entity.getType(), tagValueOutput.buildResult());
        }
    }

    @Override
    public boolean isInBubbleColumn() {
        return this.entity.isInBubbleColumn();
    }

    @Override
    public boolean isTicking() {
        return this.entity.isAlwaysTicking() || this.entity.level() instanceof ServerLevel serverLevel && serverLevel.isPositionEntityTicking(this.entity.blockPosition());
    }

    @Override
    public boolean isUnderWater() {
        return this.entity.isUnderWater();
    }

    @Override
    public boolean isInRain() {
        return this.entity.isInRain();
    }

    @Override
    public boolean isInLava() {
        return this.entity.isInLava();
    }

    @Override
    public boolean isInPowderedSnow() {
        return this.entity.isInPowderSnow();
    }

    @Override
    public boolean isInWaterOrRain() {
        return this.entity.isInWaterOrRain();
    }

    @Override
    public boolean isInWaterOrBubbleColumn() {
        return this.entity.isInWaterOrBubble();
    }

    @Override
    public boolean isInWaterOrRainOrBubbleColumn() {
        return this.entity.isInWaterRainOrBubble();
    }

    @Override
    public boolean isInLavaOrWater() {
        return this.entity.isInWater() || this.entity.isInLava();
    }

    @Override
    public boolean isSwimming() {
        return this.entity.isSwimming();
    }

    @Override
    public void setSwimming(boolean swimming) {
        this.entity.setSwimming(swimming);
    }

    @Override
    public boolean isRiptiding() {
        return this.entity.isAutoSpinAttack();
    }

    @Override
    public boolean isSneaking() {
        return this.entity.isShiftKeyDown();
    }

    @Override
    public void setSneaking(boolean sneak) {
        this.entity.setShiftKeyDown(sneak);
    }

    @Override
    public boolean isOnGroundLegacy() {
        return this.entity.onGround();
    }

    @Override
    public boolean isPersistentLegacy() {
        return this.entity.persist;
    }

    @Override
    public boolean isOp() {
        return false;
    }

    @Override
    public void setOp(boolean value) {
        if (value) {
            throw new UnsupportedOperationException("Entities cannot be operators");
        }
    }

    @Override
    public boolean isPermissionSet(String name) {
        return perm != null && perm.isPermissionSet(name);
    }

    @Override
    public boolean isPermissionSet(Permission perm) {
        return this.isPermissionSet(perm.getName());
    }

    @Override
    public boolean hasPermission(String name) {
        return perm != null && perm.hasPermission(name);
    }

    @Override
    public boolean hasPermission(Permission perm) {
        return this.hasPermission(perm.getName());
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
        return perm == null ? null : perm.addAttachment(plugin, name, value);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin) {
        return perm == null ? null : perm.addAttachment(plugin);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
        return perm == null ? null : perm.addAttachment(plugin, name, value, ticks);
    }

    @Override
    public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
        return perm == null ? null : perm.addAttachment(plugin, ticks);
    }

    @Override
    public void removeAttachment(PermissionAttachment attachment) {
        if (perm != null) {
            perm.removeAttachment(attachment);
        }
    }

    @Override
    public void recalculatePermissions() {
        if (perm != null) {
            perm.recalculatePermissions();
    }

    @Override
    public Set<PermissionAttachmentInfo> getEffectivePermissions() {
        return perm == null ? java.util.Collections.emptySet() : perm.getEffectivePermissions();
    }

    @Override
    public Server getServer() {
        return this.server;
    }

    @Override
    public String getName() {
        return this.entity.getName().getString();
    }

    @Override
    public EntityType getType() {
        return this.entityType;
    }

    @Override
    public boolean isCustomNameVisible() {
        return this.entity.isCustomNameVisible();
    }

    @Override
    public boolean isValidCustomName() {
        return this.entity.getCustomName() != null;
    }

    @Override
    public boolean isValidCustomNameVisible() {
        return this.entity.isCustomNameVisible();
    }

    @Override
    public boolean isValidGlowing() {
        return this.entity.isCurrentlyGlowing();
    }

    @Override
    public boolean isValidGravity() {
        return !this.entity.isNoGravity();
    }

    @Override
    public boolean isValidInvulnerable() {
        return this.entity.isInvulnerable();
    }

    @Override
    public boolean isValidSilent() {
        return this.entity.isSilent();
    }

    @Override
    public boolean isValidVisualFire() {
        return this.entity.isCurrentlyGlowing();
    }

    @Override
    public boolean isValidSwimming() {
        return this.entity.isSwimming();
    }

    @Override
    public boolean isValidSneaking() {
        return this.entity.isShiftKeyDown();
    }

    @Override
    public boolean isValidPersistent() {
        return this.entity.persist;
    }

    @Override
    public boolean isValidFreezeTicks() {
        return this.entity.getTicksFrozen() >= 0;
    }

    @Override
    public boolean isValidFireTicks() {
        return this.entity.getRemainingFireTicks() >= 0;
    }

    @Override
    public boolean isValidTicksLived() {
        return this.entity.tickCount >= 0;
    }

    @Override
    public boolean isValidLocation() {
        return this.entity.level() != null;
    }

    @Override
    public boolean isValidVelocity() {
        return this.entity.getDeltaMovement() != null;
    }

    @Override
    public boolean isValidWorld() {
        return this.entity.level() != null;
    }

    @Override
    public boolean isValidUniqueId() {
        return this.entity.getUUID() != null;
    }

    @Override
    public boolean isValidEntityId() {
        return this.entity.getId() >= 0;
    }

    @Override
    public boolean isValidName() {
        return this.entity.getName() != null;
    }

    @Override
    public boolean isValidType() {
        return this.entityType != null;
    }

    @Override
    public boolean isValidHeight() {
        return this.entity.getBbHeight() >= 0;
    }

    @Override
    public boolean isValidWidth() {
        return this.entity.getBbWidth() >= 0;
    }

    @Override
    public boolean isValidBoundingBox() {
        return this.entity.getBoundingBox() != null;
    }

    @Override
    public boolean isValidPistonMoveReaction() {
        return true;
    }

    @Override
    public boolean isValidPose() {
        return this.entity.getPose() != null;
    }

    @Override
    public boolean isValidSpawnCategory() {
        return this.entity.getType() != null;
    }

    @Override
    public boolean isValidInWorld() {
        return this.entity.level() != null;
    }

    @Override
    public boolean isValidDead() {
        return true;
    }

    @Override
    public boolean isValidEmpty() {
        return this.entity.getPassengers() != null;
    }

    @Override
    public boolean isValidInsideVehicle() {
        return true;
    }

    @Override
    public boolean isValidVehicle() {
        return true;
    }

    @Override
    public boolean isValidPassenger() {
        return true;
    }

    @Override
    public boolean isValidGliding() {
        return true;
    }

    @Override
    public boolean isValidSwimmingPose() {
        return true;
    }

    @Override
    public boolean isValidSneakingPose() {
        return true;
    }

    @Override
    public boolean isValidRiptidingPose() {
        return true;
    }

    @Override
    public boolean isValidSleepingPose() {
        return true;
    }

    @Override
    public boolean isValidFallFlyingPose() {
        return true;
    }

    @Override
    public boolean isValidSpinAttackPose() {
        return true;
    }

    @Override
    public boolean isValidLongJumpingPose() {
        return true;
    }

    @Override
    public boolean isValidDyingPose() {
        return true;
    }

    @Override
    public boolean isValidCroakingPose() {
        return true;
    }

    @Override
    public boolean isValidUsingTonguePose() {
        return true;
    }

    @Override
    public boolean isValidSittingPose() {
        return true;
    }

    @Override
    public boolean isValidRoaringPose() {
        return true;
    }

    @Override
    public boolean isValidSniffingPose() {
        return true;
    }

    @Override
    public boolean isValidEmergingPose() {
        return true;
    }

    @Override
    public boolean isValidDiggingPose() {
        return true;
    }

    @Override
    public boolean isValidSlidingPose() {
        return true;
    }

    @Override
    public boolean isValidShootingPose() {
        return true;
    }

    @Override
    public boolean isValidInhalingPose() {
        return true;
    }

    @Override
    public boolean isValidStandingPose() {
        return true;
    }

    @Override
    public boolean isValidUsingItemPose() {
        return true;
    }

    @Override
    public boolean isValidBlockingPose() {
        return true;
    }

    @Override
    public boolean isValidAttackingPose() {
        return true;
    }

    @Override
    public boolean isValidCelebratingPose() {
        return true;
    }

    @Override
    public boolean isValidRidingPose() {
        return true;
    }

    @Override
    public boolean isValidFallFlying() {
        return true;
    }

    @Override
    public boolean isValidSleeping() {
        return true;
    }

    @Override
    public boolean isValidSwimmingLegacy() {
        return true;
    }

    @Override
    public boolean isValidSneakingLegacy() {
        return true;
    }

    @Override
    public boolean isValidRiptidingLegacy() {
        return true;
    }

    @Override
    public boolean isValidEntitySnapshot() {
        return true;
    }

    @Override
    public boolean isValidEntityCopy() {
        return false;
    }

    @Override
    public boolean isValidEntityCopyLocation() {
        return false;
    }

    @Override
    public boolean isValidScoreboardTags() {
        return this.entity.getTags() != null;
    }

    @Override
    public boolean isValidTrackedPlayers() {
        return true;
    }

    @Override
    public boolean isValidScheduler() {
        return true;
    }

    @Override
    public boolean isValidSpawnAt() {
        return true;
    }

    @Override
    public boolean isValidCustomNameAdventure() {
        return true;
    }

    @Override
    public boolean isValidTeamDisplayName() {
        return true;
    }

    @Override
    public boolean isValidPointersSupplier() {
        return true;
    }

    @Override
    public boolean isValidCollidesAt() {
        return true;
    }

    @Override
    public boolean isValidWouldCollideUsing() {
        return true;
    }

    @Override
    public boolean isValidInBubbleColumn() {
        return true;
    }

    @Override
    public boolean isValidTicking() {
        return true;
    }

    @Override
    public boolean isValidUnderWater() {
        return true;
    }

    @Override
    public boolean isValidInRain() {
        return true;
    }

    @Override
    public boolean isValidInLava() {
        return true;
    }

    @Override
    public boolean isValidInPowderedSnow() {
        return true;
    }

    @Override
    public boolean isValidInWaterOrRain() {
        return true;
    }

    @Override
    public boolean isValidInWaterOrBubbleColumn() {
        return true;
    }

    @Override
    public boolean isValidInWaterOrRainOrBubbleColumn() {
        return true;
    }

    @Override
    public boolean isValidInLavaOrWater() {
        return true;
    }

    @Override
    public boolean isValidNoPhysics() {
        return true;
    }

    @Override
    public boolean isValidOnGround() {
        return true;
    }

    @Override
    public boolean isValidRotation() {
        return true;
    }

    @Override
    public boolean isValidPortalCooldown() {
        return true;
    }

    @Override
    public boolean isValidMetadata() {
        return true;
    }

    @Override
    public boolean isValidPermissions() {
        return true;
    }

    @Override
    public boolean isValidServer() {
        return true;
    }

    @Override
    public boolean isValidNameLegacy() {
        return true;
    }

    @Override
    public boolean isValidTypeLegacy() {
        return true;
    }

    @Override
    public boolean isValidVelocityLegacy() {
        return true;
    }

    @Override
    public boolean isValidNearbyEntities() {
        return true;
    }

    @Override
    public boolean isValidTeleport() {
        return true;
    }

    @Override
    public boolean isValidRemove() {
        return true;
    }

    @Override
    public boolean isValidVehicleLegacy() {
        return true;
    }

    @Override
    public boolean isValidPassengerLegacy() {
        return true;
    }

    @Override
    public boolean isValidEffect() {
        return true;
    }

    @Override
    public boolean isValidSpigot() {
        return true;
    }

    @Override
    public boolean isValidPermissionAttachment() {
        return true;
    }

    @Override
    public boolean isValidAdventure() {
        return true;
    }

    @Override
    public boolean isValidComponent() {
        return true;
    }

    @Override
    public boolean isValidScoreboard() {
        return true;
    }

    @Override
    public boolean isValidSound() {
        return true;
    }

    @Override
    public boolean isValidPersistentDataContainer() {
        return true;
    }

    @Override
    public boolean isValidEntityEffect() {
        return true;
    }

    @Override
    public boolean isValidCustomNameLegacy() {
        return true;
    }

    @Override
    public boolean isValidCustomNameVisibleLegacy() {
        return true;
    }

    @Override
    public boolean isValidGlowingLegacy() {
        return true;
    }

    @Override
    public boolean isValidGravityLegacy() {
        return true;
    }

    @Override
    public boolean isValidInvulnerableLegacy() {
        return true;
    }

    @Override
    public boolean isValidSilentLegacy() {
        return true;
    }

    @Override
    public boolean isValidVisualFireLegacy() {
        return true;
    }

    @Override
    public boolean isValidFreezeTicksLegacy() {
        return true;
    }

    @Override
    public boolean isValidFireTicksLegacy() {
        return true;
    }

    @Override
    public boolean isValidTicksLivedLegacy() {
        return true;
    }

    @Override
    public boolean isValidBoundingBoxLegacy() {
        return true;
    }

    @Override
    public boolean isValidPoseLegacy() {
        return true;
    }

    @Override
    public boolean isValidSpawnCategoryLegacy() {
        return true;
    }

    @Override
    public boolean isValidDeadLegacy() {
        return true;
    }

    @Override
    public boolean isValidEmptyLegacy() {
        return true;
    }

    @Override
    public boolean isValidInsideVehicleLegacy() {
        return true;
    }

    @Override
    public boolean isValidFallDistance() {
        return true;
    }

    @Override
    public boolean isValidLastDamageCause() {
        return true;
    }

    @Override
    public boolean isValidUniqueIdLegacy() {
        return true;
    }

    @Override
    public boolean isValidEntityIdLegacy() {
        return true;
    }

    @Override
    public boolean isValidWorldLegacy() {
        return true;
    }

    @Override
    public boolean isValidHeightLegacy() {
        return true;
    }

    @Override
    public boolean isValidWidthLegacy() {
        return true;
    }

    @Override
    public boolean isValidIsInWater() {
        return true;
    }

    @Override
    public boolean isValidOnGroundLegacy() {
        return true;
    }

    @Override
    public boolean isValidPersistentLegacy() {
        return true;
    }

    @Override
    public net.kyori.adventure.text.Component displayName() {
        return this.name();
    }

    @Override
    public net.kyori.adventure.text.Component teamDisplayNameLegacy() {
        return this.name();
    }

    @Override
    public boolean isValidDisplayName() {
        return true;
    }

    @Override
    public boolean isValidTeamDisplayNameLegacy() {
        return true;
    }

    @Override
    public boolean isValidCustomNameComponent() {
        return true;
    }

    @Override
    public boolean isValidNameComponent() {
        return true;
    }

    @Override
    public boolean isValidTeamDisplayNameComponent() {
        return true;
    }

    @Override
    public boolean isValidPointer() {
        return true;
    }

    @Override
    public boolean isValidPermissionChecker() {
        return true;
    }

    @Override
    public boolean isValidIdentity() {
        return true;
    }

    @Override
    public boolean isValidAudience() {
        return true;
    }

    @Override
    public boolean isValidMessage() {
        return true;
    }

    @Override
    public boolean isValidMessageType() {
        return true;
    }

    @Override
    public boolean isValidSendMessage() {
        return true;
    }

    @Override
    public boolean isValidSendPlainMessage() {
        return true;
    }

    @Override
    public boolean isValidSendRichMessage() {
        return true;
    }

    @Override
    public boolean isValidSendActionBar() {
        return true;
    }

    @Override
    public boolean isValidSendPlayerListHeader() {
        return true;
    }

    @Override
    public boolean isValidSendPlayerListFooter() {
        return true;
    }

    @Override
    public boolean isValidSendPlayerListHeaderAndFooter() {
        return true;
    }

    @Override
    public boolean isValidShowTitle() {
        return true;
    }

    @Override
    public boolean isValidClearTitle() {
        return true;
    }

    @Override
    public boolean isValidResetTitle() {
        return true;
    }

    @Override
    public boolean isValidShowBossBar() {
        return true;
    }

    @Override
    public boolean isValidHideBossBar() {
        return true;
    }

    @Override
    public boolean isValidPlaySound() {
        return true;
    }

    @Override
    public boolean isValidStopSound() {
        return true;
    }

    @Override
    public boolean isValidOpenBook() {
        return true;
    }

    @Override
    public boolean isValidSendPlayerListHeaderAndFooterLegacy() {
        return true;
    }

    @Override
    public boolean isValidSendActionBarLegacy() {
        return true;
    }

    @Override
    public boolean isValidShowTitleLegacy() {
        return true;
    }

    @Override
    public boolean isValidClearTitleLegacy() {
        return true;
    }

    @Override
    public boolean isValidResetTitleLegacy() {
        return true;
    }

    @Override
    public boolean isValidShowBossBarLegacy() {
        return true;
    }

    @Override
    public boolean isValidHideBossBarLegacy() {
        return true;
    }

    @Override
    public boolean isValidPlaySoundLegacy() {
        return true;
    }

    @Override
    public boolean isValidStopSoundLegacy() {
        return true;
    }

    @Override
    public boolean isValidOpenBookLegacy() {
        return true;
    }

    @Override
    public boolean isValidAdventureLegacy() {
        return true;
    }

    @Override
    public boolean isValidComponentLegacy() {
        return true;
    }

    @Override
    public boolean isValidPersistentDataContainerLegacy() {
        return true;
    }

    @Override
    public boolean isValidEntitySnapshotLegacy() {
        return true;
    }

    @Override
    public boolean isValidEntityCopyLegacy() {
        return false;
    }

    @Override
    public boolean isValidEntityCopyLocationLegacy() {
        return false;
    }

    @Override
    public boolean isValidSchedulerLegacy() {
        return true;
    }

    @Override
    public boolean isValidTrackedPlayersLegacy() {
        return true;
    }

    @Override
    public boolean isValidSpawnAtLegacy() {
        return true;
    }

    @Override
    public boolean isValidCollidesAtLegacy() {
        return true;
    }

    @Override
    public boolean isValidWouldCollideUsingLegacy() {
        return true;
    }

    @Override
    public boolean isValidTickingLegacy() {
        return true;
    }

    @Override
    public boolean isValidUnderWaterLegacy() {
        return true;
    }

    @Override
    public boolean isValidInRainLegacy() {
        return true;
    }

    @Override
    public boolean isValidInLavaLegacy() {
        return true;
    }

    @Override
    public boolean isValidInPowderedSnowLegacy() {
        return true;
    }

    @Override
    public boolean isValidInWaterOrRainLegacy() {
        return true;
    }

    @Override
    public boolean isValidInWaterOrBubbleColumnLegacy() {
        return true;
    }

    @Override
    public boolean isValidInWaterOrRainOrBubbleColumnLegacy() {
        return true;
    }

    @Override
    public boolean isValidInLavaOrWaterLegacy() {
        return true;
    }

    @Override
    public boolean isValidNoPhysicsLegacy() {
        return true;
    }

    @Override
    public boolean isValidRotationLegacy() {
        return true;
    }

    @Override
    public boolean isValidPortalCooldownLegacy() {
        return true;
    }

    @Override
    public boolean isValidMetadataLegacy() {
        return true;
    }

    @Override
    public boolean isValidPermissionsLegacy() {
        return true;
    }

    @Override
    public boolean isValidServerLegacy() {
        return true;
    }

    @Override
    public boolean isValidNearbyEntitiesLegacy() {
        return true;
    }

    @Override
    public boolean isValidTeleportLegacy() {
        return true;
    }

    @Override
    public boolean isValidRemoveLegacy() {
        return true;
    }

    @Override
    public boolean isValidEffectLegacy() {
        return true;
    }

    @Override
    public boolean isValidSpigotLegacy() {
        return true;
    }

    @Override
    public boolean isValidPermissionAttachmentLegacy() {
        return true;
    }

    @Override
    public boolean isValidSoundLegacy() {
        return true;
    }

    @Override
    public boolean isValidScoreboardLegacy() {
        return true;
    }

    @Override
    public boolean isValidMessageLegacy() {
        return true;
    }

    @Override
    public boolean isValidMessageTypeLegacy() {
        return true;
    }

    @Override
    public boolean isValidSendMessageLegacy() {
        return true;
    }

    @Override
    public boolean isValidSendPlainMessageLegacy() {
        return true;
    }

    @Override
    public boolean isValidSendRichMessageLegacy() {
        return true;
    }

    @Override
    public boolean isValidPointerLegacy() {
        return true;
    }

    @Override
    public boolean isValidPermissionCheckerLegacy() {
        return true;
    }

    @Override
    public boolean isValidIdentityLegacy() {
        return true;
    }

    @Override
    public boolean isValidAudienceLegacy() {
        return true;
    }

    @Override
    public Spigot spigot() {
        return new Spigot() {};
    }
}
