package org.cardboardpowered.smoke;

import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.bukkit.boss.DragonBattle;
import org.bukkit.craftbukkit.boss.CraftDragonBattle;
import org.cardboardpowered.bridge.world.entity.EntityBridge;

/** Controlled native fight states, with the actual connected owner's boss-bar packets. */
final class DragonBattleSmoke {
    static void verify(ServerPlayer owner) {
        ServerLevel level = owner.level().getServer().getLevel(Level.END);
        require(level != null && level.getDragonFight() != null, "End fight is missing");
        var fight = level.getDragonFight();
        var battle = new CraftDragonBattle(fight);
        var player = (org.bukkit.entity.Player) ((EntityBridge) owner).getBukkitEntity();
        require(battle.getBossBar().getPlayers().isEmpty(), "End fixture already has boss-bar viewers");
        Map<String, Object> saved = new LinkedHashMap<>();
        boolean visible = battle.getBossBar().isVisible();
        EndCrystal crystal = null;
        try {
            for (String name : List.of("dragonKilled", "hasPreviouslyKilledDragon", "needsStateScanning",
                    "skipArenaLoadedCheck", "ticksSinceLastPlayerScan", "respawnStage", "respawnTime", "respawnCrystals")) {
                saved.put(name, field(fight, name));
            }
            portal(fight);
            prepare(fight);
            require(battle.initiateRespawn((java.util.Collection<org.bukkit.entity.EnderCrystal>) null), "Empty API respawn did not start");
            require(battle.getRespawnPhase() == DragonBattle.RespawnPhase.START, "API respawn did not enter START");

            // The native saved-data codec is what stores this state in a world.
            var encoded = EnderDragonFight.CODEC.encodeStart(JsonOps.INSTANCE, fight).getOrThrow();
            var decoded = EnderDragonFight.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            decoded.init(level, level.getSeed(), BlockPos.ZERO);
            var decodedBattle = new CraftDragonBattle(decoded);
            require(decodedBattle.getRespawnPhase() == DragonBattle.RespawnPhase.START
                    && decodedBattle.getRespawnCrystals().isEmpty(), "Empty respawn did not survive saved-data decoding");
            configureTick(decoded);
            decodedBattle.getBossBar().addPlayer(player);
            try {
                decoded.tick();
                require(decodedBattle.getRespawnPhase() == DragonBattle.RespawnPhase.PREPARING_TO_SUMMON_PILLARS,
                        "Restored empty respawn aborted in the real native tick");
            } finally {
                decodedBattle.getBossBar().removePlayer(player);
            }

            configureTick(fight);
            battle.getBossBar().addPlayer(player);
            fight.tick();
            require(battle.getRespawnPhase() == DragonBattle.RespawnPhase.PREPARING_TO_SUMMON_PILLARS,
                    "Empty API respawn aborted in the real native tick");

            // A later ordinary respawn must retain vanilla's missing-crystal abort.
            prepare(fight);
            crystal = new EndCrystal(level, 0.5, 100, 0.5);
            require(level.addFreshEntity(crystal), "Could not add ordinary respawn crystal");
            var bukkitCrystal = (org.bukkit.entity.EnderCrystal) ((EntityBridge) crystal).getBukkitEntity();
            require(battle.initiateRespawn(List.of(bukkitCrystal)), "Ordinary API respawn did not start");
            set(fight, "respawnCrystals", List.of(EntityReference.<EndCrystal>of(UUID.randomUUID())));
            configureTick(fight);
            fight.tick();
            require(battle.getRespawnPhase() == DragonBattle.RespawnPhase.NONE, "Missing ordinary crystal did not abort respawn");
            System.out.println("DRAGON_BATTLE_TICK_PASS: empty sequence, saved-data decoding, ordinary missing-crystal abort");
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Dragon tick fixture reflection failed", error);
        } finally {
            if (crystal != null) crystal.discard();
            battle.getBossBar().removePlayer(player);
            try {
                for (var entry : saved.entrySet()) set(fight, entry.getKey(), entry.getValue());
                portal(fight);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Dragon fixture cleanup failed", error);
            } finally {
                battle.getBossBar().setVisible(visible);
                level.getChunkSource().removeTicketWithRadius(TicketType.DRAGON, new ChunkPos(0, 0), 9);
            }
        }
    }

    private static void prepare(EnderDragonFight fight) throws ReflectiveOperationException {
        set(fight, "dragonKilled", true);
        set(fight, "hasPreviouslyKilledDragon", true);
        set(fight, "respawnStage", null);
        set(fight, "respawnTime", 0);
        set(fight, "respawnCrystals", List.of());
        configureTick(fight);
        portal(fight);
    }

    private static void configureTick(EnderDragonFight fight) throws ReflectiveOperationException {
        set(fight, "needsStateScanning", false);
        set(fight, "skipArenaLoadedCheck", true);
        set(fight, "ticksSinceLastPlayerScan", -1000);
    }

    private static Object field(EnderDragonFight fight, String name) throws ReflectiveOperationException {
        var field = EnderDragonFight.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(fight);
    }

    private static void set(EnderDragonFight fight, String name, Object value) throws ReflectiveOperationException {
        var field = EnderDragonFight.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(fight, value);
    }

    private static void portal(EnderDragonFight fight) throws ReflectiveOperationException {
        var method = EnderDragonFight.class.getDeclaredMethod("spawnExitPortal", boolean.class);
        method.setAccessible(true);
        method.invoke(fight, true);
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
