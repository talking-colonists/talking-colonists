package me.sshcrack.mc_talking.conversations;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.McTalkingVoicechatPlugin;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.internal.audio.VoicechatAccess;
import me.sshcrack.mc_talking.internal.prompt.PromptRuntime;
import me.sshcrack.mc_talking.internal.session.AmbientSpeechBudget;
import me.sshcrack.mc_talking.internal.session.SpeechFloor;
import me.sshcrack.mc_talking.internal.session.ConversationEventDispatch;
import me.sshcrack.mc_talking.internal.session.ForegroundSessionRegistry;
import me.sshcrack.mc_talking.internal.session.ProviderRecoveryController;
import me.sshcrack.mc_talking.conversations.memory.CitizenMemoryGenerator;
import me.sshcrack.mc_talking.config.ConversationMode;
import me.sshcrack.mc_talking.manager.CitizenPromptViewFactory;
import me.sshcrack.mc_talking.manager.GeminiStream;
import me.sshcrack.mc_talking.manager.audio.CitizenEntityAudioProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.TtsQuotaManager;
import me.sshcrack.mc_talking.network.AiStatus;
import me.sshcrack.mc_talking.util.AiStatusHelper;
import java.util.stream.Collectors;
import me.sshcrack.mc_talking.internal.audio.SpeechTimeline;

/**
 * Orchestrates a citizen-to-citizen conversation.
 *
 * <p>Depending on {@link ConversationMode} (read from config at construction time):</p>
 * <ul>
 *   <li>{@link ConversationMode#FLASH_TTS} – generates a script with Flash and renders it
 *       through the multi-speaker TTS API (original pipeline).</li>
 *   <li>{@link ConversationMode#LIVE_WEBSOCKETS} – opens one {@link LiveConversationWsClient}
 *       per citizen; the clients feed their audio output to each other in real time.</li>
 * </ul>
 */
public class CitizenConversation {
    private final List<AbstractEntityCitizen> participants;
    private final AtomicReference<ConversationState> state = new AtomicReference<>(ConversationState.GENERATING);
    private final MinecraftServer server;
    private final ConversationMode mode;

    /**
     * Only used in FLASH_TTS mode.
     */
    private GeminiStream stream;
    private volatile UUID flashPlaybackTurnId;
    /** Flash/TTS uses one mixed channel; keep that channel on the moving group centroid. */
    private volatile LocationalAudioChannel locationalChannel;

    /**
     * Only used in LIVE_WEBSOCKETS mode.
     */
    private volatile List<LiveConversationWsClient> liveClients = List.of();
    private volatile Runnable liveAbort = () -> { };

    private Consumer<ConversationState> onStateChanged;

    /**
     * Shared terminal flag used when this pair conversation is cancelled or a player takes over one
     * of the participants via {@link me.sshcrack.mc_talking.item.CitizenTalkingDevice}.
     */
    private final ConversationCancellation cancellation = new ConversationCancellation();

    public enum ConversationState {
        GENERATING,
        PLAYING_AUDIO,
        ENDED
    }

    public CitizenConversation(MinecraftServer server, List<AbstractEntityCitizen> participants) {
        if (participants.isEmpty()) {
            throw new IllegalArgumentException("Conversation must have at least one participant");
        }

        this.participants = participants;
        this.server = server;
        this.mode = McTalkingConfig.INSTANCE.instance().conversationMode;

        McTalking.LOGGER.info("Starting conversation [{}] for participants: {}", mode,
                participants.stream().map(c -> c.getName().getString()).toList());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Kicks off the conversation asynchronously.
     * The conversation state will be updated via the {@link #setOnStateChanged} callback.
     */
    public void performConversation() {
        if (cancellation.isCancelled()) return;
        // Ambient speech budget: a citizen-to-citizen conversation counts as one ambient event for
        // every nearby player who would hear either participant, charged once here at the single
        // entry point that actually starts a pair conversation (regardless of Flash/TTS vs. Live
        // WebSocket mode, or whether one falls back to the other).
        AbstractEntityCitizen first = participants.get(0);
        AbstractEntityCitizen second = participants.size() > 1 ? participants.get(1) : null;
        if (!AmbientSpeechBudget.trySpend(first, second)) {
            setState(ConversationState.ENDED);
            return;
        }
        // The pair keeps the floor between lines too, so nobody nearby cuts in while the next one generates.
        SpeechFloor.hold(this, ConversationKind.CITIZEN_PAIR, participants);
        switch (mode) {
            case AUTO -> performAutoConversation();
            case FLASH_TTS -> performFlashTtsConversation();
            case LIVE_WEBSOCKETS -> performLiveWebsocketConversation();
        }
    }

    /** Whether the conversation is over or was aborted. */
    public boolean isEnded() {
        return state.get() == ConversationState.ENDED || cancellation.isCancelled();
    }

    public void setOnStateChanged(Consumer<ConversationState> callback) {
        this.onStateChanged = callback;
    }

    /**
     * Called by {@link me.sshcrack.mc_talking.ConversationManager} when a player
     * starts a conversation with a participant of this Flash/TTS conversation.
     * Prevents the background thread from setting {@link ConversationState#ENDED},
     * which would interfere with the player conversation's AiStatus.
     */
    public void abort() {
        cancellation.cancel();
        if (stream != null) {
            UUID turnId = flashPlaybackTurnId;
            if (turnId != null) stream.cancelTurn(turnId);
            stream.close();
        }
        liveAbort.run();
        for (LiveConversationWsClient client : liveClients) {
            try { client.close(); } catch (RuntimeException ignored) { }
        }
    }

    // -------------------------------------------------------------------------
    // Flash + TTS mode (original pipeline)
    // -------------------------------------------------------------------------

    private void performFlashTtsConversation() {
        performFlashTtsConversation(null);
    }

    private void performFlashTtsConversation(Runnable fallback) {
        if (!server.isSameThread()) {
            server.execute(() -> performFlashTtsConversation(fallback));
            return;
        }
        if (cancellation.isCancelled()) return;

        // Guard: all participants must be able to speak
        for (AbstractEntityCitizen p : participants) {
            if (!ConversationManager.canCitizenSpeak(p, ConversationKind.CITIZEN_PAIR)) {
                setState(ConversationState.ENDED);
                return;
            }
        }

        if (stream == null) {
            var channel = constructLocationalAudioChannel();
            if (channel == null) {
                McTalking.LOGGER.error("[Flash/TTS] Failed to create locational audio channel — aborting conversation");
                setState(ConversationState.ENDED);
                return;
            }
            locationalChannel = channel;
            stream = new GeminiStream(channel);
            stream.setTimeline(SpeechTimeline.tracker(participants.get(0),
                    participants.stream().map(p -> p.getDisplayName().getString())
                            .collect(Collectors.joining(" & ")), () -> "CITIZEN_PAIR"));
        }
        UUID playbackTurnId = UUID.randomUUID();
        flashPlaybackTurnId = playbackTurnId;
        stream.beginTurn(playbackTurnId);

        // Claim exact-ownership activity reservations so delayed cleanup from this
        // conversation cannot release newer work for the same citizen. The timeout is
        // a final safety net for a Flash/TTS generation that never returns.
        List<ConversationManager.CoreActivityReservation> activityReservations = new ArrayList<>();
        AtomicBoolean activityTimeoutHandled = new AtomicBoolean(false);
        Runnable timeout = () -> {
            if (!activityTimeoutHandled.compareAndSet(false, true)) return;
            abort();
            server.execute(() -> {
                for (AbstractEntityCitizen participant : participants) {
                    if (ConversationManager.getPlayerForEntity(participant.getUUID()) == null) {
                        AiStatusHelper.setAiStatusSynced(participant, AiStatus.NONE);
                    }
                }
                setState(ConversationState.ENDED);
            });
        };
        for (AbstractEntityCitizen p : participants) {
            ConversationManager.CoreActivityReservation reservation = ConversationManager.reserveCoreActivity(
                    p, 10, TimeUnit.MINUTES, timeout, this::abort);
            if (reservation == null) {
                for (ConversationManager.CoreActivityReservation acquired : activityReservations) {
                    acquired.close();
                }
                setState(ConversationState.ENDED);
                return;
            }
            activityReservations.add(reservation);
        }

        Map<UUID, String> interestedParties = new HashMap<>();
        for (AbstractEntityCitizen participant : participants) {
            interestedParties.put(participant.getUUID(), participant.getCitizenData().getName());
        }
        List<CitizenConversationGenerator.PromptParticipant> promptParticipants = new ArrayList<>();
        for (AbstractEntityCitizen participant : participants) {
            promptParticipants.add(new CitizenConversationGenerator.PromptParticipant(
                    participant.getUUID(),
                    CitizenPromptViewFactory.create(participant.getCitizenData(), interestedParties, null)
            ));
        }
        List<CitizenConversationGenerator.PromptParticipant> immutablePromptParticipants =
                List.copyOf(promptParticipants);

        new Thread(() -> {
            setState(ConversationState.GENERATING);
            boolean fallbackTriggered = false;

            try {
                AtomicBoolean playbackStarted = new AtomicBoolean(false);
                String completedTranscript = CitizenConversationGenerator.generateConversation(
                        immutablePromptParticipants,
                        chunk -> {
                            if (playbackStarted.compareAndSet(false, true)) {
                                setState(ConversationState.PLAYING_AUDIO);
                            }
                            stream.addGeminiPcmWithPitch(playbackTurnId, chunk.audioBytes(), chunk.sampleRate());
                        });

                // The websocket client flushes its stream when generation completes,
                // but the Flash/TTS path is synchronous and has to do it explicitly.
                // Without this, a short final chunk can remain buffered forever.
                stream.flushAudio(playbackTurnId);

                long playbackDeadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
                long nextLocationUpdate = 0L;
                while (!cancellation.isCancelled() && stream.hasPendingPlayback() && System.nanoTime() < playbackDeadline) {
                    long now = System.nanoTime();
                    if (now >= nextLocationUpdate) {
                        nextLocationUpdate = now + TimeUnit.MILLISECONDS.toNanos(100);
                        server.execute(this::updateLocationalAudioChannel);
                    }
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ConversationGenerationException("Conversation playback was interrupted", interrupted);
                    }
                }

                boolean playbackCompleted = !stream.hasPendingPlayback();
                if (!cancellation.isCancelled() && !playbackCompleted) {
                    McTalking.LOGGER.warn("[Flash/TTS] Playback did not drain before timeout; stopping stream");
                    stream.stop();
                }

                // Only spend the optional extra Flash request on memories after the
                // generated conversation was actually heard to completion. Aborted or
                // timed-out playback must not create memories for unheard dialogue.
                if (!cancellation.isCancelled() && playbackCompleted && playbackStarted.get()) {
                    ConversationEventDispatch.scriptUtterances(participants, completedTranscript);
                }
                if (!cancellation.isCancelled() && playbackCompleted && playbackStarted.get()
                        && McTalkingConfig.INSTANCE.instance().enableConversationSummaryAndMemorize) {
                    CitizenMemoryGenerator.addAndGenerateMemory(completedTranscript, participants, server)
                            .scheduleOrSaveMemory();
                }

            } catch (ConversationGenerationException e) {
                McTalking.LOGGER.error("Failed to generate Flash/TTS conversation: {}, cause: {}",
                        e.getMessage(), e.getCause() != null ? e.getCause().getMessage() : "none");
                if (fallback != null && !cancellation.isCancelled()) {
                    fallbackTriggered = true;
                    McTalking.LOGGER.info("[Auto] Flash/TTS failed, falling back to Live WebSockets");
                    if (stream != null) {
                        stream.stop();
                        stream.close();
                    }
                    fallback.run();
                }
            } finally {
                if (stream != null) {
                    try { stream.close(); } catch (RuntimeException ignored) { }
                }
                stream = null;
                locationalChannel = null;
                flashPlaybackTurnId = null;
                for (int i = 0; i < participants.size(); i++) {
                    AbstractEntityCitizen p = participants.get(i);
                    activityReservations.get(i).close();
                    if (!fallbackTriggered && ConversationManager.getPlayerForEntity(p.getUUID()) == null) {
                        ConversationManager.recordCooldown(p);
                        AiStatusHelper.setAiStatusSynced(p, AiStatus.NONE);
                    }
                }

                if (!fallbackTriggered) {
                    setState(ConversationState.ENDED);
                }
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Live WebSocket mode (cheaper)
    // -------------------------------------------------------------------------

    private void performLiveWebsocketConversation() {
        if (!server.isSameThread()) {
            server.execute(this::performLiveWebsocketConversation);
            return;
        }
        if (cancellation.isCancelled()) return;
        if (participants.size() < 2) {
            McTalking.LOGGER.warn("[LiveConv] Need at least 2 participants, got {}. Aborting.", participants.size());
            setState(ConversationState.ENDED);
            return;
        }

        AbstractEntityCitizen citizenA = participants.get(0);
        AbstractEntityCitizen citizenB = participants.get(1);

        // "Already busy" guard: abort if either citizen is already in any session
        if (!ConversationManager.canCitizenSpeak(citizenA, ConversationKind.CITIZEN_PAIR) || !ConversationManager.canCitizenSpeak(citizenB, ConversationKind.CITIZEN_PAIR)) {
            McTalking.LOGGER.info("[LiveConv] One or both citizens can't speak, aborting");
            setState(ConversationState.ENDED);
            return;
        }

        // Capacity check: we need 2 low-priority slots
        if (!ConversationManager.hasLowPriorityCapacity(2)) {
            McTalking.LOGGER.info("[LiveConv] Not enough free slots for citizen-to-citizen conversation, aborting");
            setState(ConversationState.ENDED);
            return;
        }

        // Reserve both low-priority participants with exact ownership handles before creating clients.
        ConversationManager.ForegroundReservation reservationA =
                ConversationManager.reserveAmbientForeground(citizenA, ConversationKind.CITIZEN_PAIR);
        if (reservationA == null) {
            McTalking.LOGGER.warn("[LiveConv] Failed to reserve first participant, aborting");
            setState(ConversationState.ENDED);
            return;
        }
        ConversationManager.ForegroundReservation reservationB =
                ConversationManager.reserveAmbientForeground(citizenB, ConversationKind.CITIZEN_PAIR);
        if (reservationB == null) {
            reservationA.end(ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "paired conversation could not reserve second participant");
            McTalking.LOGGER.warn("[LiveConv] Failed to reserve second participant, aborting");
            setState(ConversationState.ENDED);
            return;
        }

        setState(ConversationState.GENERATING);

        AtomicInteger sharedTurnCounter = new AtomicInteger(0);
        AtomicBoolean cleanupStarted = new AtomicBoolean(false);
        AtomicReference<LiveConversationWsClient> clientARef = new AtomicReference<>();
        AtomicReference<LiveConversationWsClient> clientBRef = new AtomicReference<>();
        Runnable cancelLivePair = () -> {
            if (!cleanupStarted.compareAndSet(false, true)) return;
            LiveConversationWsClient currentA = clientARef.get();
            LiveConversationWsClient currentB = clientBRef.get();
            if (currentA != null) currentA.close();
            if (currentB != null) currentB.close();
            endBoth(reservationA, reservationB, ForegroundSessionRegistry.TerminalReason.CANCELLED,
                    "paired conversation cancelled");
        };
        liveAbort = cancelLivePair;
        if (cancellation.isCancelled()) {
            cancelLivePair.run();
            return;
        }

        Runnable cancelLive = () -> {
            Runnable cleanup = () -> {
                if (!cleanupStarted.compareAndSet(false, true)) return;
                endBoth(reservationA, reservationB, ForegroundSessionRegistry.TerminalReason.CANCELLED,
                        "paired citizen conversation cancelled");
            };
            if (server.isSameThread()) cleanup.run();
            else server.execute(cleanup);
        };
        if (!cancellation.registerLiveCancellation(cancelLive)) return;

        Consumer<LiveConversationWsClient> onClientEnded = client -> {
            if (!cleanupStarted.compareAndSet(false, true)) return;
            cancellation.clearLiveCancellation(cancelLive);
            server.execute(() -> {
                liveAbort = () -> { };
                liveClients = List.of();
                endBoth(reservationA, reservationB, ForegroundSessionRegistry.TerminalReason.COMPLETED,
                        "paired citizen conversation completed");
                setState(ConversationState.ENDED);
            });
        };

        var citizenDataA = citizenA.getCitizenData();
        var citizenDataB = citizenB.getCitizenData();
        var viewA = CitizenPromptViewFactory.create(
                citizenDataA,
                Map.of(citizenB.getUUID(), citizenDataB.getName()),
                null
        );
        var viewB = CitizenPromptViewFactory.create(
                citizenDataB,
                Map.of(citizenA.getUUID(), citizenDataA.getName()),
                null
        );

        var basicPromptB = """
                You are about to start a conversation with a fellow citizen %s.
                This is some basic information about them. Talk naturally and according to your feelings.
                %s
                """.formatted(citizenDataA.getName(), PromptRuntime.getBasicCitizenInfoPrompt(viewA));

        LiveConversationWsClient clientA;
        LiveConversationWsClient clientB;
        try {
            clientA = new LiveConversationWsClient(
                    new CitizenEntityAudioProvider(citizenA, McTalkingVoicechatPlugin.CITIZEN_CONVERSATION),
                    citizenA, viewA, sharedTurnCounter, onClientEnded);
            clientARef.set(clientA);

            clientB = new LiveConversationWsClient(
                    new CitizenEntityAudioProvider(citizenB, McTalkingVoicechatPlugin.CITIZEN_CONVERSATION),
                    citizenB, viewB, sharedTurnCounter, onClientEnded, basicPromptB);
            clientBRef.set(clientB);
        } catch (RuntimeException e) {
            cleanupStarted.set(true);
            cancellation.clearLiveCancellation(cancelLive);
            LiveConversationWsClient partialA = clientARef.get();
            LiveConversationWsClient partialB = clientBRef.get();
            if (partialA != null) try { partialA.close(); } catch (Exception ignored) { }
            if (partialB != null) try { partialB.close(); } catch (Exception ignored) { }
            endBoth(reservationA, reservationB, ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "failed to construct paired provider client");
            McTalking.LOGGER.error("[LiveConv] Failed to construct paired Gemini clients", e);
            setState(ConversationState.ENDED);
            return;
        }

        clientA.setPeer(clientB);
        clientB.setPeer(clientA);
        liveClients = List.of(clientA, clientB);
        if (cancellation.isCancelled()) {
            cancelLive.run();
            return;
        }

        boolean attachedA = reservationA.attachClient(clientA);
        boolean attachedB = reservationB.attachClient(clientB);
        if (!attachedA || !attachedB) {
            cleanupStarted.set(true);
            cancellation.clearLiveCancellation(cancelLive);
            if (!attachedA && !clientA.isLifecycleClosed()) clientA.close();
            if (!attachedB && !clientB.isLifecycleClosed()) clientB.close();
            endBoth(reservationA, reservationB, ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "paired client ownership changed before attach");
            setState(ConversationState.ENDED);
            return;
        }
        if (cancellation.isCancelled()) {
            cancelLive.run();
            return;
        }

        clientA.addOnCloseAction(onProviderClosed(clientA, reservationA, reservationB, cleanupStarted, cancelLive));
        clientB.addOnCloseAction(onProviderClosed(clientB, reservationB, reservationA, cleanupStarted, cancelLive));

        if (cancellation.isCancelled()) {
            cancelLivePair.run();
            return;
        }
        try {
            clientA.connect();
            clientB.connect();
        } catch (RuntimeException e) {
            cleanupStarted.set(true);
            endBoth(reservationA, reservationB, ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "failed to connect paired provider session");
            McTalking.LOGGER.error("[LiveConv] Failed to connect paired Gemini sessions", e);
            setState(ConversationState.ENDED);
            return;
        }

        if (!reservationA.activate() || !reservationB.activate()) {
            cleanupStarted.set(true);
            cancellation.clearLiveCancellation(cancelLive);
            endBoth(reservationA, reservationB, ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "paired ownership changed before activation");
            setState(ConversationState.ENDED);
            return;
        }

        // Kick off the dialogue from A's side
        if (cancellation.isCancelled()) {
            cancelLive.run();
            return;
        }

        clientA.addPromptTextAfterTalkingComplete(
                "Start the conversation! You are talking to a fellow " + citizenDataB.getName()
                        + " basic information about them:" + PromptRuntime.getBasicCitizenInfoPrompt(viewB));

        setState(ConversationState.PLAYING_AUDIO);
    }

    private static void endBoth(ConversationManager.ForegroundReservation a, ConversationManager.ForegroundReservation b,
                                ForegroundSessionRegistry.TerminalReason reason, String detail) {
        a.end(reason, detail);
        b.end(reason, detail);
    }

    /** When one side's provider session ends on its own, end both sides; the peer ends as a provider failure. */
    private Runnable onProviderClosed(LiveConversationWsClient client, ConversationManager.ForegroundReservation own,
                                      ConversationManager.ForegroundReservation peer, AtomicBoolean cleanupStarted,
                                      Runnable cancelLive) {
        return () -> server.execute(() -> {
            if (!cleanupStarted.compareAndSet(false, true)) return;
            cancellation.clearLiveCancellation(cancelLive);
            var diagnostic = client.getRecoveryDiagnostic();
            own.end(diagnostic.terminalReason() == ProviderRecoveryController.TerminalReason.RECOVERY_EXHAUSTED
                            ? ForegroundSessionRegistry.TerminalReason.RECOVERY_EXHAUSTED
                            : ForegroundSessionRegistry.TerminalReason.PROVIDER_FAILURE,
                    diagnostic.detail());
            peer.end(ForegroundSessionRegistry.TerminalReason.PROVIDER_FAILURE,
                    "peer provider session terminated: " + diagnostic.detail());
            setState(ConversationState.ENDED);
        });
    }

    // -------------------------------------------------------------------------
    // Auto mode (try Flash+TTS, fall back to Live WebSockets)
    // -------------------------------------------------------------------------

    private void performAutoConversation() {
        if (cancellation.isCancelled()) return;
        if (TtsQuotaManager.isTtsFailed()) {
            McTalking.LOGGER.info("[Auto] TTS previously failed, skipping directly to Live WebSockets");
            performLiveWebsocketConversation();
            return;
        }

        performFlashTtsConversation(() -> server.execute(() -> {
            McTalking.LOGGER.info("[Auto] Running fallback Live WebSockets conversation");
            performLiveWebsocketConversation();
        }));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void updateLocationalAudioChannel() {
        LocationalAudioChannel channel = locationalChannel;
        if (channel == null) return;

        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        int count = 0;
        for (AbstractEntityCitizen citizen : participants) {
            if (citizen == null || citizen.isRemoved()) continue;
            x += citizen.getX();
            y += citizen.getY();
            z += citizen.getZ();
            count++;
        }
        if (count == 0) return;
        channel.updateLocation(VoicechatAccess.require().createPosition(x / count, y / count + 1.5, z / count));
    }

    private LocationalAudioChannel constructLocationalAudioChannel() {
        Vec3 sum = Vec3.ZERO;
        Vec3 minPos = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 maxPos = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);

        for (AbstractEntityCitizen citizen : participants) {
            double x = citizen.getX();
            double y = citizen.getY();
            double z = citizen.getZ();
            sum = sum.add(x, y, z);
            maxPos = new Vec3(Math.max(maxPos.x, x), Math.max(maxPos.y, y), Math.max(maxPos.z, z));
            minPos = new Vec3(Math.min(minPos.x, x), Math.min(minPos.y, y), Math.min(minPos.z, z));
        }

        Vec3 avg = sum.scale(1.0d / participants.size());
        //noinspection SequencedCollectionMethodCanBeUsed
        ServerLevel level = (ServerLevel) participants.get(0).level();

        UUID channelId = UUID.randomUUID();
        var vcApi = VoicechatAccess.require();
        var vcLevel = vcApi.fromServerLevel(level);
        var avgPos = vcApi.createPosition(avg.x, avg.y, avg.z);

        var channel = vcApi.createLocationalAudioChannel(channelId, vcLevel, avgPos);
        if (channel == null) {
            McTalking.LOGGER.error("Failed to create locational audio channel for conversation!");
            return null;
        }

        float maxDistance = (float) maxPos.subtract(minPos).length();
        channel.setDistance(Math.max(maxDistance, channel.getDistance()));
        channel.setCategory(McTalkingVoicechatPlugin.CITIZEN_CONVERSATION);
        return channel;
    }

    private void setState(ConversationState newState) {
        ConversationState previous = state.getAndSet(newState);
        if (previous == newState) return;
        if (newState == ConversationState.ENDED) SpeechFloor.release(this);
        McTalking.LOGGER.info("Conversation state changed to {}", newState);
        if (onStateChanged != null) {
            onStateChanged.accept(newState);
        }
    }
}
