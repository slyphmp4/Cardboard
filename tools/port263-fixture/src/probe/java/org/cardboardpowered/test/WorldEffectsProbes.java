package org.cardboardpowered.test;

import java.util.List;
import java.util.function.BiConsumer;
import org.bukkit.Color;
import org.bukkit.Instrument;
import org.bukkit.Note;
import org.bukkit.Particle;
import org.bukkit.World;

/** The classic entry points must reach the versioned World defaults and modern dispatch. */
final class WorldEffectsProbes {
    static void verify(World world, BiConsumer<String, WorldRayTraceProbes.Checked> check) {
        check.accept("world-note-default-contracts", () -> {
            var point = world.getSpawnLocation().add(0.25, 2, 0.25);
            var original = point.clone();
            var note = new Note(13);
            world.playNote(point, Instrument.PIANO, note);
            world.playNote(point, Instrument.CUSTOM_HEAD, note);
            world.playNote(null, Instrument.PIANO, note);
            require(point.equals(original), "Note playback mutated its Location");
            expectNull(() -> world.playNote(point, null, note));
            expectNull(() -> world.playNote(point, Instrument.PIANO, null));
        });
        check.accept("world-classic-particle-validation-and-inputs", () -> {
            var point = world.getSpawnLocation().add(-0.25, 2, 0.25);
            var original = point.clone();
            expectBadData(() -> world.spawnParticle(Particle.DUST, point, 2, "bad-dust-data"));
            expectBadData(() -> world.spawnParticle(Particle.DUST, point, 2, 0.1, 0.2, 0.3, 0.4, "bad-dust-data", true));
            expectBadData(() -> world.spawnParticle(Particle.DUST, List.of(), null,
                    point.getX(), point.getY(), point.getZ(), 2, 0.1, 0.2, 0.3, 0.4, "bad-dust-data", false));
            world.spawnParticle(Particle.POOF, List.of(), null,
                    point.getX(), point.getY(), point.getZ(), 0, 0.1, 0.2, 0.3, 0.4, null, false);
            world.spawnParticle(Particle.DUST, point, 1, new Particle.DustOptions(Color.AQUA, 1));
            require(point.equals(original), "Particle dispatch mutated its Location");
        });
    }

    private static void expectNull(Runnable action) {
        try { action.run(); } catch (NullPointerException expected) { return; }
        throw new AssertionError("Note entry point did not follow the default null contract");
    }

    private static void expectBadData(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Classic particle entry point skipped data validation");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
