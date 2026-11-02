package me.sshcrack.mc_talking.internal.session;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Token-owned busy leases for core and addon activities that are not foreground provider sessions. */
public final class CitizenActivityRegistry {
    public enum OwnerKind { CORE, ADDON }

    public record Token(UUID citizenId, UUID ownershipId, OwnerKind ownerKind) {
        public Token {
            Objects.requireNonNull(citizenId, "citizenId");
            Objects.requireNonNull(ownershipId, "ownershipId");
            Objects.requireNonNull(ownerKind, "ownerKind");
        }
    }

    private final LongSupplier nanoClock;
    private final Map<UUID, Activity> activities = new LinkedHashMap<>();

    public CitizenActivityRegistry(LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public synchronized @Nullable Token reserveCore(
            UUID citizenId,
            boolean foregroundBusy,
            long timeoutNanos,
            Runnable onTimeout,
            Runnable onPreempt
    ) {
        validateTimeout(timeoutNanos);
        Objects.requireNonNull(citizenId, "citizenId");
        Objects.requireNonNull(onTimeout, "onTimeout");
        Objects.requireNonNull(onPreempt, "onPreempt");
        purgeExpiredLocked(null);
        if (foregroundBusy || activities.containsKey(citizenId)) return null;
        Token token = new Token(citizenId, UUID.randomUUID(), OwnerKind.CORE);
        activities.put(citizenId, new Activity(token, deadline(timeoutNanos), onTimeout, onPreempt));
        return token;
    }

    public synchronized boolean reserveAddon(UUID citizenId, UUID tokenId, boolean foregroundBusy, long timeoutNanos) {
        validateTimeout(timeoutNanos);
        Objects.requireNonNull(citizenId, "citizenId");
        Objects.requireNonNull(tokenId, "tokenId");
        purgeExpiredLocked(null);
        if (foregroundBusy || activities.containsKey(citizenId)) return false;
        Token token = new Token(citizenId, tokenId, OwnerKind.ADDON);
        activities.put(citizenId, new Activity(token, deadline(timeoutNanos), null, null));
        return true;
    }

    public synchronized boolean renewAddon(UUID citizenId, UUID tokenId, long timeoutNanos) {
        validateTimeout(timeoutNanos);
        purgeExpiredLocked(null);
        Activity activity = activities.get(citizenId);
        if (activity == null || !matches(activity, citizenId, tokenId, OwnerKind.ADDON)) return false;
        activity.deadlineNanos = deadline(timeoutNanos);
        return true;
    }

    public synchronized boolean isActive(UUID citizenId, UUID tokenId, OwnerKind ownerKind) {
        purgeExpiredLocked(null);
        return matches(activities.get(citizenId), citizenId, tokenId, ownerKind);
    }

    public synchronized boolean isBusy(UUID citizenId) {
        purgeExpiredLocked(null);
        return activities.containsKey(citizenId);
    }

    public synchronized boolean release(UUID citizenId, UUID tokenId, OwnerKind ownerKind) {
        Activity activity = activities.get(citizenId);
        if (!matches(activity, citizenId, tokenId, ownerKind)) return false;
        return activities.remove(citizenId, activity);
    }

    /**
     * Removes whichever activity owns the citizen before direct-player takeover. Core activities
     * return their exact cleanup hook; addon leases are simply invalidated so stale handles cannot
     * release or renew a later owner.
     */
    public synchronized @Nullable Runnable preemptForPlayer(UUID citizenId) {
        Activity activity = activities.get(citizenId);
        if (activity == null) return null;
        activities.remove(citizenId, activity);
        return activity.token.ownerKind() == OwnerKind.CORE ? activity.onPreempt : null;
    }

    /** Removes expired leases and returns core timeout actions to execute after ownership is gone. */
    public synchronized List<Runnable> purgeExpired() {
        List<Runnable> callbacks = new ArrayList<>();
        purgeExpiredLocked(callbacks);
        return callbacks;
    }

    /** Releases all leases and returns core cleanup callbacks for server shutdown. */
    public synchronized List<Runnable> shutdown() {
        List<Runnable> callbacks = new ArrayList<>();
        for (Activity activity : activities.values()) {
            if (activity.token.ownerKind() == OwnerKind.CORE && activity.onPreempt != null) {
                callbacks.add(activity.onPreempt);
            }
        }
        activities.clear();
        return callbacks;
    }

    public synchronized int size() {
        purgeExpiredLocked(null);
        return activities.size();
    }

    private void purgeExpiredLocked(@Nullable List<Runnable> callbacks) {
        long now = nanoClock.getAsLong();
        var iterator = activities.entrySet().iterator();
        while (iterator.hasNext()) {
            Activity activity = iterator.next().getValue();
            if (now < activity.deadlineNanos) continue;
            // Core activities own cleanup hooks. Opportunistic read/reserve calls may reap expired
            // addon leases, but core ownership stays until tickMaintenance can remove it and run the
            // matching timeout hook exactly once outside the registry lock.
            if (callbacks == null && activity.token.ownerKind() == OwnerKind.CORE) continue;
            iterator.remove();
            if (callbacks != null && activity.token.ownerKind() == OwnerKind.CORE && activity.onTimeout != null) {
                callbacks.add(activity.onTimeout);
            }
        }
    }

    private static boolean matches(@Nullable Activity activity, UUID citizenId, UUID tokenId, OwnerKind kind) {
        return activity != null
                && activity.token.citizenId().equals(citizenId)
                && activity.token.ownershipId().equals(tokenId)
                && activity.token.ownerKind() == kind;
    }

    private long deadline(long timeoutNanos) {
        long now = nanoClock.getAsLong();
        return timeoutNanos >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private static void validateTimeout(long timeoutNanos) {
        if (timeoutNanos <= 0) throw new IllegalArgumentException("timeoutNanos must be positive");
    }

    private static final class Activity {
        final Token token;
        long deadlineNanos;
        @Nullable final Runnable onTimeout;
        @Nullable final Runnable onPreempt;

        Activity(Token token, long deadlineNanos, @Nullable Runnable onTimeout, @Nullable Runnable onPreempt) {
            this.token = token;
            this.deadlineNanos = deadlineNanos;
            this.onTimeout = onTimeout;
            this.onPreempt = onPreempt;
        }
    }
}
