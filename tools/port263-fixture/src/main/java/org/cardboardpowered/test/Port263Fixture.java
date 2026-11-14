package org.cardboardpowered.test;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** A real independent mod: no Cardboard internals or Bukkit on its classpath. */
public final class Port263Fixture implements ModInitializer {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("cardboard_port263_fixture", "probe");
    public static final EntityType<FixturePig> PIG = Registry.register(BuiltInRegistries.ENTITY_TYPE, ID,
            EntityType.Builder.of(FixturePig::new, MobCategory.CREATURE).sized(0.9F, 0.9F)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, ID)));
    public static final Block BLOCK = Registry.register(BuiltInRegistries.BLOCK, ID,
            new Block(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ID))));
    public static final Item ITEM = Registry.register(BuiltInRegistries.ITEM, ID,
            new BlockItem(BLOCK, new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ID))));
    // A non-vanilla namespace with the same path as a vanilla item. It also
    // catches code which mistakes every namespace starting with minecraft for vanilla.
    public static final Identifier ITEM_ONLY_ID = Identifier.fromNamespaceAndPath("minecraft_fixture", "dirt");
    public static final Item ITEM_ONLY = Registry.register(BuiltInRegistries.ITEM, ITEM_ONLY_ID,
            new Item(new Item.Properties().stacksTo(16).setId(ResourceKey.create(Registries.ITEM, ITEM_ONLY_ID))));
    public static final DataComponentType<Integer> COMPONENT = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            ID, DataComponentType.<Integer>builder().persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT).build());

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(PIG, Pig.createAttributes());
        System.out.println("PORT263_CUSTOM_FIXTURE_REGISTERED " + ID);
    }

    public static net.minecraft.world.item.ItemStack createMarkedStack() {
        var stack = new net.minecraft.world.item.ItemStack(ITEM);
        stack.set(COMPONENT, 1729);
        return stack;
    }

    public static int readMarker(net.minecraft.world.item.ItemStack stack) {
        return stack.getOrDefault(COMPONENT, -1);
    }

    public static int[] nearestVillage(net.minecraft.server.level.ServerLevel level, int x, int y, int z) {
        var village = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .get(Identifier.parse("minecraft:village_plains")).orElseThrow();
        var result = level.getChunkSource().getGenerator().findNearestMapStructure(level,
                net.minecraft.core.HolderSet.direct(java.util.List.of(village)), new net.minecraft.core.BlockPos(x, y, z), 32, false);
        if (result == null) throw new AssertionError("No native plains village configured in fixture");
        return new int[] {result.getFirst().getX(), result.getFirst().getY(), result.getFirst().getZ()};
    }

    public static boolean hasVillageAt(net.minecraft.server.level.ServerLevel level, int x, int y, int z) {
        var village = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .get(Identifier.parse("minecraft:village_plains")).orElseThrow().value();
        return level.structureManager().getStructureWithPieceAt(new net.minecraft.core.BlockPos(x, y, z),
                holder -> holder.value() == village).isValid();
    }

    public static AutoCloseable installRaidProbe(net.minecraft.server.level.ServerLevel level, int x, int y, int z, boolean farOnly) throws Exception {
        var field = net.minecraft.world.entity.raid.Raids.class.getDeclaredField("raidMap");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var raids = (it.unimi.dsi.fastutil.ints.Int2ObjectMap<net.minecraft.world.entity.raid.Raid>) field.get(level.getRaids());
        if (raids.containsKey(941263) || raids.containsKey(941264)) throw new AssertionError("Probe raid IDs already in use");
        if (!farOnly) raids.put(941263, new net.minecraft.world.entity.raid.Raid(new net.minecraft.core.BlockPos(x + 4, y, z), net.minecraft.world.Difficulty.NORMAL));
        raids.put(941264, new net.minecraft.world.entity.raid.Raid(new net.minecraft.core.BlockPos(x + 50_000, y, z), net.minecraft.world.Difficulty.NORMAL));
        return () -> { raids.remove(941263); raids.remove(941264); };
    }

    public static int[] healingCrystalPosition(net.minecraft.server.level.ServerLevel level) {
        var spike = net.minecraft.world.level.levelgen.feature.EndSpikeFeature.getSpikesForLevel(level).getFirst();
        return new int[] {spike.getCenterX(), spike.getHeight() + 1, spike.getCenterZ()};
    }

    public static Object[] prepareDragonProbe(net.minecraft.server.level.ServerLevel level) throws Exception {
        var fight = level.getDragonFight();
        var saved = new java.util.LinkedHashMap<String, Object>();
        for (var name : java.util.List.of("dragonKilled", "hasPreviouslyKilledDragon", "respawnStage", "respawnTime", "respawnCrystals")) {
            var field = fight.getClass().getDeclaredField(name);
            field.setAccessible(true);
            saved.put(name, field.get(fight));
        }
        // Keep the probe bounded: a native portal is present before the API's
        // idempotence check, avoiding an unbounded first-time portal scan.
        setProbePortal(fight, false);
        var pos = healingCrystalPosition(level);
        var crystal = new net.minecraft.world.entity.boss.enderdragon.EndCrystal(net.minecraft.world.entity.EntityTypes.END_CRYSTAL, level);
        crystal.setPos(pos[0] + 0.5, pos[1], pos[2] + 0.5);
        crystal.setPermanentlyInvulnerable(true);
        crystal.setBeamTarget(new net.minecraft.core.BlockPos(0, 100, 0));
        if (!level.addFreshEntity(crystal)) throw new AssertionError("Could not add probe healing crystal");
        return new Object[] {saved, crystal.getUUID()};
    }

    public static void readyDragonRespawn(net.minecraft.server.level.ServerLevel level) throws Exception {
        var fight = level.getDragonFight();
        for (var name : java.util.List.of("dragonKilled", "hasPreviouslyKilledDragon")) {
            var field = fight.getClass().getDeclaredField(name); field.setAccessible(true); field.set(fight, true);
        }
        var phase = fight.getClass().getDeclaredField("respawnStage"); phase.setAccessible(true); phase.set(fight, null);
        var crystals = fight.getClass().getDeclaredField("respawnCrystals"); crystals.setAccessible(true); crystals.set(fight, java.util.List.of());
        setProbePortal(fight, true);
    }

    public static void cleanupDragonProbe(net.minecraft.server.level.ServerLevel level, Object[] state) throws Exception {
        var crystal = level.getEntity((java.util.UUID) state[1]);
        if (crystal != null) crystal.discard();
        var fight = level.getDragonFight();
        var saved = (java.util.Map<?, ?>) state[0];
        for (var entry : saved.entrySet()) {
            var field = fight.getClass().getDeclaredField((String) entry.getKey()); field.setAccessible(true); field.set(fight, entry.getValue());
        }
        setProbePortal(fight, true);
        fight.setDirty();
    }

    private static void setProbePortal(net.minecraft.world.level.dimension.end.EnderDragonFight fight, boolean active) throws Exception {
        var method = fight.getClass().getDeclaredMethod("spawnExitPortal", boolean.class);
        method.setAccessible(true);
        method.invoke(fight, active);
    }

    public static final class FixturePig extends Pig {
        public FixturePig(EntityType<? extends Pig> type, Level level) {
            super(type, level);
        }
    }
}
