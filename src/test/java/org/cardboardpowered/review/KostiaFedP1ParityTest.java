package org.cardboardpowered.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KostiaFedP1ParityTest {

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void clientBrandSurvivesConfigurationToPlayTransition() throws Exception {
        String bridge = source("src/main/java/org/cardboardpowered/bridge/network/ConnectionBridge.java");
        String connection = source("src/main/java/org/cardboardpowered/mixin/network/ConnectionMixin.java");
        String brand = source("src/main/java/org/cardboardpowered/mixin/server/network/ServerCommonPacketListenerImplMixin_Brand.java");

        assertTrue(bridge.contains("cardboard$getClientBrand()"));
        assertTrue(bridge.contains("cardboard$setClientBrand(String brand)"));
        assertTrue(connection.contains("private String cardboard$clientBrand"));
        assertTrue(brand.contains("((ConnectionBridge) this.connection).cardboard$setClientBrand(brandPayload.brand())"));
        assertTrue(brand.contains("((ConnectionBridge) this.connection).cardboard$getClientBrand()"));
        assertFalse(brand.contains("private String cardboard$clientBrand"));
    }

    @Test
    void dimensionTransferIsNotReportedAsPlayerQuit() throws Exception {
        String source = source("src/main/java/org/bukkit/craftbukkit/entity/CraftEntity.java");

        assertTrue(source.contains("cardboard$setRemoveEventCause(org.bukkit.event.entity.EntityRemoveEvent.Cause.PLUGIN)"));
        assertTrue(source.contains("return ((EntityBridge) this.entity).cardboard$getRemoveEventCause();"));
        assertFalse(source.contains("case KILLED -> org.bukkit.event.entity.EntityRemoveEvent.Cause.DEATH"));
        assertFalse(source.contains("case UNLOADED_WITH_PLAYER -> org.bukkit.event.entity.EntityRemoveEvent.Cause.PLAYER_QUIT"));
        assertFalse(source.contains("case CHANGED_DIMENSION ->"));
    }

    @Test
    void mobTargetWarningsAreScopedAndTargetReasonsPreserveOverrides() throws Exception {
        String mob = source("src/main/java/org/cardboardpowered/mixin/world/entity/MobMixin.java");
        String bridge = source("src/main/java/org/cardboardpowered/bridge/world/entity/MobBridge.java");
        String hurt = source("src/main/java/org/cardboardpowered/mixin/world/entity/ai/goal/target/HurtByTargetGoalMixin.java");

        assertTrue(mob.contains("ConcurrentHashMap.newKeySet()"));
        assertTrue(mob.contains("StackWalker.getInstance()"));
        assertTrue(mob.contains("mobType + '@' + callSite"));
        assertTrue(mob.contains("getTargetUnchecked()"));
        assertTrue(mob.contains("this.asValidTarget(target)"));
        assertTrue(bridge.contains("cardboard$setTargetWithReason"));
        assertTrue(hurt.contains("cardboard$setTargetWithReason"));
        assertFalse(mob.contains("AtomicBoolean cardboard$warnedUnknownTarget"));
    }

    @Test
    void bedCompatibilityDoesNotRegisterLegacyBedOrFakePersistentStorage() throws Exception {
        String states = source("src/main/java/org/bukkit/craftbukkit/block/CraftBlockStates.java");
        String bed = source("src/main/java/org/bukkit/craftbukkit/block/CraftBed.java");

        assertTrue(states.contains("material.isBlock() && !material.isLegacy() && material.name().endsWith(\"_BED\")"));
        assertTrue(bed.contains("UnsupportedOperationException"));
        assertFalse(bed.contains("private final org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer"));
    }

    @Test
    void creakingHeartDelegatesToVanillaHooks() throws Exception {
        String heart = source("src/main/java/org/bukkit/craftbukkit/block/CraftCreakingHeart.java");
        String accessor = source("src/main/java/org/cardboardpowered/mixin/world/level/block/entity/CreakingHeartBlockEntityAccessor.java");

        assertTrue(heart.contains("cardboard$clearCreakingInfo()"));
        assertTrue(heart.contains("cardboard$spawnProtector"));
        assertTrue(heart.contains("cardboard$spreadResin"));
        assertFalse(heart.contains("spreadResin is not supported on Cardboard"));
        assertFalse(heart.contains("spawnCreaking is not supported on Cardboard"));
        assertTrue(accessor.contains("@Invoker(\"clearCreakingInfo\")"));
    }

    @Test
    void playerPoseAndFlyingApisAreBackedByRuntimeState() throws Exception {
        String entity = source("src/main/java/org/bukkit/craftbukkit/entity/CraftEntity.java");
        String player = source("src/main/java/org/bukkit/craftbukkit/entity/CraftPlayer.java");

        assertTrue(entity.contains("bridge.cardboard$setFixedPose(fixed)"));
        assertTrue(entity.contains("cardboard$hasFixedPose()"));
        assertTrue(player.contains("cardboard$setFixedPose(false)"));
        assertTrue(player.contains("cardboard$updatePlayerPose()"));
        assertTrue(player.contains("connection.resetFlyingTicks()"));
    }

    @Test
    void cubeWanderStateIsPersistentAndAffectsGoals() throws Exception {
        String bridge = source("src/main/java/org/cardboardpowered/bridge/world/entity/monster/SlimeBridge.java");
        String mixin = source("src/main/java/org/cardboardpowered/mixin/world/entity/monster/SlimeMixin.java");
        String attackGoal = source("src/main/java/org/cardboardpowered/mixin/world/entity/monster/CubeMobAttackGoalMixin.java");
        String floatGoal = source("src/main/java/org/cardboardpowered/mixin/world/entity/monster/CubeMobFloatGoalMixin.java");
        String randomGoal = source("src/main/java/org/cardboardpowered/mixin/world/entity/monster/CubeMobRandomDirectionGoalMixin.java");
        String jumpingGoal = source("src/main/java/org/cardboardpowered/mixin/world/entity/monster/CubeMobKeepOnJumpingGoalMixin.java");
        String sulfur = source("src/main/java/org/bukkit/craftbukkit/entity/CraftSulfurCube.java");

        assertTrue(bridge.contains("cardboard$canWander()"));
        assertTrue(mixin.contains("\"Paper.canWander\""));
        assertTrue(attackGoal.contains("cardboard$canWander()"));
        assertTrue(floatGoal.contains("cardboard$canWander()"));
        assertTrue(randomGoal.contains("cardboard$canWander()"));
        assertTrue(jumpingGoal.contains("cardboard$canWander()"));
        assertTrue(attackGoal.contains("canContinueToUse"));
        assertTrue(sulfur.contains("cardboard$setWander(canWander)"));
    }

    @Test
    void tropicalFishBucketTracksComponentsIndependently() throws Exception {
        String source = source("src/main/java/org/bukkit/craftbukkit/inventory/CraftMetaTropicalFishBucket.java");

        assertTrue(source.contains("DataComponents.TROPICAL_FISH_PATTERN"));
        assertTrue(source.contains("DataComponents.TROPICAL_FISH_BASE_COLOR"));
        assertTrue(source.contains("DataComponents.TROPICAL_FISH_PATTERN_COLOR"));
        assertTrue(source.contains("return this.pattern != null;"));
        assertTrue(source.contains("return this.baseColor != null;"));
        assertTrue(source.contains("return this.patternColor != null;"));
        assertTrue(source.contains("this.isEmptyEntityTag(entityTag, net.minecraft.world.entity.EntityTypes.TROPICAL_FISH)"));
        assertTrue(source.contains("bucketEntityTag.getInt(CraftMetaTropicalFishBucket.VARIANT.NBT).ifPresent"));
        assertTrue(source.contains("return this.pattern != null && this.baseColor != null && this.patternColor != null;"));
        assertFalse(source.contains("public boolean hasPattern() {\n        return this.hasVariant();"));
    }
}
