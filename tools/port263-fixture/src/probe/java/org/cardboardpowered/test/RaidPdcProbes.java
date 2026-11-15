package org.cardboardpowered.test;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import org.bukkit.NamespacedKey;
import org.bukkit.Raid;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/** Version-specific native setup and codec oracles; identical public PDC contracts on Paper. */
final class RaidPdcProbes {
    private static final NamespacedKey NUMBER = new NamespacedKey("cardboard_raid_probe", "number");
    private static final NamespacedKey TEXT = new NamespacedKey("cardboard_raid_probe", "text");
    private static final NamespacedKey ARRAY = new NamespacedKey("cardboard_raid_probe", "array");
    private static final NamespacedKey CHILD = new NamespacedKey("cardboard_raid_probe", "child");
    private static final int PERSISTED = 941267;

    static void verify(World world, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("raid-pdc-stable-container-and-independent-raids", () -> {
            try (var fixture = new Fixture(world)) {
                var first = pdc(fixture.first());
                var second = pdc(fixture.second());
                require(first == pdc(fixture.first()) && first != second, "Raid wrappers do not share the correct native container");
                require(first.isEmpty() && second.isEmpty(), "New raid PDC is not empty");
                first.set(NUMBER, PersistentDataType.INTEGER, 1729);
                first.set(TEXT, PersistentDataType.STRING, "raid-data");
                require(pdc(fixture.first()).get(NUMBER, PersistentDataType.INTEGER) == 1729, "Held container is detached from native raid");
                require(second.isEmpty() && first.has(NUMBER, PersistentDataType.INTEGER) && !first.has(NUMBER, PersistentDataType.STRING), "Types or raid isolation are wrong");
                first.remove(TEXT);
                require(!first.has(TEXT) && first.getKeys().contains(NUMBER), "PDC removal/key enumeration failed");
            }
        });
        check.accept("raid-pdc-arrays-nested-copy-and-byte-contracts", () -> {
            try (var fixture = new Fixture(world)) {
                var source = pdc(fixture.first());
                var target = pdc(fixture.second());
                source.set(NUMBER, PersistentDataType.INTEGER, 53);
                source.set(ARRAY, PersistentDataType.LONG_ARRAY, new long[]{Long.MIN_VALUE, 42, Long.MAX_VALUE});
                var child = source.getAdapterContext().newPersistentDataContainer();
                child.set(TEXT, PersistentDataType.BYTE_ARRAY, new byte[]{-128, 0, 127});
                source.set(CHILD, PersistentDataType.TAG_CONTAINER, child);
                target.set(NUMBER, PersistentDataType.INTEGER, 79);
                source.copyTo(target, false);
                require(target.get(NUMBER, PersistentDataType.INTEGER) == 79, "copyTo(false) overwrote an existing key");
                require(Arrays.equals(target.get(ARRAY, PersistentDataType.LONG_ARRAY), new long[]{Long.MIN_VALUE, 42, Long.MAX_VALUE}), "Copied long array was changed");
                source.copyTo(target, true);
                require(target.get(NUMBER, PersistentDataType.INTEGER) == 53, "copyTo(true) ignored replacement");
                var bytes = source.serializeToBytes();
                target.set(TEXT, PersistentDataType.STRING, "keep-on-merge");
                target.readFromBytes(bytes, false);
                require("keep-on-merge".equals(target.get(TEXT, PersistentDataType.STRING)), "readFromBytes(false) cleared unrelated keys");
                target.readFromBytes(bytes, true);
                require(!target.has(TEXT) && Arrays.equals(target.get(CHILD, PersistentDataType.TAG_CONTAINER).get(TEXT, PersistentDataType.BYTE_ARRAY), new byte[]{-128, 0, 127}), "Byte roundtrip lost clear/nested type semantics");
                var item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE);
                require(item.editPersistentDataContainer(data -> data.set(TEXT, PersistentDataType.STRING, "readonly-source")), "Could not initialize item PDC view");
                item.getPersistentDataContainer().copyTo(target, true);
                require("readonly-source".equals(target.get(TEXT, PersistentDataType.STRING)), "Readonly item view could not copy to raid PDC");
                require(source.getKeys().size() == 3, "Target updates modified source keys");
            }
        });
        check.accept("raid-pdc-native-nbt-codec-roundtrip", () -> {
            try (var fixture = new Fixture(world)) {
                var source = pdc(fixture.first());
                source.set(NUMBER, PersistentDataType.INTEGER, 263);
                source.set(ARRAY, PersistentDataType.LONG_ARRAY, new long[]{Long.MIN_VALUE, 17, Long.MAX_VALUE});
                var child = source.getAdapterContext().newPersistentDataContainer();
                child.set(TEXT, PersistentDataType.STRING, "nested");
                source.set(CHILD, PersistentDataType.TAG_CONTAINER, child);
                var tag = fixture.encode(fixture.firstNative());
                require(tag.getCompound("BukkitValues").isPresent(), "Native Raid.MAP_CODEC omitted BukkitValues");
                fixture.raids.put(Fixture.FIRST, fixture.decode(tag));
                var restored = pdc(fixture.first());
                require(restored.get(NUMBER, PersistentDataType.INTEGER) == 263 && restored != source, "Codec did not restore independent raid PDC");
                require(Arrays.equals(restored.get(ARRAY, PersistentDataType.LONG_ARRAY), new long[]{Long.MIN_VALUE, 17, Long.MAX_VALUE})
                        && "nested".equals(restored.get(CHILD, PersistentDataType.TAG_CONTAINER).get(TEXT, PersistentDataType.STRING)), "NBT codec lost tag types or nested data");
                restored.remove(NUMBER);
                require(source.has(NUMBER), "Decoded PDC aliases source");
                require(fixture.first().getLocation().getBlockX() == 100_000, "PDC codec changed vanilla raid fields");
            }
        });
        check.accept("raid-pdc-old-empty-malformed-nbt-and-validation", () -> {
            try (var fixture = new Fixture(world)) {
                var container = pdc(fixture.first());
                var old = fixture.encode(fixture.firstNative());
                require(!old.contains("BukkitValues"), "Empty PDC was unnecessarily encoded");
                fixture.raids.put(Fixture.FIRST, fixture.decode(old));
                require(pdc(fixture.first()).isEmpty(), "Old vanilla raid acquired PDC values");
                var malformed = old.copy();
                malformed.putString("BukkitValues", "invalid optional type");
                fixture.raids.put(Fixture.FIRST, fixture.decode(malformed));
                require(pdc(fixture.first()).isEmpty() && fixture.first().getLocation().getBlockX() == 100_000, "Malformed optional PDC invalidated vanilla raid");
                invalid(() -> container.set(null, PersistentDataType.INTEGER, 1));
                invalid(() -> container.set(NUMBER, null, 1));
                invalid(() -> container.set(NUMBER, PersistentDataType.INTEGER, null));
                invalid(() -> container.copyTo(null, true));
                var missingNativeField = old.copy();
                missingNativeField.remove("center");
                require(fixture.codec.parse(NbtOps.INSTANCE, missingNativeField).error().isPresent(),
                        "Optional PDC wrapper hid a required native raid field error");
            }
        });
    }

    static void verifyNative(World world, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("raid-pdc-immediate-saved-data-dirty-oracle", () -> {
            try (var fixture = new Fixture(world)) {
                var container = pdc(fixture.first());
                boolean previous = fixture.dirty();
                try {
                    fixture.mark(false);
                    require(!fixture.dirty(), "Could not establish clean native raid data");
                    container.set(NUMBER, PersistentDataType.INTEGER, 101);
                    require(fixture.dirty(), "set did not immediately mark raid data dirty");
                    fixture.mark(false);
                    container.remove(NUMBER);
                    require(fixture.dirty(), "remove did not immediately mark raid data dirty");
                    var source = container.getAdapterContext().newPersistentDataContainer();
                    source.set(TEXT, PersistentDataType.STRING, "copied");
                    fixture.mark(false);
                    source.copyTo(container, false);
                    require(fixture.dirty(), "copyTo(false) did not notify target raid's dirty observer");
                    source.set(TEXT, PersistentDataType.STRING, "replaced");
                    fixture.mark(false);
                    source.copyTo(container, true);
                    require(fixture.dirty(), "copyTo(true) did not notify target raid's dirty observer");
                    source.set(NUMBER, PersistentDataType.INTEGER, 103);
                    var bytes = source.serializeToBytes();
                    fixture.mark(false);
                    container.readFromBytes(bytes, false);
                    require(fixture.dirty(), "Byte merge did not immediately mark raid data dirty");
                    fixture.mark(false);
                    container.readFromBytes(bytes, true);
                    require(fixture.dirty(), "Byte replacement did not immediately mark raid data dirty");
                    var item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE);
                    require(item.editPersistentDataContainer(data -> data.set(CHILD, PersistentDataType.STRING, "readonly")), "Could not initialize readonly PDC source");
                    fixture.mark(false);
                    item.getPersistentDataContainer().copyTo(container, false);
                    require(fixture.dirty() && "readonly".equals(container.get(CHILD, PersistentDataType.STRING)), "Readonly view copyTo(false) bypassed target dirty observer");
                    item.editPersistentDataContainer(data -> data.set(CHILD, PersistentDataType.STRING, "readonly-replaced"));
                    fixture.mark(false);
                    item.getPersistentDataContainer().copyTo(container, true);
                    require(fixture.dirty() && "readonly-replaced".equals(container.get(CHILD, PersistentDataType.STRING)), "Readonly view copyTo(true) bypassed target dirty observer");
                    fixture.raids.put(Fixture.FIRST, fixture.decode(fixture.encode(fixture.firstNative())));
                    var decoded = pdc(fixture.first());
                    fixture.mark(false);
                    decoded.set(NUMBER, PersistentDataType.INTEGER, 104);
                    require(fixture.dirty(), "Decoded raid PDC lost its saved-data observer");
                } finally { fixture.mark(previous); }
            }
        });
    }

    static void persistence(World world, boolean verify) throws Exception {
        var access = new Access(world);
        if (!verify) {
            require(!access.raids.containsKey(PERSISTED), "Restart fixture raid ID is already in use");
            access.raids.put(PERSISTED, access.create(100_000));
            boolean prepared = false;
            try {
                var held = pdc(world.getRaid(PERSISTED));
                access.mark(true);
                world.save();
                access.mark(false);
                require(!access.dirty(), "Baseline raid data was not clean");
                held.set(TEXT, PersistentDataType.STRING, "raid-PDC-process-restart");
                held.set(ARRAY, PersistentDataType.LONG_ARRAY, new long[]{Long.MIN_VALUE, 2631729, Long.MAX_VALUE});
                require(access.dirty(), "Held PDC alone did not mark already-saved raid data dirty");
                world.save();
                prepared = true;
            } finally {
                if (!prepared) { access.raids.remove(PERSISTED); access.mark(true); }
            }
        } else {
            try {
                require(access.raids.containsKey(PERSISTED), "Saved inactive raid did not survive full process restart");
                var held = pdc(world.getRaid(PERSISTED));
                require("raid-PDC-process-restart".equals(held.get(TEXT, PersistentDataType.STRING))
                        && Arrays.equals(held.get(ARRAY, PersistentDataType.LONG_ARRAY), new long[]{Long.MIN_VALUE, 2631729, Long.MAX_VALUE}), "Raid PDC did not survive full process restart");
                require(held == pdc(world.getRaid(PERSISTED)), "Reloaded raid replaced its held PDC across wrappers");
            } finally { access.raids.remove(PERSISTED); access.mark(true); }
        }
    }

    private static PersistentDataContainer pdc(Raid raid) {
        require(raid != null, "Native fixture raid is absent from World.getRaid");
        var container = raid.getPersistentDataContainer();
        require(container != null, "Raid.getPersistentDataContainer returned null");
        return container;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void invalid(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("Expected IllegalArgumentException"); }

    private static class Access {
        final World world;
        final Object owner;
        final Map<Integer, Object> raids;
        final Codec<Object> codec;
        final Constructor<?> raidConstructor, posConstructor;
        final Object normal;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Access(World world) throws Exception {
            this.world = world;
            require(!world.isChunkLoaded(100_000 >> 4, 100_000 >> 4), "Raid fixture would overlap loaded terrain");
            var level = world.getClass().getMethod("getHandle").invoke(world);
            this.owner = level.getClass().getMethod("getRaids").invoke(level);
            var field = Class.forName("net.minecraft.world.entity.raid.Raids").getDeclaredField("raidMap");
            field.setAccessible(true);
            this.raids = (Map<Integer, Object>) field.get(this.owner);
            var type = Class.forName("net.minecraft.world.entity.raid.Raid");
            var pos = Class.forName("net.minecraft.core.BlockPos");
            var difficulty = Class.forName("net.minecraft.world.Difficulty");
            this.raidConstructor = type.getConstructor(pos, difficulty);
            this.posConstructor = pos.getConstructor(int.class, int.class, int.class);
            this.normal = Enum.valueOf((Class<? extends Enum>) difficulty, "NORMAL");
            this.codec = ((MapCodec<Object>) type.getField("MAP_CODEC").get(null)).codec();
        }
        Object create(int x) throws Exception { return this.raidConstructor.newInstance(this.posConstructor.newInstance(x, 70, 100_000), this.normal); }
        CompoundTag encode(Object raid) { return (CompoundTag) this.codec.encodeStart(NbtOps.INSTANCE, raid).getOrThrow(); }
        Object decode(Tag tag) { return this.codec.parse(NbtOps.INSTANCE, tag).getOrThrow(); }
        void mark(boolean dirty) throws Exception { this.owner.getClass().getMethod("setDirty", boolean.class).invoke(this.owner, dirty); }
        boolean dirty() throws Exception { return (Boolean) this.owner.getClass().getMethod("isDirty").invoke(this.owner); }
    }

    private static final class Fixture extends Access implements AutoCloseable {
        static final int FIRST = 941265, SECOND = 941266;
        Fixture(World world) throws Exception {
            super(world);
            require(!this.raids.containsKey(FIRST) && !this.raids.containsKey(SECOND), "Fixture raid IDs are already in use");
            var first = this.create(100_000);
            var second = this.create(100_016);
            this.raids.put(FIRST, first);
            this.raids.put(SECOND, second);
        }
        Raid first() { return this.world.getRaid(FIRST); }
        Raid second() { return this.world.getRaid(SECOND); }
        Object firstNative() { return this.raids.get(FIRST); }
        @Override public void close() { this.raids.remove(FIRST); this.raids.remove(SECOND); }
    }
}
