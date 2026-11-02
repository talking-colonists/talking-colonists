package me.sshcrack.mc_talking.internal.session;

import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Token-owned source of truth for foreground conversation capacity and client ownership.
 *
 * <p>The registry deliberately contains no Minecraft or Gemini types. A caller supplies the
 * entity/client objects and close hooks, which makes ownership transitions deterministic and easy
 * to exercise with fakes. Every mutation that can end a session validates its opaque token first;
 * a delayed callback from an old session can therefore never release a replacement session.</p>
 */
public final class ForegroundSessionRegistry<E, C> {
    public enum Priority { AMBIENT, PLAYER }

    public enum State {
        RESERVED,
        STARTING,
        ACTIVE,
        RECOVERING,
        TERMINATED
    }

    public enum TerminalReason {
        COMPLETED,
        CANCELLED,
        EVICTED,
        REPLACED,
        STARTUP_FAILED,
        PROVIDER_FAILURE,
        RECOVERY_EXHAUSTED,
        ENTITY_UNAVAILABLE,
        PLAYER_DISCONNECTED,
        SERVER_SHUTDOWN
    }

    public enum RejectionReason {
        BUSY,
        CAPACITY_EXHAUSTED,
        PLAYER_ALREADY_ACTIVE
    }

    public record Token(UUID citizenId, UUID ownershipId) {
        public Token {
            Objects.requireNonNull(citizenId, "citizenId");
            Objects.requireNonNull(ownershipId, "ownershipId");
        }
    }

    public record Snapshot<E>(
            Token token,
            E entity,
            ConversationKind kind,
            Priority priority,
            @Nullable UUID playerId,
            @Nullable UUID sessionId,
            @Nullable UUID turnId,
            State state,
            long reservedAtNanos,
            int recoveryAttempts,
            String diagnostic
    ) {
    }

    public record TerminalDiagnostic(
            Token token,
            ConversationKind kind,
            Priority priority,
            @Nullable UUID playerId,
            TerminalReason reason,
            String detail,
            int recoveryAttempts,
            long terminalAtNanos
    ) {
    }

    public record Reservation<E>(@Nullable Token token, @Nullable Snapshot<E> snapshot,
                                 @Nullable RejectionReason rejectionReason) {
        public boolean granted() {
            return token != null;
        }

        public static <E> Reservation<E> granted(Token token, Snapshot<E> snapshot) {
            return new Reservation<>(token, snapshot, null);
        }

        public static <E> Reservation<E> rejected(RejectionReason reason) {
            return new Reservation<>(null, null, Objects.requireNonNull(reason, "reason"));
        }
    }

    public record Transition<E>(Snapshot<E> before, Snapshot<E> after) {
    }

    public record EndedSession<E, C>(Snapshot<E> snapshot, State previousState, @Nullable C client,
                                     TerminalReason reason, String detail) {
    }

    private static final int TERMINAL_HISTORY_LIMIT = 128;

    private final IntSupplier capacitySupplier;
    private final LongSupplier nanoClock;
    private final Consumer<C> closeClient;
    private final Predicate<C> clientClosed;
    private final Consumer<EndedSession<E, C>> terminalListener;

    private final LinkedHashMap<UUID, Entry<E, C>> sessions = new LinkedHashMap<>();
    private final Map<UUID, UUID> playerToCitizen = new LinkedHashMap<>();
    private final ArrayDeque<TerminalDiagnostic> terminalHistory = new ArrayDeque<>();

    public ForegroundSessionRegistry(
            IntSupplier capacitySupplier,
            LongSupplier nanoClock,
            Consumer<C> closeClient,
            Predicate<C> clientClosed,
            Consumer<EndedSession<E, C>> terminalListener
    ) {
        this.capacitySupplier = Objects.requireNonNull(capacitySupplier, "capacitySupplier");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.closeClient = Objects.requireNonNull(closeClient, "closeClient");
        this.clientClosed = Objects.requireNonNull(clientClosed, "clientClosed");
        this.terminalListener = Objects.requireNonNull(terminalListener, "terminalListener");
    }

    /**
     * Atomically reserves one foreground slot. Player reservations may evict the oldest ambient
     * reservation; ambient reservations never evict existing work.
     */
    public Reservation<E> reserve(
            UUID citizenId,
            E entity,
            ConversationKind kind,
            Priority priority,
            @Nullable UUID playerId
    ) {
        return reserve(citizenId, entity, kind, priority, playerId, null, null);
    }

    public Reservation<E> reserve(
            UUID citizenId,
            E entity,
            ConversationKind kind,
            Priority priority,
            @Nullable UUID playerId,
            @Nullable UUID sessionId,
            @Nullable UUID turnId
    ) {
        Objects.requireNonNull(citizenId, "citizenId");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(priority, "priority");
        if ((priority == Priority.PLAYER) != (playerId != null)) {
            throw new IllegalArgumentException("Player priority requires a playerId and ambient priority forbids one");
        }
        if ((sessionId == null) != (turnId == null)) {
            throw new IllegalArgumentException("sessionId and turnId must be supplied together");
        }
        if (kind == ConversationKind.CONTROLLED && sessionId == null) {
            throw new IllegalArgumentException("controlled foreground sessions require sessionId and turnId");
        }

        EndedSession<E, C> evicted = null;
        Reservation<E> result;
        synchronized (this) {
            if (sessions.containsKey(citizenId)) {
                return Reservation.rejected(RejectionReason.BUSY);
            }
            if (playerId != null) {
                UUID existingCitizen = playerToCitizen.get(playerId);
                if (existingCitizen != null && !existingCitizen.equals(citizenId)) {
                    return Reservation.rejected(RejectionReason.PLAYER_ALREADY_ACTIVE);
                }
            }

            int capacity = Math.max(0, capacitySupplier.getAsInt());
            if (sessions.size() >= capacity) {
                if (priority == Priority.AMBIENT) {
                    return Reservation.rejected(RejectionReason.CAPACITY_EXHAUSTED);
                }
                Entry<E, C> victim = oldestAmbientLocked();
                if (victim == null) {
                    return Reservation.rejected(RejectionReason.CAPACITY_EXHAUSTED);
                }
                evicted = detachLocked(victim, TerminalReason.EVICTED, "preempted by player conversation");
            }

            Token token = new Token(citizenId, UUID.randomUUID());
            Entry<E, C> entry = new Entry<>(token, entity, kind, priority, playerId, sessionId, turnId, nanoClock.getAsLong());
            sessions.put(citizenId, entry);
            if (playerId != null) playerToCitizen.put(playerId, citizenId);
            result = Reservation.granted(token, snapshotLocked(entry));
        }
        finishDetached(evicted);
        return result;
    }

    /** Reuses an existing ambient session for a player without changing its ownership token. */
    public Optional<Transition<E>> promoteToPlayer(Token token, UUID playerId, ConversationKind newKind) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(newKind, "newKind");
        synchronized (this) {
            Entry<E, C> entry = ownedLocked(token);
            if (entry == null || entry.priority == Priority.PLAYER) return Optional.empty();
            UUID existingCitizen = playerToCitizen.get(playerId);
            if (existingCitizen != null && !existingCitizen.equals(token.citizenId())) return Optional.empty();
            Snapshot<E> before = snapshotLocked(entry);
            entry.priority = Priority.PLAYER;
            entry.playerId = playerId;
            entry.kind = newKind;
            playerToCitizen.put(playerId, token.citizenId());
            return Optional.of(new Transition<>(before, snapshotLocked(entry)));
        }
    }

    /** Attaches exactly one client to this reservation. A client attached after ownership changed is closed. */
    public boolean attachClient(Token token, C client) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(client, "client");
        C rejected = null;
        synchronized (this) {
            Entry<E, C> entry = ownedLocked(token);
            if (entry == null || entry.client != null) {
                rejected = client;
            } else {
                entry.client = client;
                entry.state = State.STARTING;
                return true;
            }
        }
        closeSafely(rejected);
        return false;
    }

    public boolean markActive(Token token, String diagnostic) {
        return updateState(token, State.ACTIVE, 0, diagnostic);
    }

    public boolean markRecovering(Token token, int attempts, String diagnostic) {
        return updateState(token, State.RECOVERING, attempts, diagnostic);
    }

    private synchronized boolean updateState(Token token, State state, int attempts, String diagnostic) {
        Entry<E, C> entry = ownedLocked(token);
        if (entry == null) return false;
        entry.state = state;
        entry.recoveryAttempts = Math.max(entry.recoveryAttempts, Math.max(0, attempts));
        entry.diagnostic = diagnostic == null ? "" : diagnostic;
        return true;
    }

    public boolean end(Token token, TerminalReason reason, String detail) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(reason, "reason");
        EndedSession<E, C> ended;
        synchronized (this) {
            Entry<E, C> entry = ownedLocked(token);
            if (entry == null) return false;
            ended = detachLocked(entry, reason, detail);
        }
        finishDetached(ended);
        return true;
    }

    public boolean endCitizen(UUID citizenId, TerminalReason reason, String detail) {
        Token token;
        synchronized (this) {
            Entry<E, C> entry = sessions.get(citizenId);
            if (entry == null) return false;
            token = entry.token;
        }
        return end(token, reason, detail);
    }

    public boolean endPlayer(UUID playerId, TerminalReason reason, String detail) {
        Token token;
        synchronized (this) {
            UUID citizenId = playerToCitizen.get(playerId);
            if (citizenId == null) return false;
            Entry<E, C> entry = sessions.get(citizenId);
            if (entry == null || !playerId.equals(entry.playerId)) return false;
            token = entry.token;
        }
        return end(token, reason, detail);
    }

    /** Ends startup reservations that never became active. */
    public int purgeStartupTimeouts(long timeoutNanos) {
        if (timeoutNanos <= 0) throw new IllegalArgumentException("timeoutNanos must be positive");
        List<Token> expired = new ArrayList<>();
        long now = nanoClock.getAsLong();
        synchronized (this) {
            for (Entry<E, C> entry : sessions.values()) {
                if (entry.state != State.RESERVED && entry.state != State.STARTING) continue;
                if (now - entry.reservedAtNanos >= timeoutNanos) expired.add(entry.token);
            }
        }
        int count = 0;
        for (Token token : expired) {
            if (end(token, TerminalReason.STARTUP_FAILED, "foreground startup deadline exceeded")) count++;
        }
        return count;
    }

    public int shutdown() {
        List<EndedSession<E, C>> ended = new ArrayList<>();
        synchronized (this) {
            for (Entry<E, C> entry : new ArrayList<>(sessions.values())) {
                ended.add(detachLocked(entry, TerminalReason.SERVER_SHUTDOWN, "server shutdown"));
            }
        }
        ended.forEach(this::finishDetached);
        return ended.size();
    }

    public synchronized boolean isBusy(UUID citizenId) {
        return sessions.containsKey(citizenId);
    }

    public synchronized boolean isCurrent(Token token) {
        return ownedLocked(token) != null;
    }

    public synchronized int usedSlots() {
        return sessions.size();
    }

    public synchronized boolean hasLowPriorityCapacity(int slotsNeeded) {
        if (slotsNeeded < 1) throw new IllegalArgumentException("slotsNeeded must be positive");
        return Math.max(0, capacitySupplier.getAsInt()) - sessions.size() >= slotsNeeded;
    }

    public synchronized @Nullable C client(UUID citizenId) {
        Entry<E, C> entry = sessions.get(citizenId);
        return entry == null ? null : entry.client;
    }

    public synchronized @Nullable Token token(UUID citizenId) {
        Entry<E, C> entry = sessions.get(citizenId);
        return entry == null ? null : entry.token;
    }

    public synchronized @Nullable UUID playerForCitizen(UUID citizenId) {
        Entry<E, C> entry = sessions.get(citizenId);
        return entry == null ? null : entry.playerId;
    }

    public synchronized @Nullable UUID citizenForPlayer(UUID playerId) {
        return playerToCitizen.get(playerId);
    }

    public synchronized @Nullable E entityForPlayer(UUID playerId) {
        UUID citizenId = playerToCitizen.get(playerId);
        Entry<E, C> entry = citizenId == null ? null : sessions.get(citizenId);
        return entry == null ? null : entry.entity;
    }

    public synchronized @Nullable ConversationKind kind(UUID citizenId) {
        Entry<E, C> entry = sessions.get(citizenId);
        return entry == null ? null : entry.kind;
    }

    public synchronized Optional<Snapshot<E>> snapshot(UUID citizenId) {
        Entry<E, C> entry = sessions.get(citizenId);
        return entry == null ? Optional.empty() : Optional.of(snapshotLocked(entry));
    }

    public synchronized List<Snapshot<E>> snapshots() {
        return sessions.values().stream().map(this::snapshotLocked).toList();
    }

    public synchronized Map<UUID, C> clientsSnapshot() {
        Map<UUID, C> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Entry<E, C>> entry : sessions.entrySet()) {
            if (entry.getValue().client != null) result.put(entry.getKey(), entry.getValue().client);
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized Map<UUID, UUID> citizenToPlayerSnapshot() {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, Entry<E, C>> entry : sessions.entrySet()) {
            if (entry.getValue().playerId != null) result.put(entry.getKey(), entry.getValue().playerId);
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized Map<UUID, UUID> playerToCitizenSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(playerToCitizen));
    }

    public synchronized List<TerminalDiagnostic> terminalHistory() {
        return List.copyOf(terminalHistory);
    }

    public synchronized Optional<TerminalDiagnostic> lastTerminal(UUID citizenId) {
        TerminalDiagnostic found = null;
        for (TerminalDiagnostic diagnostic : terminalHistory) {
            if (diagnostic.token().citizenId().equals(citizenId)) found = diagnostic;
        }
        return Optional.ofNullable(found);
    }

    @Nullable
    private Entry<E, C> oldestAmbientLocked() {
        for (Entry<E, C> entry : sessions.values()) {
            if (entry.priority == Priority.AMBIENT) return entry;
        }
        return null;
    }

    @Nullable
    private Entry<E, C> ownedLocked(Token token) {
        Entry<E, C> entry = sessions.get(token.citizenId());
        return entry != null && entry.token.equals(token) ? entry : null;
    }

    private EndedSession<E, C> detachLocked(Entry<E, C> entry, TerminalReason reason, String detail) {
        State previousState = entry.state;
        sessions.remove(entry.token.citizenId(), entry);
        if (entry.playerId != null) playerToCitizen.remove(entry.playerId, entry.token.citizenId());
        entry.state = State.TERMINATED;
        String safeDetail = detail == null ? reason.name().toLowerCase() : detail;
        entry.diagnostic = safeDetail;
        Snapshot<E> snapshot = snapshotLocked(entry);
        terminalHistory.addLast(new TerminalDiagnostic(
                entry.token,
                entry.kind,
                entry.priority,
                entry.playerId,
                reason,
                safeDetail,
                entry.recoveryAttempts,
                nanoClock.getAsLong()
        ));
        while (terminalHistory.size() > TERMINAL_HISTORY_LIMIT) terminalHistory.removeFirst();
        return new EndedSession<>(snapshot, previousState, entry.client, reason, safeDetail);
    }

    private Snapshot<E> snapshotLocked(Entry<E, C> entry) {
        return new Snapshot<>(
                entry.token,
                entry.entity,
                entry.kind,
                entry.priority,
                entry.playerId,
                entry.sessionId,
                entry.turnId,
                entry.state,
                entry.reservedAtNanos,
                entry.recoveryAttempts,
                entry.diagnostic
        );
    }

    private void finishDetached(@Nullable EndedSession<E, C> ended) {
        if (ended == null) return;
        closeSafely(ended.client());
        try {
            terminalListener.accept(ended);
        } catch (RuntimeException ignored) {
            // Ownership is already detached; lifecycle observation must not resurrect or leak it.
        }
    }

    private void closeSafely(@Nullable C client) {
        if (client == null || clientClosed.test(client)) return;
        try {
            closeClient.accept(client);
        } catch (RuntimeException ignored) {
            // Lifecycle ownership must still be released even when a client close hook misbehaves.
        }
    }

    private static final class Entry<E, C> {
        final Token token;
        final E entity;
        ConversationKind kind;
        Priority priority;
        @Nullable UUID playerId;
        @Nullable final UUID sessionId;
        @Nullable final UUID turnId;
        final long reservedAtNanos;
        State state = State.RESERVED;
        @Nullable C client;
        int recoveryAttempts;
        String diagnostic = "reserved";

        Entry(Token token, E entity, ConversationKind kind, Priority priority,
              @Nullable UUID playerId, @Nullable UUID sessionId, @Nullable UUID turnId, long reservedAtNanos) {
            this.token = token;
            this.entity = entity;
            this.kind = kind;
            this.priority = priority;
            this.playerId = playerId;
            this.sessionId = sessionId;
            this.turnId = turnId;
            this.reservedAtNanos = reservedAtNanos;
        }
    }
}
