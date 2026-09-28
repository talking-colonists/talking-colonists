package me.sshcrack.mc_talking.manager;

import me.sshcrack.mc_talking.internal.audio.VoicechatAccess;
import me.sshcrack.mc_talking.internal.session.OutputTurnTracker;
import me.sshcrack.mc_talking.internal.tool.ProviderToolCallIds;
import org.jetbrains.annotations.NotNull;
import me.sshcrack.mc_talking.internal.session.UtteranceTracker;
import me.sshcrack.mc_talking.api.conversation.ConversationUtteranceEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import me.sshcrack.gemini_live_lib.GeminiLiveClient;
import me.sshcrack.gemini_live_lib.gson.BidiGenerateContentSetup;
import me.sshcrack.gemini_live_lib.gson.ClientMessages;
import me.sshcrack.gemini_live_lib.gson.RealtimeInput;
import me.sshcrack.gemini_live_lib.websocket.handshake.ServerHandshake;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.broadcast.PlayerWords;
import me.sshcrack.mc_talking.internal.tool.AiToolDispatcher;
import me.sshcrack.mc_talking.internal.tool.AiToolExecutionContext;
import me.sshcrack.mc_talking.internal.tool.AiToolRuntime;
import me.sshcrack.mc_talking.internal.audio.MicrophoneTurnModule;
import me.sshcrack.mc_talking.internal.audio.PlaybackDrainCoordinator;
import me.sshcrack.mc_talking.internal.session.ProviderRecoveryController;
import me.sshcrack.mc_talking.internal.session.ProviderInputBuffer;
import me.sshcrack.mc_talking.internal.session.MinecraftConversationParticipationAdapter;
import me.sshcrack.mc_talking.internal.session.ServerThreadGate;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.AvailableAI;
import me.sshcrack.mc_talking.config.QuotaTracker;
import me.sshcrack.mc_talking.config.ModalityModes;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import me.sshcrack.mc_talking.manager.audio.AudioProvider;
import me.sshcrack.mc_talking.manager.tools.AITools;
import me.sshcrack.mc_talking.network.AiStatus;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.internal.audio.SpeechTimeline;

public abstract class GeminiWsClient extends GeminiLiveClient {
    private static final int MAX_TOTAL_RECOVERY_ATTEMPTS = 6;
    private static final long MAX_RECOVERY_WINDOW_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long GRACEFUL_CLOSE_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30);
    private static final AiToolDispatcher ADDON_TOOL_DISPATCHER = new AiToolDispatcher();
    private static volatile ScheduledExecutorService RECONNECT_EXECUTOR;
    private static volatile ScheduledExecutorService MICROPHONE_EXECUTOR;
    private static final long MICROPHONE_INPUT_MAX_QUEUE_NANOS = TimeUnit.MILLISECONDS.toNanos(2500);
    private static final long GENERATED_PADDING_MAX_QUEUE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private static synchronized ScheduledExecutorService getReconnectExecutor() {
        if (RECONNECT_EXECUTOR == null || RECONNECT_EXECUTOR.isShutdown() || RECONNECT_EXECUTOR.isTerminated()) {
            RECONNECT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mc_talking_ws_reconnect");
                t.setDaemon(true);
                return t;
            });
        }
        return RECONNECT_EXECUTOR;
    }

    private static synchronized ScheduledExecutorService getMicrophoneExecutor() {
        if (MICROPHONE_EXECUTOR == null || MICROPHONE_EXECUTOR.isShutdown() || MICROPHONE_EXECUTOR.isTerminated()) {
            MICROPHONE_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mc_talking_microphone_turns");
                t.setDaemon(true);
                return t;
            });
        }
        return MICROPHONE_EXECUTOR;
    }

    /**
     * Returns the model name string for quota tracking.
     */
    protected abstract String getModelName();

    private boolean hasMadeInitialConnection = false;
    private volatile boolean reconnectScheduled = false;
    private volatile boolean producedOutputSinceSetup = false;
    private final ProviderRecoveryController recoveryController = new ProviderRecoveryController(
            MAX_TOTAL_RECOVERY_ATTEMPTS, MAX_RECOVERY_WINDOW_MS, System::currentTimeMillis);
    private final List<Consumer<ProviderRecoveryController.Diagnostic>> recoveryObservers =
            Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closeStarted = new AtomicBoolean(false);
    private final AtomicBoolean providerTerminalEventFired = new AtomicBoolean(false);
    private final AtomicBoolean gracefulEndSignal = new AtomicBoolean(false);
    private volatile boolean finalGenerationCompleted = false;
    @Nullable
    private ScheduledFuture<?> reconnectFuture;
    protected volatile boolean generationComplete = false;
    private volatile boolean suppressProviderOutput = false;
    /** The citizen's current output turn and which transcript counts as heard. */
    private final OutputTurnTracker turns = new OutputTurnTracker();
    private final PlaybackDrainCoordinator gracefulPlaybackClose;
    /**
     * Whether the AI has started generating audio at least once (used to gate onGenerationPaused).
     */
    private boolean sentGeneratingStatus = false;
    protected boolean shouldEndConversation = false;

    /**
     * The most recent text submitted via {@link #addPromptTextAfterTalkingComplete}.
     * Saved so that after a session-token invalidation and reconnect the prompt can
     * be re-queued, preventing system-controlled sessions (mumbling / urgent contact)
     * from falling silent.
     */
    @Nullable
    private String lastPromptText = null;

    /**
     * Accumulates AI-generated text/transcription for the current turn to display in chat.
     */
    protected String currentTurnTranscript = "";
    /** Audio bytes of the current provider turn, to catch turns that end without any audio. */
    private long audioBytesThisTurn;
    private boolean textThisTurn;
    private boolean silentTurnRetried;
    public static final String SILENT_TURN_RETRY_PROMPT =
            "(Nobody heard that: you did not speak. Say your reply out loud now, in your own voice.)";
    private long receivedAudioBytes;
    private long droppedAudioBytes;

    private final String logPrefix;
    private final UUID providerToolSessionId = UUID.randomUUID();
    private final ProviderToolCallIds providerToolCallIds = new ProviderToolCallIds();
    protected final GeminiStream stream;
    @Nullable
    private volatile String selectedVoiceName;
    @Nullable
    private volatile AvailableAI selectedVoiceAi;
    private volatile boolean selectedVoiceFemale;
    private final AbstractEntityCitizen entity;
    private final OpusDecoder decoder;
    private final ProviderInputBuffer pendingInput = new ProviderInputBuffer();
    private final List<String> pendingTextAfterTalking = Collections.synchronizedList(new ArrayList<>());
    private final List<Runnable> onCloseActions = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean closeActionsFired = new AtomicBoolean(false);
    @Nullable
    private volatile MinecraftConversationParticipationAdapter conversationParticipation;
    @Nullable
    private volatile MicrophoneTurnModule microphoneTurns;

    private final long sessionStartTimeMs = System.currentTimeMillis();

    public long getSessionStartTimeMs() {
        return sessionStartTimeMs;
    }

    /** Immutable snapshot of generated citizen speech collected for this session. */
    public String getSessionTranscriptSnapshot() {
        synchronized (sessionTranscript) {
            return sessionTranscript.toString();
        }
    }

    @Nullable
    private VisibleCitizenStatus lastStatus;

    public AbstractEntityCitizen getEntity() {
        return entity;
    }

    // AudioProvider creates channels/decoders so this client can be tested/mockable
    protected GeminiWsClient(AudioProvider audioProvider, AbstractEntityCitizen entity) {
        super(McTalkingConfig.INSTANCE.instance().geminiApiKey);
        this.entity = entity;
        this.logPrefix = "[GeminiWsClient, " + entity.getUUID() + "]";
        AudioChannel channel = audioProvider.createChannel();
        this.decoder = audioProvider.createDecoder();
        stream = new GeminiStream(channel);
        stream.setTimeline(SpeechTimeline.tracker(entity, null,
                () -> ConversationManager.kindLabel(entity.getUUID())));
        stream.setOnPause(this::onStreamPause);
        gracefulPlaybackClose = new PlaybackDrainCoordinator(
                this::flushCurrentOutputTurn,
                stream::hasPendingPlayback,
                GRACEFUL_CLOSE_TIMEOUT_MS,
                (task, delayMillis) -> {
                    ScheduledFuture<?> future = getReconnectExecutor().schedule(() -> {
                        McTalking.LOGGER.warn("{} Graceful close timed out after {} ms; forcing session end",
                                logPrefix, GRACEFUL_CLOSE_TIMEOUT_MS);
                        task.run();
                    }, delayMillis, TimeUnit.MILLISECONDS);
                    return () -> future.cancel(false);
                },
                this::finishGracefulEnd
        );

        var citizenData = entity.getCitizenData();
        if (citizenData == null) {
            throw new IllegalArgumentException("CitizenData cannot be null for entity " + entity.getUUID());
        }
        var isFemale = citizenData.isFemale();
        var isChild = citizenData.isChild();
        if (isChild && !isFemale)
            stream.setPitch(1.2f); // Increase pitch
    }

    public boolean shouldResumeAndSaveSession() {
        return true;
    }

    @Nullable
    public VisibleCitizenStatus getLastStatus() {
        return lastStatus;
    }

    public void setLastStatus(@Nullable VisibleCitizenStatus lastStatus) {
        this.lastStatus = lastStatus;
    }

    public synchronized void bindConversationParticipation(MinecraftConversationParticipationAdapter participation) {
        Objects.requireNonNull(participation, "participation");
        if (conversationParticipation != null && conversationParticipation != participation) {
            throw new IllegalStateException("Conversation participation is already bound");
        }
        conversationParticipation = participation;
        if (microphoneTurns == null) {
            microphoneTurns = createMicrophoneTurnModule(participation);
        }
    }

    private MicrophoneTurnModule createMicrophoneTurnModule(MinecraftConversationParticipationAdapter participation) {
        UUID sessionId = participation.ownershipId();
        return new MicrophoneTurnModule(
                sessionId,
                System::nanoTime,
                (task, delayNanos) -> {
                    ScheduledFuture<?> future = getMicrophoneExecutor().schedule(task, delayNanos, TimeUnit.NANOSECONDS);
                    return () -> future.cancel(false);
                },
                new MicrophoneTurnModule.ProviderAdapter() {
                    @Override
                    public void acceptAudio(
                            UUID expectedSessionId,
                            @Nullable UUID turnId,
                            MicrophoneTurnModule.InputKind kind,
                            short[] pcm
                    ) {
                        if (!sessionId.equals(expectedSessionId) || closeStarted.get() || !participation.isCurrent()) return;
                        if (kind == MicrophoneTurnModule.InputKind.GENERATED_PADDING && !isSessionReadyForInput()) return;
                        if (kind != MicrophoneTurnModule.InputKind.GENERATED_PADDING) {
                            onBeforePlayerMicrophoneInput(kind);
                        }
                        submitPcmInput(pcm, kind);
                    }

                    @Override
                    public boolean cancelPlayback(UUID expectedSessionId, UUID turnId) {
                        if (!sessionId.equals(expectedSessionId) || closeStarted.get() || !participation.isCurrent()) return false;
                        boolean cancelled = interruptCurrentOutputForBargeIn();
                        if (cancelled) presentationIdle();
                        return cancelled;
                    }

                    @Override
                    public void waitingForResponse(UUID expectedSessionId, UUID turnId, boolean waiting) {
                        if (!sessionId.equals(expectedSessionId) || closeStarted.get() || !participation.isCurrent()) return;
                        participation.inputAwaitingResponse(waiting);
                    }

                    @Override
                    public void diagnostic(MicrophoneTurnModule.Diagnostic diagnostic) {
                        logMicrophoneDiagnostic(diagnostic);
                    }
                }
        );
    }

    protected void onBeforePlayerMicrophoneInput(MicrophoneTurnModule.InputKind kind) {
        // no-op by default; CitizenWsClient uses this seam for takeover context injection
    }

    private void logMicrophoneDiagnostic(MicrophoneTurnModule.Diagnostic diagnostic) {
        String turn = diagnostic.turnId() == null ? "none" : diagnostic.turnId().toString();
        switch (diagnostic.event()) {
            case INPUT_ACCEPTED, PROVIDER_INPUT_OBSERVED -> McTalking.LOGGER.debug(
                    "{} microphone session={} turn={} event={} source={}",
                    logPrefix, diagnostic.sessionId(), turn, diagnostic.event(), diagnostic.inputKind());
            case RESPONSE_TIMEOUT -> McTalking.LOGGER.warn(
                    "{} microphone session={} turn={} provider response timeout; returning to truthful ready state",
                    logPrefix, diagnostic.sessionId(), turn);
            default -> McTalking.LOGGER.info(
                    "{} microphone session={} turn={} event={} source={}",
                    logPrefix, diagnostic.sessionId(), turn, diagnostic.event(), diagnostic.inputKind());
        }
    }

    public void addOnCloseAction(Runnable action) {
        onCloseActions.add(action);
    }

    private void fireOnCloseActions() {
        if (!closeActionsFired.compareAndSet(false, true)) {
            return;
        }

        synchronized (onCloseActions) {
            for (Runnable action : onCloseActions) {
                try {
                    action.run();
                } catch (Exception e) {
                    McTalking.LOGGER.error("{} Error executing onClose action", logPrefix, e);
                }
            }
            onCloseActions.clear();
        }
    }

    private void presentationProviderConnecting() {
        var participation = conversationParticipation;
        if (participation != null) participation.providerConnecting();
    }

    private void presentationProviderReady() {
        var participation = conversationParticipation;
        if (participation != null) participation.providerReady();
    }

    private void presentationProviderRecovering() {
        var participation = conversationParticipation;
        if (participation != null) participation.providerRecovering();
    }

    private void presentationThinking() {
        var participation = conversationParticipation;
        if (participation != null) participation.playbackThinking();
    }

    private void presentationTalking() {
        var participation = conversationParticipation;
        if (participation != null) participation.playbackTalking();
    }

    private void presentationIdle() {
        var participation = conversationParticipation;
        if (participation != null) participation.playbackIdle();
    }

    protected final void presentationFailure(AiStatus status) {
        var participation = conversationParticipation;
        if (participation != null) participation.failure(status);
    }

    public void endConversationWhenPossible() {
        if (!gracefulEndSignal.compareAndSet(false, true)) return;
        this.shouldEndConversation = true;
    }

    private void requestGracefulEndAfterPlayback() {
        gracefulPlaybackClose.request();
    }

    private void finishGracefulEnd() {
        var server = entity.level().getServer();
        Runnable finish = () -> {
            var playerUUID = ConversationManager.getPlayerForEntity(entity.getUUID());
            if (playerUUID != null) {
                ConversationManager.endConversation(playerUUID, false);
            } else {
                close();
            }
        };
        if (server != null && !server.isSameThread()) {
            server.execute(finish);
        } else {
            finish.run();
        }
    }

    private UUID ensureOutputTurn() {
        return turns.ensure(stream::beginTurn);
    }

    @Nullable
    private UUID currentOutputTurn() {
        return turns.current();
    }

    private void flushCurrentOutputTurn() {
        UUID turnId = currentOutputTurn();
        if (turnId != null) stream.flushAudio(turnId);
    }

    private boolean interruptCurrentOutputForBargeIn() {
        UUID turnId = currentOutputTurn();
        if (turnId == null) return false;
        OutputTurnTracker.BargeIn bargeIn = turns.interruptForBargeIn(turnId);
        if (bargeIn == null) return false;
        boolean cancelled = stream.cancelTurn(turnId);

        // If Gemini had already completed generation, there will be no later interruption/turn
        // completion event to retire this local playback identity. Retire it now so the user's
        // audio can immediately start a fresh provider turn instead of inheriting a cancelled ID.
        if (bargeIn.providerAlreadyFinished()) {
            completeInterruptedTurn(turnId);
            onConversationEnded();
            gracefulPlaybackClose.onPlaybackDrained();
        }
        return cancelled;
    }

    private void invalidateCurrentOutputTurn() {
        UUID turnId = turns.invalidate();
        currentTurnTranscript = "";
        if (turnId != null) stream.cancelTurn(turnId);
        else stream.stop();
    }

    /** Commits only a transcript whose exact audio turn reached the end of playback. */
    private boolean completeAudibleTurn(UUID turnId) {
        OutputTurnTracker.Completion completion = turns.completeAudible(turnId);
        if (!completion.completed()) return false;
        String heardTranscript = completion.heardTranscript();
        if (heardTranscript != null && !heardTranscript.isBlank()) {
            synchronized (sessionTranscript) {
                if (!sessionTranscript.isEmpty()) sessionTranscript.append("\n");
                sessionTranscript.append(entity.getDisplayName().getString()).append(": ").append(heardTranscript.trim());
            }
            utterances.onCitizenTurnHeard(heardTranscript);
            SpeechTimeline.said(entity,
                    ConversationManager.kindLabel(entity.getUUID()), heardTranscript);
            onAudibleTranscriptComplete(heardTranscript.trim());
        }
        return true;
    }

    private void completeInterruptedTurn(UUID turnId) {
        if (turns.completeInterrupted(turnId)) currentTurnTranscript = "";
    }

    public boolean isSessionReadyForInput() {
        return !closeStarted.get() && recoveryController.diagnostic().state() == ProviderRecoveryController.State.ACTIVE
                && this.isOpen() && this.isSetupComplete();
    }

    /** Observable bounded-recovery state used by lifecycle/debug ownership code. */
    public ProviderRecoveryController.Diagnostic getRecoveryDiagnostic() {
        return recoveryController.diagnostic();
    }

    public void addRecoveryObserver(Consumer<ProviderRecoveryController.Diagnostic> observer) {
        Objects.requireNonNull(observer, "observer");
        recoveryObservers.add(observer);
        observer.accept(recoveryController.diagnostic());
    }

    /** True only after this client has run its local close/stream cleanup exactly once. */
    public boolean isLifecycleClosed() {
        return closeStarted.get();
    }

    private void transitionRecovery(String reason, Runnable transition) {
        ProviderRecoveryController.Diagnostic before = recoveryController.diagnostic();
        transition.run();
        ProviderRecoveryController.Diagnostic after = recoveryController.diagnostic();
        if (before.state() != after.state() || !Objects.equals(before.detail(), after.detail())) {
            McTalking.LOGGER.info("{} provider session {} -> {} ({})", logPrefix, before.state(), after.state(), reason);
        }
        notifyRecoveryObservers(after);
    }

    private void notifyRecoveryObservers(ProviderRecoveryController.Diagnostic diagnostic) {
        synchronized (recoveryObservers) {
            for (Consumer<ProviderRecoveryController.Diagnostic> observer : recoveryObservers) {
                try {
                    observer.accept(diagnostic);
                } catch (RuntimeException e) {
                    McTalking.LOGGER.warn("{} Recovery observer failed", logPrefix, e);
                }
            }
        }
    }

    private ProviderRecoveryController.State recoveryState() {
        return recoveryController.diagnostic().state();
    }

    private boolean canAttemptRecovery() {
        return !QuotaTracker.isQuotaExceeded(getModelName()) && recoveryController.canRecover();
    }

    private synchronized void ensureConnectionForQueuedInput(String source) {
        if (!canAttemptRecovery()) return;
        ProviderRecoveryController.State state = recoveryState();
        if (this.isOpen()
                || state == ProviderRecoveryController.State.CONNECTING
                || state == ProviderRecoveryController.State.SETTING_UP
                || state == ProviderRecoveryController.State.RECOVERING) {
            return;
        }
        if (!hasMadeInitialConnection) {
            McTalking.LOGGER.info("{} Starting initial websocket connection ({})", logPrefix, source);
            connect();
            return;
        }
        scheduleRecovery(source);
    }

    private synchronized boolean scheduleRecovery(String cause) {
        if (reconnectScheduled || this.isOpen()) return true;

        ProviderRecoveryController.Diagnostic before = recoveryController.diagnostic();
        ProviderRecoveryController.RecoveryAttempt attempt = recoveryController.beginRecovery(cause);
        ProviderRecoveryController.Diagnostic after = attempt.diagnostic();
        if (before.state() != after.state() || !Objects.equals(before.detail(), after.detail())) {
            McTalking.LOGGER.info("{} provider session {} -> {} ({})", logPrefix, before.state(), after.state(), cause);
        }
        notifyRecoveryObservers(after);

        if (!attempt.allowed()) {
            finishProviderTerminal(new RuntimeException(after.detail()));
            return false;
        }

        presentationProviderRecovering();
        reconnectScheduled = true;
        reconnectFuture = getReconnectExecutor().schedule(() -> {
            synchronized (GeminiWsClient.this) {
                reconnectScheduled = false;
                reconnectFuture = null;
                ProviderRecoveryController.Diagnostic diagnostic = recoveryController.diagnostic();
                if (diagnostic.terminal() || recoveryController.intentionalClose() || GeminiWsClient.this.isOpen()) return;
            }
            try {
                GeminiWsClient.super.reconnect();
            } catch (RuntimeException e) {
                McTalking.LOGGER.error("{} Provider reconnect attempt failed", logPrefix, e);
                scheduleRecovery("reconnect exception: " + e.getClass().getSimpleName());
            }
        }, attempt.delayMillis(), TimeUnit.MILLISECONDS);
        return true;
    }

    private void finishProviderTerminal(@Nullable Exception error) {
        fireOnCloseActions();
        if (error != null && providerTerminalEventFired.compareAndSet(false, true)) {
            onErrorEvent(error);
        }
    }

    /**
     * Returns the effective modality for this client.
     * Subclasses may override to force a specific modality regardless of the global config.
     * Defaults to the globally configured modality.
     */
    protected ModalityModes getEffectiveModality() {
        return McTalkingConfig.INSTANCE.instance().modality;
    }

    /**
     * Accumulates the AI-generated text across all turns in this session, used for memory generation.
     * Only populated when audio transcription is enabled (i.e. when citizen memory is enabled).
     */
    protected final StringBuilder sessionTranscript = new StringBuilder();
    /** Finished player and citizen utterances for addon listeners (roadmap A5). */
    private final UtteranceTracker utterances = new UtteranceTracker(new UtteranceTracker.Sink() {
        @Override
        public void player(@NotNull String text) {
            ConversationManager.emitClientUtterance(GeminiWsClient.this, ConversationUtteranceEvent.Speaker.PLAYER, text);
        }

        @Override
        public void citizen(@NotNull String text) {
            ConversationManager.emitClientUtterance(GeminiWsClient.this, ConversationUtteranceEvent.Speaker.CITIZEN, text);
        }
    });

    @Override
    public BidiGenerateContentSetup getSetup() {
        var setup = new BidiGenerateContentSetup("models/" + getModelName());

        var modality = getEffectiveModality();
        setup.generationConfig.responseModalities = modality.getModalities();

        if (modality == ModalityModes.TEXT_AND_AUDIO || McTalkingConfig.INSTANCE.instance().enableConversationSummaryAndMemorize) {
            setup.outputAudioTranscription = new JsonObject();
        }

        if (modality != ModalityModes.TEXT) {
            var citizenData = entity.getCitizenData();
            if (citizenData == null) {
                McTalking.LOGGER.warn("{} CitizenData not available for entity {} during setup, skipping audio config", logPrefix, entity.getUUID());
            } else {
                setup.generationConfig.speechConfig = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig();
                setup.generationConfig.speechConfig.language_code = McTalkingConfig.INSTANCE.instance().language;
                var female = citizenData.isFemale();
                var uuid = entity.getUUID();

                setup.sessionResumption = new BidiGenerateContentSetup.SessionResumptionConfig();
                var mem = ((CitizenDataMemoryExtended) citizenData).mc_talking$getOrInitializeMemory();
                var sessionToken = mem.getSessionToken();
                if (!sessionToken.isBlank() && shouldResumeAndSaveSession()) {
                    setup.sessionResumption = new BidiGenerateContentSetup.SessionResumptionConfig(sessionToken);
                }
                setup.generationConfig.speechConfig.voice_config = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig.VoiceConfig();
                setup.generationConfig.speechConfig.voice_config.prebuiltVoiceConfig = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig.PrebuiltVoiceConfig();
                var selectedAi = McTalkingConfig.INSTANCE.instance().currentAiModel;
                selectedVoiceAi = selectedAi;
                selectedVoiceFemale = female;
                selectedVoiceName = VoiceSelectionService.select(
                        VoiceSelectionService.Backend.LIVE,
                        selectedAi.getName(),
                        selectedAi,
                        uuid,
                        female);
                setup.generationConfig.speechConfig.voice_config.prebuiltVoiceConfig.voice_name = selectedVoiceName;
            }
        }

        setup.realtimeInputConfig = new BidiGenerateContentSetup.RealtimeInputConfig();

        //TODO: Allow citizens to speak for themselves
        //setup.realtimeInputConfig.turnCoverage = BidiGenerateContentSetup.RealtimeInputConfig.TurnCoverage.TURN_INCLUDES_ALL_INPUT;

        var sys = new BidiGenerateContentSetup.SystemInstruction();
        //TODO change player when other player is talking to AI
        //TODO actually make a summary of the conversation after it has ended

        var p = new BidiGenerateContentSetup.SystemInstruction.Part(getSystemPrompt());
        sys.parts.add(p);

        setup.systemInstruction = sys;

        setup.tools.addAll(AITools.getEnabledTools(this::allowBuiltInTool, tool -> allowAddonTool(tool.id())));

        return setup;
    }

    protected abstract String getSystemPrompt();

    /** Session-specific addon-tool policy. Ordinary conversations allow registered tools. */
    protected boolean allowAddonTool(String toolId) { return true; }

    /** Session-specific built-in tool policy. Ordinary conversations allow every enabled built-in. */
    protected boolean allowBuiltInTool(String name) { return true; }

    protected UUID toolSessionId() { return providerToolSessionId; }

    protected UUID toolOperationScopeId() { return providerToolSessionId; }

    @Nullable
    protected UUID toolTurnId() { return null; }

    /**
     * Resolves the active player for this conversation so that generated text and transcriptions
     * can be forwarded as chat messages. Subclasses may override this to provide the player
     * directly without going through {@link ConversationManager}.
     */
    @Nullable
    protected ServerPlayer resolveActivePlayer() {
        var playerUUID = ConversationManager.getPlayerForEntity(entity.getUUID());
        if (playerUUID == null) return null;
        return Objects.requireNonNull(entity.level().getServer()).getPlayerList().getPlayer(playerUUID);
    }

    /** Player authority used for addon tools; subclasses may authenticate a player without enabling live input. */
    @Nullable
    protected ServerPlayer resolveAuthenticatedToolPlayer() {
        return resolveActivePlayer();
    }

    protected void onStreamPause() {
        UUID turnId = currentOutputTurn();
        if (turnId != null && turns.isProviderTurnComplete() && completeAudibleTurn(turnId)) {
            onConversationEnded();
            gracefulPlaybackClose.onPlaybackDrained();
        } else if (recoveryState() == ProviderRecoveryController.State.ACTIVE && audioBytesThisTurn == 0) {
            // Audio that runs dry mid-line is a gap in the stream, not the citizen thinking of a reply:
            // they stay TALKING until the rest arrives.
            presentationThinking();
        }
    }

    protected void onConversationEnded() {
        // Audible playback has drained regardless of provider transport state. Keep that fact
        // independent from readiness so a disconnect during buffered speech cannot leave TALKING
        // stuck after the local stream becomes silent.
        presentationIdle();
        if (recoveryState() == ProviderRecoveryController.State.ACTIVE) {
            flushPendingText();
        }
    }

    /**
     * Immediately sends any buffered {@link #pendingTextAfterTalking} to the API.
     *
     * <p>Called internally by {@link #onConversationEnded()} and may also be
     * invoked externally (e.g. by the peer in a citizen-to-citizen conversation)
     * to release text that was queued early — before the peer's own audio stream
     * had a chance to drain.
     */
    protected void flushPendingText() {
        synchronized (pendingTextAfterTalking) {
            if (!pendingTextAfterTalking.isEmpty()) {
                String message = String.join("\n", pendingTextAfterTalking);
                pendingTextAfterTalking.clear();
                addPromptTextImmediate(message);
            }
        }
    }

    protected void onGenerationStarted() {
        sentGeneratingStatus = true;
        if (recoveryState() == ProviderRecoveryController.State.ACTIVE) {
            presentationTalking();
        }
    }

    protected void onGenerationPaused() {
        if (recoveryState() == ProviderRecoveryController.State.ACTIVE) {
            presentationThinking();
        }
    }

    protected abstract void onQuotaExceededEvent(String message);

    protected abstract void onErrorEvent(Exception ex);

    @Override
    public void send(String text) {
        super.send(text);
        generationComplete = false;
    }

    @Override
    public void onUsageMetadata(JsonObject obj) {
        //McTalking.LOGGER.info("Gemini usage metadata: {}", obj.toString());
    }

    @Override
    public void onSessionResumptionUpdate(String newHandle, boolean resumable) {
        if (!resumable || !shouldResumeAndSaveSession())
            return;

        McTalking.LOGGER.debug("{} Received updated session-resumption handle", logPrefix);
        var mem = ((CitizenDataMemoryExtended) entity.getCitizenData()).mc_talking$getOrInitializeMemory();
        mem.setSessionToken(newHandle);
    }

    @Override
    public void onGenerationComplete() {
        McTalking.LOGGER.info("{} Gemini generation complete", logPrefix);
        if (suppressProviderOutput) return;

        producedOutputSinceSetup = true;
        UUID turnId = ensureOutputTurn();
        if (shouldEndConversation) finalGenerationCompleted = true;
        stream.flushAudio(turnId);
        generationComplete = true;
    }

    @Override
    public void onInterrupted() {
        McTalking.LOGGER.info("{} Gemini generation interrupted", logPrefix);
        if (suppressProviderOutput) return;
        UUID turnId = ensureOutputTurn();
        turns.markInterrupted();
        stream.cancelTurn(turnId);

        // Provider-side interruption already updates Gemini's conversation state. The local
        // transcript may describe audio that was queued but never heard, so it is intentionally
        // not committed to persistent/session memory or forwarded to a peer as heard speech.
        var sPlayer = resolveActivePlayer();
        if (!currentTurnTranscript.isBlank()) sendTranscriptToChat(sPlayer);
        currentTurnTranscript = "";
    }

    private void sendTranscriptToChat(@Nullable ServerPlayer sPlayer) {
        var modality = getEffectiveModality();
        var hasTextEnabled = modality == ModalityModes.TEXT || modality == ModalityModes.TEXT_AND_AUDIO;
        if (!hasTextEnabled) return;

        var message = entity.getDisplayName().copy().append(": ").append(Component.literal(currentTurnTranscript.trim()));

        if (sPlayer != null) {
            sPlayer.sendSystemMessage(message);
        } else if (McTalkingConfig.INSTANCE.instance().sendMumblingAndConversationsToChat) {
            var server = entity.level().getServer();
            if (server != null) {
                double range = McTalkingConfig.INSTANCE.instance().citizenInteractionRange * 2;
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.level() == entity.level() && player.distanceTo(entity) <= range) {
                        player.sendSystemMessage(message);
                    }
                }
            }
        }
    }

    @Override
    public void onGeneratedText(String text) {
        if (finalGenerationCompleted || suppressProviderOutput) return;
        microphoneProviderProgress(MicrophoneTurnModule.ProviderProgress.RESPONSE_STARTED);
        ensureOutputTurn();
        producedOutputSinceSetup = true;
        textThisTurn = true;
        // With audio output, the spoken words arrive as output transcription; text parts are the model's
        // thoughts or an unspoken reply, and must not show up as something the citizen said.
        if (getEffectiveModality() != ModalityModes.TEXT) return;

        currentTurnTranscript += text;
    }

    @Override
    public void onInputTranscription(String transcription) {
        microphoneProviderProgress(MicrophoneTurnModule.ProviderProgress.INPUT_OBSERVED);
        if (!suppressProviderOutput && transcription != null) {
            utterances.onInputChunk(transcription);
            PlayerWords.hearing(entity.getUUID(), transcription);
        }
    }

    @Override
    public void onOutputTranscription(String transcription) {
        if (finalGenerationCompleted || suppressProviderOutput) return;
        microphoneProviderProgress(MicrophoneTurnModule.ProviderProgress.RESPONSE_STARTED);
        ensureOutputTurn();
        producedOutputSinceSetup = true;
        textThisTurn = true;
        currentTurnTranscript += transcription;
    }

    @Override
    public void onTurnComplete() {
        McTalking.LOGGER.info("{} Gemini turn complete", logPrefix);
        if (SpeechTimeline.enabled()) {
            long[] streamCounters = stream.takeAudioCounters();
            SpeechTimeline.turn(entity, ConversationManager.kindLabel(entity.getUUID()), receivedAudioBytes,
                    droppedAudioBytes, streamCounters[0], streamCounters[1], currentTurnTranscript.length(),
                    suppressProviderOutput);
            receivedAudioBytes = 0;
            droppedAudioBytes = 0;
        }
        if (suppressProviderOutput) return;
        if (handleSilentTurn()) return;
        microphoneProviderProgress(MicrophoneTurnModule.ProviderProgress.TURN_COMPLETED);
        utterances.onProviderTurnComplete();
        QuotaTracker.reportSuccess(getModelName());
        UUID turnId = ensureOutputTurn();
        generationComplete = true;
        turns.markProviderTurnComplete();

        if (turns.isInterrupted()) {
            completeInterruptedTurn(turnId);
            onConversationEnded();
            if (shouldEndConversation) requestGracefulEndAfterPlayback();
            return;
        }

        var sPlayer = resolveActivePlayer();
        if (!currentTurnTranscript.isBlank()) {
            String transcript = currentTurnTranscript.trim();
            sendTranscriptToChat(sPlayer);
            turns.setPendingTranscript(turnId, transcript);
            currentTurnTranscript = "";
        }

        stream.flushAudio(turnId);
        if (!stream.hasPendingPlayback() && completeAudibleTurn(turnId)) {
            onConversationEnded();
        }

        if (shouldEndConversation) {
            finalGenerationCompleted = true;
            requestGracefulEndAfterPlayback();
        }
    }

    /**
     * The model sometimes ends a turn without any audio (only thoughts or an unspoken reply). Nobody
     * heard it, so drop its transcript and turn, and ask once to say it aloud. Returns true while the
     * retry is pending.
     */
    private boolean handleSilentTurn() {
        boolean silent = getEffectiveModality() != ModalityModes.TEXT && audioBytesThisTurn == 0
                && textThisTurn && !turns.isInterrupted() && !shouldEndConversation;
        audioBytesThisTurn = 0;
        textThisTurn = false;
        if (!silent) {
            silentTurnRetried = false;
            return false;
        }
        currentTurnTranscript = "";
        if (silentTurnRetried) {
            McTalking.LOGGER.warn("{} Provider turn ended without audio again; giving up on it", logPrefix);
            silentTurnRetried = false;
            return false;
        }
        silentTurnRetried = true;
        McTalking.LOGGER.warn("{} Provider turn ended without audio; asking to say it aloud", logPrefix);
        // Generation completion already started draining the silent turn, so its gate would reject the
        // retry's audio: retire it, and the retry opens a fresh turn.
        invalidateCurrentOutputTurn();
        addPromptTextImmediate(SILENT_TURN_RETRY_PROMPT);
        return true;
    }

    /** Called only after the transcript's exact audible turn has drained successfully. */
    protected void onAudibleTranscriptComplete(String transcript) {
        // no-op by default
    }

    @Override
    public void onOpen(ServerHandshake data) {
        transitionRecovery("websocket opened", () -> recoveryController.markSettingUp("websocket opened"));
        try {
            super.onOpen(data);
        } catch (VoiceSelectionService.VoiceCandidatesExhaustedException e) {
            terminateVoiceRecovery(e);
        }
    }

    @Override
    public void onGeneratedAudio(byte[] data, int sampleRate) {
        receivedAudioBytes += data.length;
        if (finalGenerationCompleted || suppressProviderOutput) {
            McTalking.LOGGER.debug("{} Dropping audio outside the active provider turn", logPrefix);
            droppedAudioBytes += data.length;
            return;
        }
        microphoneProviderProgress(MicrophoneTurnModule.ProviderProgress.RESPONSE_STARTED);
        producedOutputSinceSetup = true;
        audioBytesThisTurn += data.length;
        UUID turnId = ensureOutputTurn();
        var isJustStarted = stream.addGeminiPcmWithPitch(turnId, data, sampleRate);
        if (!isJustStarted)
            return;
        onGenerationStarted();
    }

    /**
     * Sends a text prompt directly to the Gemini API right now, bypassing the
     * "after talking" buffer. If the connection is not yet ready the text is
     * queued and sent as soon as setup completes, just like audio prompts.
     */
    public void addPromptTextImmediate(String text) {
        var input = new RealtimeInput();
        input.text = text;
        submitInput(ClientMessages.input(input));
    }

    private void submitInput(String frame) {
        submitInput(frame, Long.MAX_VALUE);
    }

    private void submitInput(String frame, long maxQueueAgeNanos) {
        if (closeStarted.get() || recoveryController.diagnostic().terminal()) return;
        try {
            ProviderInputBuffer.DrainResult result = pendingInput.submit(
                    frame,
                    maxQueueAgeNanos,
                    System::nanoTime,
                    this::isSessionReadyForInput,
                    this::send
            );
            if (result.droppedExpired() > 0) {
                McTalking.LOGGER.warn("{} Dropped {} stale queued provider input frame(s)",
                        logPrefix, result.droppedExpired());
            }
        } catch (RuntimeException error) {
            onError(error);
        }
        ensureConnectionForQueuedInput("queued input");
    }

    private void microphoneProviderProgress(MicrophoneTurnModule.ProviderProgress progress) {
        MicrophoneTurnModule turns = microphoneTurns;
        if (turns != null) turns.providerProgress(progress);
    }

    @Override
    public void onSetupComplete() {
        if (closeStarted.get() || recoveryController.diagnostic().terminal()) return;
        suppressProviderOutput = false;
        producedOutputSinceSetup = false;
        synchronized (this) {
            reconnectScheduled = false;
            if (reconnectFuture != null) {
                reconnectFuture.cancel(false);
                reconnectFuture = null;
            }
        }
        transitionRecovery("setup complete", recoveryController::setupSucceeded);
        presentationProviderReady();

        McTalking.LOGGER.info("{} Gemini setup complete", logPrefix);
        try {
            ProviderInputBuffer.DrainResult result = pendingInput.flush(
                    this::isSessionReadyForInput, this::send, System::nanoTime);
            if (result.droppedExpired() > 0) {
                McTalking.LOGGER.warn("{} Dropped {} stale queued provider input frame(s) after setup",
                        logPrefix, result.droppedExpired());
            }
        } catch (RuntimeException error) {
            onError(error);
        }
    }

    @Override
    public void onMessage(String message) {
        providerToolCallIds.handle(message, () -> super.onMessage(message));
    }

    @Override
    public JsonObject onFunctionCall(String name, @Nullable JsonObject args) {
        // Gemini may batch built-in and addon calls together. Consume every call ID in order so a
        // preceding built-in never shifts the idempotency key used by a later addon command.
        String providerCallId = providerToolCallIds.poll(name, (expected, received) -> McTalking.LOGGER.warn(
                "{} Provider tool-call ID/name mismatch: expected {}, received {}", logPrefix, expected, received));
        var colony = this.entity.getCitizenColonyHandler().getColony();

        var action = AITools.getAction(name);
        if (action != null && !allowBuiltInTool(name)) action = null;
        var addonAction = AiToolRuntime.findByProviderName(name);
        if (addonAction != null && !allowAddonTool(addonAction.id())) addonAction = null;
        if (action == null && addonAction == null) {
            McTalking.LOGGER.warn("{} Unknown function call: {}", logPrefix, name);
            var error = new JsonObject();
            error.addProperty("error", "Unknown function: " + name);
            return error;
        }

        var activePlayer = resolveActivePlayer();
        if (action != null && action.isPlayerOnly() && activePlayer == null) {
            McTalking.LOGGER.warn("{} Player-only tool {} called without active player", logPrefix, name);
            var error = new JsonObject();
            error.addProperty("error", "You cannot use this tool until a player is speaking to you directly.");
            return error;
        }

        McTalking.LOGGER.info("{} [TOOL-CALL] Entity {} has called tool {} with parameters {}", logPrefix, entity.getStringUUID(), name, new Gson().toJson(args));
        JsonObject result;
        try {
            if (addonAction != null) {
                var context = new AiToolExecutionContext(
                        toolSessionId(), toolTurnId(), this.entity, colony, resolveAuthenticatedToolPlayer());
                var endpoint = new AiToolDispatcher.SessionEndpoint() {
                    @Override
                    public UUID sessionId() {
                        return toolSessionId();
                    }

                    @Override
                    public UUID operationScopeId() {
                        return toolOperationScopeId();
                    }

                    @Override
                    public AiToolExecutionContext context() {
                        return context;
                    }

                    @Override
                    public boolean isAvailable() {
                        return isSessionReadyForInput();
                    }

                    @Override
                    public boolean deliver(JsonObject outcome) {
                        return tryDeliverToolOperationOutcome(outcome);
                    }
                };
                result = ADDON_TOOL_DISPATCHER.dispatch(providerCallId, name, args, endpoint);
            } else {
                var server = Objects.requireNonNull(entity.level().getServer(), "Citizen has no server");
                var builtin = action;
                result = new ServerThreadGate(server, server::isSameThread).call(
                        () -> isSessionReadyForInput() && entity.isAlive()
                                && (!builtin.isPlayerOnly() || resolveActivePlayer() != null),
                        () -> builtin.execute(entity, entity.getCitizenColonyHandler().getColony(), args),
                        10_000);
            }
        } catch (Exception e) {
            McTalking.LOGGER.error("{} [TOOL-CALL] Tool threw an unexpected exception. Params are {}.", logPrefix, (new Gson()).toJson(args), e);
            var error = new JsonObject();
            error.addProperty("error", "A fatal error occurred. Don't call this tool again.");

            return error;
        }

        McTalking.LOGGER.info("{} [TOOL-CALL] Result of {}: {}", logPrefix, name, result);
        return result;
    }

    private boolean tryDeliverToolOperationOutcome(JsonObject outcome) {
        if (!isSessionReadyForInput()) return false;
        try {
            var input = new RealtimeInput();
            input.text = "A previously accepted Talking Colonists tool operation has finished. "
                    + "Use this structured result for the current conversation only:\n" + outcome;
            if (!isSessionReadyForInput()) return false;
            send(ClientMessages.input(input));
            return true;
        } catch (RuntimeException e) {
            McTalking.LOGGER.warn("{} Could not deliver asynchronous tool result to the active session", logPrefix, e);
            return false;
        }
    }

    @Override
    public void onQuotaExceeded() {
        McTalking.LOGGER.warn("{} Quota exceeded for Gemini API, please check your API key and usage limits.", logPrefix);
        QuotaTracker.reportQuotaExceeded(getModelName());
        transitionRecovery("quota exceeded", () -> recoveryController.quotaExceeded("quota exceeded"));
        onQuotaExceededEvent("Quota exceeded for Gemini API, please check your API key and usage limits.");
        fireOnCloseActions();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        try {
            super.onClose(code, reason, remote);
        } catch (Exception e) {
            McTalking.LOGGER.error("{} Error in GeminiLiveClient.onClose", logPrefix, e);
        }

        MicrophoneTurnModule turns = microphoneTurns;
        if (turns != null) turns.providerDisconnected();

        if (recoveryController.intentionalClose()) return;
        if (QuotaTracker.isQuotaExceeded(getModelName())
                || recoveryState() == ProviderRecoveryController.State.QUOTA_EXCEEDED) return;

        AvailableAI rejectedAi = selectedVoiceAi;
        if (VoiceSelectionService.isExplicitLiveVoiceRejection(code, reason)
                && rejectedAi != null
                && VoiceSelectionService.noteLiveRejected(rejectedAi, selectedVoiceName, code, reason)) {
            try {
                String fallback = VoiceSelectionService.select(
                        VoiceSelectionService.Backend.LIVE,
                        rejectedAi.getName(),
                        rejectedAi,
                        entity.getUUID(),
                        selectedVoiceFemale);
                McTalking.LOGGER.warn(
                        "{} Voice recovery backend=live model={} rejected={} selected={} result=retry",
                        logPrefix, rejectedAi.getName(), selectedVoiceName, fallback);
            } catch (VoiceSelectionService.VoiceCandidatesExhaustedException e) {
                terminateVoiceRecovery(e);
                return;
            }
            scheduleRecovery("explicit voice rejection");
            return;
        }

        ProviderRecoveryController.CloseDisposition disposition =
                ProviderRecoveryController.classifyClose(code, reason);

        if (disposition == ProviderRecoveryController.CloseDisposition.SESSION_TOKEN_INVALID) {
            McTalking.LOGGER.info("{} Session token invalidated; clearing resumable state before bounded reconnect", logPrefix);
            var mem = ((CitizenDataMemoryExtended) entity.getCitizenData()).mc_talking$getOrInitializeMemory();
            mem.setSessionToken("");
            suppressProviderOutput = true;
            int discardedAudio = stream.discardPendingAudio();
            invalidateCurrentOutputTurn();
            presentationIdle();
            if (discardedAudio > 0) {
                McTalking.LOGGER.info("{} Discarded {} stale queued audio chunks before replaying invalidated session",
                        logPrefix, discardedAudio);
            }
            if (lastPromptText != null) {
                var replay = new RealtimeInput();
                replay.text = lastPromptText;
                pendingInput.enqueueIfAbsent(ClientMessages.input(replay));
            }
            scheduleRecovery("session token invalidated");
            return;
        }

        if (disposition == ProviderRecoveryController.CloseDisposition.NORMAL) {
            transitionRecovery("normal close", () -> recoveryController.closeNormal("provider close " + code + ": " + reason));
            McTalking.LOGGER.info("{} GeminiWsClient closed normally: {}", logPrefix, reason);
            fireOnCloseActions();
            return;
        }

        if (disposition == ProviderRecoveryController.CloseDisposition.TRANSIENT) {
            McTalking.LOGGER.warn("{} Transient provider close {}: {}; scheduling bounded recovery", logPrefix, code, reason);
            scheduleRecovery("provider close " + code + ": " + reason);
            return;
        }

        ProviderRecoveryController.TerminalReason terminalReason = switch (disposition) {
            case AUTHENTICATION_FAILURE -> ProviderRecoveryController.TerminalReason.AUTHENTICATION;
            case CONFIGURATION_FAILURE -> ProviderRecoveryController.TerminalReason.CONFIGURATION;
            case POLICY_FAILURE -> ProviderRecoveryController.TerminalReason.PROVIDER_POLICY;
            default -> ProviderRecoveryController.TerminalReason.PROVIDER_ERROR;
        };
        String detail = "provider close " + code + ": " + (reason == null ? "" : reason);
        transitionRecovery(detail, () -> recoveryController.terminal(terminalReason, detail));
        finishProviderTerminal(new RuntimeException(detail));
    }

    private void terminateVoiceRecovery(VoiceSelectionService.VoiceCandidatesExhaustedException error) {
        String detail = error.getMessage();
        McTalking.LOGGER.error("{} {} result=terminal", logPrefix, detail);
        transitionRecovery(detail, () -> recoveryController.terminal(
                ProviderRecoveryController.TerminalReason.CONFIGURATION, detail));
        finishProviderTerminal(error);
        close();
    }

    @Override
    public void onError(Exception ex) {
        McTalking.LOGGER.error("{} Error in GeminiWsClient", logPrefix, ex);
        ProviderRecoveryController.Diagnostic diagnostic = recoveryController.diagnostic();
        if (recoveryController.intentionalClose() || diagnostic.terminal()
                || QuotaTracker.isQuotaExceeded(getModelName())) return;
        if (isOpen()) {
            closeConnection(1006, "websocket error: " + ex.getClass().getSimpleName());
            return;
        }
        scheduleRecovery("websocket error: " + ex.getClass().getSimpleName());
    }

    @Override
    public void addPromptAudio(short[] audio) {
        // Generic provider audio is intentionally non-interrupting. Real microphone packets must
        // enter through acceptMicrophoneOpus(), where decoded PCM and turn ownership decide barge-in.
        submitPcmInput(audio, MicrophoneTurnModule.InputKind.QUIET_MICROPHONE);
    }

    private void submitPcmInput(short[] audio, MicrophoneTurnModule.InputKind kind) {
        var vcApi = VoicechatAccess.get();
        if (audio == null || audio.length == 0 || vcApi == null) return;
        var input = new RealtimeInput();
        var byteAudio = vcApi.getAudioConverter().shortsToBytes(audio);
        input.audio = new RealtimeInput.Blob("audio/pcm;rate=48000", byteAudio);
        long maxAge = kind == MicrophoneTurnModule.InputKind.GENERATED_PADDING
                ? GENERATED_PADDING_MAX_QUEUE_NANOS
                : MICROPHONE_INPUT_MAX_QUEUE_NANOS;
        submitInput(ClientMessages.input(input), maxAge);
    }

    /**
     * Queues {@code text} to be sent to the API after the current AI turn finishes.
     * If the session is not yet ready the text is buffered in {@link #pendingInput}
     * and sent once setup completes.
     *
     * <p>The text is also saved to {@link #lastPromptText} so that if the session
     * token is later invalidated the prompt can be replayed on the new connection,
     * preventing system-controlled conversations from going silent.</p>
     *
     * @param text the text prompt to send after the current AI turn completes
     */
    public void addPromptTextAfterTalkingComplete(String text) {
        this.lastPromptText = text;

        if (sentGeneratingStatus)
            onGenerationPaused();

        queueTextAfterTalking(text);
    }

    /**
     * Sends an addon context note (roadmap A4) once the citizen's current turn has finished, so it
     * does not cut the citizen off. Unlike {@link #addPromptTextAfterTalkingComplete} it is not
     * replayed after a reconnect: it describes a moment, not the session's purpose.
     */
    public void addContextNoteAfterTalkingComplete(String text) {
        queueTextAfterTalking(text);
    }

    private void queueTextAfterTalking(String text) {
        synchronized (pendingTextAfterTalking) {
            if (isSessionReadyForInput() && currentOutputTurn() != null) {
                pendingTextAfterTalking.add(text);
                return;
            }
        }
        addPromptTextImmediate(text);
    }

    /** Records a line the player typed (roadmap A4) so memory extraction sees it. */
    public void appendPlayerTranscriptLine(String playerName, String text) {
        synchronized (sessionTranscript) {
            if (!sessionTranscript.isEmpty()) sessionTranscript.append("\n");
            sessionTranscript.append(playerName).append(": ").append(text);
        }
    }

    @Override
    public void connect() {
        if (recoveryController.diagnostic().terminal()) {
            McTalking.LOGGER.debug("{} Ignoring connect() after terminal provider state {}",
                    logPrefix, recoveryController.diagnostic().state());
            return;
        }
        hasMadeInitialConnection = true;
        transitionRecovery("connect()", () -> recoveryController.markConnecting("connect()"));
        presentationProviderConnecting();
        super.connect();
    }

    @Override
    public void reconnect() {
        scheduleRecovery("explicit reconnect");
    }

    /** Decodes one Simple Voice Chat packet and feeds the session-owned microphone-turn module. */
    public boolean acceptMicrophoneOpus(byte[] audio) {
        if (decoder == null || audio == null || audio.length == 0) return false;
        short[] raw = decoder.decode(audio);
        MicrophoneTurnModule turns = microphoneTurns;
        if (turns == null) {
            McTalking.LOGGER.warn("{} Dropping microphone packet without bound foreground participation", logPrefix);
            return false;
        }
        return turns.acceptMicrophone(raw);
    }

    @Override
    public void close() {
        if (!closeStarted.compareAndSet(false, true)) return;
        utterances.end();
        MicrophoneTurnModule turns = microphoneTurns;
        if (turns != null) turns.close();
        pendingInput.close();
        pendingTextAfterTalking.clear();
        synchronized (this) {
            transitionRecovery("close()", () -> recoveryController.closeIntentional("close()"));
            reconnectScheduled = false;
            if (reconnectFuture != null) {
                reconnectFuture.cancel(false);
                reconnectFuture = null;
            }
        }
        gracefulPlaybackClose.cancel();
        invalidateCurrentOutputTurn();
        ADDON_TOOL_DISPATCHER.forgetSession(toolOperationScopeId());
        try {
            super.close();
        } finally {
            stream.close();
            fireOnCloseActions();
        }
    }

    /**
     * If set to true, avoids to send new status updates while the conversation is active, which can be used to reduce status update spam when the AI is generating multiple turns in a row.
     *
     * @return false, if new status updates should NOT be sent
     */
    public boolean sendStatusUpdates() {
        return true;
    }

    public static void shutdownExecutor() {
        shutdownExecutor(RECONNECT_EXECUTOR);
        shutdownExecutor(MICROPHONE_EXECUTOR);
    }

    private static void shutdownExecutor(@Nullable ScheduledExecutorService executor) {
        if (executor == null) return;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
