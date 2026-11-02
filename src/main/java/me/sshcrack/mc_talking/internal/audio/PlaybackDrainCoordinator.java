package me.sshcrack.mc_talking.internal.audio;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Coordinates one bounded graceful playback drain without owning a scheduler thread. */
public final class PlaybackDrainCoordinator {
    @FunctionalInterface
    public interface Scheduler {
        @NotNull Cancellation schedule(@NotNull Runnable task, long delayMillis);
    }

    @FunctionalInterface
    public interface Cancellation {
        void cancel();
    }

    private final Runnable flush;
    private final BooleanSupplier hasPendingPlayback;
    private final long timeoutMillis;
    private final Scheduler scheduler;
    private final Runnable completion;
    private final AtomicBoolean requested = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private volatile @Nullable Cancellation timeoutCancellation;

    public PlaybackDrainCoordinator(
            @NotNull Runnable flush,
            @NotNull BooleanSupplier hasPendingPlayback,
            long timeoutMillis,
            @NotNull Scheduler scheduler,
            @NotNull Runnable completion
    ) {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
        this.flush = Objects.requireNonNull(flush, "flush");
        this.hasPendingPlayback = Objects.requireNonNull(hasPendingPlayback, "hasPendingPlayback");
        this.timeoutMillis = timeoutMillis;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.completion = Objects.requireNonNull(completion, "completion");
    }

    /** Returns true only for the first graceful-end request. */
    public boolean request() {
        if (completed.get() || !requested.compareAndSet(false, true)) return false;
        flush.run();
        if (!hasPendingPlayback.getAsBoolean()) {
            finish();
            return true;
        }
        timeoutCancellation = scheduler.schedule(this::finish, timeoutMillis);
        if (completed.get()) cancelTimeout();
        return true;
    }

    /** Called when the voice-chat supplier reports that queued playback has naturally drained. */
    public void onPlaybackDrained() {
        if (requested.get()) finish();
    }

    /** Cancels only the pending timeout; it deliberately does not run graceful completion. */
    public void cancel() {
        completed.set(true);
        cancelTimeout();
    }

    public boolean requested() {
        return requested.get();
    }

    public boolean completed() {
        return completed.get();
    }

    private void finish() {
        if (!completed.compareAndSet(false, true)) return;
        cancelTimeout();
        completion.run();
    }

    private void cancelTimeout() {
        Cancellation cancellation = timeoutCancellation;
        if (cancellation != null) {
            timeoutCancellation = null;
            cancellation.cancel();
        }
    }
}
