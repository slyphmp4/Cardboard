package io.papermc.paper.raytracing;

import com.google.common.base.Preconditions;
import java.util.EnumSet;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

public class PositionedRayTraceConfigurationBuilderImpl implements PositionedRayTraceConfigurationBuilder {
    public Location start;
    public Vector direction;
    public OptionalDouble maxDistance = OptionalDouble.empty();
    public FluidCollisionMode fluidCollisionMode = FluidCollisionMode.NEVER;
    public BlockCollisionMode blockCollisionMode = BlockCollisionMode.OUTLINE;
    public double raySize;
    public Predicate<? super Entity> entityFilter;
    public Predicate<? super Block> blockFilter;
    public EnumSet<RayTraceTarget> targets = EnumSet.noneOf(RayTraceTarget.class);

    private static <T> T required(T value, String name) {
        Preconditions.checkArgument(value != null, "%s must not be null", name);
        return value;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder start(Location location) {
        this.start = required(location, "start").clone();
        return this;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder direction(Vector vector) {
        this.direction = required(vector, "direction").clone();
        return this;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder maxDistance(double distance) {
        Preconditions.checkArgument(distance >= 0, "maxDistance must be non-negative");
        this.maxDistance = OptionalDouble.of(distance);
        return this;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder fluidCollisionMode(FluidCollisionMode mode) {
        this.fluidCollisionMode = required(mode, "fluidCollisionMode");
        return this;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder blockCollisionMode(BlockCollisionMode mode) {
        this.blockCollisionMode = required(mode, "blockCollisionMode");
        return this;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder ignorePassableBlocks(boolean ignore) {
        return this.blockCollisionMode(ignore ? BlockCollisionMode.COLLIDER : BlockCollisionMode.OUTLINE);
    }

    @Override
    public PositionedRayTraceConfigurationBuilder raySize(double size) {
        Preconditions.checkArgument(size >= 0, "raySize must be non-negative");
        this.raySize = size;
        return this;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder entityFilter(Predicate<? super Entity> filter) {
        this.entityFilter = required(filter, "entityFilter");
        return this;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder blockFilter(Predicate<? super Block> filter) {
        this.blockFilter = required(filter, "blockFilter");
        return this;
    }

    @Override
    public PositionedRayTraceConfigurationBuilder targets(RayTraceTarget first, RayTraceTarget... others) {
        this.targets = EnumSet.of(required(first, "first"), required(others, "others"));
        return this;
    }
}
