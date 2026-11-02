package me.sshcrack.mc_talking;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.conversation.AmbientLineResult;
import me.sshcrack.mc_talking.api.conversation.ControlledAudioAnchor;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.api.prompt.PromptSessionContext;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.internal.session.AmbientSpeechBudget;
import me.sshcrack.mc_talking.internal.session.ConversationEventDispatch;
import me.sshcrack.mc_talking.internal.session.ForegroundSessionRegistry;
import me.sshcrack.mc_talking.internal.session.RecentAmbientLines;
import me.sshcrack.mc_talking.manager.CitizenWsClient;
import me.sshcrack.mc_talking.manager.audio.ControlledTurnAudioProvider;
import me.sshcrack.mc_talking.util.MumblingTopicHelper;
import me.sshcrack.mc_talking.util.UrgentContactPrompts;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Starts the citizen's one-sided ambient speech: mumbling, addon and system lines, addon-controlled
 * floor turns and urgent announcements. Each start checks eligibility, reserves an ambient
 * foreground slot from {@link ConversationManager}, spends ambient budget where it applies, then
 * wires and activates a {@link CitizenWsClient}. Cancelling a running session stays in
 * {@link ConversationManager}, which owns the session registry.
 */
public final class AmbientSessions {
    private static final RecentAmbientLines RECENT_LINES = new RecentAmbientLines();

    private AmbientSessions() {
    }

    public static void onServerStop() {
        RECENT_LINES.clear();
    }

    /** Mumbles and addon/system lines see what the colony just said, so they do not repeat it. */
    private static boolean avoidsRepeats(ConversationKind kind) {
        return kind == ConversationKind.MUMBLE || kind == ConversationKind.ADDON_AMBIENT;
    }

    private static @Nullable String colonyKey(AbstractEntityCitizen citizen) {
        var data = citizen.getCitizenData();
        if (data == null || data.getColony() == null) return null;
        return data.getColony().getDimension().location() + "#" + data.getColony().getID();
    }

    private static String speakerName(AbstractEntityCitizen citizen) {
        return citizen.getDisplayName().getString();
    }

    private static void rememberLine(AbstractEntityCitizen citizen, String transcript) {
        String key = colonyKey(citizen);
        if (key == null || transcript == null) return;
        String prefix = speakerName(citizen) + ": ";
        StringBuilder spoken = new StringBuilder();
        for (String line : transcript.split("\n")) {
            if (!line.startsWith(prefix)) continue;
            if (!spoken.isEmpty()) spoken.append(' ');
            spoken.append(line.substring(prefix.length()));
        }
        RECENT_LINES.record(key, speakerName(citizen), spoken.toString(), System.currentTimeMillis());
    }

    /**
     * Starts a citizen mumbling to itself when a player is nearby (low-priority).
     *
     * <p>Silently returns if the citizen is already busy or if no low-priority
     * slot is available (pool is full of player conversations).</p>
     */
    public static void startMumbling(AbstractEntityCitizen citizen) {
        if (!McTalkingConfig.hasGeminiApiKey()) return;
        startLowPrioritySession(citizen, MumblingTopicHelper.buildPrompt(citizen), ConversationKind.MUMBLE);
    }

    /**
     * Starts a low-priority, one-sided AI voice session for {@code citizen}.
     *
     * <h4>How it works under the hood</h4>
     * <p>This method opens a <em>Gemini Live WebSocket</em> in system-controlled
     * (mumbling) mode.  The session uses
     * {@code CitizenPromptProvider.generateSystemControlledRoleplayPrompt(...)}
     * as the base system prompt, then appends {@code userPrompt} to that system
     * prompt before sending the first turn.  The model then speaks aloud as the
     * citizen, and the session closes automatically when talking is complete.</p>
     *
     * <h4>Prompt authoring contract — IMPORTANT</h4>
     * <p>{@code userPrompt} is injected <strong>into the system prompt</strong>, not
     * sent as a user chat message.  It must therefore be written as a directive
     * addressed to the AI model — second person, imperative — describing what the
     * citizen should say or do in this turn.  Examples of correct phrasing:</p>
     * <pre>{@code
     * // ✓ Correct — system directive style:
     * "## CURRENT TASK\nTurn to Aldric and mention the food shortage you heard about. One sentence."
     *
     * // ✗ Wrong — reads like a user chat message:
     * "Tell Aldric about this: Rumor: I heard from ... that ..."
     * }</pre>
     * <p>For a reference implementation see
     * {@link me.sshcrack.mc_talking.broadcast.BroadcastPropagationService} (broadcast
     * yelling prompt) and
     * {@link me.sshcrack.mc_talking.rumor.RumorMillService#attemptRumorTalking}.</p>
     *
     * <h4>Guards</h4>
     * <ul>
     *   <li>API key must be configured.</li>
     *   <li>{@link ConversationManager#canCitizenSpeak} must return {@code true} (subsumes busy,
     *       cooldown, sleeping, and visitor checks).</li>
     *   <li>A low-priority foreground reservation must be available.</li>
     * </ul>
     * <p>Silently returns without throwing if any guard fails.</p>
     *
     * @param citizen    the citizen who will speak
     * @param userPrompt a system-prompt addition written as a directive to the AI
     *                   model; see authoring contract above
     */
    public static boolean startLowPrioritySession(AbstractEntityCitizen citizen, String userPrompt) {
        return startLowPrioritySession(citizen, userPrompt, ConversationKind.ADDON_AMBIENT);
    }

    /** Supported internal entry used by the public addon conversation service. */
    public static boolean startAddonAmbientSession(AbstractEntityCitizen citizen, String userPrompt) {
        return startAddonAmbientSession(citizen, userPrompt, null);
    }

    /**
     * Starts one addon-directed ambient line. When supplied, {@code completion} is dispatched on
     * the Minecraft server thread after audible playback has drained, or when the session closes
     * terminally before completing.
     */
    public static boolean startAddonAmbientSession(
            AbstractEntityCitizen citizen,
            String userPrompt,
            @Nullable Consumer<AmbientLineResult> completion
    ) {
        return startAddonAmbientSession(citizen, userPrompt, completion, PromptSessionContext.empty());
    }

    public static boolean startAddonAmbientSession(
            AbstractEntityCitizen citizen,
            String userPrompt,
            @Nullable Consumer<AmbientLineResult> completion,
            PromptSessionContext promptSessionContext
    ) {
        return startAddonAmbientSession(citizen, userPrompt, completion, promptSessionContext, null);
    }

    public static boolean startAddonAmbientSession(
            AbstractEntityCitizen citizen,
            String userPrompt,
            @Nullable Consumer<AmbientLineResult> completion,
            PromptSessionContext promptSessionContext,
            @Nullable ControlledAudioAnchor audioAnchor
    ) {
        return startLowPrioritySession(citizen, userPrompt, ConversationKind.ADDON_AMBIENT, completion,
                promptSessionContext, audioAnchor, null);
    }

    /** Starts an exact addon-controlled floor turn without automatic-conversation cooldown semantics. */
    public static boolean startControlledAmbientSession(
            AbstractEntityCitizen citizen,
            String userPrompt,
            @Nullable Consumer<AmbientLineResult> completion,
            PromptSessionContext promptSessionContext,
            @Nullable UUID authenticatedToolPlayerId,
            @Nullable ServerPlayer boundToolPlayer,
            @Nullable ControlledAudioAnchor audioAnchor,
            int maxOutputTokens
    ) {
        if (maxOutputTokens < 0) throw new IllegalArgumentException("maxOutputTokens must not be negative");
        return startLowPrioritySession(citizen, userPrompt, ConversationKind.CONTROLLED, completion,
                promptSessionContext, audioAnchor, maxOutputTokens == 0 ? null : maxOutputTokens,
                authenticatedToolPlayerId, boundToolPlayer);
    }

    /**
     * Starts the one-sided urgent announcement on an already-owned urgent-contact reservation.
     *
     * <p>The caller owns the reservation lifecycle. This method attaches/activates the provider
     * and reports audible/provider completion, but deliberately does not end the reservation. That
     * lets the urgent-contact lifecycle decide completion versus cancellation and lets an in-place
     * player promotion retain the exact same foreground token.</p>
     */
    public static boolean startUrgentAnnouncement(
            ConversationManager.ForegroundReservation reservation,
            AbstractEntityCitizen citizen,
            String originPlayerName,
            Consumer<AmbientLineResult> completion
    ) {
        return startUrgentAnnouncement(reservation, citizen, originPlayerName, null, completion);
    }

    public static boolean startUrgentAnnouncement(
            ConversationManager.ForegroundReservation reservation,
            AbstractEntityCitizen citizen,
            String originPlayerName,
            @Nullable UUID originPlayerId,
            Consumer<AmbientLineResult> completion
    ) {
        java.util.Objects.requireNonNull(reservation, "reservation");
        java.util.Objects.requireNonNull(citizen, "citizen");
        java.util.Objects.requireNonNull(originPlayerName, "originPlayerName");
        java.util.Objects.requireNonNull(completion, "completion");
        if (!McTalkingConfig.hasGeminiApiKey()
                || !reservation.citizenId().equals(citizen.getUUID())
                || !reservation.isCurrent()) {
            return false;
        }

        AtomicBoolean startupCommitted = new AtomicBoolean(false);
        AtomicBoolean completionDelivered = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<AmbientLineResult> earlyResult =
                new java.util.concurrent.atomic.AtomicReference<>();

        Consumer<AmbientLineResult> report = result -> ConversationEventDispatch.runOnServerThread(citizen, () -> {
            if (!startupCommitted.get()) {
                earlyResult.compareAndSet(null, result);
                return;
            }
            if (completionDelivered.compareAndSet(false, true)) completion.accept(result);
        });

        try {
            CitizenWsClient client = new CitizenWsClient(
                    new ControlledTurnAudioProvider(citizen, null),
                    citizen,
                    c -> report.accept(AmbientLineResult.completed(c.getSessionTranscriptSnapshot())),
                    PromptSessionContext.empty(),
                    null
            );

            if (!reservation.attachClient(client)) return false;

            client.addOnCloseAction(() -> {
                var diagnostic = client.getRecoveryDiagnostic();
                report.accept(client.isPlayerTakeoverPending()
                        ? AmbientLineResult.cancelled("player conversation preempted urgent announcement")
                        : AmbientLineResult.failed(diagnostic.detail()));
            });

            if (!reservation.activate()) return false;
            client.addPromptTextAfterTalkingComplete(
                    UrgentContactPrompts.build(citizen, originPlayerName, originPlayerId));

            startupCommitted.set(true);
            AmbientLineResult early = earlyResult.getAndSet(null);
            if (early != null) report.accept(early);
            return true;
        } catch (RuntimeException e) {
            McTalking.LOGGER.error("[AmbientSessions] Failed to start urgent announcement for {}",
                    citizen.getUUID(), e);
            return false;
        }
    }

    private static boolean startLowPrioritySession(
            AbstractEntityCitizen citizen,
            String userPrompt,
            ConversationKind kind
    ) {
        return startLowPrioritySession(citizen, userPrompt, kind, null, PromptSessionContext.empty(), null, null);
    }

    private static boolean startLowPrioritySession(
            AbstractEntityCitizen citizen,
            String userPrompt,
            ConversationKind kind,
            @Nullable Consumer<AmbientLineResult> completion
    ) {
        return startLowPrioritySession(citizen, userPrompt, kind, completion, PromptSessionContext.empty(),
                null, null, null, null);
    }

    private static boolean startLowPrioritySession(
            AbstractEntityCitizen citizen,
            String userPrompt,
            ConversationKind kind,
            @Nullable Consumer<AmbientLineResult> completion,
            PromptSessionContext promptSessionContext,
            @Nullable ControlledAudioAnchor audioAnchor,
            @Nullable Integer maxOutputTokens
    ) {
        return startLowPrioritySession(citizen, userPrompt, kind, completion, promptSessionContext,
                audioAnchor, maxOutputTokens, null, null);
    }

    private static boolean startLowPrioritySession(
            AbstractEntityCitizen citizen,
            String userPrompt,
            ConversationKind kind,
            @Nullable Consumer<AmbientLineResult> completion,
            PromptSessionContext promptSessionContext,
            @Nullable ControlledAudioAnchor audioAnchor,
            @Nullable Integer maxOutputTokens,
            @Nullable UUID authenticatedToolPlayerId,
            @Nullable ServerPlayer boundToolPlayer
    ) {
        if (!McTalkingConfig.hasGeminiApiKey()) return false;
        if (!ConversationManager.canCitizenSpeak(citizen, kind)) return false;

        UUID citizenId = citizen.getUUID();
        ConversationManager.ForegroundReservation reservation = ConversationManager.reserveAmbientForeground(
                citizen, kind, promptSessionContext.sessionId(), promptSessionContext.turnId());
        if (reservation == null) {
            McTalking.LOGGER.debug("[AmbientSessions] No low-priority slot available for session for citizen {}", citizenId);
            return false;
        }

        if (AmbientSpeechBudget.appliesTo(kind) && !AmbientSpeechBudget.trySpend(citizen)) {
            reservation.end(ForegroundSessionRegistry.TerminalReason.CANCELLED, "ambient speech budget exhausted");
            return false;
        }

        try {
            AtomicBoolean audibleCompletion = new AtomicBoolean(false);
            AtomicBoolean completionDelivered = new AtomicBoolean(false);
            CitizenWsClient client = new CitizenWsClient(new ControlledTurnAudioProvider(citizen, audioAnchor), citizen, c -> {
                String transcript = c.getSessionTranscriptSnapshot();
                ConversationEventDispatch.runOnServerThread(citizen, () -> {
                    if (avoidsRepeats(kind)) rememberLine(citizen, transcript);
                    audibleCompletion.set(true);
                    if (!reservation.end(ForegroundSessionRegistry.TerminalReason.COMPLETED,
                            "ambient audible turn completed")) return;
                    if (completion != null && completionDelivered.compareAndSet(false, true)) {
                        completion.accept(AmbientLineResult.completed(transcript));
                    }
                });
            }, promptSessionContext, maxOutputTokens, authenticatedToolPlayerId, boundToolPlayer);

            if (!reservation.attachClient(client)) {
                reservation.end(ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                        "foreground ownership changed before client attach");
                return false;
            }

            client.addOnCloseAction(() -> ConversationEventDispatch.runOnServerThread(citizen, () -> {
                var diagnostic = client.getRecoveryDiagnostic();
                reservation.end(ConversationManager.providerTerminalReason(client), diagnostic.detail());
                if (completion != null && !audibleCompletion.get()
                        && completionDelivered.compareAndSet(false, true)) {
                    completion.accept(client.isPlayerTakeoverPending()
                            ? AmbientLineResult.cancelled("player conversation preempted controlled turn")
                            : AmbientLineResult.failed(diagnostic.detail()));
                }
            }));

            if (!reservation.activate()) {
                reservation.end(ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                        "foreground ownership changed before activation");
                return false;
            }
            String prompt = userPrompt;
            String key = avoidsRepeats(kind) ? colonyKey(citizen) : null;
            if (key != null) {
                prompt += RecentAmbientLines.promptSection(
                        RECENT_LINES.recent(key, speakerName(citizen), System.currentTimeMillis()));
            }
            client.addPromptTextAfterTalkingComplete(prompt);
            return true;
        } catch (RuntimeException e) {
            reservation.end(ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "failed to start low-priority provider session: " + e.getClass().getSimpleName());
            McTalking.LOGGER.error("[AmbientSessions] Failed to start low-priority session for {}", citizenId, e);
            return false;
        }
    }
}
