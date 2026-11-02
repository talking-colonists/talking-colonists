package me.sshcrack.mc_talking.internal.session;

import me.sshcrack.mc_talking.util.BackgroundSlotType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Token-owned capacity and client lifecycle for invisible background Gemini sessions. */
public final class BackgroundSessionRegistry<C> {
    public record Token(UUID citizenId, UUID ownershipId) {
    }

    public record Expired(UUID citizenId, BackgroundSlotType type, boolean deadlineExceeded) {
    }

    private final IntSupplier capacitySupplier;
    private final LongSupplier nanoClock;
    private final ToLongFunction<BackgroundSlotType> timeoutNanos;
    private final Consumer<C> closeClient;
    private final Predicate<C> clientClosed;
    private final LinkedHashMap<UUID, Entry<C>> slots = new LinkedHashMap<>();

    public BackgroundSessionRegistry(
            IntSupplier capacitySupplier,
            LongSupplier nanoClock,
            ToLongFunction<BackgroundSlotType> timeoutNanos,
            Consumer<C> closeClient,
            Predicate<C> clientClosed
    ) {
        this.capacitySupplier = Objects.requireNonNull(capacitySupplier, "capacitySupplier");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.timeoutNanos = Objects.requireNonNull(timeoutNanos, "timeoutNanos");
        this.closeClient = Objects.requireNonNull(closeClient, "closeClient");
        this.clientClosed = Objects.requireNonNull(clientClosed, "clientClosed");
    }

    /** Compaction may evict the oldest pregeneration slot; pregeneration never evicts. */
    @Nullable
    public Token reserve(UUID citizenId, BackgroundSlotType type) {
        Objects.requireNonNull(citizenId, "citizenId");
        Objects.requireNonNull(type, "type");
        purge();
        C evictedClient = null;
        synchronized (this) {
            if (slots.containsKey(citizenId)) return null;
            int capacity = Math.max(0, capacitySupplier.getAsInt());
            if (slots.size() >= capacity && type == BackgroundSlotType.COMPACTION) {
                Entry<C> victim = null;
                for (Entry<C> candidate : slots.values()) {
                    if (candidate.type == BackgroundSlotType.PREGEN) {
                        victim = candidate;
                        break;
                    }
                }
                if (victim != null) {
                    slots.remove(victim.token.citizenId(), victim);
                    evictedClient = victim.client;
                }
            }
            if (slots.size() >= capacity) return null;
            Token token = new Token(citizenId, UUID.randomUUID());
            long timeout = timeoutNanos.applyAsLong(type);
            long now = nanoClock.getAsLong();
            long deadline = timeout >= Long.MAX_VALUE - now ? Long.MAX_VALUE : now + timeout;
            slots.put(citizenId, new Entry<>(token, type, deadline));
            closeSafely(evictedClient);
            return token;
        }
    }

    public boolean attach(Token token, C client) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(client, "client");
        boolean accepted;
        synchronized (this) {
            Entry<C> entry = ownedLocked(token);
            accepted = entry != null && entry.client == null;
            if (accepted) {
                // `accepted` is exactly the case where `entry` exists without a client.
                Objects.requireNonNull(entry).client = client;
            }
        }
        if (!accepted) closeSafely(client);
        return accepted;
    }

    public synchronized boolean isActive(Token token) {
        return ownedLocked(token) != null;
    }

    public boolean release(Token token) {
        C client;
        synchronized (this) {
            Entry<C> entry = ownedLocked(token);
            if (entry == null) return false;
            slots.remove(token.citizenId(), entry);
            client = entry.client;
        }
        closeSafely(client);
        return true;
    }

    public boolean cancel(UUID citizenId) {
        C client;
        synchronized (this) {
            Entry<C> entry = slots.remove(citizenId);
            if (entry == null) return false;
            client = entry.client;
        }
        closeSafely(client);
        return true;
    }

    public synchronized boolean hasCapacity(int slotsNeeded) {
        if (slotsNeeded < 1) throw new IllegalArgumentException("slotsNeeded must be positive");
        return Math.max(0, capacitySupplier.getAsInt()) - slots.size() >= slotsNeeded;
    }

    public synchronized int size() {
        return slots.size();
    }

    public List<Expired> purge() {
        List<C> close = new ArrayList<>();
        List<Expired> result = new ArrayList<>();
        long now = nanoClock.getAsLong();
        synchronized (this) {
            var iterator = slots.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<C> entry = iterator.next().getValue();
                boolean closed = entry.client != null && clientClosed.test(entry.client);
                boolean expired = now >= entry.deadlineNanos;
                if (!closed && !expired) continue;
                iterator.remove();
                if (expired && !closed && entry.client != null) close.add(entry.client);
                result.add(new Expired(entry.token.citizenId(), entry.type, expired && !closed));
            }
        }
        close.forEach(this::closeSafely);
        return result;
    }

    public int shutdown() {
        List<C> close = new ArrayList<>();
        int size;
        synchronized (this) {
            size = slots.size();
            for (Entry<C> entry : slots.values()) {
                if (entry.client != null) close.add(entry.client);
            }
            slots.clear();
        }
        close.forEach(this::closeSafely);
        return size;
    }

    @Nullable
    private Entry<C> ownedLocked(Token token) {
        Entry<C> entry = slots.get(token.citizenId());
        return entry != null && entry.token.equals(token) ? entry : null;
    }

    private void closeSafely(@Nullable C client) {
        if (client == null || clientClosed.test(client)) return;
        try {
            closeClient.accept(client);
        } catch (RuntimeException ignored) {
            // Capacity ownership is already gone; a broken close hook cannot resurrect it.
        }
    }

    private static final class Entry<C> {
        final Token token;
        final BackgroundSlotType type;
        final long deadlineNanos;
        @Nullable C client;

        Entry(Token token, BackgroundSlotType type, long deadlineNanos) {
            this.token = token;
            this.type = type;
            this.deadlineNanos = deadlineNanos;
        }
    }
}
