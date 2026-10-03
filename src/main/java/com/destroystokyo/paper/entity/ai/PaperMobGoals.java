package com.destroystokyo.paper.entity.ai;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.bukkit.craftbukkit.entity.CraftMob;
import org.bukkit.entity.Mob;
import org.cardboardpowered.bridge.world.entity.MobBridge;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class PaperMobGoals implements MobGoals {

    @Override
    public <T extends Mob> void addGoal(T mob, int priority, Goal<T> goal) {
        CraftMob craftMob = (CraftMob) mob;
        net.minecraft.world.entity.ai.goal.Goal mojangGoal;

        if (goal instanceof PaperGoal<?> paperGoal) {
            mojangGoal = paperGoal.getHandle();
        } else {
            mojangGoal = new PaperCustomGoal<>(goal);
        }

        getHandle(craftMob, goal.getTypes()).addGoal(priority, mojangGoal);
    }

    @Override
    public <T extends Mob> void removeGoal(T mob, Goal<T> goal) {
        CraftMob craftMob = (CraftMob) mob;
        if (goal instanceof PaperGoal<?> paperGoal) {
            getHandle(craftMob, goal.getTypes()).removeGoal(paperGoal.getHandle());
            return;
        }

        List<net.minecraft.world.entity.ai.goal.Goal> toRemove = new LinkedList<>();
        for (WrappedGoal item : getHandle(craftMob, goal.getTypes()).getAvailableGoals()) {
            if (item.getGoal() instanceof PaperCustomGoal<?> customGoal && customGoal.getHandle() == goal) {
                toRemove.add(item.getGoal());
            }
        }

        for (net.minecraft.world.entity.ai.goal.Goal nmsGoal : toRemove) {
            getHandle(craftMob, goal.getTypes()).removeGoal(nmsGoal);
        }
    }

    @Override
    public <T extends Mob> void removeAllGoals(T mob) {
        for (GoalType type : GoalType.values()) {
            removeAllGoals(mob, type);
        }
    }

    @Override
    public <T extends Mob> void removeAllGoals(T mob, GoalType type) {
        for (Goal<T> goal : getAllGoals(mob, type)) {
            removeGoal(mob, goal);
        }
    }

    @Override
    public <T extends Mob> void removeGoal(T mob, GoalKey<T> key) {
        for (Goal<T> goal : getGoals(mob, key)) {
            removeGoal(mob, goal);
        }
    }

    @Override
    public <T extends Mob> boolean hasGoal(T mob, GoalKey<T> key) {
        for (Goal<T> goal : getAllGoals(mob)) {
            if (goal.getKey().equals(key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public <T extends Mob> Goal<T> getGoal(T mob, GoalKey<T> key) {
        for (Goal<T> goal : getAllGoals(mob)) {
            if (goal.getKey().equals(key)) {
                return goal;
            }
        }
        return null;
    }

    @Override
    public <T extends Mob> Collection<Goal<T>> getGoals(T mob, GoalKey<T> key) {
        Set<Goal<T>> goals = new HashSet<>();
        for (Goal<T> goal : getAllGoals(mob)) {
            if (goal.getKey().equals(key)) {
                goals.add(goal);
            }
        }
        return goals;
    }

    @Override
    public <T extends Mob> Collection<Goal<T>> getAllGoals(T mob) {
        Set<Goal<T>> goals = new HashSet<>();
        for (GoalType type : GoalType.values()) {
            goals.addAll(getAllGoals(mob, type));
        }
        return goals;
    }

    @Override
    public <T extends Mob> Collection<Goal<T>> getAllGoals(T mob, GoalType type) {
        CraftMob craftMob = (CraftMob) mob;
        Set<Goal<T>> goals = new HashSet<>();
        for (WrappedGoal item : getHandle(craftMob, type).getAvailableGoals()) {
            if (!MobGoalHelper.hasType(item.getGoal(), type)) {
                continue;
            }
            goals.add(toPaperGoal(item.getGoal()));
        }
        return goals;
    }

    @Override
    public <T extends Mob> Collection<Goal<T>> getAllGoalsWithout(T mob, GoalType type) {
        CraftMob craftMob = (CraftMob) mob;
        Set<Goal<T>> goals = new HashSet<>();
        for (GoalType internalType : GoalType.values()) {
            if (internalType == type) {
                continue;
            }
            for (WrappedGoal item : getHandle(craftMob, internalType).getAvailableGoals()) {
                if (MobGoalHelper.hasType(item.getGoal(), type)) {
                    continue;
                }
                goals.add(toPaperGoal(item.getGoal()));
            }
        }
        return goals;
    }

    @Override
    public <T extends Mob> Collection<Goal<T>> getRunningGoals(T mob) {
        Set<Goal<T>> goals = new HashSet<>();
        for (GoalType type : GoalType.values()) {
            goals.addAll(getRunningGoals(mob, type));
        }
        return goals;
    }

    @Override
    public <T extends Mob> Collection<Goal<T>> getRunningGoals(T mob, GoalType type) {
        CraftMob craftMob = (CraftMob) mob;
        Set<Goal<T>> goals = new HashSet<>();
        getHandle(craftMob, type).getAvailableGoals().stream()
            .filter(WrappedGoal::isRunning)
            .filter(item -> MobGoalHelper.hasType(item.getGoal(), type))
            .forEach(item -> goals.add(toPaperGoal(item.getGoal())));
        return goals;
    }

    @Override
    public <T extends Mob> Collection<Goal<T>> getRunningGoalsWithout(T mob, GoalType type) {
        CraftMob craftMob = (CraftMob) mob;
        Set<Goal<T>> goals = new HashSet<>();
        for (GoalType internalType : GoalType.values()) {
            if (internalType == type) {
                continue;
            }
            getHandle(craftMob, internalType).getAvailableGoals().stream()
                .filter(WrappedGoal::isRunning)
                .filter(item -> !MobGoalHelper.hasType(item.getGoal(), type))
                .forEach(item -> goals.add(toPaperGoal(item.getGoal())));
        }
        return goals;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Mob> Goal<T> toPaperGoal(net.minecraft.world.entity.ai.goal.Goal nmsGoal) {
        if (nmsGoal instanceof PaperCustomGoal<?> customGoal) {
            return (Goal<T>) customGoal.getHandle();
        }
        return new PaperGoal<>(nmsGoal);
    }

    private GoalSelector getHandle(CraftMob mob, EnumSet<GoalType> types) {
        MobBridge bridge = (MobBridge) (Object) mob.getHandle();
        return types.contains(GoalType.TARGET)
            ? bridge.cardboard$getTargetSelector()
            : bridge.cardboard$getGoalSelector();
    }

    private GoalSelector getHandle(CraftMob mob, GoalType type) {
        MobBridge bridge = (MobBridge) (Object) mob.getHandle();
        return type == GoalType.TARGET
            ? bridge.cardboard$getTargetSelector()
            : bridge.cardboard$getGoalSelector();
    }
}
