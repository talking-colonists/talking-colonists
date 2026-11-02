package me.sshcrack.mc_talking.internal.api;

import me.sshcrack.mc_talking.api.conversation.AmbientLineResult;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionHandle;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionPolicy;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationSession;
import me.sshcrack.mc_talking.api.conversation.ControlledSessionRejectedException;
import me.sshcrack.mc_talking.api.conversation.ControlledTurnResult;
import me.sshcrack.mc_talking.api.conversation.ConversationTranscriptEntry;
import me.sshcrack.mc_talking.api.prompt.PromptSessionContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Deterministic controlled-turn state machine; Minecraft/provider access is supplied by Hooks. */
final class ControlledConversationRuntime<P, A> {
    static final int MAX_TRANSCRIPT_CHARS = 8_000;

    interface Hooks<P, A> {
        void execute(@NotNull Runnable task);
        @NotNull UUID id(@NotNull P participant);
        @NotNull String name(@NotNull P participant);
        long gameTime(@NotNull P participant);
        @NotNull Availability availability(@NotNull P participant, @Nullable A audioAnchor);
        boolean hasCapacity();
        @NotNull StartResult start(@NotNull P participant, @NotNull String prompt,
                                   @NotNull PromptSessionContext promptContext,
                                   @Nullable UUID authenticatedPlayerId,
                                   @Nullable A audioAnchor,
                                   int maxOutputTokens,
                                   @NotNull Consumer<AmbientLineResult> audibleCompletion);
        void cancel(@NotNull P participant, @NotNull UUID sessionId, @NotNull UUID turnId);
        boolean playerOwnsConversation(@NotNull P participant);

        /** Monotonic clock used only for autonomous scheduling limits. */
        default long monotonicNanos() { return System.nanoTime(); }

        /** The participant's colony, or {@code null} when unknown. */
        default @Nullable ColonyRef colony(@NotNull P participant) { return null; }

        /** The participant's dimension; all known dimensions of a session must match. */
        default @Nullable Object dimension(@NotNull P participant) { return null; }

        /** How the speaker's colony regards {@code other}, for example {@code "ALLIES"}; null if unknown. */
        default @Nullable String relation(@NotNull P speaker, @NotNull ColonyRef other) { return null; }
    }

    /** A colony as named in cross-colony turn prompts. Colony IDs are unique within one dimension. */
    record ColonyRef(int id, @NotNull String name) { }

    record Availability(boolean available, @Nullable ControlledTurnResult.FailureReason reason,
                        @NotNull String detail) {
        static @NotNull Availability ok() { return new Availability(true, null, "available"); }
        static @NotNull Availability rejected(@NotNull ControlledTurnResult.FailureReason reason,
                                              @NotNull String detail) {
            return new Availability(false, reason, detail);
        }
    }

    enum StartResult { STARTED, PROVIDER_UNAVAILABLE, SPEAKER_UNAVAILABLE, CAPACITY_EXHAUSTED, FAILED }

    private final UUID sessionId = UUID.randomUUID();
    private final Hooks<P, A> hooks;
    private final ControlledConversationOptions options;
    private final List<P> participants;
    private final Map<UUID, P> byId;
    private final Object transitionLock = new Object();
    private final AtomicReference<ControlledConversationSession.State> state =
            new AtomicReference<>(ControlledConversationSession.State.OPEN);
    private final AtomicReference<ActiveTurn<P>> activeTurn = new AtomicReference<>();
    private final AtomicReference<AutomaticDiscussion> automaticDiscussion = new AtomicReference<>();
    private final ArrayDeque<ConversationTranscriptEntry> transcript = new ArrayDeque<>();
    private int transcriptChars;
    private volatile String agenda;
    @Nullable private UUID authenticatedPlayerId;

    ControlledConversationRuntime(@NotNull List<P> participants, @NotNull String agenda,
                                  @NotNull ControlledConversationOptions options,
                                  @NotNull Hooks<P, A> hooks) {
        if (participants.isEmpty()) throw new IllegalArgumentException("Controlled session requires participants");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.options = Objects.requireNonNull(options, "options");
        this.agenda = Objects.requireNonNull(agenda, "agenda");
        LinkedHashMap<UUID, P> unique = new LinkedHashMap<>();
        for (P participant : participants) {
            if (participant == null) throw new IllegalArgumentException("participant must not be null");
            UUID id = Objects.requireNonNull(hooks.id(participant), "participant id");
            if (unique.putIfAbsent(id, participant) != null) {
                throw new IllegalArgumentException("Duplicate participant " + id);
            }
        }
        this.byId = Map.copyOf(unique);
        this.participants = List.copyOf(unique.values());
        requireOneDimension();
    }

    private void requireOneDimension() {
        Object first = null;
        for (P participant : participants) {
            Object dimension = hooks.dimension(participant);
            if (dimension == null) continue;
            if (first == null) first = dimension;
            else if (!first.equals(dimension)) {
                throw new ControlledSessionRejectedException(ControlledSessionRejectedException.Reason.CROSS_DIMENSION,
                        "Controlled session attendees must be in the same dimension (" + first + " and " + dimension + ")");
            }
        }
    }

    /** Names the other colonies attending, for the speaker's turn prompt; empty for one-colony sessions. */
    @NotNull String crossColonyContext(@NotNull P speaker) {
        ColonyRef own = hooks.colony(speaker);
        if (own == null) return "";
        Map<Integer, ColonyRef> colonies = new LinkedHashMap<>();
        Map<Integer, List<String>> attendees = new LinkedHashMap<>();
        for (P participant : participants) {
            ColonyRef colony = hooks.colony(participant);
            if (colony == null || colony.id() == own.id()) continue;
            colonies.putIfAbsent(colony.id(), colony);
            attendees.computeIfAbsent(colony.id(), ignored -> new ArrayList<>()).add(hooks.name(participant));
        }
        if (colonies.isEmpty()) return "";
        StringBuilder context = new StringBuilder("Attendees from other colonies:\n");
        for (ColonyRef colony : colonies.values()) {
            String relation = hooks.relation(speaker, colony);
            context.append("- ").append(colony.name())
                    .append(" (your colony's relation: ").append(relation == null ? "unknown" : relation).append("): ")
                    .append(String.join(", ", attendees.get(colony.id()))).append('\n');
        }
        context.append("You belong to ").append(own.name())
                .append(" and speak for it. Your tools and permissions only apply to your own colony.\n");
        return context.toString();
    }

    @NotNull UUID sessionId() { return sessionId; }
    @NotNull List<P> participants() { return participants; }
    @NotNull ControlledConversationSession.State state() {
        return java.util.Objects.requireNonNull(state.get(), "state");
    }

    void setAgenda(@NotNull String agenda) {
        synchronized (transitionLock) {
            if (state.get() == ControlledConversationSession.State.ENDED) throw new IllegalStateException("session ended");
            this.agenda = Objects.requireNonNull(agenda, "agenda");
        }
    }

    void addTranscript(@NotNull ConversationTranscriptEntry entry) {
        synchronized (transitionLock) {
            if (state.get() == ControlledConversationSession.State.ENDED) throw new IllegalStateException("session ended");
            appendTranscript(entry);
        }
    }

    void bindAuthenticatedPlayer(@NotNull UUID playerId) {
        synchronized (transitionLock) {
            if (state.get() == ControlledConversationSession.State.ENDED) throw new IllegalStateException("session ended");
            authenticatedPlayerId = Objects.requireNonNull(playerId, "playerId");
        }
    }

    @NotNull CompletableFuture<ControlledTurnResult> requestTurn(@NotNull P speaker,
                                                                  @NotNull String topicOrInstruction,
                                                                  @Nullable A audioAnchor) {
        AutomaticDiscussion automatic = automaticDiscussion.get();
        if (automatic != null && automatic.ownsFloor()) {
            UUID turnId = UUID.randomUUID();
            return completeOnExecutor(ControlledTurnResult.rejected(
                    sessionId, turnId, ControlledTurnResult.FailureReason.TURN_ALREADY_ACTIVE,
                    "automatic discussion currently owns the floor"));
        }
        return requestTurnInternal(speaker, topicOrInstruction, audioAnchor, false, 0);
    }

    @NotNull AutonomousDiscussionHandle delegateAutonomousDiscussion(@NotNull AutonomousDiscussionPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        if (participants.size() < 2) {
            throw new IllegalStateException("Autonomous discussion requires at least two participants");
        }
        AutomaticDiscussion discussion;
        synchronized (transitionLock) {
            if (state.get() == ControlledConversationSession.State.ENDED) {
                throw new IllegalStateException("session ended");
            }
            if (state.get() != ControlledConversationSession.State.OPEN || activeTurn.get() != null) {
                throw new IllegalStateException("cannot delegate automatic floor while a turn is active");
            }
            if (automaticDiscussion.get() != null) {
                throw new IllegalStateException("automatic floor is already delegated");
            }
            discussion = new AutomaticDiscussion(policy);
            automaticDiscussion.set(discussion);
        }
        hooks.execute(discussion::start);
        return discussion;
    }

    private @NotNull CompletableFuture<ControlledTurnResult> requestTurnInternal(
            @NotNull P speaker,
            @NotNull String topicOrInstruction,
            @Nullable A audioAnchor,
            boolean automatic,
            int responseTokenLimit
    ) {
        Objects.requireNonNull(speaker, "speaker");
        Objects.requireNonNull(topicOrInstruction, "topicOrInstruction");
        UUID turnId = UUID.randomUUID();
        if (!byId.containsKey(hooks.id(speaker))) {
            return completeOnExecutor(ControlledTurnResult.rejected(
                    sessionId, turnId, ControlledTurnResult.FailureReason.SPEAKER_NOT_PARTICIPANT,
                    "speaker is not a session participant"));
        }
        CompletableFuture<ControlledTurnResult> future;
        ActiveTurn<P> turn;
        String turnAgenda;
        synchronized (transitionLock) {
            if (state.get() == ControlledConversationSession.State.ENDED) {
                return completeOnExecutor(ControlledTurnResult.sessionEnded(sessionId, turnId, "session has ended"));
            }
            if (state.get() != ControlledConversationSession.State.OPEN || activeTurn.get() != null) {
                return completeOnExecutor(ControlledTurnResult.rejected(
                        sessionId, turnId, ControlledTurnResult.FailureReason.TURN_ALREADY_ACTIVE,
                        "another speaker already has the floor"));
            }
            future = new CompletableFuture<>();
            turn = new ActiveTurn<>(turnId, speaker, future, automatic, responseTokenLimit, authenticatedPlayerId);
            activeTurn.set(turn);
            state.set(ControlledConversationSession.State.TURN_ACTIVE);
            turnAgenda = agenda;
        }
        hooks.execute(() -> startTurn(turn, topicOrInstruction, turnAgenda, audioAnchor));
        return future;
    }

    boolean interruptTurn() {
        ActiveTurn<P> turn;
        synchronized (transitionLock) {
            turn = activeTurn.get();
            if (turn == null || state.get() != ControlledConversationSession.State.TURN_ACTIVE) return false;
            activeTurn.set(null);
            state.set(ControlledConversationSession.State.OPEN);
        }
        hooks.execute(() -> {
            ControlledTurnResult result = ControlledTurnResult.interrupted(sessionId, turn.turnId());
            turn.future().complete(result);
            hooks.cancel(turn.speaker(), sessionId, turn.turnId());
            notifyTurnTerminal(turn, result);
        });
        return true;
    }

    void end(@NotNull ControlledConversationSession.EndReason reason) {
        Objects.requireNonNull(reason, "reason");
        AutomaticDiscussion automatic;
        ActiveTurn<P> turn;
        synchronized (transitionLock) {
            if (state.get() == ControlledConversationSession.State.ENDED) return;
            state.set(ControlledConversationSession.State.ENDED);
            automatic = automaticDiscussion.getAndSet(null);
            turn = activeTurn.getAndSet(null);
        }
        if (automatic != null || turn != null) {
            hooks.execute(() -> {
                if (automatic != null) automatic.terminateForSessionEnd();
                if (turn != null) {
                    ControlledTurnResult result = ControlledTurnResult.sessionEnded(
                            sessionId, turn.turnId(), "session ended: "
                                    + reason.name().toLowerCase(java.util.Locale.ROOT));
                    turn.future().complete(result);
                    hooks.cancel(turn.speaker(), sessionId, turn.turnId());
                }
            });
        }
    }

    @NotNull List<ConversationTranscriptEntry> transcript() {
        synchronized (transcript) { return List.copyOf(transcript); }
    }

    @NotNull String sharedTranscript() {
        synchronized (transcript) {
            return transcript.stream().map(entry -> entry.speakerName() + ": " + entry.text())
                    .collect(Collectors.joining("\n"));
        }
    }

    private void startTurn(ActiveTurn<P> turn, String topicOrInstruction, String turnAgenda,
                           @Nullable A audioAnchor) {
        if (!isCurrent(turn)) return;
        String prompt = buildTurnPrompt(topicOrInstruction, turnAgenda, turn.responseTokenLimit())
                + crossColonyContext(turn.speaker());
        PromptSessionContext promptContext = PromptSessionContext.controlled(
                sessionId, turn.turnId(), turnAgenda, options.allowAllAddonTools(), options.allowedAddonTools());
        Availability availability = hooks.availability(turn.speaker(), audioAnchor);
        if (!availability.available()) {
            finish(turn, ControlledTurnResult.rejected(sessionId, turn.turnId(),
                    Objects.requireNonNull(availability.reason()), availability.detail()));
            return;
        }
        if (!hooks.hasCapacity()) {
            finish(turn, ControlledTurnResult.rejected(sessionId, turn.turnId(),
                    ControlledTurnResult.FailureReason.CAPACITY_EXHAUSTED, "provider capacity is exhausted"));
            return;
        }
        StartResult started = hooks.start(turn.speaker(), prompt, promptContext, turn.authenticatedPlayerId(), audioAnchor,
                turn.responseTokenLimit(), result -> hooks.execute(() -> completeAudibly(turn, result)));
        if (started == StartResult.STARTED || !isCurrent(turn)) return;
        ControlledTurnResult.FailureReason reason = switch (started) {
            case PROVIDER_UNAVAILABLE -> ControlledTurnResult.FailureReason.PROVIDER_UNAVAILABLE;
            case SPEAKER_UNAVAILABLE -> ControlledTurnResult.FailureReason.SPEAKER_UNAVAILABLE;
            case CAPACITY_EXHAUSTED -> ControlledTurnResult.FailureReason.CAPACITY_EXHAUSTED;
            case FAILED -> ControlledTurnResult.FailureReason.INTERNAL_FAILURE;
            case STARTED -> throw new IllegalStateException("unreachable");
        };
        finish(turn, ControlledTurnResult.rejected(sessionId, turn.turnId(), reason,
                "controlled turn could not start"));
    }

    private void completeAudibly(ActiveTurn<P> turn, AmbientLineResult result) {
        if (!isCurrent(turn)) return;
        P speaker = turn.speaker();
        if (result.status() == AmbientLineResult.Status.COMPLETED) {
            String boundedTranscript = result.transcript() == null ? "" : result.transcript().trim();
            if (!boundedTranscript.isBlank()) {
                appendTranscript(new ConversationTranscriptEntry(
                        ConversationTranscriptEntry.SpeakerKind.CITIZEN,
                        hooks.id(speaker), hooks.name(speaker), boundedTranscript, hooks.gameTime(speaker)));
            }
            finish(turn, ControlledTurnResult.completed(sessionId, turn.turnId(), boundedTranscript));
            return;
        }
        if (result.status() == AmbientLineResult.Status.CANCELLED) {
            finish(turn, new ControlledTurnResult(sessionId, turn.turnId(), ControlledTurnResult.Status.INTERRUPTED,
                    null, "", result.detail()));
            return;
        }
        Availability availability = hooks.availability(speaker, null);
        ControlledTurnResult terminal;
        if (!availability.available() && availability.reason() == ControlledTurnResult.FailureReason.SPEAKER_UNLOADED) {
            terminal = ControlledTurnResult.failed(sessionId, turn.turnId(),
                    ControlledTurnResult.FailureReason.SPEAKER_UNLOADED, result.detail());
        } else if (hooks.playerOwnsConversation(speaker)) {
            terminal = new ControlledTurnResult(sessionId, turn.turnId(), ControlledTurnResult.Status.INTERRUPTED,
                    null, "", "player conversation preempted controlled turn");
        } else {
            terminal = ControlledTurnResult.failed(sessionId, turn.turnId(),
                    ControlledTurnResult.FailureReason.PROVIDER_FAILED, result.detail());
        }
        finish(turn, terminal);
    }

    private boolean isCurrent(ActiveTurn<P> turn) {
        synchronized (transitionLock) {
            return state.get() == ControlledConversationSession.State.TURN_ACTIVE
                    && activeTurn.get() == turn && !turn.future().isDone();
        }
    }

    private void finish(ActiveTurn<P> turn, ControlledTurnResult result) {
        synchronized (transitionLock) {
            if (activeTurn.get() != turn) return;
            activeTurn.set(null);
            if (state.get() != ControlledConversationSession.State.ENDED) {
                state.set(ControlledConversationSession.State.OPEN);
            }
        }
        turn.future().complete(result);
        notifyTurnTerminal(turn, result);
    }

    private void notifyTurnTerminal(ActiveTurn<P> turn, ControlledTurnResult result) {
        AutomaticDiscussion automatic = automaticDiscussion.get();
        if (automatic == null) return;
        if (turn.automatic()) automatic.onAutomaticTurnTerminal(turn, result);
        else automatic.onManualFloorAvailable();
    }

    private CompletableFuture<ControlledTurnResult> completeOnExecutor(ControlledTurnResult result) {
        CompletableFuture<ControlledTurnResult> future = new CompletableFuture<>();
        hooks.execute(() -> future.complete(result));
        return future;
    }

    private String buildTurnPrompt(String topicOrInstruction, String turnAgenda, int responseTokenLimit) {
        String history = sharedTranscript();
        String boundedTopic = topicOrInstruction.length() > 2_000 ? topicOrInstruction.substring(0, 2_000) : topicOrInstruction;
        String responseLimit = responseTokenLimit > 0
                ? "Keep this response concise; the provider will enforce a maximum of "
                        + responseTokenLimit + " output tokens."
                : "";
        return """
                ## CONTROLLED ADDON CONVERSATION
                You have explicitly been given the floor. Speak exactly one natural turn, then stop and wait.
                Meeting/session agenda: %s
                Requested topic/instruction for your turn: %s
                Shared transcript so far:
                %s
                %s
                Do not invent statements for other attendees and do not decide who speaks next.
                """.formatted(turnAgenda, boundedTopic, history.isBlank() ? "(none yet)" : history, responseLimit);
    }

    private void appendTranscript(ConversationTranscriptEntry entry) {
        synchronized (transcript) {
            int overhead = entry.speakerName().length() + 2;
            int maxTextChars = Math.max(1, MAX_TRANSCRIPT_CHARS - overhead);
            ConversationTranscriptEntry bounded = entry.text().length() <= maxTextChars ? entry
                    : new ConversationTranscriptEntry(entry.speakerKind(), entry.speakerId(), entry.speakerName(),
                    entry.text().substring(0, maxTextChars), entry.gameTimeTicks());
            int entryChars = overhead + bounded.text().length() + (transcript.isEmpty() ? 0 : 1);
            while (!transcript.isEmpty() && transcriptChars + entryChars > MAX_TRANSCRIPT_CHARS) {
                ConversationTranscriptEntry removed = transcript.removeFirst();
                transcriptChars -= removed.speakerName().length() + 2 + removed.text().length();
                if (!transcript.isEmpty()) transcriptChars -= 1;
            }
            if (!transcript.isEmpty()) transcriptChars += 1;
            transcript.addLast(bounded);
            transcriptChars += overhead + bounded.text().length();
        }
    }

    private final class AutomaticDiscussion implements AutonomousDiscussionHandle {
        private final AutonomousDiscussionPolicy policy;
        private final CompletableFuture<CompletionReason> completion = new CompletableFuture<>();
        private final AtomicReference<State> automaticState = new AtomicReference<>(State.RUNNING);
        private volatile @Nullable PauseReason pauseReason;
        private long startedNanos;
        private volatile int completedTurns;
        private int nextParticipantIndex;
        private int unavailableAttempts;
        private @Nullable UUID lastCompletedSpeaker;

        private AutomaticDiscussion(AutonomousDiscussionPolicy policy) {
            this.policy = policy;
        }

        private void start() {
            if (state.get() == ControlledConversationSession.State.ENDED) {
                terminate(CompletionReason.STOPPED, State.STOPPED, false);
                return;
            }
            startedNanos = hooks.monotonicNanos();
            scheduleNext();
        }

        private boolean ownsFloor() {
            return automaticState.get() == State.RUNNING;
        }

        @Override
        public @NotNull State state() {
            return java.util.Objects.requireNonNull(automaticState.get(), "state");
        }

        @Override
        public int completedTurns() {
            return completedTurns;
        }

        @Override
        public @NotNull Optional<PauseReason> pauseReason() {
            return automaticState.get() == State.PAUSED ? Optional.ofNullable(pauseReason) : Optional.empty();
        }

        @Override
        public void pause() {
            hooks.execute(() -> pauseInternal(PauseReason.CALLER));
        }

        @Override
        public void resume() {
            hooks.execute(() -> {
                if (!automaticState.compareAndSet(State.PAUSED, State.RUNNING)) return;
                pauseReason = null;
                if (activeTurn.get() == null && state.get() == ControlledConversationSession.State.OPEN) {
                    scheduleNext();
                }
            });
        }

        @Override
        public void stop() {
            hooks.execute(() -> terminate(CompletionReason.STOPPED, State.STOPPED, true));
        }

        @Override
        public @NotNull CompletableFuture<CompletionReason> completion() {
            return completion;
        }

        private void scheduleNext() {
            if (automaticState.get() != State.RUNNING) return;
            if (state.get() == ControlledConversationSession.State.ENDED) {
                terminate(CompletionReason.STOPPED, State.STOPPED, false);
                return;
            }
            if (activeTurn.get() != null || state.get() != ControlledConversationSession.State.OPEN) return;
            if (completedTurns >= policy.maxTurns()) {
                terminate(CompletionReason.TURN_LIMIT, State.COMPLETED, false);
                return;
            }
            if (hooks.monotonicNanos() - startedNanos >= policy.maxDuration().toNanos()) {
                terminate(CompletionReason.DURATION_LIMIT, State.COMPLETED, false);
                return;
            }
            if (!hooks.hasCapacity()) {
                pauseInternal(PauseReason.CAPACITY_UNAVAILABLE);
                return;
            }

            P speaker = selectNextAvailableParticipant();
            if (speaker == null) {
                terminate(CompletionReason.NO_AVAILABLE_PARTICIPANTS, State.COMPLETED, false);
                return;
            }

            String instruction = "Continue the shared group discussion naturally from your own perspective. "
                    + "Respond to what has actually been said, stay on the agenda, and leave selection of the next "
                    + "speaker to the conversation controller.";
            requestTurnInternal(speaker, instruction, null, true, policy.maxResponseTokens());
        }

        @Nullable
        private P selectNextAvailableParticipant() {
            int checked = 0;
            int size = participants.size();
            while (checked < size) {
                int index = nextParticipantIndex % size;
                nextParticipantIndex = (index + 1) % size;
                checked++;
                P candidate = participants.get(index);
                UUID candidateId = hooks.id(candidate);
                if (candidateId.equals(lastCompletedSpeaker)) continue;
                Availability availability = hooks.availability(candidate, null);
                if (!availability.available()) continue;
                return candidate;
            }
            return null;
        }

        private void onAutomaticTurnTerminal(ActiveTurn<P> turn, ControlledTurnResult result) {
            if (automaticState.get() == State.STOPPED || automaticState.get() == State.COMPLETED) return;

            if (result.status() == ControlledTurnResult.Status.COMPLETED) {
                completedTurns++;
                unavailableAttempts = 0;
                lastCompletedSpeaker = hooks.id(turn.speaker());
                if (automaticState.get() == State.RUNNING) scheduleNext();
                return;
            }

            if (result.status() == ControlledTurnResult.Status.INTERRUPTED) {
                pauseInternal(hooks.playerOwnsConversation(turn.speaker())
                        ? PauseReason.PLAYER_INTERRUPTED
                        : PauseReason.CALLER);
                return;
            }
            if (result.status() == ControlledTurnResult.Status.SESSION_ENDED) {
                terminate(CompletionReason.STOPPED, State.STOPPED, false);
                return;
            }

            ControlledTurnResult.FailureReason reason = result.failureReason();
            if (reason == ControlledTurnResult.FailureReason.CAPACITY_EXHAUSTED) {
                pauseInternal(PauseReason.CAPACITY_UNAVAILABLE);
                return;
            }
            if (reason == ControlledTurnResult.FailureReason.SPEAKER_UNAVAILABLE
                    || reason == ControlledTurnResult.FailureReason.SPEAKER_UNLOADED) {
                unavailableAttempts++;
                if (unavailableAttempts >= participants.size()) {
                    terminate(CompletionReason.NO_AVAILABLE_PARTICIPANTS, State.COMPLETED, false);
                } else if (automaticState.get() == State.RUNNING) {
                    scheduleNext();
                }
                return;
            }
            terminate(CompletionReason.PROVIDER_FAILURE, State.COMPLETED, false);
        }

        private void onManualFloorAvailable() {
            if (automaticState.get() == State.RUNNING) scheduleNext();
        }

        private void pauseInternal(PauseReason reason) {
            if (automaticState.compareAndSet(State.RUNNING, State.PAUSED)) {
                pauseReason = reason;
            }
        }

        private void terminateForSessionEnd() {
            State previous = automaticState.getAndSet(State.STOPPED);
            if (previous == State.STOPPED || previous == State.COMPLETED) return;
            completion.complete(CompletionReason.STOPPED);
        }

        private void terminate(CompletionReason reason, State terminalState, boolean interruptAutomaticTurn) {
            State previous = automaticState.getAndSet(terminalState);
            if (previous == State.STOPPED || previous == State.COMPLETED) return;
            pauseReason = null;
            automaticDiscussion.compareAndSet(this, null);
            if (interruptAutomaticTurn) {
                ActiveTurn<P> turn = activeTurn.get();
                if (turn != null && turn.automatic()) interruptTurn();
            }
            completion.complete(reason);
        }
    }

    private record ActiveTurn<P>(@NotNull UUID turnId, @NotNull P speaker,
                                 @NotNull CompletableFuture<ControlledTurnResult> future,
                                 boolean automatic, int responseTokenLimit,
                                 @Nullable UUID authenticatedPlayerId) { }
}
