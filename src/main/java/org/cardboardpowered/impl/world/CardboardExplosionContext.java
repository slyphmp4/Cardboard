package org.cardboardpowered.impl.world;

import java.util.ArrayDeque;
import java.util.Deque;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.cardboardpowered.bridge.world.level.ServerExplosionBridge;

/** Associates one API invocation with its native explosion, including nested calls. */
public final class CardboardExplosionContext implements AutoCloseable {
    private static final ThreadLocal<Deque<CardboardExplosionContext>> ACTIVE = new ThreadLocal<>();

    private final ServerLevel level;
    private final boolean excludeSource;
    private final boolean noDropDecay;
    private boolean claimed;
    private ServerExplosion explosion;

    private CardboardExplosionContext(ServerLevel level, boolean excludeSource, boolean noDropDecay) {
        this.level = level;
        this.excludeSource = excludeSource;
        this.noDropDecay = noDropDecay;
        Deque<CardboardExplosionContext> stack = ACTIVE.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            ACTIVE.set(stack);
        }
        stack.push(this);
    }

    public static CardboardExplosionContext open(ServerLevel level, boolean excludeSource, boolean noDropDecay) {
        return new CardboardExplosionContext(level, excludeSource, noDropDecay);
    }

    /** Claim before construction, so a nested native explosion cannot steal the result. */
    public static CardboardExplosionContext claim(ServerLevel level) {
        Deque<CardboardExplosionContext> stack = ACTIVE.get();
        CardboardExplosionContext current = stack == null ? null : stack.peek();
        if (current == null || current.level != level || current.claimed) return null;
        current.claimed = true;
        return current;
    }

    public boolean noDropDecay() { return this.noDropDecay; }

    public void attach(ServerExplosion explosion) {
        this.explosion = explosion;
        ((ServerExplosionBridge) (Object) explosion).cardboard$setExcludeSource(this.excludeSource);
    }

    public boolean wasCancelled() {
        return this.explosion != null && ((ServerExplosionBridge) (Object) this.explosion).cardboard$wasCancelled();
    }

    @Override
    public void close() {
        Deque<CardboardExplosionContext> stack = ACTIVE.get();
        if (stack == null || stack.peek() != this) throw new IllegalStateException("Explosion context closed out of order");
        stack.pop();
        if (stack.isEmpty()) ACTIVE.remove();
    }
}
