package me.sshcrack.mc_talking.internal.api;

import me.sshcrack.mc_talking.api.memory.BroadcastReach;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.api.memory.AddonMemoryWriteResult;
import me.sshcrack.mc_talking.api.memory.BroadcastPublishResult;
import me.sshcrack.mc_talking.api.memory.BroadcastRequest;
import me.sshcrack.mc_talking.api.memory.CitizenMemorySnapshot;
import me.sshcrack.mc_talking.api.memory.CitizenRelationshipDimension;
import me.sshcrack.mc_talking.api.memory.MemoryProvenance;
import me.sshcrack.mc_talking.api.service.MemoryService;
import me.sshcrack.mc_talking.broadcast.BroadcastPublisher;
import me.sshcrack.mc_talking.broadcast.MineColoniesBroadcastColony;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.conversations.memory.MemorySnapshotFactory;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class MemoryServiceBackend implements MemoryService {
    @Override
    public boolean addEvent(@NotNull ICitizenData citizen, @NotNull String event) {
        if (event.isBlank()) throw new IllegalArgumentException("event must not be blank");
        return onCitizenServerThread(citizen, false, () -> {
            CitizenMemories memories = getOrCreate(citizen);
            if (memories == null) return false;
            memories.addEvent(event, MemoryProvenance.ADDON_DIRECT_WRITE, null, null, null);
            return true;
        });
    }

    @Override
    public boolean removeEvent(@NotNull ICitizenData citizen, @NotNull String event) {
        if (event.isBlank()) throw new IllegalArgumentException("event must not be blank");
        return onCitizenServerThread(citizen, false, () -> {
            CitizenMemories memories = getExisting(citizen);
            return memories != null && memories.removeEvent(event);
        });
    }

    @Override
    public boolean addFact(@NotNull ICitizenData citizen, @NotNull String fact) {
        if (fact.isBlank()) throw new IllegalArgumentException("fact must not be blank");
        return onCitizenServerThread(citizen, false, () -> {
            CitizenMemories memories = getOrCreate(citizen);
            if (memories == null) return false;
            memories.addFact(fact, MemoryProvenance.ADDON_DIRECT_WRITE, null, null, null);
            return true;
        });
    }

    @Override
    public boolean removeFact(@NotNull ICitizenData citizen, @NotNull String fact) {
        if (fact.isBlank()) throw new IllegalArgumentException("fact must not be blank");
        return onCitizenServerThread(citizen, false, () -> {
            CitizenMemories memories = getExisting(citizen);
            return memories != null && memories.removeFact(fact);
        });
    }

    @Override
    public boolean addRelationshipChange(@NotNull ICitizenData citizen, @NotNull UUID targetId,
                                         @NotNull CitizenRelationshipDimension dimension, float delta) {
        java.util.Objects.requireNonNull(targetId, "targetId");
        java.util.Objects.requireNonNull(dimension, "dimension");
        if (!Float.isFinite(delta) || delta < -1.0f || delta > 1.0f) {
            throw new IllegalArgumentException("relationship delta must be finite and within [-1, 1]");
        }
        return onCitizenServerThread(citizen, false, () -> {
            CitizenMemories memories = getOrCreate(citizen);
            if (memories == null) return false;
            memories.addRelationshipChange(targetId, dimension, delta,
                    MemoryProvenance.ADDON_DIRECT_WRITE, null, null, null);
            return true;
        });
    }

    @Override
    public @NotNull AddonMemoryWriteResult confirmOutcome(@NotNull ICitizenData citizen,
                                                           @NotNull AddonConfirmedOutcome outcome) {
        java.util.Objects.requireNonNull(outcome, "outcome");
        return onCitizenServerThread(citizen, AddonMemoryWriteResult.UNAVAILABLE, () -> {
            CitizenMemories memories = getOrCreate(citizen);
            return memories == null ? AddonMemoryWriteResult.UNAVAILABLE : memories.addConfirmedOutcome(outcome);
        });
    }

    @Override
    public @NotNull Optional<CitizenMemorySnapshot> snapshot(@NotNull ICitizenData citizen) {
        return onCitizenServerThread(citizen, Optional.empty(), () -> {
            CitizenMemories memories = getExisting(citizen);
            return memories == null ? Optional.empty() : Optional.of(MemorySnapshotFactory.create(memories));
        });
    }

    @Override
    public @NotNull BroadcastPublishResult publishBroadcast(@NotNull IColony colony, @NotNull BroadcastRequest request) {
        java.util.Objects.requireNonNull(colony, "colony");
        java.util.Objects.requireNonNull(request, "request");
        return onColonyServerThread(colony, BroadcastPublishResult.failed(BroadcastPublishResult.Status.UNAVAILABLE),
                () -> {
                    var config = McTalkingConfig.INSTANCE.instance();
                    return BroadcastPublisher.INSTANCE.publish(
                            new MineColoniesBroadcastColony(colony, config.broadcastPropagationRange),
                            request,
                            new BroadcastPublisher.Settings(config.enableBroadcastPropagation, config.maxBroadcastsStored));
                });
    }

    @Override
    public @NotNull Optional<BroadcastReach> broadcastReach(@NotNull IColony colony, @NotNull String broadcastId) {
        Objects.requireNonNull(colony, "colony");
        Objects.requireNonNull(broadcastId, "broadcastId");
        return onColonyServerThread(colony, Optional.empty(), () -> {
            int citizens = 0;
            int heard = 0;
            for (ICitizenData citizen : colony.getCitizenManager().getCitizens()) {
                citizens++;
                CitizenMemories memories = getExisting(citizen);
                if (memories != null && memories.hasHeardBroadcast(broadcastId)) heard++;
            }
            return heard == 0 ? Optional.<BroadcastReach>empty() : Optional.of(new BroadcastReach(heard, citizens));
        });
    }

    @Override
    public boolean retractBroadcast(@NotNull IColony colony, @NotNull String broadcastId) {
        java.util.Objects.requireNonNull(colony, "colony");
        java.util.Objects.requireNonNull(broadcastId, "broadcastId");
        return onColonyServerThread(colony, false, () -> BroadcastPublisher.INSTANCE.retract(
                new MineColoniesBroadcastColony(colony, McTalkingConfig.INSTANCE.instance().broadcastPropagationRange),
                broadcastId));
    }

    private static <T> T onCitizenServerThread(@NotNull ICitizenData citizen, T unavailable,
                                                @NotNull Supplier<T> operation) {
        java.util.Objects.requireNonNull(citizen, "citizen");
        return onColonyServerThread(citizen.getColony(), unavailable, operation);
    }

    private static <T> T onColonyServerThread(IColony colony, T unavailable, @NotNull Supplier<T> operation) {
        java.util.Objects.requireNonNull(operation, "operation");
        var level = colony == null ? null : colony.getWorld();
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null) return unavailable;
        if (server.isSameThread()) return operation.get();

        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(operation.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.join();
    }

    @Nullable
    private static CitizenMemories getExisting(ICitizenData citizen) {
        if (!(citizen instanceof CitizenDataMemoryExtended extended)) return null;
        return extended.mc_talking$getMemory();
    }

    @Nullable
    private static CitizenMemories getOrCreate(ICitizenData citizen) {
        if (!(citizen instanceof CitizenDataMemoryExtended extended)) return null;
        return extended.mc_talking$getOrInitializeMemory();
    }
}
