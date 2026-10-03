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
}
