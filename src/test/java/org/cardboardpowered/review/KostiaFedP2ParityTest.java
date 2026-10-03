package org.cardboardpowered.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KostiaFedP2ParityTest {

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void unsafeNextEntityIdUsesWorldAllocator() throws Exception {
        String magic = source("src/main/java/org/bukkit/craftbukkit/util/CraftMagicNumbers.java");
        assertTrue(magic.contains("@Override\n    public int nextEntityId(final org.bukkit.World world)"));
        assertTrue(magic.contains("getHandle().getNextEntityId()"));
        assertFalse(magic.contains("return 0;\n    }\n\n    @Override\n    public String getMainLevelName"));
    }

    @Test
    void entitySerializationDoesNotShadowOrMutatePassengers() throws Exception {
        String mixin = source("src/main/java/org/cardboardpowered/mixin/world/entity/EntityMixin.java");
        String bridge = source("src/main/java/org/cardboardpowered/bridge/world/entity/EntityBridge.java");
        String magic = source("src/main/java/org/bukkit/craftbukkit/util/CraftMagicNumbers.java");

        assertFalse(mixin.contains("@Shadow\n    private java.util.List<Entity> passengers"));
        assertTrue(mixin.contains("final java.util.List<Entity> originalPassengers = self.getPassengers()"));
        assertTrue(mixin.contains("output.discard(\"Passengers\")"));
        assertTrue(bridge.contains("boolean includePassengers"));
        assertTrue(magic.contains("forceSerialization,\n                            serializePassengers"));
        assertFalse(magic.contains("nmsEntity.passengers ="));
        assertTrue(magic.contains("e.getType().canSerialize() || allowMiscSerialization"));
        assertFalse(magic.contains("nmsEntity.getType().canSerialize() || allowMiscSerialization"));
    }

    @Test
    void itemStackOwnsMovedPaperApis() throws Exception {
        String stack = source("src/main/java/org/bukkit/craftbukkit/inventory/CraftItemStack.java");
        String magic = source("src/main/java/org/bukkit/craftbukkit/util/CraftMagicNumbers.java");

        assertTrue(stack.contains("public @NotNull String translationKey()"));
        assertTrue(stack.contains("public boolean isRepairableBy(@NotNull final ItemStack repairMaterial)"));
        assertTrue(stack.contains("computeTooltipLines("));
        assertFalse(magic.contains("isValidRepairItemStack("));
        assertFalse(magic.contains("computeTooltipLines("));
        assertFalse(magic.contains("getTranslationKey(ItemStack itemStack)"));
    }

    @Test
    void internalApiBridgeOwnsMovedInternals() throws Exception {
        String bridge = source("src/main/java/org/cardboardpowered/impl/PaperServerInternalAPIBridge.java");
        String magic = source("src/main/java/org/bukkit/craftbukkit/util/CraftMagicNumbers.java");

        assertTrue(bridge.contains("new org.bukkit.craftbukkit.damage.CraftDamageSourceBuilder(damageType)"));
        assertTrue(bridge.contains("CraftEntityType.bukkitToMinecraft(entityType).getDescriptionId()"));
        assertTrue(bridge.contains("ComponentUtils.resolve("));
        assertTrue(bridge.contains("withMaximumPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS)"));
        assertTrue(bridge.contains("VersionFetcher.DummyVersionFetcher()"));

        assertFalse(magic.contains("public DamageSource.Builder createDamageSourceBuilder("));
        assertFalse(magic.contains("public String getTranslationKey(EntityType entityType)"));
        assertFalse(magic.contains("public net.kyori.adventure.text.Component resolveWithContext("));
        assertFalse(magic.contains("public com.destroystokyo.paper.util.VersionFetcher getVersionFetcher()"));
    }

    @Test
    void removedUnsafeHelpersDoNotLingerAsDeadMethods() throws Exception {
        String magic = source("src/main/java/org/bukkit/craftbukkit/util/CraftMagicNumbers.java");

        for (String dead : new String[] {
                "colorDownsamplingGsonComponentSerializer()",
                "gsonComponentSerializer()",
                "plainTextSerializer()",
                "legacyComponentSerializer()",
                "getDefaultAttributeModifiers(Material material",
                "getCreativeCategory(Material material",
                "getBlockTranslationKey(Material material",
                "getItemTranslationKey(Material material",
                "getTranslationKey(final Attribute attribute)",
                "serializeItem(ItemStack item)",
                "serializeStack(final ItemStack itemStack)",
                "getBiomeKey(",
                "setBiomeKey(",
                "getSpawnEggLayerColor("
        }) {
            assertFalse(magic.contains(dead), dead + " should not remain on CraftMagicNumbers");
        }
    }
    @Test
    void combatTrackerAndPotionApisAreNotStubs() throws Exception {
        String wrapper = source("src/main/java/io/papermc/paper/world/damagesource/PaperCombatTrackerWrapper.java");
        String mixin = source("src/main/java/org/cardboardpowered/mixin/world/damagesource/CombatTrackerMixin.java");
        String potion = source("src/main/java/org/cardboardpowered/impl/CardboardPotionEffectType.java");
        String bridge = source("src/main/java/org/cardboardpowered/impl/PaperServerInternalAPIBridge.java");

        assertTrue(wrapper.contains("cardboard$recordDamageAndCheckCombatState"));
        assertTrue(wrapper.contains("cardboard$resetCombatState"));
        assertTrue(wrapper.contains("this.handle.mob.tickCount - this.handle.lastDamageTime"));
        assertTrue(mixin.contains("this.mob.onEnterCombat()"));
        assertTrue(mixin.contains("this.mob.onLeaveCombat()"));
        assertFalse(wrapper.contains("// this.handle.recordDamageAndCheckCombatState"));
        assertFalse(wrapper.contains("// this.handle.resetCombatState"));

        assertTrue(potion.contains("attributeModifiers.containsKey(nmsAttribute)"));
        assertTrue(potion.contains("CraftAttributeInstance.convert(attributeModifier.create(0))"));
        assertTrue(potion.contains("case BENEFICIAL -> Category.BENEFICIAL"));
        assertTrue(potion.contains("return this.getHandle().getDescriptionId();"));
        assertTrue(potion.contains("return CraftRegistry.bukkitToMinecraftHolder(type);"));
        assertFalse(potion.contains("getEffectAttributes() {\n\t\t// TODO Auto-generated method stub\n\t\treturn null;"));
        assertFalse(potion.contains("Optional<Reference<MobEffect>>"));
        assertFalse(potion.contains("getAttributeModifierAmount(@NotNull Attribute arg0, int arg1)"));

        assertTrue(bridge.contains("new PaperSkinParts.Mutable(Mannequin.ALL_LAYERS)"));
        assertTrue(bridge.contains("PaperAdventure.asAdventure(Mannequin.DEFAULT_DESCRIPTION)"));
        assertFalse(bridge.contains("MannequinEntity_ALL_MODEL_PARTS"));
    }

    @Test
    void worldSpawnFlagsBackTheNewMonsterToggle() throws Exception {
        String world = source("src/main/java/org/cardboardpowered/impl/world/CraftWorld.java");
        String cache = source("src/main/java/org/cardboardpowered/mixin/server/level/ServerChunkCacheMixin.java");

        assertTrue(world.contains("cardboard$getSpawnFriendlies()"));
        assertTrue(world.contains("cardboard$getSpawnEnemies()"));
        assertTrue(world.contains("cardboard$setSpawnSettings(allowMonsters, allowAnimals)"));
        assertTrue(world.contains("this.world.getChunkSource().setSpawnSettings(allow);"));
        assertFalse(world.contains("setSpawnFlags(boolean arg0, boolean arg1)"));
        assertTrue(cache.contains("private boolean cardboard$spawnFriendlies = true"));
        assertFalse(cache.contains("getFilteredSpawningCategories"));
        String natural = source("src/main/java/org/cardboardpowered/mixin/world/level/NaturalSpawnerMixin.java");
        assertTrue(natural.contains("@Inject(method = \"spawnCategoryForChunk\", at = @At(\"HEAD\"), cancellable = true)"));
        assertTrue(natural.contains("category.isFriendly()"));
        assertTrue(natural.contains("cardboard$getSpawnFriendlies()"));
        assertTrue(natural.contains("ci.cancel()"));

        String mixins = source("src/main/resources/bukkitfabric.mixins.json");
        assertTrue(mixins.contains("\"server.level.ServerChunkCacheMixin\""));
        assertTrue(mixins.contains("\"world.level.NaturalSpawnerMixin\""));
        assertTrue(mixins.contains("\"world.damagesource.CombatTrackerMixin\""));
    }

    @Test
    void rawSerializationUsesCardboardBridgesForPaperPatchedNms() throws Exception {
        String magic = source("src/main/java/org/bukkit/craftbukkit/util/CraftMagicNumbers.java");
        String entity = source("src/main/java/org/cardboardpowered/mixin/world/entity/EntityMixin.java");
        String custom = source("src/main/java/org/cardboardpowered/mixin/world/item/component/CustomDataMixin.java");

        assertTrue(magic.contains("CustomDataSerialization.setSerializeAsSnbt(true)"));
        assertTrue(magic.contains("cardboard$saveAsPassenger(output, includeNonSaveable, forceSerialization)"));
        assertTrue(entity.contains("Temporarily detach them and recurse through this bridge"));
        assertTrue(entity.contains("passengerOutputs.addChild()"));
        assertTrue(custom.contains("Codec.either(CompoundTag.CODEC, TagParser.FLATTENED_CODEC)"));
        assertFalse(magic.contains("CustomData.SERIALIZE_CUSTOM_AS_SNBT"));
        assertFalse(magic.contains("saveAsPassenger(output, true, includeNonSaveable, forceSerialization)"));
    }

}
