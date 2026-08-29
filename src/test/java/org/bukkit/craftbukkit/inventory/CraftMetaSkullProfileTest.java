package org.bukkit.craftbukkit.inventory;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.common.collect.ImmutableMap;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.server.Bootstrap;
import org.bukkit.profile.PlayerTextures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraftMetaSkullProfileTest {

    private static final String TEXTURE_VALUE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzk5YWQ3YTA0MzE2OTI5OTRiNmM0MTJjN2VhZmI5ZTBmYzQ5OTc1MjQwYjczYTI3ZDI0ZWQ3OTcwMzVmYjg5NCJ9fX0=";
    private static final URL BASE64_TEXTURE_URL;
    private static final URL TEXTURE_URL;

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Bootstrap.validate();

        try {
            BASE64_TEXTURE_URL = URI.create("http://textures.minecraft.net/texture/399ad7a0431692994b6c412c7eafb9e0fc49975240b73a27d24ed797035fb894").toURL();
            TEXTURE_URL = URI.create("https://textures.minecraft.net/texture/399ad7a0431692994b6c412c7eafb9e0fc49975240b73a27d24ed797035fb894").toURL();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private static CraftMetaSkull newMeta() {
        return new CraftMetaSkull(DataComponentPatch.builder().build(), Set.of());
    }

    private static CraftMetaSkull roundTrip(CraftMetaSkull meta) {
        CraftMetaItem.Applicator applicator = new CraftMetaItem.Applicator() {};
        assertDoesNotThrow(() -> meta.applyToItem(applicator));
        return new CraftMetaSkull(applicator.build(), Set.of());
    }

    @Test
    void paperProfilePropertyIsStoredInProfileDataComponent() {
        CraftMetaSkull meta = newMeta();
        com.destroystokyo.paper.profile.CraftPlayerProfile profile =
            new com.destroystokyo.paper.profile.CraftPlayerProfile(UUID.randomUUID(), "TestHead");
        profile.clearProperties();
        profile.setProperty(new ProfileProperty("textures", TEXTURE_VALUE));

        assertDoesNotThrow(() -> meta.setPlayerProfile(profile));
        assertTrue(meta.hasOwner());
        assertNotNull(meta.getPlayerProfile());
        assertTrue(meta.getPlayerProfile().hasProperty("textures"));

        CraftMetaSkull restored = roundTrip(meta);
        assertTrue(restored.hasOwner());
        com.destroystokyo.paper.profile.PlayerProfile restoredProfile = restored.getPlayerProfile();
        assertNotNull(restoredProfile);
        assertTrue(restoredProfile.hasProperty("textures"));
        assertEquals(TEXTURE_VALUE, restoredProfile.getProperties().stream()
            .filter(property -> property.getName().equals("textures"))
            .findFirst()
            .orElseThrow()
            .getValue());
    }

    @Test
    void bukkitPlayerTexturesAreStoredInProfileDataComponent() {
        CraftMetaSkull meta = newMeta();
        org.bukkit.craftbukkit.profile.CraftPlayerProfile profile =
            new org.bukkit.craftbukkit.profile.CraftPlayerProfile(UUID.randomUUID(), "TestHead");
        PlayerTextures textures = profile.getTextures();
        textures.setSkin(TEXTURE_URL);
        profile.setTextures(textures);

        assertFalse(profile.getTextures().isEmpty());
        assertDoesNotThrow(() -> meta.setOwnerProfile(profile));
        assertTrue(meta.hasOwner());

        CraftMetaSkull restored = roundTrip(meta);
        assertTrue(restored.hasOwner());
        org.bukkit.profile.PlayerProfile restoredProfile = restored.getOwnerProfile();
        assertNotNull(restoredProfile);
        assertEquals(TEXTURE_URL, restoredProfile.getTextures().getSkin());
    }

    @Test
    void customTextureSurvivesBukkitMetaSerialization() {
        CraftMetaSkull meta = newMeta();
        com.destroystokyo.paper.profile.CraftPlayerProfile profile =
            new com.destroystokyo.paper.profile.CraftPlayerProfile(UUID.randomUUID(), "TestHead");
        profile.setProperty(new ProfileProperty("textures", TEXTURE_VALUE));
        meta.setPlayerProfile(profile);

        Map<String, Object> serialized = meta.serialize(ImmutableMap.builder()).build();
        Object serializedOwner = serialized.get(CraftMetaSkull.SKULL_OWNER.BUKKIT);
        com.destroystokyo.paper.profile.CraftPlayerProfile serializedProfile = assertInstanceOf(
            com.destroystokyo.paper.profile.CraftPlayerProfile.class,
            serializedOwner
        );
        assertTrue(serializedProfile.hasProperty("textures"));
        assertFalse(serializedProfile.getTextures().isEmpty());
        assertEquals(BASE64_TEXTURE_URL, serializedProfile.getTextures().getSkin());
        assertFalse(((java.util.List<?>) serializedProfile.serialize().get("properties")).isEmpty());

        CraftMetaSkull restored = new CraftMetaSkull(serialized);
        assertTrue(restored.hasOwner());
        assertNotNull(restored.getOwnerProfile());
        assertEquals(BASE64_TEXTURE_URL, restored.getOwnerProfile().getTextures().getSkin());
    }
}
