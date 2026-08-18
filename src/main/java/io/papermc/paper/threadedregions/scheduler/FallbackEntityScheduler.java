package io.papermc.paper.threadedregions.scheduler;

import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * EntityScheduler compatibility implementation for Cardboard's
 * single main-thread server model.
 *
 * Tasks are dispatched through the already existing global-region
 * scheduler while retaining Paper's EntityScheduler API semantics.
 */
public final class FallbackEntityScheduler implements EntityScheduler {

    private final CraftEntity entity;

    /*
     * Paper maintains a persistent retired state for an EntityScheduler.
     * Merely checking Entity#isRemoved when the delayed task finally runs
     * is insufficient: retirement must actively settle already-scheduled
     * callbacks as soon as the entity leaves the server permanently.
     */
    private final Object lifecycleLock = new Object();

    private final Set<PendingExecute> pendingExecutes =
            new HashSet<>();

    private final Set<EntityScheduledTask> pendingScheduledTasks =
            new HashSet<>();

    private boolean retired;

    public FallbackEntityScheduler(final CraftEntity entity) {
        this.entity = Objects.requireNonNull(entity, "entity");
    }

    private boolean isRetired() {
        synchronized (this.lifecycleLock) {
            return this.retired
                    || this.entity.getHandleRaw() == null
                    || this.entity.getHandleRaw().isRemoved();
        }
    }

    private boolean registerPendingExecute(
            final PendingExecute task
    ) {
        synchronized (this.lifecycleLock) {
            if (
                    this.retired
                            || this.entity.getHandleRaw() == null
                            || this.entity.getHandleRaw().isRemoved()
            ) {
                return false;
            }

            this.pendingExecutes.add(task);
            return true;
        }
    }

    private void unregisterPendingExecute(
            final PendingExecute task
    ) {
        synchronized (this.lifecycleLock) {
            this.pendingExecutes.remove(task);
        }
    }

    private boolean registerScheduledTask(
            final EntityScheduledTask task
    ) {
        synchronized (this.lifecycleLock) {
            if (
                    this.retired
                            || this.entity.getHandleRaw() == null
                            || this.entity.getHandleRaw().isRemoved()
            ) {
                return false;
            }

            this.pendingScheduledTasks.add(task);
            return true;
        }
    }

    private void unregisterScheduledTask(
            final EntityScheduledTask task
    ) {
        synchronized (this.lifecycleLock) {
            this.pendingScheduledTasks.remove(task);
        }
    }

    /**
     * Retires this scheduler and immediately settles pending execute()
     * tasks through their retired callback.
     *
     * Paper performs scheduler retirement when an entity is truly
     * removed from the server rather than waiting for each task's
     * original execution tick.
     */
    public void retire() {
        final PendingExecute[] pendingExecutes;
        final EntityScheduledTask[] pendingScheduledTasks;

        synchronized (this.lifecycleLock) {
            if (this.retired) {
                return;
            }

            this.retired = true;

            pendingExecutes =
                    this.pendingExecutes.toArray(
                            new PendingExecute[0]
                    );

            pendingScheduledTasks =
                    this.pendingScheduledTasks.toArray(
                            new EntityScheduledTask[0]
                    );

            this.pendingExecutes.clear();
            this.pendingScheduledTasks.clear();
        }

        for (final PendingExecute task : pendingExecutes) {
            task.retireInternal();
        }

        for (final EntityScheduledTask task : pendingScheduledTasks) {
            task.retireInternal();
        }
    }

    @Override
    public boolean execute(
            final Plugin plugin,
            final Runnable run,
            final Runnable retired,
            final long delay
    ) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        Objects.requireNonNull(run, "Runnable may not be null");

        if (this.isRetired()) {
            return false;
        }

        /*
         * Keep the existing disabled-plugin compatibility behaviour.
         */
        if (!plugin.isEnabled()) {
            return true;
        }

        final PendingExecute pending =
                new PendingExecute(
                        plugin,
                        run,
                        retired
                );

        if (!this.registerPendingExecute(pending)) {
            return false;
        }

        try {
            this.entity.getServer()
                    .getGlobalRegionScheduler()
                    .runDelayed(
                            plugin,
                            ignored -> pending.executeInternal(),
                            Math.max(1L, delay)
                    );

            return true;
        } catch (final IllegalPluginAccessException ignored) {
            /*
             * Plugin may have become disabled between registration
             * and delegation to the global scheduler.
             */
            pending.cancelInternal();
            return true;
        }
    }

    @Override
    public @Nullable ScheduledTask run(
            final Plugin plugin,
            final Consumer<ScheduledTask> task,
            final Runnable retired
    ) {
        return this.runDelayed(plugin, task, retired, 1L);
    }

    @Override
    public @Nullable ScheduledTask runDelayed(
            final Plugin plugin,
            final Consumer<ScheduledTask> task,
            final Runnable retired,
            final long delayTicks
    ) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        Objects.requireNonNull(task, "Task may not be null");

        if (delayTicks <= 0L) {
            throw new IllegalArgumentException(
                    "Delay ticks may not be <= 0"
            );
        }

        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException(
                    "Plugin attempted to register task while disabled"
            );
        }

        final EntityScheduledTask ret =
                new EntityScheduledTask(
                        plugin,
                        -1L,
                        task,
                        retired
                );

        if (!this.scheduleInternal(ret, delayTicks)) {
            return null;
        }

        if (!plugin.isEnabled()) {
            ret.cancel();
        }

        return ret;
    }

    @Override
    public @Nullable ScheduledTask runAtFixedRate(
            final Plugin plugin,
            final Consumer<ScheduledTask> task,
            final Runnable retired,
            final long initialDelayTicks,
            final long periodTicks
    ) {
        Objects.requireNonNull(plugin, "Plugin may not be null");
        Objects.requireNonNull(task, "Task may not be null");

        if (initialDelayTicks <= 0L) {
            throw new IllegalArgumentException(
                    "Initial delay ticks may not be <= 0"
            );
        }

        if (periodTicks <= 0L) {
            throw new IllegalArgumentException(
                    "Period ticks may not be <= 0"
            );
        }

        if (!plugin.isEnabled()) {
            throw new IllegalPluginAccessException(
                    "Plugin attempted to register task while disabled"
            );
        }

        final EntityScheduledTask ret =
                new EntityScheduledTask(
                        plugin,
                        periodTicks,
                        task,
                        retired
                );

        if (!this.scheduleInternal(ret, initialDelayTicks)) {
            return null;
        }

        if (!plugin.isEnabled()) {
            ret.cancel();
        }

        return ret;
    }

    private boolean scheduleInternal(
            final EntityScheduledTask task,
            final long delay
    ) {
        if (!this.registerScheduledTask(task)) {
            return false;
        }

        try {
            this.entity.getServer()
                    .getGlobalRegionScheduler()
                    .runDelayed(
                            task.plugin,
                            ignored -> task.executeInternal(),
                            Math.max(1L, delay)
                    );

            return true;
        } catch (final IllegalPluginAccessException ignored) {
            this.unregisterScheduledTask(task);
            return false;
        }
    }

    private final class PendingExecute {

        private final Plugin plugin;
        private final Runnable run;
        private final Runnable retired;

        private final AtomicBoolean settled =
                new AtomicBoolean(false);

        private PendingExecute(
                final Plugin plugin,
                final Runnable run,
                final Runnable retired
        ) {
            this.plugin = plugin;
            this.run = run;
            this.retired = retired;
        }

        private void executeInternal() {
            if (!this.settled.compareAndSet(false, true)) {
                return;
            }

            FallbackEntityScheduler.this.unregisterPendingExecute(this);

            if (!this.plugin.isEnabled()) {
                return;
            }

            final boolean entityRetired =
                    FallbackEntityScheduler.this.isRetired();

            this.invoke(
                    entityRetired
                            ? this.retired
                            : this.run
            );
        }

        private void retireInternal() {
            if (!this.settled.compareAndSet(false, true)) {
                return;
            }

            FallbackEntityScheduler.this.unregisterPendingExecute(this);

            if (!this.plugin.isEnabled()) {
                return;
            }

            this.invoke(this.retired);
        }

        private void cancelInternal() {
            if (!this.settled.compareAndSet(false, true)) {
                return;
            }

            FallbackEntityScheduler.this.unregisterPendingExecute(this);
        }

        private void invoke(
                final Runnable callback
        ) {
            if (callback == null) {
                return;
            }

            try {
                callback.run();
            } catch (final Throwable throwable) {
                this.plugin.getLogger().log(
                        Level.WARNING,
                        "Entity task for "
                                + this.plugin.getDescription().getFullName()
                                + " generated an exception",
                        throwable
                );
            }
        }
    }

    private static VarHandle getVarHandle(
            final Class<?> owner,
            final String field,
            final Class<?> type
    ) {
        try {
            return MethodHandles.privateLookupIn(
                    owner,
                    MethodHandles.lookup()
            ).findVarHandle(owner, field, type);
        } catch (final ReflectiveOperationException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final class EntityScheduledTask implements ScheduledTask {

        private static final int STATE_IDLE = 0;
        private static final int STATE_EXECUTING = 1;
        private static final int STATE_EXECUTING_CANCELLED = 2;
        private static final int STATE_FINISHED = 3;
        private static final int STATE_CANCELLED = 4;

        private final Plugin plugin;
        private final long repeatDelay;

        private Consumer<ScheduledTask> run;
        private Runnable retired;

        private volatile int state;

        private static final VarHandle STATE_HANDLE =
                getVarHandle(
                        EntityScheduledTask.class,
                        "state",
                        int.class
                );

        private EntityScheduledTask(
                final Plugin plugin,
                final long repeatDelay,
                final Consumer<ScheduledTask> run,
                final Runnable retired
        ) {
            this.plugin = plugin;
            this.repeatDelay = repeatDelay;
            this.run = run;
            this.retired = retired;
        }

        private int getStateVolatile() {
            return (int) STATE_HANDLE.getVolatile(this);
        }

        private int compareAndExchangeState(
                final int expected,
                final int update
        ) {
            return (int) STATE_HANDLE.compareAndExchange(
                    this,
                    expected,
                    update
            );
        }

        private void setStateVolatile(final int value) {
            STATE_HANDLE.setVolatile(this, value);
        }

        private void clearCallbacks() {
            this.run = null;
            this.retired = null;
        }

        private void executeInternal() {
            if (!this.plugin.isEnabled()) {
                this.setStateVolatile(STATE_CANCELLED);
                FallbackEntityScheduler.this.unregisterScheduledTask(this);
                this.clearCallbacks();
                return;
            }

            final boolean repeating = this.isRepeatingTask();

            if (STATE_IDLE != this.compareAndExchangeState(
                    STATE_IDLE,
                    STATE_EXECUTING
            )) {
                return;
            }

            final boolean entityRetired =
                    FallbackEntityScheduler.this.isRetired();

            try {
                if (!entityRetired) {
                    final Consumer<ScheduledTask> callback = this.run;

                    if (callback != null) {
                        callback.accept(this);
                    }
                } else {
                    final Runnable callback = this.retired;

                    if (callback != null) {
                        callback.run();
                    }
                }
            } catch (final Throwable throwable) {
                this.plugin.getLogger().log(
                        Level.WARNING,
                        "Entity task for "
                                + this.plugin.getDescription().getFullName()
                                + " generated an exception",
                        throwable
                );
            } finally {
                boolean reschedule = false;

                if (!repeating && !entityRetired) {
                    this.setStateVolatile(STATE_FINISHED);
                } else if (
                        entityRetired
                                || !this.plugin.isEnabled()
                ) {
                    this.setStateVolatile(STATE_CANCELLED);
                } else if (
                        STATE_EXECUTING
                                == this.compareAndExchangeState(
                                        STATE_EXECUTING,
                                        STATE_IDLE
                                )
                ) {
                    reschedule = true;
                }

                if (!reschedule) {
                    FallbackEntityScheduler.this.unregisterScheduledTask(this);
                    this.clearCallbacks();
                } else if (
                        !FallbackEntityScheduler.this.scheduleInternal(
                                this,
                                this.repeatDelay
                        )
                ) {
                    this.setStateVolatile(STATE_CANCELLED);
                    FallbackEntityScheduler.this.unregisterScheduledTask(this);
                    this.clearCallbacks();
                }
            }
        }

        private void retireInternal() {
            for (int current = this.getStateVolatile();;) {
                switch (current) {
                    case STATE_IDLE -> {
                        current = this.compareAndExchangeState(
                                STATE_IDLE,
                                STATE_EXECUTING
                        );

                        if (current != STATE_IDLE) {
                            continue;
                        }

                        FallbackEntityScheduler.this.unregisterScheduledTask(this);

                        try {
                            if (
                                    this.plugin.isEnabled()
                                            && this.retired != null
                            ) {
                                this.retired.run();
                            }
                        } catch (final Throwable throwable) {
                            this.plugin.getLogger().log(
                                    Level.WARNING,
                                    "Entity retired task for "
                                            + this.plugin.getDescription().getFullName()
                                            + " generated an exception",
                                    throwable
                            );
                        } finally {
                            this.setStateVolatile(STATE_CANCELLED);
                            this.clearCallbacks();
                        }

                        return;
                    }

                    case STATE_EXECUTING,
                         STATE_EXECUTING_CANCELLED,
                         STATE_FINISHED,
                         STATE_CANCELLED -> {
                        return;
                    }

                    default -> throw new IllegalStateException(
                            "Unknown task state: " + current
                    );
                }
            }
        }

        @Override
        public Plugin getOwningPlugin() {
            return this.plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return this.repeatDelay > 0L;
        }

        @Override
        public CancelledState cancel() {
            for (int current = this.getStateVolatile();;) {
                switch (current) {
                    case STATE_IDLE -> {
                        current = this.compareAndExchangeState(
                                STATE_IDLE,
                                STATE_CANCELLED
                        );

                        if (current == STATE_IDLE) {
                            FallbackEntityScheduler.this.unregisterScheduledTask(this);
                            this.clearCallbacks();
                            return CancelledState.CANCELLED_BY_CALLER;
                        }
                    }

                    case STATE_EXECUTING -> {
                        if (!this.isRepeatingTask()) {
                            return CancelledState.RUNNING;
                        }

                        current = this.compareAndExchangeState(
                                STATE_EXECUTING,
                                STATE_EXECUTING_CANCELLED
                        );

                        if (current == STATE_EXECUTING) {
                            return CancelledState.NEXT_RUNS_CANCELLED;
                        }
                    }

                    case STATE_EXECUTING_CANCELLED -> {
                        return CancelledState.NEXT_RUNS_CANCELLED_ALREADY;
                    }

                    case STATE_FINISHED -> {
                        return CancelledState.ALREADY_EXECUTED;
                    }

                    case STATE_CANCELLED -> {
                        return CancelledState.CANCELLED_ALREADY;
                    }

                    default -> throw new IllegalStateException(
                            "Unknown task state: " + current
                    );
                }
            }
        }

        @Override
        public ExecutionState getExecutionState() {
            return switch (this.getStateVolatile()) {
                case STATE_IDLE -> ExecutionState.IDLE;
                case STATE_EXECUTING -> ExecutionState.RUNNING;
                case STATE_EXECUTING_CANCELLED ->
                        ExecutionState.CANCELLED_RUNNING;
                case STATE_FINISHED -> ExecutionState.FINISHED;
                case STATE_CANCELLED -> ExecutionState.CANCELLED;
                default -> throw new IllegalStateException(
                        "Unknown task state"
                );
            };
        }
    }
}