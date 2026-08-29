package org.cardboardpowered.profile;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.mojang.authlib.properties.Property;
import java.net.URI;
import java.net.URL;
import java.util.UUID;
import net.minecraft.world.item.component.ResolvableProfile;
import org.bukkit.profile.PlayerTextures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PlayerHeadProfileTest {

    private static final String TEXTURE_VALUE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMzk5YWQ3YTA0MzE2OTI5OTRiNmM0MTJjN2VhZmI5ZTBmYzQ5OTc1MjQwYjczYTI3ZDI0ZWQ3OTcwMzVmYjg5NCJ9fX0=";
    private static final URL TEXTURE_URL;

    static {
        try {
            TEXTURE_URL = URI.create("https://textures.minecraft.net/texture/399ad7a0431692994b6c412c7eafb9e0fc49975240b73a27d24ed797035fb894").toURL();
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @Test
    void paperProfileTexturePropertySurvivesResolvableProfileConversion() {
        com.destroystokyo.paper.profile.CraftPlayerProfile profile =
            new com.destroystokyo.paper.profile.CraftPlayerProfile(UUID.randomUUID(), "TestHead");

        profile.clearProperties();
        profile.setProperty(new ProfileProperty("textures", TEXTURE_VALUE));

        ResolvableProfile resolvableProfile = profile.buildResolvableProfile();
        Property textures = resolvableProfile.partialProfile().properties().get("textures").stream().findFirst().orElse(null);

        assertNotNull(textures);
        assertEquals(TEXTURE_VALUE, textures.value());
        assertFalse(resolvableProfile.partialProfile().properties().get("textures").isEmpty());
    }

    @Test
    void bukkitPlayerTexturesSurviveResolvableProfileConversion() {
        org.bukkit.craftbukkit.profile.CraftPlayerProfile profile =
            new org.bukkit.craftbukkit.profile.CraftPlayerProfile(UUID.randomUUID(), "TestHead");

        PlayerTextures textures = profile.getTextures();
        textures.setSkin(TEXTURE_URL);
        profile.setTextures(textures);

        ResolvableProfile resolvableProfile = profile.buildResolvableProfile();
        org.bukkit.craftbukkit.profile.CraftPlayerProfile roundTrip =
            new org.bukkit.craftbukkit.profile.CraftPlayerProfile(resolvableProfile);

        assertNotNull(resolvableProfile.partialProfile().properties().get("textures").stream().findFirst().orElse(null));
        assertEquals(TEXTURE_URL, roundTrip.getTextures().getSkin());
    }
}
