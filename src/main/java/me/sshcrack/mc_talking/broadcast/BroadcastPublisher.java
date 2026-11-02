package me.sshcrack.mc_talking.broadcast;

import me.sshcrack.mc_talking.api.memory.BroadcastPublishResult;
import me.sshcrack.mc_talking.api.memory.BroadcastRequest;
import me.sshcrack.mc_talking.api.memory.BroadcastScope;
import me.sshcrack.mc_talking.api.memory.BroadcastSource;
import me.sshcrack.mc_talking.api.memory.MemoryProvenance;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Single runtime for creating colony broadcasts: the addon API and the {@code initiate_broadcast}
 * AI tool both publish through here, so validation, rate limits and provenance are shared.
 */
public final class BroadcastPublisher {
    /** Publishes allowed per colony within {@link #RATE_WINDOW_MS}. */
    public static final int MAX_PUBLISHES_PER_WINDOW = 10;
    public static final long RATE_WINDOW_MS = 10 * 60_000L;

    public static final BroadcastPublisher INSTANCE = new BroadcastPublisher(System::currentTimeMillis);

    /** A citizen that can receive a broadcast. */
    public interface Recipient {
        @NotNull UUID citizenId();

        @NotNull String name();

        @NotNull CitizenMemories memories();
    }

    /** The colony as the publisher sees it; the MineColonies adapter implements this. */
    public interface Colony {
        /** Identifies the colony for rate limiting (colony ids repeat across dimensions). */
        @NotNull Object rateKey();

        @NotNull List<? extends Recipient> all();

        @Nullable Recipient byId(@NotNull UUID citizenId);

        /** Loaded citizens close enough to {@code position} to hear a broadcast from there. */
        @NotNull List<? extends Recipient> near(@NotNull BlockPos position);
    }

    public record Settings(boolean enabled, int maxStoredPerCitizen) {
    }

    private final LongSupplier clock;
    private final Map<Object, Deque<Long>> publishTimes = new HashMap<>();

    public BroadcastPublisher(@NotNull LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized @NotNull BroadcastPublishResult publish(@NotNull Colony colony,
                                                                @NotNull BroadcastRequest request,
                                                                @NotNull Settings settings) {
        if (!settings.enabled()) return BroadcastPublishResult.failed(BroadcastPublishResult.Status.DISABLED);

        List<? extends Recipient> recipients;
        String originatorName = request.source().displayName();
        if (request.scope() == BroadcastScope.COLONY_IMMEDIATE) {
            recipients = colony.all();
        } else if (request.originCitizen() != null) {
            Recipient origin = colony.byId(request.originCitizen());
            recipients = origin == null ? List.of() : List.of(origin);
            if (origin != null) originatorName = origin.name();
        } else {
            BlockPos originPosition = request.originPosition();
            recipients = originPosition == null ? List.of() : colony.near(originPosition);
        }
        if (recipients.isEmpty()) return BroadcastPublishResult.failed(BroadcastPublishResult.Status.NO_RECIPIENTS);

        long now = clock.getAsLong();
        Deque<Long> times = publishTimes.computeIfAbsent(colony.rateKey(), ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= RATE_WINDOW_MS) times.pollFirst();
        if (times.size() >= MAX_PUBLISHES_PER_WINDOW) {
            return BroadcastPublishResult.failed(BroadcastPublishResult.Status.RATE_LIMITED);
        }
        times.addLast(now);

        ColonyBroadcast broadcast = toBroadcast(UUID.randomUUID().toString(), originatorName, request, now);
        int delivered = 0;
        for (Recipient recipient : recipients) {
            if (recipient.memories().addBroadcast(broadcast, settings.maxStoredPerCitizen())) delivered++;
        }
        return BroadcastPublishResult.published(broadcast.getId(), delivered);
    }

    /** Makes every citizen forget the broadcast; returns whether any citizen still remembered it. */
    public boolean retract(@NotNull Colony colony, @NotNull String broadcastId) {
        boolean removed = false;
        for (Recipient recipient : colony.all()) {
            removed |= recipient.memories().removeBroadcast(broadcastId);
        }
        return removed;
    }

    static ColonyBroadcast toBroadcast(String id, String originatorName, BroadcastRequest request, long now) {
        BroadcastSource source = request.source();
        boolean player = source.kind() == BroadcastSource.Kind.PLAYER;
        long expiresAt = request.expiresAfter() == null ? 0L : now + request.expiresAfter().toMillis();
        return new ColonyBroadcast(
                id,
                originatorName,
                request.message(),
                now,
                source.displayName(),
                player ? MemoryProvenance.PLAYER_STATEMENT : MemoryProvenance.ADDON_DIRECT_WRITE,
                player ? null : source.displayName(),
                expiresAt,
                request.announceAloud());
    }
}
