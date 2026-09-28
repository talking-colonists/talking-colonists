package me.sshcrack.mc_talking;

import me.sshcrack.mc_talking.internal.session.AddressCooldowns;
import me.sshcrack.mc_talking.api.conversation.ConversationUtteranceEvent;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.visitor.VisitorCitizen;
import me.sshcrack.gemini_live_lib.GeminiLiveClient;
import me.sshcrack.mc_talking.api.conversation.ConversationEligibility;
import me.sshcrack.mc_talking.api.conversation.ConversationLifecycleEvent;
import me.sshcrack.mc_talking.api.conversation.PlayerConversationOptions;
import me.sshcrack.mc_talking.api.conversation.ConversationStartResult;
import me.sshcrack.mc_talking.internal.api.ConversationEventRuntime;
import me.sshcrack.mc_talking.internal.api.ConversationRuleRuntime;
import me.sshcrack.mc_talking.internal.session.AmbientSpeechBudget;
import me.sshcrack.mc_talking.internal.session.ConversationEventDispatch;
import me.sshcrack.mc_talking.internal.session.BackgroundSessionRegistry;
import me.sshcrack.mc_talking.internal.session.CitizenActivityRegistry;
import me.sshcrack.mc_talking.internal.session.ConversationCooldownRegistry;
import me.sshcrack.mc_talking.internal.session.ConversationParticipationModule;
import me.sshcrack.mc_talking.internal.session.DefaultConversationParticipationModule;
import me.sshcrack.mc_talking.internal.session.ForegroundSessionRegistry;
import me.sshcrack.mc_talking.internal.session.MinecraftConversationParticipationAdapter;
import me.sshcrack.mc_talking.internal.session.ProviderRecoveryController;
import me.sshcrack.mc_talking.internal.session.SpeechFloor;
import me.sshcrack.mc_talking.broadcast.PlayerWords;
import me.sshcrack.mc_talking.handler.UrgentContactHandler;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.conversations.complaints.Complaints;
import me.sshcrack.mc_talking.config.QuotaTracker;
import me.sshcrack.mc_talking.item.CitizenTalkingDevice;
import me.sshcrack.mc_talking.manager.CitizenWsClient;
import me.sshcrack.mc_talking.manager.GeminiWsClient;
import me.sshcrack.mc_talking.manager.audio.CitizenEntityAudioProvider;
import me.sshcrack.mc_talking.util.BackgroundSlotType;
import me.sshcrack.mc_talking.util.CitizenNeedAssessor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/*? if neoforge {*/
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.core.component.DataComponents;
/*? }*/
/*? if forge {*/
/*import net.minecraft.nbt.CompoundTag;
 *//*? }*/

/**
 * Manages conversations between players and citizens, including tracking
 * active conversations, entity focus, and handling conversation lifecycle.
 *
 * <h2>Slot priority</h2>
 * <ul>
 *   <li><b>High-priority (player conversations)</b>: evict the oldest
 *       <em>non-player</em> session when needed, but never kick another player
 *       conversation. If every slot belongs to a player the request is rejected.</li>
 *   <li><b>Low-priority (mumbles, citizen-to-citizen)</b>: rejected when no
 *       free or evictable non-player slot is available, so they can never
 *       displace a player.</li>
 * </ul>
 *
 * <h2>"Already busy" guard</h2>
 * Every entry point checks {@link #isCitizenBusy} before creating a new
 * session, so a citizen that is already mumbling, in a player conversation, or
 * in a citizen-to-citizen conversation will never be started again.
 */
public class ConversationManager {
    private ConversationManager() { /* utility class */ }

    private static final long ORPHAN_FOREGROUND_SLOT_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final long PREGEN_BACKGROUND_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(3);
    private static final long COMPACTION_BACKGROUND_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10);

    /** Citizen sessions, busy leases, cooldowns, and background work each have one token-owned registry. */
    private static final ForegroundSessionRegistry<AbstractEntityCitizen, GeminiWsClient> foregroundSessions =
            new ForegroundSessionRegistry<>(
                    () -> McTalkingConfig.INSTANCE.instance().maxConcurrentAgents,
                    System::nanoTime,
                    GeminiWsClient::close,
                    GeminiWsClient::isLifecycleClosed,
                    ConversationManager::onForegroundTerminated
            );
    private static final ConversationParticipationModule conversationParticipation =
            new DefaultConversationParticipationModule(citizenId -> foregroundSessions.snapshot(citizenId)
                    .map(snapshot -> new ConversationParticipationModule.Ownership(
                            snapshot.token(), snapshot.playerId()))
                    .orElse(null));
    /** Addon purpose tags of running player conversations, keyed by foreground ownership ID. */
    private static final Map<UUID, String> sessionPurposes = new ConcurrentHashMap<>();
    private static final Map<UUID, MinecraftConversationParticipationAdapter> participationAdapters =
            new ConcurrentHashMap<>();
    private static final CitizenActivityRegistry activities = new CitizenActivityRegistry(System::nanoTime);
    private static final ConversationCooldownRegistry cooldowns = new ConversationCooldownRegistry(System::currentTimeMillis);
    private static final BackgroundSessionRegistry<GeminiLiveClient> backgroundSessions =
            new BackgroundSessionRegistry<>(
                    () -> McTalkingConfig.INSTANCE.instance().maxConcurrentBackground,
                    System::nanoTime,
                    type -> type == BackgroundSlotType.COMPACTION
                            ? COMPACTION_BACKGROUND_TIMEOUT_NANOS
                            : PREGEN_BACKGROUND_TIMEOUT_NANOS,
                    GeminiLiveClient::close,
                    GeminiLiveClient::isClosed
            );


    /** Exact-ownership handle for internal non-WebSocket citizen activity. */
    public static final class CoreActivityReservation implements AutoCloseable {
        private final CitizenActivityRegistry.Token token;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private CoreActivityReservation(CitizenActivityRegistry.Token token) {
            this.token = token;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            activities.release(token.citizenId(), token.ownershipId(), token.ownerKind());
        }
    }

    /** Exact token-owned foreground capacity/client reservation used by core conversation flows. */
    public static final class ForegroundReservation implements AutoCloseable {
        private final ForegroundSessionRegistry.Token token;
        private final MinecraftConversationParticipationAdapter participation;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean lifecycleStarted = new AtomicBoolean(false);

        private ForegroundReservation(
                ForegroundSessionRegistry.Token token,
                MinecraftConversationParticipationAdapter participation
        ) {
            this.token = token;
            this.participation = participation;
        }

        public UUID citizenId() {
            return token.citizenId();
        }

        public boolean attachClient(GeminiWsClient client) {
            // Bind ownership before any rejection path can close the client. A late rejected close
            // must not fall back to an unowned NONE write that could erase a replacement session.
            client.bindConversationParticipation(participation);
            if (closed.get()) {
                client.close();
                return false;
            }
            if (!foregroundSessions.attachClient(token, client)) return false;
            client.addRecoveryObserver(diagnostic -> {
                if (!lifecycleStarted.get() || !foregroundSessions.isCurrent(token)) return;
                if (diagnostic.state() == ProviderRecoveryController.State.RECOVERING) {
                    foregroundSessions.markRecovering(token, diagnostic.totalRecoveryAttempts(), diagnostic.detail());
                } else if (diagnostic.state() == ProviderRecoveryController.State.ACTIVE) {
                    foregroundSessions.markActive(token, diagnostic.detail());
                }
            });
            return true;
        }

        public boolean activate() {
            if (closed.get() || !foregroundSessions.markActive(token, "conversation active")) return false;
            if (lifecycleStarted.compareAndSet(false, true)) {
                foregroundSessions.snapshot(token.citizenId()).ifPresent(snapshot ->
                        dispatchLifecycleStarted(snapshot.entity(), snapshot.kind(), snapshot.playerId(), snapshot.sessionId(), snapshot.turnId(),
                                sessionPurposes.get(token.ownershipId())));
            }
            return true;
        }

        public boolean isCurrent() {
            return !closed.get() && foregroundSessions.isCurrent(token);
        }

        public void markUrgentWalking(boolean active) {
            participation.urgentWalking(active);
        }

        public boolean end(ForegroundSessionRegistry.TerminalReason reason, String detail) {
            closed.set(true);
            return foregroundSessions.end(token, reason, detail);
        }

        @Override
        public void close() {
            end(ForegroundSessionRegistry.TerminalReason.CANCELLED, "foreground reservation closed");
        }
    }

    /** Ownership handle for one bounded background task. */
    public static final class BackgroundReservation implements AutoCloseable {
        private final BackgroundSessionRegistry.Token token;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private BackgroundReservation(BackgroundSessionRegistry.Token token) {
            this.token = token;
        }

        public boolean attachClient(GeminiLiveClient client) {
            if (closed.get()) {
                closeQuietly(client, "late background client", token.citizenId());
                return false;
            }
            return backgroundSessions.attach(token, client);
        }

        public boolean isActive() {
            return !closed.get() && backgroundSessions.isActive(token);
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            backgroundSessions.release(token);
        }
    }

    // -------------------------------------------------------------------------
    // Token-owned foreground/background reservations
    // -------------------------------------------------------------------------

    /** Reserves one low-priority foreground session. Ambient work never evicts active work. */
    public static ForegroundReservation reserveAmbientForeground(
            AbstractEntityCitizen citizen,
            ConversationKind kind
    ) {
        return reserveAmbientForeground(citizen, kind, null, null);
    }

    static ForegroundReservation reserveAmbientForeground(
            AbstractEntityCitizen citizen,
            ConversationKind kind,
            @Nullable UUID sessionId,
            @Nullable UUID turnId
    ) {
        java.util.Objects.requireNonNull(citizen, "citizen");
        java.util.Objects.requireNonNull(kind, "kind");
        UUID citizenId = citizen.getUUID();
        backgroundSessions.cancel(citizenId);
        if (activities.isBusy(citizenId)) return null;
        var reservation = foregroundSessions.reserve(
                citizenId, citizen, kind, ForegroundSessionRegistry.Priority.AMBIENT, null, sessionId, turnId);
        if (!reservation.granted()) return null;
        McTalking.LOGGER.info("[ConversationManager] Reserved ambient foreground session for {} ({})", citizenId, kind);
        return new ForegroundReservation(reservation.token(), registerParticipation(citizen, reservation.token()));
    }

    private static ForegroundReservation reservePlayerForeground(
            AbstractEntityCitizen citizen,
            UUID playerId
    ) {
        UUID citizenId = citizen.getUUID();
        backgroundSessions.cancel(citizenId);
        var reservation = foregroundSessions.reserve(
                citizenId, citizen, ConversationKind.PLAYER, ForegroundSessionRegistry.Priority.PLAYER, playerId);
        if (!reservation.granted()) return null;
        McTalking.LOGGER.info("[ConversationManager] Reserved player foreground session for {} / {}", citizenId, playerId);
        return new ForegroundReservation(reservation.token(), registerParticipation(citizen, reservation.token()));
    }

    private static MinecraftConversationParticipationAdapter registerParticipation(
            AbstractEntityCitizen citizen,
            ForegroundSessionRegistry.Token token
    ) {
        conversationParticipation.register(token);
        var adapter = new MinecraftConversationParticipationAdapter(citizen, token, conversationParticipation);
        participationAdapters.put(token.ownershipId(), adapter);
        return adapter;
    }

    public static boolean hasLowPriorityCapacity(int slotsNeeded) {
        return foregroundSessions.hasLowPriorityCapacity(slotsNeeded);
    }

    public static boolean hasFreeCapacity(int slotsNeeded) {
        return foregroundSessions.hasLowPriorityCapacity(slotsNeeded);
    }

    public static boolean hasFreeBackgroundCapacity(int slotsNeeded) {
        backgroundSessions.purge();
        return backgroundSessions.hasCapacity(slotsNeeded);
    }

    public static int getUsedForegroundSlots() {
        return foregroundSessions.usedSlots();
    }

    public static int getUsedBackgroundSlots() {
        backgroundSessions.purge();
        return backgroundSessions.size();
    }

    public static int getMaxBackgroundSlots() {
        return McTalkingConfig.INSTANCE.instance().maxConcurrentBackground;
    }

    public static BackgroundReservation reserveBackgroundSlot(
            AbstractEntityCitizen citizen,
            BackgroundSlotType type
    ) {
        return reserveBackgroundSlot(citizen.getUUID(), type);
    }

    /** Background slot keyed by any UUID, e.g. a colony-wide text request with no citizen. Thread-safe. */
    public static BackgroundReservation reserveBackgroundSlot(UUID ownerId, BackgroundSlotType type) {
        var token = backgroundSessions.reserve(ownerId, type);
        if (token == null) return null;
        McTalking.LOGGER.info("[ConversationManager] Reserved background slot for {} (type={})", ownerId, type);
        return new BackgroundReservation(token);
    }

    public static boolean cancelBackgroundSlot(UUID entityId, String reason) {
        boolean cancelled = backgroundSessions.cancel(entityId);
        if (cancelled) {
            McTalking.LOGGER.info("[ConversationManager] Released background slot for {} ({})", entityId, reason);
        }
        return cancelled;
    }

    /** Runs bounded lifecycle cleanup independent of new slot requests. */
    public static void tickMaintenance() {
        for (BackgroundSessionRegistry.Expired expired : backgroundSessions.purge()) {
            if (expired.deadlineExceeded()) {
                McTalking.LOGGER.warn("[ConversationManager] Background {} session for {} exceeded its deadline",
                        expired.type(), expired.citizenId());
            }
        }

        int startupTimeouts = foregroundSessions.purgeStartupTimeouts(ORPHAN_FOREGROUND_SLOT_TIMEOUT_NANOS);
        if (startupTimeouts > 0) {
            McTalking.LOGGER.warn("[ConversationManager] Recovered {} foreground startup reservation(s) after timeout", startupTimeouts);
        }

        for (var snapshot : foregroundSessions.snapshots()) {
            AbstractEntityCitizen entity = snapshot.entity();
            if (!entity.isAlive() || entity.isRemoved()) {
                foregroundSessions.end(snapshot.token(), ForegroundSessionRegistry.TerminalReason.ENTITY_UNAVAILABLE,
                        "citizen unloaded, removed, or died");
            }
        }

        for (Runnable timeoutAction : activities.purgeExpired()) {
            try {
                timeoutAction.run();
            } catch (Throwable t) {
                McTalking.LOGGER.error("[ConversationManager] Core activity timeout cleanup failed", t);
            }
        }
    }

    private static void closeQuietly(GeminiLiveClient client, String reason, UUID entityId) {
        if (client == null) return;
        try {
            client.close();
        } catch (Exception e) {
            McTalking.LOGGER.warn("[ConversationManager] Error closing background client {} ({})", entityId, reason, e);
        }
    }

    // -------------------------------------------------------------------------
    // Debug / inspection accessors
    // -------------------------------------------------------------------------

    public static Map<UUID, GeminiWsClient> getClients() {
        return foregroundSessions.clientsSnapshot();
    }

    public static Map<UUID, UUID> getCitizenToPlayer() {
        return foregroundSessions.citizenToPlayerSnapshot();
    }

    public static Map<UUID, UUID> getPlayerConversationPartners() {
        return foregroundSessions.playerToCitizenSnapshot();
    }

    public static Map<UUID, Long> getLastSessionEndTimes() {
        return cooldowns.endTimesSnapshot();
    }

    public static List<ForegroundSessionRegistry.TerminalDiagnostic> getForegroundTerminalHistory() {
        return foregroundSessions.terminalHistory();
    }

    // -------------------------------------------------------------------------
    // Busy ownership and cooldowns
    // -------------------------------------------------------------------------

    public static CoreActivityReservation reserveCoreActivity(
            AbstractEntityCitizen citizen,
            long timeout,
            TimeUnit unit,
            Runnable onTimeout,
            Runnable onPreempt
    ) {
        java.util.Objects.requireNonNull(citizen, "citizen");
        java.util.Objects.requireNonNull(unit, "unit");
        java.util.Objects.requireNonNull(onTimeout, "onTimeout");
        java.util.Objects.requireNonNull(onPreempt, "onPreempt");
        if (timeout <= 0) throw new IllegalArgumentException("timeout must be positive");

        UUID citizenId = citizen.getUUID();
        CitizenActivityRegistry.Token token = activities.reserveCore(
                citizenId, foregroundSessions.isBusy(citizenId), unit.toNanos(timeout), onTimeout, onPreempt);
        return token == null ? null : new CoreActivityReservation(token);
    }

    private static Runnable removeActivityForPlayerPreemption(UUID citizenId) {
        return activities.preemptForPlayer(citizenId);
    }

    private static final AddressCooldowns addressCooldowns = new AddressCooldowns(System::currentTimeMillis);

    /** How recently citizens addressed each player unprompted, and sought players out. */
    public static AddressCooldowns addressCooldowns() {
        return addressCooldowns;
    }

    /** The current foreground session kind of a citizen, for diagnostics. */
    public static String kindLabel(UUID citizenId) {
        ConversationKind kind = foregroundSessions.kind(citizenId);
        if (kind != null) return kind.name();
        return activities.isBusy(citizenId) ? "ACTIVITY" : "NONE";
    }

    public static boolean isCitizenBusy(AbstractEntityCitizen citizen) {
        UUID id = citizen.getUUID();
        return foregroundSessions.isBusy(id) || activities.isBusy(id);
    }

    /**
     * Citizens saying an addon line (a campaign speech, an introduction, a delivery) stand still and face the
     * nearest player while they speak: pausing their AI does not stop a walk already under way. Mumbles
     * may go on while walking. Server thread, every tick.
     */
    public static void holdAddressedSpeakers() {
        if (McTalkingConfig.INSTANCE.instance().continueWorkDuringConversation) return;
        for (var snapshot : foregroundSessions.snapshots()) {
            AbstractEntityCitizen citizen = snapshot.entity();
            if (snapshot.kind() != ConversationKind.ADDON_AMBIENT || citizen.isRemoved()) continue;
            if (!citizen.getNavigation().isDone()) citizen.getNavigation().stop();
            Player nearest = citizen.level().getNearestPlayer(citizen, 12);
            if (nearest != null) citizen.getLookControl().setLookAt(nearest, 30f, 30f);
        }
    }

    /**
     * Whether the citizen's MineColonies routine should wait: it is busy, or a controlled session (such as
     * a campfire circle) holds it between lines, so the night routine does not walk it off to bed.
     */
    public static boolean shouldPauseRoutine(AbstractEntityCitizen citizen) {
        return isCitizenBusy(citizen) || SpeechFloor.holds(citizen.getUUID(), ConversationKind.CONTROLLED);
    }

    public static boolean claimAddonActivity(AbstractEntityCitizen citizen, UUID token, long timeoutNanos) {
        return activities.reserveAddon(citizen.getUUID(), token, foregroundSessions.isBusy(citizen.getUUID()), timeoutNanos);
    }

    public static boolean renewAddonActivity(UUID citizenId, UUID token, long timeoutNanos) {
        return activities.renewAddon(citizenId, token, timeoutNanos);
    }

    public static boolean isAddonActivityActive(UUID citizenId, UUID token) {
        return activities.isActive(citizenId, token, CitizenActivityRegistry.OwnerKind.ADDON);
    }

    public static boolean releaseAddonActivity(UUID citizenId, UUID token) {
        return activities.release(citizenId, token, CitizenActivityRegistry.OwnerKind.ADDON);
    }

    public static boolean requestGracefulEnd(AbstractEntityCitizen citizen) {
        GeminiWsClient client = foregroundSessions.client(citizen.getUUID());
        if (client == null) return false;
        client.endConversationWhenPossible();
        return true;
    }

    public static void recordCooldown(AbstractEntityCitizen citizen) {
        cooldowns.record(citizen.getUUID(), computeNeedSignature(citizen));
    }

    public static boolean isCitizenOnCooldown(AbstractEntityCitizen citizen) {
        long cooldownMs = McTalkingConfig.INSTANCE.instance().citizenCooldownSeconds * 1000L;
        return cooldowns.isActive(citizen.getUUID(), computeNeedSignature(citizen), cooldownMs);
    }

    public static void forceRemoveCooldown(AbstractEntityCitizen citizen) {
        cooldowns.reset(citizen.getUUID());
    }

    public static String computeNeedSignature(AbstractEntityCitizen citizen) {
        return CitizenNeedAssessor.computeNeedSignature(citizen);
    }

    private static void dispatchLifecycleStarted(AbstractEntityCitizen citizen, ConversationKind kind,
                                                 @Nullable UUID playerId, @Nullable UUID sessionId,
                                                 @Nullable UUID turnId, @Nullable String purpose) {
        ConversationEventDispatch.lifecycle(ConversationLifecycleEvent.Phase.STARTED, citizen, kind, playerId,
                sessionId, turnId, purpose);
    }

    private static void dispatchLifecycleEnded(AbstractEntityCitizen citizen, ConversationKind kind,
                                               @Nullable UUID playerId, @Nullable UUID sessionId,
                                               @Nullable UUID turnId, @Nullable String purpose) {
        ConversationEventDispatch.lifecycle(ConversationLifecycleEvent.Phase.ENDED, citizen, kind, playerId,
                sessionId, turnId, purpose);
    }

    private static void onForegroundTerminated(
            ForegroundSessionRegistry.EndedSession<AbstractEntityCitizen, GeminiWsClient> ended
    ) {
        var snapshot = ended.snapshot();
        var participation = participationAdapters.remove(snapshot.token().ownershipId());
        if (participation != null) participation.complete();
        String purpose = sessionPurposes.remove(snapshot.token().ownershipId());
        // Provider callbacks may arrive off-thread. Lifecycle dispatch, need inspection for cooldown,
        // inventory/status cleanup, and every other Minecraft-world read happen on the server thread.
        ConversationEventDispatch.runOnServerThread(snapshot.entity(), () -> {
            boolean wasActive = ended.previousState() == ForegroundSessionRegistry.State.ACTIVE
                    || ended.previousState() == ForegroundSessionRegistry.State.RECOVERING;
            if (wasActive) {
                dispatchLifecycleEnded(snapshot.entity(), snapshot.kind(), snapshot.playerId(), snapshot.sessionId(), snapshot.turnId(),
                        purpose);
                if (snapshot.priority() == ForegroundSessionRegistry.Priority.AMBIENT
                        && snapshot.kind() != ConversationKind.CONTROLLED
                        && switch (ended.reason()) {
                            case COMPLETED, CANCELLED, PROVIDER_FAILURE, RECOVERY_EXHAUSTED -> true;
                            default -> false;
                        }) {
                    recordCooldown(snapshot.entity());
                }
            }

            if (snapshot.playerId() != null) {
                schedulePlayerPresentationCleanup(snapshot.entity(), snapshot.playerId(), false);
            }
        });
    }

    static ForegroundSessionRegistry.TerminalReason providerTerminalReason(GeminiWsClient client) {
        return switch (client.getRecoveryDiagnostic().terminalReason()) {
            case RECOVERY_EXHAUSTED -> ForegroundSessionRegistry.TerminalReason.RECOVERY_EXHAUSTED;
            case NORMAL_CLOSE -> ForegroundSessionRegistry.TerminalReason.COMPLETED;
            case INTENTIONAL_CLOSE -> ForegroundSessionRegistry.TerminalReason.CANCELLED;
            default -> ForegroundSessionRegistry.TerminalReason.PROVIDER_FAILURE;
        };
    }

    /**
     * Reports a finished utterance of a provider session (roadmap A5). The session context is read
     * now, so a session that ended or was replaced reports nothing; delivery is on the server thread.
     */
    public static void emitClientUtterance(GeminiWsClient client, ConversationUtteranceEvent.Speaker speaker, String text) {
        emitClientUtterance(client, speaker, text, ConversationUtteranceEvent.Source.TRANSCRIPTION);
    }

    public static void emitClientUtterance(GeminiWsClient client, ConversationUtteranceEvent.Speaker speaker, String text,
                                           ConversationUtteranceEvent.Source source) {
        boolean listeners = ConversationEventRuntime.hasUtteranceListeners();
        if (!listeners && speaker != ConversationUtteranceEvent.Speaker.PLAYER) return;
        AbstractEntityCitizen citizen = client.getEntity();
        if (foregroundSessions.client(citizen.getUUID()) != client) return;
        var snapshot = foregroundSessions.snapshot(citizen.getUUID()).orElse(null);
        if (snapshot == null) return;
        UUID playerId = snapshot.playerId();
        if (speaker == ConversationUtteranceEvent.Speaker.PLAYER && playerId == null) return;
        // The player talking back counts as a reply to what the citizen raised with them today.
        if (speaker == ConversationUtteranceEvent.Speaker.PLAYER) {
            PlayerWords.record(citizen.getUUID(), text, System.currentTimeMillis());
            ConversationEventDispatch.runOnServerThread(citizen, () -> Complaints.recordAnswered(citizen, playerId));
        }
        if (!listeners) return;
        ConversationEventDispatch.utterance(citizen, snapshot.kind(), speaker, playerId, snapshot.sessionId(),
                snapshot.turnId(), text, source);
    }

    private static void schedulePlayerPresentationCleanup(
            AbstractEntityCitizen entity,
            UUID playerId,
            boolean sendMessage
    ) {
        ConversationEventDispatch.runOnServerThread(entity, () -> {
            if (!entity.isAlive()) return;
            MinecraftServer server = entity.level().getServer();
            if (server == null) return;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return;

            for (ItemStack item : player.getInventory().items) {
                if (item.getItem() instanceof CitizenTalkingDevice) {
                    /*? if forge {*/
                    /*CompoundTag tag = item.getOrCreateTag();
                    tag.putInt("CustomModelData", 0);
                    *//*?}*/
                    /*? if neoforge {*/
                    item.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(0));
                    /*?}*/
                }
            }
            if (sendMessage) {
                player.sendSystemMessage(Component.translatable("mc_talking.too_far")
                        .withStyle(ChatFormatting.YELLOW));
            }
        });
    }

    // -------------------------------------------------------------------------
    // Public conversation API
    // -------------------------------------------------------------------------

    public static boolean canCitizenSpeak(AbstractEntityCitizen citizen) {
        return canCitizenSpeak(citizen, ConversationKind.ADDON_AMBIENT);
    }

    /**
     * Whether the citizen is asleep: MineColonies' own sleep state (a citizen in its bed) or the
     * vanilla sleeping pose. Sleeping citizens never speak, gesture or show a conversation bubble.
     */
    public static boolean isAsleep(AbstractEntityCitizen citizen) {
        var sleep = citizen.getCitizenSleepHandler();
        return citizen.isSleeping() || (sleep != null && sleep.isAsleep());
    }

    public static boolean canCitizenSpeak(AbstractEntityCitizen citizen, boolean isPlayerRequest) {
        return canCitizenSpeak(citizen, isPlayerRequest ? ConversationKind.PLAYER : ConversationKind.ADDON_AMBIENT);
    }

    /**
     * Detailed, side-effect-free speech eligibility used by core and addons.
     *
     * <h4>Ambient speech budget</h4>
     * <p>For every {@link ConversationKind} except {@code PLAYER}, {@code URGENT_CONTACT}, and
     * {@code CONTROLLED} (player-started, urgent, and controlled/meeting sessions are exempt by
     * design), this also reports {@link ConversationEligibility.Status#BUDGET_EXCEEDED} when any
     * player who would currently hear this citizen has already used up their rolling ambient
     * speech budget (see {@code ambientSpeechBudget*} config). This is a read-only peek — handlers
     * that scan many candidate citizens before picking one to actually speak (mumbling,
     * greetings, random citizen conversations, ...) can call this freely without spending budget.
     * The budget itself is spent once, atomically, at the few real commit points where a line is
     * actually about to start or play: {@link AmbientSessions#startLowPrioritySession}, the citizen-pair
     * conversation start in {@code CitizenConversation}, and pregenerated audio playback in
     * {@code PregenerationPlayback}. See {@link AmbientSpeechBudget#trySpend}.</p>
     */
    public static ConversationEligibility conversationEligibility(
            AbstractEntityCitizen citizen,
            ConversationKind kind
    ) {
        if (isAsleep(citizen)) {
            return ConversationEligibility.rejected(ConversationEligibility.Status.SLEEPING, "citizen is sleeping");
        }
        if (citizen instanceof VisitorCitizen && !ConversationRuleRuntime.addonsAllowVisitor(citizen, kind)) {
            return ConversationEligibility.rejected(ConversationEligibility.Status.VISITOR,
                    "visitors only speak when an addon visitor policy allows it");
        }

        if (kind != ConversationKind.PLAYER) {
            if (kind != ConversationKind.CONTROLLED && isCitizenOnCooldown(citizen)) {
                return ConversationEligibility.rejected(ConversationEligibility.Status.COOLDOWN, "automatic conversation cooldown is active");
            }
            if (isCitizenBusy(citizen)) {
                return ConversationEligibility.rejected(ConversationEligibility.Status.BUSY, "citizen is already busy");
            }
            if (kind != ConversationKind.CONTROLLED && isFloorTaken(citizen, kind)) {
                return ConversationEligibility.rejected(ConversationEligibility.Status.BUSY,
                        "someone within earshot is already speaking");
            }
        }

        if (!ConversationRuleRuntime.addonsAllowSpeech(citizen, kind)) {
            return ConversationEligibility.rejected(
                    ConversationEligibility.Status.ADDON_POLICY_VETO,
                    "an addon speech policy vetoed this conversation kind"
            );
        }

        if (AmbientSpeechBudget.appliesTo(kind) && !AmbientSpeechBudget.hasCapacity(citizen)) {
            return ConversationEligibility.rejected(
                    ConversationEligibility.Status.BUDGET_EXCEEDED,
                    "a nearby player's ambient speech budget is exhausted"
            );
        }

        return ConversationEligibility.eligibleResult();
    }

    public static boolean canCitizenSpeak(AbstractEntityCitizen citizen, ConversationKind kind) {
        return conversationEligibility(citizen, kind).eligible();
    }

    /**
     * Whether a player who would hear {@code citizen} already hears someone else: a foreground
     * session (a player conversation holds the floor while listening too) or a {@link SpeechFloor}
     * holder such as a pregenerated clip or a campfire group. A member of a group is engaged for
     * any speech other than the group's own kind. Unprompted speech waits; player conversations are
     * never held back, and controlled sessions pause until the floor around their group is free.
     */
    public static boolean isFloorTaken(AbstractEntityCitizen citizen, ConversationKind kind) {
        double radius = McTalkingConfig.INSTANCE.instance().speechFloorRadius;
        if (radius <= 0 || !(citizen.level() instanceof ServerLevel level)) return false;
        UUID self = citizen.getUUID();
        Set<UUID> exempt = new HashSet<>();
        exempt.add(self);
        List<SpeechFloor.Voice> speakers = new ArrayList<>();
        for (SpeechFloor.Group group : SpeechFloor.groups()) {
            boolean own = group.members().stream().anyMatch(member -> member.getUUID().equals(self));
            if (own && group.kind() != kind) return true;
            for (Entity member : group.members()) {
                if (own) exempt.add(member.getUUID());
                else if (!member.isRemoved()) speakers.add(SpeechFloor.Voice.of(member));
            }
        }
        for (var snapshot : foregroundSessions.snapshots()) {
            if (!snapshot.entity().isRemoved()) speakers.add(SpeechFloor.Voice.of(snapshot.entity()));
        }
        if (speakers.isEmpty()) return false;
        List<SpeechFloor.Voice> listeners = level.players().stream()
                .filter(player -> !player.isSpectator())
                .map(SpeechFloor.Voice::of)
                .toList();
        return SpeechFloor.wouldOverlap(SpeechFloor.Voice.of(citizen), speakers, listeners, exempt, radius);
    }


    /**
     * Whether a player is talking with a citizen (other than {@code exempt}) whose voice would overlap
     * {@code citizen}'s. A player conversation never waits for the speech floor, so background speech
     * such as a citizen pair chat gives way to it.
     */
    public static boolean isPlayerConversationWithinEarshot(AbstractEntityCitizen citizen, Collection<? extends Entity> exempt) {
        double radius = McTalkingConfig.INSTANCE.instance().speechFloorRadius;
        if (radius <= 0 || !(citizen.level() instanceof ServerLevel level)) return false;
        Set<UUID> exemptIds = new HashSet<>();
        exemptIds.add(citizen.getUUID());
        for (Entity entity : exempt) exemptIds.add(entity.getUUID());
        List<SpeechFloor.Voice> speakers = new ArrayList<>();
        for (var snapshot : foregroundSessions.snapshots()) {
            if (snapshot.kind() == ConversationKind.PLAYER && !snapshot.entity().isRemoved()
                    && !exemptIds.contains(snapshot.entity().getUUID())) {
                speakers.add(SpeechFloor.Voice.of(snapshot.entity()));
            }
        }
        if (speakers.isEmpty()) return false;
        List<SpeechFloor.Voice> listeners = level.players().stream()
                .filter(player -> !player.isSpectator())
                .map(SpeechFloor.Voice::of)
                .toList();
        return SpeechFloor.wouldOverlap(SpeechFloor.Voice.of(citizen), speakers, listeners, exemptIds, radius);
    }

    /** Cancels only the exact controlled turn identity; stale cancellation cannot kill a replacement turn. */
    public static boolean cancelControlledAmbientSession(
            AbstractEntityCitizen citizen,
            UUID sessionId,
            UUID turnId
    ) {
        UUID citizenId = citizen.getUUID();
        if (foregroundSessions.playerForCitizen(citizenId) != null) return false;
        GeminiWsClient client = foregroundSessions.client(citizenId);
        ForegroundSessionRegistry.Token token = foregroundSessions.token(citizenId);
        if (token == null || !(client instanceof CitizenWsClient cws)
                || !cws.ownsControlledTurn(sessionId, turnId)) return false;
        return foregroundSessions.end(token, ForegroundSessionRegistry.TerminalReason.CANCELLED,
                "controlled turn cancelled");
    }

    /** Cancels an addon/system ambient session without exposing its Gemini client. */
    public static boolean cancelAddonAmbientSession(AbstractEntityCitizen citizen) {
        UUID citizenId = citizen.getUUID();
        if (foregroundSessions.playerForCitizen(citizenId) != null) return false;
        GeminiWsClient client = foregroundSessions.client(citizenId);
        ForegroundSessionRegistry.Token token = foregroundSessions.token(citizenId);
        if (token == null || !(client instanceof CitizenWsClient cws) || !cws.isMumbling()) return false;
        return foregroundSessions.end(token, ForegroundSessionRegistry.TerminalReason.CANCELLED,
                "ambient session cancelled");
    }

    /**
     * Returns {@code true} if any online player is within {@code range} blocks
     * of the given citizen in the same dimension.
     */
    public static boolean hasPlayerNearby(AbstractEntityCitizen citizen, MinecraftServer server, double range) {
        double rangeSqr = range * range;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == citizen.level() && player.distanceToSqr(citizen) <= rangeSqr) {
                return true;
            }
        }
        return false;
    }

    /**
     * <p>If the citizen is already mumbling the existing session is reused
     * (no reconnect). If the citizen is in a different active session (citizen-to-
     * citizen) it is closed first so the player always wins.</p>
     */
    public static ConversationStartResult startPlayerConversationDetailed(ServerPlayer player, AbstractEntityCitizen citizen) {
        return startPlayerConversationDetailed(player, citizen, PlayerConversationOptions.defaults());
    }

    /**
     * Starts a player conversation with addon options. Non-default options never take over a
     * mumbling session, because that session's prompt was built without the agenda.
     */
    public static ConversationStartResult startPlayerConversationDetailed(ServerPlayer player, AbstractEntityCitizen citizen,
                                                                          PlayerConversationOptions options) {
        if (!McTalkingConfig.hasGeminiApiKey()) {
            player.sendSystemMessage(Component.translatable("mc_talking.no_key").withStyle(ChatFormatting.RED));
            return ConversationStartResult.rejected(
                    ConversationStartResult.Status.PROVIDER_UNAVAILABLE,
                    "Gemini API key/provider is unavailable");
        }

        ConversationEligibility eligibility = conversationEligibility(citizen, ConversationKind.PLAYER);
        if (!eligibility.eligible()) {
            ConversationStartResult.Status status = switch (eligibility.status()) {
                case SLEEPING -> ConversationStartResult.Status.SLEEPING;
                case VISITOR -> ConversationStartResult.Status.VISITOR;
                case ADDON_POLICY_VETO -> ConversationStartResult.Status.ADDON_POLICY_VETO;
                default -> ConversationStartResult.Status.FAILED;
            };
            return ConversationStartResult.rejected(status, eligibility.detail());
        }

        UUID playerId = player.getUUID();
        UUID citizenId = citizen.getUUID();

        UUID existingPlayerId = foregroundSessions.playerForCitizen(citizenId);
        if (existingPlayerId != null && !existingPlayerId.equals(playerId)) {
            player.sendSystemMessage(Component.translatable("mc_talking.citizen_in_use").withStyle(ChatFormatting.YELLOW));
            return ConversationStartResult.rejected(
                    ConversationStartResult.Status.IN_USE_BY_OTHER_PLAYER,
                    "citizen is already in a direct conversation with another player");
        }

        UUID currentCitizenId = foregroundSessions.citizenForPlayer(playerId);
        if (currentCitizenId != null && !currentCitizenId.equals(citizenId)) {
            endConversation(playerId, false);
        }

        GeminiWsClient existingClient = foregroundSessions.client(citizenId);
        ForegroundSessionRegistry.Token existingToken = foregroundSessions.token(citizenId);
        var existingSnapshot = foregroundSessions.snapshot(citizenId).orElse(null);

        if (options.isDefault()
                && existingClient instanceof CitizenWsClient cws && cws.isMumbling() && cws.isPlayerTakeoverAllowed()
                && existingToken != null && existingSnapshot != null
                && existingSnapshot.priority() == ForegroundSessionRegistry.Priority.AMBIENT) {
            var promoted = foregroundSessions.promoteToPlayer(existingToken, playerId, ConversationKind.PLAYER);
            if (promoted.isEmpty()) {
                return ConversationStartResult.rejected(ConversationStartResult.Status.FAILED,
                        "ambient session ownership changed during player takeover");
            }
            dispatchLifecycleEnded(citizen, promoted.get().before().kind(), null, null, null, null);
            cws.transitionToPlayer(player);
            var participation = participationAdapters.get(existingToken.ownershipId());
            if (participation != null) participation.refresh();
            UrgentContactHandler.onPlayerTakeover(citizen, player);
            dispatchLifecycleStarted(citizen, ConversationKind.PLAYER, playerId, null, null, null);
            return ConversationStartResult.startedResult();
        }

        Runnable preemptActivity = removeActivityForPlayerPreemption(citizenId);
        if (preemptActivity != null) {
            try {
                preemptActivity.run();
            } catch (Throwable t) {
                McTalking.LOGGER.error("Failed to preempt core activity for {}", citizenId, t);
            }
        }

        if (existingToken != null) {
            if (existingClient instanceof CitizenWsClient cws && !cws.isPlayerTakeoverAllowed()) {
                cws.markPlayerTakeoverPending();
            }
            foregroundSessions.end(existingToken, ForegroundSessionRegistry.TerminalReason.REPLACED,
                    "replaced by direct player conversation");
        }

        ForegroundReservation reservation = reservePlayerForeground(citizen, playerId);
        if (reservation == null) {
            player.sendSystemMessage(Component.translatable("mc_talking.capacity_reached").withStyle(ChatFormatting.YELLOW));
            return ConversationStartResult.rejected(
                    ConversationStartResult.Status.CAPACITY_EXHAUSTED,
                    "all foreground slots are occupied by player conversations");
        }

        final CitizenWsClient ws;
        try {
            ws = new CitizenWsClient(
                    new CitizenEntityAudioProvider(citizen, McTalkingVoicechatPlugin.DIRECT_PLAYER_DIALOG),
                    citizen, player, options);
        } catch (RuntimeException e) {
            reservation.end(ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "failed to construct provider session");
            McTalking.LOGGER.error("Failed to construct Gemini client for player conversation with {}", citizenId, e);
            return ConversationStartResult.rejected(ConversationStartResult.Status.FAILED,
                    "failed to construct provider session");
        }

        if (!reservation.attachClient(ws)) {
            reservation.end(ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "foreground ownership changed before player client attach");
            return ConversationStartResult.rejected(ConversationStartResult.Status.FAILED,
                    "player conversation ownership changed during startup");
        }

        ws.addOnCloseAction(() -> ConversationEventDispatch.runOnServerThread(citizen, () -> {
            var diagnostic = ws.getRecoveryDiagnostic();
            reservation.end(providerTerminalReason(ws), diagnostic.detail());
        }));

        McTalking.LOGGER.info("Starting initial websocket connection from outer...");
        try {
            ws.connect();
        } catch (RuntimeException e) {
            reservation.end(ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "failed to connect provider session");
            McTalking.LOGGER.error("Failed to connect Gemini client for player conversation with {}", citizenId, e);
            return ConversationStartResult.rejected(ConversationStartResult.Status.FAILED,
                    "failed to connect provider session");
        }

        if (options.purpose() != null) sessionPurposes.put(reservation.token.ownershipId(), options.purpose());
        if (!reservation.activate()) {
            reservation.end(ForegroundSessionRegistry.TerminalReason.STARTUP_FAILED,
                    "foreground ownership changed before player activation");
            return ConversationStartResult.rejected(ConversationStartResult.Status.FAILED,
                    "player conversation ownership changed during activation");
        }
        UrgentContactHandler.onPlayerTakeover(citizen, player);
        return ConversationStartResult.startedResult();
    }

    public static boolean startPlayerConversation(ServerPlayer player, AbstractEntityCitizen citizen) {
        return startPlayerConversationDetailed(player, citizen).started();
    }

    public static void endConversation(UUID playerId, boolean sendMessage) {
        endConversation(playerId, sendMessage, ForegroundSessionRegistry.TerminalReason.CANCELLED,
                "player conversation ended");
    }

    public static void endConversationForDisconnect(UUID playerId) {
        endConversation(playerId, false, ForegroundSessionRegistry.TerminalReason.PLAYER_DISCONNECTED,
                "player disconnected");
    }

    private static void endConversation(
            UUID playerId,
            boolean sendMessage,
            ForegroundSessionRegistry.TerminalReason reason,
            String detail
    ) {
        UUID citizenId = foregroundSessions.citizenForPlayer(playerId);
        if (citizenId == null) return;
        AbstractEntityCitizen entity = foregroundSessions.entityForPlayer(playerId);
        if (!foregroundSessions.endPlayer(playerId, reason, detail)) return;
        if (sendMessage && entity != null) schedulePlayerPresentationCleanup(entity, playerId, true);
    }

    // -------------------------------------------------------------------------
    // Query helpers
    // -------------------------------------------------------------------------

    public static boolean isPlayerInConversation(UUID playerId) {
        return foregroundSessions.citizenForPlayer(playerId) != null;
    }

    public static GeminiWsClient getClientForEntity(UUID entityId) {
        return foregroundSessions.client(entityId);
    }

    /**
     * Returns the exact current direct-player client only while its provider can accept microphone input.
     * Participation display and microphone routing therefore use the same token/player/readiness facts.
     */
    @Nullable
    public static GeminiWsClient getReadyInputClientForPlayer(UUID playerId) {
        UUID citizenId = foregroundSessions.citizenForPlayer(playerId);
        if (citizenId == null) return null;
        ForegroundSessionRegistry.Token token = foregroundSessions.token(citizenId);
        if (token == null || !conversationParticipation.canRouteInput(token, playerId)) return null;
        GeminiWsClient client = foregroundSessions.client(citizenId);
        if (client == null || client.isLifecycleClosed()) return null;
        if (client instanceof CitizenWsClient citizenClient
                && !citizenClient.isAssociatedWithPlayer(playerId)) return null;
        // The registry/client reads are individually synchronized, not one composite operation.
        // Revalidate the exact token after retrieving the client so takeover/replacement cannot
        // route a packet to a newer provider that has not reached readiness yet.
        if (!foregroundSessions.isCurrent(token) || !conversationParticipation.canRouteInput(token, playerId)) {
            return null;
        }
        return client;
    }

    public static AbstractEntityCitizen getActiveEntityForPlayer(UUID playerId) {
        return foregroundSessions.entityForPlayer(playerId);
    }

    public static UUID getPlayerForEntity(UUID entityId) {
        return foregroundSessions.playerForCitizen(entityId);
    }

    public static ConversationKind getActiveConversationKind(UUID entityId) {
        return foregroundSessions.kind(entityId);
    }

    public static void cleanup() {
        foregroundSessions.shutdown();
        backgroundSessions.shutdown();
        for (Runnable cleanup : activities.shutdown()) {
            try {
                cleanup.run();
            } catch (Throwable t) {
                McTalking.LOGGER.error("Error running activity cleanup during shutdown", t);
            }
        }
        cooldowns.clear();
        AmbientSpeechBudget.clear();
        QuotaTracker.clear();
        me.sshcrack.mc_talking.manager.VoiceSelectionService.clear();
        GeminiWsClient.shutdownExecutor();
    }

}
