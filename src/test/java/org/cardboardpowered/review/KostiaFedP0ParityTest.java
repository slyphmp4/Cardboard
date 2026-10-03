package org.cardboardpowered.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class KostiaFedP0ParityTest {

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void livingEntityKnockbackUsesKnockbackSemantics() throws Exception {
        String source = source("src/main/java/org/bukkit/craftbukkit/entity/CraftLivingEntity.java");
        int start = source.indexOf("public void knockback(double strength, double directionX, double directionZ)");
        int end = source.indexOf("// 1.19.4:", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("Preconditions.checkArgument(strength > 0"));
        assertTrue(method.contains("getHandle().knockback("));
        assertTrue(method.contains("damageSources().generic()"));
        assertFalse(method.contains("getHandle().push("));
    }

    @Test
    void vexOwnerAcceptsAnyLivingEntityWithoutMobCast() throws Exception {
        String source = source("src/main/java/org/bukkit/craftbukkit/entity/CraftVex.java");
        int start = source.indexOf("public void setOwner(org.bukkit.entity.LivingEntity owner)");
        String method = source.substring(start, source.indexOf("\n    }", start) + 6);

        assertTrue(method.contains("EntityReference.of(((CraftLivingEntity) owner).getHandle())"));
        assertFalse(method.contains("(net.minecraft.world.entity.Mob)"));
    }

    @Test
    void loginFinishedPacketUsesServerSessionId() throws Exception {
        String source = source("src/main/java/org/cardboardpowered/mixin/server/network/ServerLoginPacketListenerImplMixin.java");

        assertTrue(source.contains("new ClientboundLoginFinishedPacket(profile, this.server.getConnection().getSessionId())"));
        assertFalse(source.contains("new ClientboundLoginFinishedPacket(profile, java.util.UUID.randomUUID())"));
    }

    @Test
    void teamColorsUseSerializedNamesThroughOneConversionPath() throws Exception {
        String adventure = source("src/main/java/io/papermc/paper/adventure/PaperAdventure.java");
        String team = source("src/main/java/org/bukkit/craftbukkit/scoreboard/CraftTeam.java");

        assertTrue(adventure.contains("teamColor.getSerializedName()"));
        assertTrue(adventure.contains("TeamColor.byName(color.toString())"));
        assertTrue(adventure.contains("TeamColor.byName(vanillaColor.serialize())"));
        assertFalse(adventure.contains("TeamColor.byName(formatting.name())"));
        assertFalse(adventure.contains("TeamColor.byName(value.name())"));

        assertTrue(team.contains("PaperAdventure::asVanilla"));
        assertTrue(team.contains("TeamColor::byName"));
        assertTrue(team.contains("TextColor::serialize"));
        assertFalse(team.contains("TeamColor.byName(CraftChatMessage.getColor(color).name())"));
    }
}
