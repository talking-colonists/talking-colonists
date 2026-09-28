package me.sshcrack.mc_talking.internal.api;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.AmbientSessions;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.api.TalkingColonistsApi;
import me.sshcrack.mc_talking.api.conversation.AmbientLineResult;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionHandle;
import me.sshcrack.mc_talking.api.conversation.AutonomousDiscussionPolicy;
import me.sshcrack.mc_talking.api.conversation.CitizenActivityReservation;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationHandle;
import me.sshcrack.mc_talking.api.conversation.CitizenSpeechPolicy;
import me.sshcrack.mc_talking.api.conversation.CitizenUrgencyModifier;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationSession;
import me.sshcrack.mc_talking.api.conversation.ControlledTurnResult;
import me.sshcrack.mc_talking.api.conversation.ConversationUtteranceEvent;
import me.sshcrack.mc_talking.api.conversation.ConversationUtteranceListener;
import me.sshcrack.mc_talking.api.conversation.PlayerConversationOptions;
import me.sshcrack.mc_talking.api.conversation.PlayerTextResult;
import me.sshcrack.mc_talking.api.conversation.ControlledConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ControlledAudioAnchor;
import me.sshcrack.mc_talking.api.conversation.ConversationTranscriptEntry;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.api.conversation.ConversationEligibility;
import me.sshcrack.mc_talking.api.conversation.ConversationLifecycleListener;
import me.sshcrack.mc_talking.api.conversation.ConversationStartResult;
import me.sshcrack.mc_talking.api.conversation.ProviderSessionStatus;
import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.api.memory.AddonMemoryWriteResult;
import me.sshcrack.mc_talking.api.memory.CitizenMemorySnapshot;
import me.sshcrack.mc_talking.api.memory.CitizenRelationshipDimension;
import me.sshcrack.mc_talking.api.pregen.PregenerationPromptModifier;
import me.sshcrack.mc_talking.api.prompt.CitizenPromptContributor;
import me.sshcrack.mc_talking.api.prompt.CitizenPromptProvider;
import me.sshcrack.mc_talking.api.prompt.PromptSessionContext;
import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import me.sshcrack.mc_talking.api.registration.AddonRegistration;
import me.sshcrack.mc_talking.api.registration.NamespacedAddonId;
import me.sshcrack.mc_talking.api.memory.MemoryProvenance;
import me.sshcrack.mc_talking.api.tool.AiTool;
import me.sshcrack.mc_talking.conversations.CitizenConversation;
import me.sshcrack.mc_talking.conversations.memory.MemorySnapshotFactory;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.internal.prompt.PromptRuntime;
import me.sshcrack.mc_talking.internal.compat.FakePlayerCompatibility;
import me.sshcrack.mc_talking.internal.tool.AiToolRuntime;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import me.sshcrack.mc_talking.internal.session.PlayerTextInput;
import me.sshcrack.mc_talking.manager.CitizenPromptViewFactory;
import me.sshcrack.mc_talking.manager.CitizenWsClient;
import me.sshcrack.mc_talking.manager.GeminiWsClient;
import me.sshcrack.mc_talking.manager.DefaultCitizenPromptProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import me.sshcrack.mc_talking.internal.session.SpeechFloor;

/** Full-mod implementation of the standalone addon API bridge. */
final class ConversationServiceBackend implements me.sshcrack.mc_talking.api.service.ConversationService {
    private static final int MAX_CONTROLLED_TRANSCRIPT_CHARS = 8_000;
    private static final PlayerTextInput PLAYER_TEXT =
            new PlayerTextInput(ConversationServiceBackend::directConversation, System::nanoTime);
    private static final Duration MAX_ADDON_ACTIVITY_LEASE = Duration.ofHours(1);
    private static final Map<MinecraftServer, java.util.Set<ControlledSession>> CONTROLLED_SESSIONS =
            new ConcurrentHashMap<>();

    ConversationServiceBackend() {
    }

    @Override
    public boolean isBusy(@NotNull AbstractEntityCitizen citizen) {
        return ConversationManager.isCitizenBusy(citizen);
    }

    @Override
    public @NotNull ConversationEligibility eligibility(
            @NotNull AbstractEntityCitizen citizen,
            @NotNull ConversationKind kind
    ) {
        return ConversationManager.conversationEligibility(citizen, kind);
    }

    @Override
    public @NotNull ConversationStartResult startPlayerConversation(
            @NotNull ServerPlayer player,
            @NotNull AbstractEntityCitizen citizen
    ) {
        return ConversationManager.startPlayerConversationDetailed(player, citizen);
    }

    @Override
    public @NotNull ConversationStartResult startPlayerConversation(
            @NotNull ServerPlayer player,
            @NotNull AbstractEntityCitizen citizen,
            @NotNull PlayerConversationOptions options
    ) {
        return ConversationManager.startPlayerConversationDetailed(player, citizen, options);
    }

    @Override
    public @NotNull CompletableFuture<AmbientLineResult> requestAmbientLine(
            @NotNull AbstractEntityCitizen citizen,
            @NotNull String promptDirective
    ) {
        if (!McTalkingConfig.hasGeminiApiKey()) {
            return CompletableFuture.completedFuture(AmbientLineResult.rejected(
                    AmbientLineResult.RejectionReason.PROVIDER_UNAVAILABLE,
                    "Gemini API key/provider is unavailable"
            ));
        }

        ConversationEligibility eligibility = ConversationManager.conversationEligibility(
                citizen, ConversationKind.ADDON_AMBIENT
        );
        if (!eligibility.eligible()) {
            return CompletableFuture.completedFuture(AmbientLineResult.rejected(
                    mapAmbientRejection(eligibility.status()),
                    eligibility.detail()
            ));
        }
        if (!ConversationManager.hasLowPriorityCapacity(1)) {
            return CompletableFuture.completedFuture(AmbientLineResult.rejected(
                    AmbientLineResult.RejectionReason.CAPACITY_EXHAUSTED,
                    "no low-priority foreground capacity is available"
            ));
        }

        CompletableFuture<AmbientLineResult> future = new CompletableFuture<>();
        boolean started = AmbientSessions.startAddonAmbientSession(citizen, promptDirective, future::complete);
        if (!started) {
            future.complete(AmbientLineResult.rejected(
                    AmbientLineResult.RejectionReason.CAPACITY_EXHAUSTED,
                    "conversation became unavailable before the ambient line could start"
            ));
        }
        return future;
    }

    @Override
    public @NotNull AddonRegistration registerLifecycleListener(
            @NotNull String id,
            int order,
            @NotNull ConversationLifecycleListener listener
    ) {
        return ConversationEventRuntime.register(id, order, listener);
    }

    @Override
    public @NotNull AddonRegistration registerUtteranceListener(
            @NotNull String id,
            int order,
            @NotNull ConversationUtteranceListener listener
    ) {
        return ConversationEventRuntime.registerUtterance(id, order, listener);
    }

    @Override
    public @NotNull PlayerTextResult sendPlayerText(@NotNull ServerPlayer player, @NotNull AbstractEntityCitizen citizen,
                                                    @NotNull String text) {
        java.util.Objects.requireNonNull(player, "player");
        java.util.Objects.requireNonNull(citizen, "citizen");
        return PLAYER_TEXT.sendText(player.getUUID(), player.getName().getString(), citizen.getUUID(), text);
    }

    @Override
    public @NotNull PlayerTextResult addContext(@NotNull ServerPlayer player, @NotNull AbstractEntityCitizen citizen,
                                                @NotNull String note) {
        java.util.Objects.requireNonNull(player, "player");
        java.util.Objects.requireNonNull(citizen, "citizen");
        return PLAYER_TEXT.addContext(player.getUUID(), player.getName().getString(), citizen.getUUID(), note);
    }

    /** Forgets the player's typed-text rate history when they log out. */
    public static void onPlayerLoggedOut(UUID playerId) {
        PLAYER_TEXT.forget(playerId);
    }

    /** The citizen's current direct player conversation, when its session can take input. */
    @Nullable
    private static PlayerTextInput.Conversation directConversation(UUID citizenId) {
        if (ConversationManager.getActiveConversationKind(citizenId) != ConversationKind.PLAYER) return null;
        UUID owner = ConversationManager.getPlayerForEntity(citizenId);
        GeminiWsClient client = ConversationManager.getClientForEntity(citizenId);
        if (owner == null || client == null || client.isLifecycleClosed()) return null;
        if (!(client instanceof CitizenWsClient citizenClient) || !citizenClient.isAssociatedWithPlayer(owner)) return null;
        return new PlayerTextInput.Conversation() {
            @Override
            public UUID playerId() {
                return owner;
            }

            @Override
            public void sendPlayerTurn(String text) {
                client.addPromptTextImmediate(text);
            }

            @Override
            public void sendContextNote(String note) {
                client.addContextNoteAfterTalkingComplete(note);
            }

            @Override
            public void recordTypedLine(String playerName, String text) {
                client.appendPlayerTranscriptLine(playerName, text);
                ConversationManager.emitClientUtterance(client, ConversationUtteranceEvent.Speaker.PLAYER, text,
                        ConversationUtteranceEvent.Source.TYPED);
            }
        };
    }

    @Override
    public @NotNull Optional<ConversationKind> activeKind(@NotNull AbstractEntityCitizen citizen) {
        return Optional.ofNullable(ConversationManager.getActiveConversationKind(citizen.getUUID()));
    }

    @Override
    public @NotNull Optional<UUID> activePlayerId(@NotNull AbstractEntityCitizen citizen) {
        return Optional.ofNullable(ConversationManager.getPlayerForEntity(citizen.getUUID()));
    }

    @Override
    public @NotNull Optional<ProviderSessionStatus> providerStatus(@NotNull AbstractEntityCitizen citizen) {
        var client = ConversationManager.getClientForEntity(citizen.getUUID());
        if (client == null) return Optional.empty();
        var diagnostic = client.getRecoveryDiagnostic();
        var state = switch (diagnostic.state()) {
            case NEW -> ProviderSessionStatus.State.NEW;
            case CONNECTING -> ProviderSessionStatus.State.CONNECTING;
            case SETTING_UP -> ProviderSessionStatus.State.SETTING_UP;
            case ACTIVE -> ProviderSessionStatus.State.ACTIVE;
            case RECOVERING -> ProviderSessionStatus.State.RECOVERING;
            case CLOSED -> ProviderSessionStatus.State.CLOSED;
            case TERMINAL_ERROR -> ProviderSessionStatus.State.TERMINAL_ERROR;
            case QUOTA_EXCEEDED -> ProviderSessionStatus.State.QUOTA_EXCEEDED;
        };
        return Optional.of(new ProviderSessionStatus(state,
                state == ProviderSessionStatus.State.ACTIVE && client.isSessionReadyForInput(),
                diagnostic.totalRecoveryAttempts()));
    }

    @Override
    public boolean isPlayerInConversation(@NotNull ServerPlayer player) {
        return ConversationManager.isPlayerInConversation(player.getUUID());
    }

    @Override
    public boolean hasAmbientCapacity(int slotsNeeded) {
        if (slotsNeeded < 1) throw new IllegalArgumentException("slotsNeeded must be positive");
        return ConversationManager.hasLowPriorityCapacity(slotsNeeded);
    }

    @Override
    public boolean hasPlayerNearby(@NotNull AbstractEntityCitizen citizen, double range) {
        if (!Double.isFinite(range) || range < 0.0) {
            throw new IllegalArgumentException("range must be finite and non-negative");
        }
        MinecraftServer server = citizen.level().getServer();
        return server != null && ConversationManager.hasPlayerNearby(citizen, server, range);
    }

    @Override
    public boolean requestGracefulEnd(@NotNull AbstractEntityCitizen citizen) {
        return ConversationManager.requestGracefulEnd(citizen);
    }

    @Override
    public @NotNull Optional<CitizenActivityReservation> reserveActivity(
            @NotNull AbstractEntityCitizen citizen,
            @NotNull String ownerId,
            @NotNull Duration timeout
    ) {
        java.util.Objects.requireNonNull(citizen, "citizen");
        NamespacedAddonId.require(ownerId, "ownerId");
        long timeoutNanos = validateActivityTimeout(timeout);
        UUID token = UUID.randomUUID();
        if (!ConversationManager.claimAddonActivity(citizen, token, timeoutNanos)) return Optional.empty();
        return Optional.of(new ActivityReservation(citizen.getUUID(), ownerId, token));
    }

    @Override
    public void resetAutomaticCooldown(@NotNull AbstractEntityCitizen citizen) {
        ConversationManager.forceRemoveCooldown(citizen);
    }

    @Override
    public @NotNull ControlledConversationSession createControlledSession(
            @NotNull MinecraftServer server,
            @NotNull List<AbstractEntityCitizen> participants,
            @NotNull String agenda,
            @NotNull ControlledConversationOptions options
    ) {
        ControlledSession session = new ControlledSession(server, participants, agenda, options);
        CONTROLLED_SESSIONS.computeIfAbsent(server, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        // Participants stay engaged between turns: no greetings or mumbling from a campfire circle.
        SpeechFloor.hold(session, ConversationKind.CONTROLLED, participants);
        return session;
    }

    /** Ends even idle controlled sessions before foreground provider shutdown starts. */
    public static void onServerStopping(@NotNull MinecraftServer server) {
        java.util.Set<ControlledSession> sessions = CONTROLLED_SESSIONS.remove(server);
        if (sessions == null) return;
        for (ControlledSession session : List.copyOf(sessions)) {
            session.end(ControlledConversationSession.EndReason.SERVER_SHUTDOWN);
        }
    }

    private static void unregisterControlledSession(MinecraftServer server, ControlledSession session) {
        CONTROLLED_SESSIONS.computeIfPresent(server, (ignored, sessions) -> {
            sessions.remove(session);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    @Override
    public @NotNull CitizenConversationHandle createPairConversation(
            @NotNull MinecraftServer server,
            @NotNull AbstractEntityCitizen first,
            @NotNull AbstractEntityCitizen second
    ) {
        if (first == second || first.getUUID().equals(second.getUUID())) {
            throw new IllegalArgumentException("A pair conversation requires two distinct citizens");
        }
        return new PairHandle(server, new CitizenConversation(server, List.of(first, second)));
    }

    private static long validateActivityTimeout(Duration timeout) {
        java.util.Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("activity lease timeout must be positive");
        }
        if (timeout.compareTo(MAX_ADDON_ACTIVITY_LEASE) > 0) {
            throw new IllegalArgumentException("activity lease timeout must not exceed " + MAX_ADDON_ACTIVITY_LEASE);
        }
        return timeout.toNanos();
    }

    private static AmbientLineResult.RejectionReason mapAmbientRejection(ConversationEligibility.Status status) {
        return switch (status) {
            case SLEEPING -> AmbientLineResult.RejectionReason.SLEEPING;
            case VISITOR -> AmbientLineResult.RejectionReason.VISITOR;
            case COOLDOWN -> AmbientLineResult.RejectionReason.COOLDOWN;
            case BUSY -> AmbientLineResult.RejectionReason.BUSY;
            case ADDON_POLICY_VETO -> AmbientLineResult.RejectionReason.ADDON_POLICY_VETO;
            case BUDGET_EXCEEDED -> AmbientLineResult.RejectionReason.BUDGET_EXCEEDED;
            case ELIGIBLE -> throw new IllegalArgumentException("ELIGIBLE is not a rejection");
        };
    }

    private static final class ActivityReservation implements CitizenActivityReservation {
        private final UUID citizenId;
        private final String ownerId;
        private final UUID token;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private ActivityReservation(UUID citizenId, String ownerId, UUID token) {
            this.citizenId = citizenId;
            this.ownerId = ownerId;
            this.token = token;
        }

        @Override
        public @NotNull UUID citizenId() {
            return citizenId;
        }

        @Override
        public @NotNull String ownerId() {
            return ownerId;
        }

        @Override
        public boolean isClosed() {
            if (closed.get()) return true;
            if (ConversationManager.isAddonActivityActive(citizenId, token)) return false;
            closed.set(true);
            return true;
        }

        @Override
        public boolean renew(@NotNull Duration timeout) {
            if (closed.get()) return false;
            boolean renewed = ConversationManager.renewAddonActivity(
                    citizenId, token, validateActivityTimeout(timeout)
            );
            if (!renewed) closed.set(true);
            return renewed;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ConversationManager.releaseAddonActivity(citizenId, token);
        }
    }

    private static final class ControlledSession implements ControlledConversationSession {
        private final MinecraftServer server;
        private final ControlledHooks hooks;
        private final ControlledConversationRuntime<AbstractEntityCitizen, ControlledAudioAnchor> runtime;

        private ControlledSession(
                MinecraftServer server,
                List<AbstractEntityCitizen> participants,
                String agenda,
                ControlledConversationOptions options
        ) {
            this.server = java.util.Objects.requireNonNull(server, "server");
            this.hooks = new ControlledHooks(server);
            this.runtime = new ControlledConversationRuntime<>(participants, agenda, options, hooks);
            hooks.participants = runtime::participants;
        }

        @Override
        public @NotNull UUID sessionId() { return runtime.sessionId(); }

        @Override
        public @NotNull List<AbstractEntityCitizen> participants() { return runtime.participants(); }

        @Override
        public @NotNull State state() { return runtime.state(); }

        @Override
        public void setAgenda(@NotNull String agenda) { runtime.setAgenda(agenda); }

        @Override
        public void addPlayerStatement(@NotNull ServerPlayer player, @NotNull String statement) {
            java.util.Objects.requireNonNull(player, "player");
            java.util.Objects.requireNonNull(statement, "statement");
            if (statement.isBlank()) return;
            if (runtime.state() == State.ENDED) throw new IllegalStateException("session ended");
            hooks.bindAuthenticatedPlayer(player);
            runtime.bindAuthenticatedPlayer(player.getUUID());
            Runnable append = () -> {
                if (runtime.state() == State.ENDED) return;
                runtime.addTranscript(new ConversationTranscriptEntry(
                        ConversationTranscriptEntry.SpeakerKind.PLAYER,
                        player.getUUID(), player.getName().getString(), statement.trim(), player.level().getGameTime()));
                ConversationEventRuntime.emitUtterance(new ConversationUtteranceEvent(ConversationKind.CONTROLLED,
                        null, ConversationUtteranceEvent.Speaker.PLAYER, player.getUUID(), player.getName().getString(),
                        statement.trim(), runtime.sessionId(), null, ConversationUtteranceEvent.Source.TYPED,
                        player.level().getGameTime()));
            };
            if (server.isSameThread()) append.run();
            else server.execute(append);
        }

        @Override
        public @NotNull CompletableFuture<ControlledTurnResult> requestTurn(
                @NotNull AbstractEntityCitizen speaker,
                @NotNull String topicOrInstruction,
                @Nullable ControlledAudioAnchor audioAnchor
        ) {
            return runtime.requestTurn(speaker, topicOrInstruction, audioAnchor);
        }

        @Override
        public @NotNull AutonomousDiscussionHandle delegateAutonomousDiscussion(
                @NotNull AutonomousDiscussionPolicy policy
        ) {
            return runtime.delegateAutonomousDiscussion(policy);
        }

        @Override
        public boolean interruptTurn() { return runtime.interruptTurn(); }

        @Override
        public void end(@NotNull EndReason reason) {
            List<AbstractEntityCitizen> gathered = runtime.participants();
            runtime.end(reason);
            SpeechFloor.release(this);
            unregisterControlledSession(server, this);
            restListeners(gathered);
        }

        /**
         * Players who listened to the gathering count as just addressed: nobody walks up to them with an
         * introduction or greeting the moment the circle breaks up.
         */
        private void restListeners(List<AbstractEntityCitizen> gathered) {
            double radius = McTalkingConfig.INSTANCE.instance().speechFloorRadius;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                boolean listened = gathered.stream().anyMatch(citizen -> citizen.level() == player.level()
                        && citizen.distanceToSqr(player) <= radius * radius);
                if (listened) ConversationManager.addressCooldowns().recordAddressed(player.getUUID());
            }
        }

        @Override
        public @NotNull List<ConversationTranscriptEntry> transcript() { return runtime.transcript(); }

        @Override
        public @NotNull String sharedTranscript() { return runtime.sharedTranscript(); }

        private static final class ControlledHooks
                implements ControlledConversationRuntime.Hooks<AbstractEntityCitizen, ControlledAudioAnchor> {
            private final MinecraftServer server;
            private final Map<UUID, ServerPlayer> detachedPlayers = new ConcurrentHashMap<>();
            private Supplier<List<AbstractEntityCitizen>> participants = List::of;

            private ControlledHooks(MinecraftServer server) { this.server = server; }

            private void bindAuthenticatedPlayer(@NotNull ServerPlayer player) {
                if (FakePlayerCompatibility.isFakePlayer(player)) {
                    detachedPlayers.put(player.getUUID(), player);
                } else {
                    detachedPlayers.remove(player.getUUID());
                }
            }

            @Override
            public void execute(@NotNull Runnable task) {
                if (server.isSameThread()) task.run();
                else server.execute(task);
            }

            @Override
            public @NotNull UUID id(@NotNull AbstractEntityCitizen participant) { return participant.getUUID(); }

            @Override
            public @NotNull String name(@NotNull AbstractEntityCitizen participant) {
                return participant.getName().getString();
            }

            @Override
            public long gameTime(@NotNull AbstractEntityCitizen participant) { return participant.level().getGameTime(); }

            @Override
            public @Nullable ControlledConversationRuntime.ColonyRef colony(@NotNull AbstractEntityCitizen participant) {
                var data = participant.getCitizenData();
                IColony colony = data == null ? null : data.getColony();
                return colony == null ? null : new ControlledConversationRuntime.ColonyRef(colony.getID(), colony.getName());
            }

            @Override
            public @NotNull Object dimension(@NotNull AbstractEntityCitizen participant) {
                return participant.level().dimension();
            }

            @Override
            public @Nullable String relation(@NotNull AbstractEntityCitizen speaker,
                                             @NotNull ControlledConversationRuntime.ColonyRef other) {
                var data = speaker.getCitizenData();
                IColony colony = data == null ? null : data.getColony();
                if (colony == null || colony.getConnectionManager() == null) return null;
                try {
                    var status = colony.getConnectionManager().getColonyDiplomacyStatus(other.id());
                    return status == null ? null : status.name();
                } catch (RuntimeException e) {
                    return null;
                }
            }

            @Override
            public @NotNull ControlledConversationRuntime.Availability availability(
                    @NotNull AbstractEntityCitizen participant,
                    @Nullable ControlledAudioAnchor audioAnchor
            ) {
                if (participant.isRemoved() || !participant.isAlive() || participant.getCitizenData() == null) {
                    return ControlledConversationRuntime.Availability.rejected(
                            ControlledTurnResult.FailureReason.SPEAKER_UNLOADED,
                            "speaker is unloaded or unavailable");
                }
                if (audioAnchor != null && participant.level().dimension() != audioAnchor.dimension()) {
                    return ControlledConversationRuntime.Availability.rejected(
                            ControlledTurnResult.FailureReason.UNSUPPORTED_OPERATION,
                            "cross-dimension controlled audio anchors are not supported");
                }
                if (audioAnchor != null && server.getLevel(audioAnchor.dimension()) == null) {
                    return ControlledConversationRuntime.Availability.rejected(
                            ControlledTurnResult.FailureReason.SPEAKER_UNAVAILABLE,
                            "audio-anchor dimension is not loaded");
                }
                if (!McTalkingConfig.hasGeminiApiKey()) {
                    return ControlledConversationRuntime.Availability.rejected(
                            ControlledTurnResult.FailureReason.PROVIDER_UNAVAILABLE,
                            "Gemini provider is unavailable");
                }
                ConversationEligibility eligibility = ConversationManager.conversationEligibility(
                        participant, ConversationKind.CONTROLLED);
                if (!eligibility.eligible()) {
                    return ControlledConversationRuntime.Availability.rejected(
                            ControlledTurnResult.FailureReason.SPEAKER_UNAVAILABLE,
                            eligibility.detail());
                }
                return ControlledConversationRuntime.Availability.ok();
            }

            @Override
            public boolean hasCapacity() {
                // Someone outside the session is audible near the group: wait (the session pauses) rather
                // than talk over them.
                return ConversationManager.hasLowPriorityCapacity(1) && participants.get().stream()
                        .noneMatch(member -> ConversationManager.isFloorTaken(member, ConversationKind.CONTROLLED));
            }

            @Override
            public @NotNull ControlledConversationRuntime.StartResult start(
                    @NotNull AbstractEntityCitizen participant,
                    @NotNull String prompt,
                    @NotNull PromptSessionContext promptContext,
                    @Nullable UUID authenticatedPlayerId,
                    @Nullable ControlledAudioAnchor audioAnchor,
                    int maxOutputTokens,
                    @NotNull Consumer<AmbientLineResult> audibleCompletion
            ) {
                if (!McTalkingConfig.hasGeminiApiKey()) {
                    return ControlledConversationRuntime.StartResult.PROVIDER_UNAVAILABLE;
                }
                if (!ConversationManager.hasLowPriorityCapacity(1)) {
                    return ControlledConversationRuntime.StartResult.CAPACITY_EXHAUSTED;
                }
                ServerPlayer boundPlayer = authenticatedPlayerId == null
                        ? null
                        : detachedPlayers.get(authenticatedPlayerId);
                boolean started = AmbientSessions.startControlledAmbientSession(
                        participant, prompt, audibleCompletion, promptContext, authenticatedPlayerId, boundPlayer,
                        audioAnchor, maxOutputTokens);
                if (started) return ControlledConversationRuntime.StartResult.STARTED;
                if (!ConversationManager.hasLowPriorityCapacity(1)) {
                    return ControlledConversationRuntime.StartResult.CAPACITY_EXHAUSTED;
                }
                return ControlledConversationRuntime.StartResult.SPEAKER_UNAVAILABLE;
            }

            @Override
            public void cancel(
                    @NotNull AbstractEntityCitizen participant,
                    @NotNull UUID sessionId,
                    @NotNull UUID turnId
            ) {
                ConversationManager.cancelControlledAmbientSession(participant, sessionId, turnId);
            }

            @Override
            public boolean playerOwnsConversation(@NotNull AbstractEntityCitizen participant) {
                return ConversationManager.getPlayerForEntity(participant.getUUID()) != null;
            }
        }
    }

    private static final class PairHandle implements CitizenConversationHandle {
        private final MinecraftServer server;
        private final CitizenConversation delegate;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<State> state = new AtomicReference<>(State.READY);
        private volatile Consumer<State> listener = ignored -> { };

        private PairHandle(MinecraftServer server, CitizenConversation delegate) {
            this.server = server;
            this.delegate = delegate;
            delegate.setOnStateChanged(coreState -> {
                State mapped = switch (coreState) {
                    case GENERATING -> State.GENERATING;
                    case PLAYING_AUDIO -> State.PLAYING_AUDIO;
                    case ENDED -> State.ENDED;
                };
                state.set(mapped);
                server.execute(() -> listener.accept(mapped));
            });
        }

        @Override
        public void start() {
            if (!started.compareAndSet(false, true) || cancelled.get()) return;
            server.execute(delegate::performConversation);
        }

        @Override
        public void cancel() {
            if (!cancelled.compareAndSet(false, true)) return;
            state.set(State.ENDED);
            server.execute(() -> {
                delegate.abort();
                listener.accept(State.ENDED);
            });
        }

        @Override
        public @NotNull State state() {
            return state.get();
        }

        @Override
        public void setStateListener(@NotNull Consumer<State> listener) {
            this.listener = java.util.Objects.requireNonNull(listener, "listener");
        }
    }
}
