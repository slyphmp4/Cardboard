package org.cardboardpowered.smoke;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.Ghast;
import org.bukkit.Bukkit;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.event.entity.EntityTargetEvent.TargetReason;
import org.bukkit.plugin.Plugin;
import org.cardboardpowered.bridge.world.entity.MobBridge;

/** Calls real transformed NMS methods; the separate Bukkit plugin observes and changes their events. */
final class EntityEventSmoke {
    private EntityEventSmoke() {}

    static void verifyClient(Minecraft client) {
        Pig subject = new Pig(EntityTypes.PIG, client.level);
        Pig first = new Pig(EntityTypes.PIG, client.level);
        Pig second = new Pig(EntityTypes.PIG, client.level);
        // Packet-created client entities receive IDs before passenger updates.
        // These isolated copies are not inserted into ClientLevel, so assign their IDs explicitly.
        subject.setId(1_000_001);
        first.setId(1_000_002);
        second.setId(1_000_003);
        // The LAN guest has no local Bukkit server; host checks additionally count Bukkit events.
        Probe probe = Bukkit.getServer() == null ? null : new Probe(Bukkit.getPluginManager().getPlugin("IntegratedSmoke"));
        if (probe != null) probe.arm(subject, "OBSERVE", null);
        List<Throwable> failures = new ArrayList<>();
        check(failures, "client vanilla heal", () -> {
            subject.setHealth(4.0F);
            subject.heal(2.0F);
            require(subject.getHealth() == 6.0F, "Vanilla client healing was lost");
        });
        check(failures, "client reasoned heal", () -> {
            subject.setHealth(4.0F);
            healWithReason(subject, 2.0F);
            require(subject.getHealth() == 6.0F, "Reasoned client healing was lost");
        });
        check(failures, "client healing clamp and dead entity", () -> {
            subject.setHealth(4.0F);
            subject.heal(100.0F);
            require(subject.getHealth() == subject.getMaxHealth(), "Client heal bypassed maximum health");
            subject.setHealth(0.0F);
            subject.heal(2.0F);
            require(subject.getHealth() == 0.0F, "Client healing revived a dead entity");
        });
        check(failures, "client vanilla setTarget", () -> {
            subject.setHealth(4.0F);
            subject.setTarget(first);
            require(subject.getTargetUnchecked() == first, "Vanilla client target assignment was lost");
        });
        check(failures, "client reasoned setTarget", () -> {
            ((MobBridge) subject).cardboard$setTargetWithReason(second, TargetReason.CLOSEST_ENTITY);
            require(subject.getTargetUnchecked() == second, "Reasoned client target assignment was lost");
        });
        check(failures, "client direct target bridge", () -> {
            require(((MobBridge) subject).cardboard$setTarget(first, TargetReason.CLOSEST_ENTITY), "Client bridge did not change the target");
            require(subject.getTargetUnchecked() == first, "Client bridge lost its target");
            require(!((MobBridge) subject).cardboard$setTarget(first, TargetReason.CLOSEST_ENTITY), "Unchanged client bridge target reported a change");
            require(((MobBridge) subject).cardboard$setTarget(null, null), "Client bridge did not clear the target");
            require(subject.getTargetUnchecked() == null, "Client bridge failed to clear the target");
        });
        check(failures, "client vanilla target validation", () -> {
            subject.setTarget(new Ghast(EntityTypes.GHAST, client.level));
            require(subject.getTargetUnchecked() == null, "Client setTarget bypassed vanilla ghast filtering");
        });
        check(failures, "client current target revalidation", () -> {
            subject.setTarget(first);
            first.setInvulnerable(true);
            try {
                subject.setTarget(first);
                require(subject.getTargetUnchecked() == null, "Client retained an invulnerable current target");
            } finally {
                first.setInvulnerable(false);
            }
        });
        check(failures, "client eventful mount and dismount", () -> {
            require(subject.startRiding(first, true, true), "Client mount was rejected");
            require(subject.getVehicle() == first && first.getPassengers().contains(subject), "Client mount lost its passenger");
            subject.removeVehicle();
            require(subject.getVehicle() == null && first.getPassengers().isEmpty(), "Client dismount left inconsistent passengers");
        });
        check(failures, "client packet-style mount and eject", () -> {
            require(subject.startRiding(first, true, false), "Client packet-style mount was rejected");
            first.ejectPassengers();
            require(subject.getVehicle() == null && first.getPassengers().isEmpty(), "Client packet-style eject left a passenger");
        });
        check(failures, "client vehicle transfer", () -> {
            require(subject.startRiding(first, true, false), "Client initial mount was rejected");
            require(subject.startRiding(second, true, true), "Client vehicle transfer was rejected");
            require(subject.getVehicle() == second && first.getPassengers().isEmpty(), "Client transfer retained the old vehicle");
            subject.removeVehicle();
            require(second.getPassengers().isEmpty(), "Client transfer cleanup left a passenger");
        });
        if (probe != null) check(failures, "client Bukkit isolation", () -> {
            require(probe.count(subject, "heal") == 0, "Client healing emitted a Bukkit event");
            require(probe.count(subject, "target") == 0, "Client target assignment emitted a Bukkit event");
            require(probe.count(subject, "vehicleEnter") == 0, "Client mounting emitted a Bukkit event");
            require(probe.count(subject, "vehicleExit") == 0, "Client dismounting emitted a Bukkit event");
        });
        finish(failures, "CLIENT_ENTITY_EVENTS_PASS");
    }

    static void verifyServer(ServerPlayer owner, Plugin plugin) {
        ServerLevel level = owner.level();
        Pig subject = spawn(level, owner, 1.0);
        Pig first = spawn(level, owner, 2.0);
        Pig second = spawn(level, owner, 3.0);
        Probe probe = new Probe(plugin);
        List<Throwable> failures = new ArrayList<>();
        try {
            check(failures, "server healing amount", () -> {
                subject.setHealth(4.0F);
                probe.arm(subject, "HEAL_MODIFY", null);
                subject.heal(1.0F);
                require(subject.getHealth() == 7.0F, "Plugin-modified healing amount was ignored");
                require(probe.count(subject, "heal") == 1, "Expected exactly one healing event");
                require("CUSTOM".equals(probe.reason(subject, "heal")), "Vanilla heal reason changed");
            });
            check(failures, "server healing cancellation", () -> {
                subject.setHealth(4.0F);
                probe.arm(subject, "HEAL_CANCEL", null);
                int before = probe.count(subject, "heal");
                subject.heal(2.0F);
                require(subject.getHealth() == 4.0F, "Cancelled healing changed health");
                require(probe.count(subject, "heal") == before + 1, "Cancelled healing event was missing or duplicated");
            });
            check(failures, "server reasoned healing", () -> {
                subject.setHealth(4.0F);
                probe.arm(subject, "HEAL_MODIFY", null);
                int before = probe.count(subject, "heal");
                healWithReason(subject, 1.0F);
                require(subject.getHealth() == 7.0F, "Reasoned heal ignored the plugin amount");
                require(probe.count(subject, "heal") == before + 1, "Reasoned heal duplicated its event");
                require("MAGIC".equals(probe.reason(subject, "heal")), "Explicit healing reason was lost");
            });
            check(failures, "server target event and reason", () -> {
                probe.arm(subject, "OBSERVE", null);
                ((MobBridge) subject).cardboard$setTargetWithReason(first, TargetReason.CLOSEST_ENTITY);
                require(subject.getTargetUnchecked() == first, "Server target assignment failed");
                require(probe.count(subject, "target") == 1, "Expected exactly one target event");
                require("CLOSEST_ENTITY".equals(probe.reason(subject, "target")), "Explicit target reason was lost");
            });
            check(failures, "server vanilla target cancellation", () -> {
                probe.arm(subject, "TARGET_CANCEL", null);
                int before = probe.count(subject, "target");
                subject.setTarget(second);
                require(subject.getTargetUnchecked() == first, "Cancelled setTarget fell through to the vanilla assignment");
                require(probe.count(subject, "target") == before + 1, "Cancelled target event was missing or duplicated");
            });
            check(failures, "server target replacement", () -> {
                probe.arm(subject, "TARGET_REPLACE", first);
                int before = probe.count(subject, "target");
                ((MobBridge) subject).cardboard$setTargetWithReason(second, TargetReason.CLOSEST_ENTITY);
                require(subject.getTargetUnchecked() == first, "Plugin replacement target was ignored");
                require(probe.count(subject, "target") == before + 1, "Replacement target event was missing or duplicated");
            });
            check(failures, "server target clear cancellation", () -> {
                probe.arm(subject, "TARGET_CANCEL", null);
                subject.setTarget(null);
                require(subject.getTargetUnchecked() == first, "Cancelled clear removed the server target");
            });
            check(failures, "server direct bridge cancellation", () -> {
                require(!((MobBridge) subject).cardboard$setTarget(second, TargetReason.CLOSEST_ENTITY), "Cancelled bridge reported success");
                require(subject.getTargetUnchecked() == first, "Cancelled bridge changed the target");
            });
            check(failures, "server target clearing and no-op", () -> {
                probe.arm(subject, "OBSERVE", null);
                ((MobBridge) subject).cardboard$setTargetWithReason(null, TargetReason.FORGOT_TARGET);
                require(subject.getTargetUnchecked() == null, "Server target did not clear");
                int before = probe.count(subject, "target");
                subject.setTarget(null);
                require(probe.count(subject, "target") == before, "Unchanged target emitted an event");
                ((MobBridge) subject).cardboard$setTargetWithReason(first, TargetReason.CLOSEST_ENTITY);
                before = probe.count(subject, "target");
                require(((MobBridge) subject).cardboard$setTarget(null, null), "No-event server bridge did not clear the target");
                require(subject.getTargetUnchecked() == null && probe.count(subject, "target") == before, "No-event bridge emitted a target event");
            });
            check(failures, "server vanilla target validation", () -> {
                ((MobBridge) subject).cardboard$setTargetWithReason(owner, TargetReason.CLOSEST_PLAYER);
                require(subject.getTargetUnchecked() == null, "Server target assignment bypassed vanilla creative-player filtering");
            });
            check(failures, "server current target revalidation", () -> {
                ((MobBridge) subject).cardboard$setTargetWithReason(first, TargetReason.CLOSEST_ENTITY);
                int before = probe.count(subject, "target");
                first.setInvulnerable(true);
                try {
                    subject.setTarget(first);
                    require(subject.getTargetUnchecked() == null, "Server retained an invulnerable current target");
                    require(probe.count(subject, "target") == before, "Revalidation duplicated a Bukkit target event");
                } finally {
                    first.setInvulnerable(false);
                }
            });
            check(failures, "server vehicle events remain active", () -> {
                int enters = probe.count(subject, "vehicleEnter");
                int exits = probe.count(subject, "vehicleExit");
                require(subject.startRiding(first, true, true), "Server mount was rejected");
                require(subject.getVehicle() == first && probe.count(subject, "vehicleEnter") == enters + 1, "Server vehicle enter event was lost or duplicated");
                subject.removeVehicle();
                require(subject.getVehicle() == null && first.getPassengers().isEmpty(), "Server dismount left inconsistent passengers");
                require(probe.count(subject, "vehicleExit") == exits + 1, "Server vehicle exit event was lost or duplicated");
            });
        } finally {
            subject.discard();
            first.discard();
            second.discard();
        }
        finish(failures, "SERVER_ENTITY_EVENTS_PASS");
    }

    private static Pig spawn(ServerLevel level, ServerPlayer owner, double offset) {
        Pig pig = new Pig(EntityTypes.PIG, level);
        pig.setPos(owner.getX() + offset, owner.getY(), owner.getZ());
        pig.setNoAi(true);
        require(level.addFreshEntity(pig), "Could not add the server probe entity");
        return pig;
    }

    private static void healWithReason(LivingEntity entity, float amount) {
        try {
            entity.getClass().getMethod("heal", float.class, RegainReason.class).invoke(entity, amount, RegainReason.MAGIC);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Reasoned heal failed", error instanceof InvocationTargetException invocation ? invocation.getCause() : error);
        }
    }

    private static void check(List<Throwable> failures, String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            failures.add(new AssertionError(name, error));
        }
    }

    private static void finish(List<Throwable> failures, String marker) {
        if (!failures.isEmpty()) {
            AssertionError error = new AssertionError(marker + " failed: " + failures.size() + " cases");
            failures.forEach(error::addSuppressed);
            throw error;
        }
        System.out.println(marker);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Probe(Plugin plugin) {
        void arm(LivingEntity entity, String behavior, LivingEntity replacement) {
            call("probeEntity", new Class<?>[]{UUID.class, String.class, UUID.class}, entity.getUUID(), behavior, replacement == null ? null : replacement.getUUID());
        }

        int count(LivingEntity entity, String kind) {
            return (Integer) call("probeCount", new Class<?>[]{UUID.class, String.class}, entity.getUUID(), kind);
        }

        String reason(LivingEntity entity, String kind) {
            return (String) call("probeReason", new Class<?>[]{UUID.class, String.class}, entity.getUUID(), kind);
        }

        private Object call(String method, Class<?>[] types, Object... args) {
            try {
                return plugin.getClass().getMethod(method, types).invoke(plugin, args);
            } catch (ReflectiveOperationException error) {
                throw new AssertionError("Bukkit probe " + method + " failed", error);
            }
        }
    }
}

