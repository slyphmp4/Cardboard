package org.cardboardpowered.impl.world;

import java.util.ArrayDeque;
import java.util.Deque;
import org.bukkit.craftbukkit.util.CraftStructureTransformer;

/** Every native placement pushes a frame, so nested vanilla placements inherit nothing. */
public final class CardboardStructureContext implements AutoCloseable {
    private static final ThreadLocal<Deque<CardboardStructureContext>> ACTIVE = new ThreadLocal<>();
    private final CraftStructureTransformer transformer;

    private CardboardStructureContext(CraftStructureTransformer transformer) {
        this.transformer = transformer;
        var stack = ACTIVE.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            ACTIVE.set(stack);
        }
        stack.push(this);
    }

    public static CardboardStructureContext open(CraftStructureTransformer transformer) {
        return new CardboardStructureContext(transformer);
    }

    public static CraftStructureTransformer current() {
        var stack = ACTIVE.get();
        return stack == null || stack.isEmpty() ? null : stack.peek().transformer;
    }

    @Override
    public void close() {
        var stack = ACTIVE.get();
        if (stack == null || stack.peek() != this) throw new IllegalStateException("Structure context closed out of order");
        stack.pop();
        if (stack.isEmpty()) ACTIVE.remove();
    }
}
