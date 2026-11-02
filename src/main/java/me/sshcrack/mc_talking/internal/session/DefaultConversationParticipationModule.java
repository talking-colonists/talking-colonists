package me.sshcrack.mc_talking.internal.session;

import me.sshcrack.mc_talking.network.AiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Thread-safe, Minecraft-free implementation of {@link ConversationParticipationModule}. */
public final class DefaultConversationParticipationModule implements ConversationParticipationModule {
    private final OwnershipView ownershipView;
    private final Map<ForegroundSessionRegistry.Token, Facts> factsByOwner = new HashMap<>();

    public DefaultConversationParticipationModule(OwnershipView ownershipView) {
        this.ownershipView = Objects.requireNonNull(ownershipView, "ownershipView");
    }

    @Override
    public synchronized void register(ForegroundSessionRegistry.Token token) {
        factsByOwner.put(Objects.requireNonNull(token, "token"), new Facts());
    }

    @Override
    public synchronized void providerConnecting(ForegroundSessionRegistry.Token token) {
        mutate(token, facts -> facts.providerReadiness = ProviderReadiness.CONNECTING);
    }

    @Override
    public synchronized void providerReady(ForegroundSessionRegistry.Token token) {
        mutate(token, facts -> facts.providerReadiness = ProviderReadiness.READY);
    }

    @Override
    public synchronized void providerRecovering(ForegroundSessionRegistry.Token token) {
        mutate(token, facts -> facts.providerReadiness = ProviderReadiness.RECOVERING);
    }

    @Override
    public synchronized void playbackThinking(ForegroundSessionRegistry.Token token) {
        mutate(token, facts -> facts.audibleActivity = AudibleActivity.THINKING);
    }

    @Override
    public synchronized void playbackTalking(ForegroundSessionRegistry.Token token) {
        mutate(token, facts -> facts.audibleActivity = AudibleActivity.TALKING);
    }

    @Override
    public synchronized void playbackIdle(ForegroundSessionRegistry.Token token) {
        mutate(token, facts -> facts.audibleActivity = AudibleActivity.IDLE);
    }

    @Override
    public synchronized void inputAwaitingResponse(ForegroundSessionRegistry.Token token, boolean active) {
        mutate(token, facts -> facts.awaitingProviderResponse = active);
    }

    @Override
    public synchronized void urgentWalking(ForegroundSessionRegistry.Token token, boolean active) {
        mutate(token, facts -> facts.urgentWalking = active);
    }

    @Override
    public synchronized void failure(ForegroundSessionRegistry.Token token, AiStatus status) {
        Objects.requireNonNull(status, "status");
        if (status != AiStatus.ERROR && status != AiStatus.QUOTA_EXCEEDED) {
            throw new IllegalArgumentException("Only terminal error/quota presentation may be supplied");
        }
        mutate(token, facts -> facts.failure = status);
    }

    @Override
    public synchronized Optional<AiStatus> presentationIfCurrent(ForegroundSessionRegistry.Token token) {
        Ownership ownership = ownershipView.current(token.citizenId());
        Facts facts = factsByOwner.get(token);
        if (ownership == null || !ownership.token().equals(token) || facts == null) return Optional.empty();
        return Optional.of(derivePresentation(facts, ownership.directPlayerId()));
    }

    @Override
    public synchronized boolean canRouteInput(ForegroundSessionRegistry.Token token, UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        Ownership ownership = ownershipView.current(token.citizenId());
        Facts facts = factsByOwner.get(token);
        return ownership != null
                && ownership.token().equals(token)
                && playerId.equals(ownership.directPlayerId())
                && facts != null
                && facts.failure == null
                && facts.providerReadiness == ProviderReadiness.READY;
    }

    @Override
    public synchronized void complete(ForegroundSessionRegistry.Token token) {
        factsByOwner.remove(token);
    }

    @Override
    public synchronized boolean canClearAfterCompletion(ForegroundSessionRegistry.Token token) {
        Ownership ownership = ownershipView.current(token.citizenId());
        return ownership == null || ownership.token().equals(token);
    }

    private void mutate(ForegroundSessionRegistry.Token token, java.util.function.Consumer<Facts> mutation) {
        Facts facts = factsByOwner.get(token);
        if (facts != null) mutation.accept(facts);
    }

    private static AiStatus derivePresentation(Facts facts, @Nullable UUID directPlayerId) {
        if (facts.failure != null) return facts.failure;
        if (facts.audibleActivity == AudibleActivity.TALKING) return AiStatus.TALKING;
        if (facts.providerReadiness == ProviderReadiness.RECOVERING) return AiStatus.RECONNECTING;
        if (facts.providerReadiness == ProviderReadiness.CONNECTING) return AiStatus.CONNECTING;
        if (facts.awaitingProviderResponse) return AiStatus.THINKING;
        if (facts.audibleActivity == AudibleActivity.THINKING) return AiStatus.THINKING;
        if (facts.urgentWalking) return AiStatus.URGENT_WALKING;
        if (facts.providerReadiness == ProviderReadiness.READY && directPlayerId != null) return AiStatus.LISTENING;
        return AiStatus.NONE;
    }

    private static final class Facts {
        ProviderReadiness providerReadiness = ProviderReadiness.NOT_READY;
        AudibleActivity audibleActivity = AudibleActivity.IDLE;
        boolean awaitingProviderResponse;
        boolean urgentWalking;
        @Nullable AiStatus failure;
    }
}
