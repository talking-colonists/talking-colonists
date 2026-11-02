package me.sshcrack.mc_talking.pregen;

import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.jobs.JobDeliveryman;
import me.sshcrack.gemini_live_lib.misc.GeminiTTS.AudioChunk;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.manager.CitizenPromptViewFactory;
import me.sshcrack.mc_talking.config.QuotaTracker;
import me.sshcrack.mc_talking.util.BackgroundSlotType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

public class DeliveryInteractionManager {
    private DeliveryInteractionManager() {}

    private static final int PREGEN_DISTANCE_SQ = 15 * 15;
    private static final long STALE_TIMEOUT_MS = 300_000;
    private static final long RETRY_COOLDOWN_MS = 1_000;

    private static final ConcurrentHashMap<String, PendingDelivery> pendingDeliveries = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CachedAudio> pregenCache = new ConcurrentHashMap<>();

    private record SpeakerAndPrompt(AbstractEntityCitizen speaker, String prompt) {}

    private static class PendingDelivery {
        final IToken<?> token;
        final BlockPos targetPos;
        final int colonyId;
        final ResourceKey<Level> dimension;
        final String itemName;
        final int itemCount;
        final long trackedSince;
        boolean pregenStarted;
        UUID deliverymanUuid;

        long lastRetryTime;

        PendingDelivery(IToken<?> token, BlockPos targetPos, int colonyId, ResourceKey<Level> dimension,
                        String itemName, int itemCount) {
            this.token = token;
            this.targetPos = targetPos;
            this.colonyId = colonyId;
            this.dimension = dimension;
            this.itemName = itemName;
            this.itemCount = itemCount;
            this.trackedSince = System.currentTimeMillis();
            this.lastRetryTime = System.currentTimeMillis();
        }
    }

    private static class CachedAudio {
        final AudioChunk audio;
        final UUID speakerUuid;

        CachedAudio(AudioChunk audio, UUID speakerUuid) {
            this.audio = audio;
            this.speakerUuid = speakerUuid;
        }
    }

    /**
     * Called from the AbstractRequest.setState() mixin when a Delivery request
     * transitions to IN_PROGRESS. Catches all state transitions since AbstractRequest
     * is the sole concrete IRequest implementation.
     */
    public static void trackDelivery(IColony colony, IRequest<?> request, IToken<?> token) {
        if (!(request.getRequest() instanceof Delivery delivery)) return;
        if (!McTalkingConfig.INSTANCE.instance().enablePregeneration) return;
        if (!McTalkingConfig.hasGeminiApiKey()) return;

        String key = token.toString();
        if (pendingDeliveries.containsKey(key)) return;

        BlockPos targetPos = delivery.getTarget().getInDimensionLocation();
        String itemName = delivery.getStack().getHoverName().getString();
        int itemCount = delivery.getStack().getCount();

        PendingDelivery pd = new PendingDelivery(token, targetPos, colony.getID(), colony.getDimension(),
                itemName, itemCount);

        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd.getJob() instanceof JobDeliveryman dman) {
                if (dman.getTaskQueue().contains(token)) {
                    cd.getEntity().ifPresent(entity -> pd.deliverymanUuid = entity.getUUID());
                    break;
                }
            }
        }

        pendingDeliveries.put(key, pd);
        McTalking.LOGGER.info("[DeliveryInteraction] Fast-path tracked delivery {} (item: {} x{})",
                key, itemName, itemCount);
    }

    public static void tick(MinecraftServer server) {

        if (pendingDeliveries.isEmpty()) return;

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, PendingDelivery>> it = pendingDeliveries.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, PendingDelivery> entry = it.next();
            PendingDelivery pd = entry.getValue();

            if (pd.pregenStarted) continue;

            if (now - pd.trackedSince > STALE_TIMEOUT_MS) {
                McTalking.LOGGER.info("[DeliveryInteraction] Removing stale delivery {}", entry.getKey());
                it.remove();
                continue;
            }

            if (pd.deliverymanUuid == null) {
                if (now - pd.lastRetryTime < RETRY_COOLDOWN_MS) continue;
                pd.lastRetryTime = now;
                tryRetryFindDeliveryman(pd);
                if (pd.deliverymanUuid == null) continue;
            }

            ServerLevel world = server.getLevel(pd.dimension);
            if (world == null) continue;

            Entity raw = world.getEntity(pd.deliverymanUuid);
            if (!(raw instanceof AbstractEntityCitizen deliveryman) || !deliveryman.isAlive()) {
                pd.deliverymanUuid = null;
                continue;
            }

            double distSq = deliveryman.distanceToSqr(
                    pd.targetPos.getX() + 0.5,
                    pd.targetPos.getY() + 0.5,
                    pd.targetPos.getZ() + 0.5);
            if (distSq > PREGEN_DISTANCE_SQ) continue;

            if (world.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                    new AABB(pd.targetPos.getX() - McTalkingConfig.INSTANCE.instance().pregeneratedGreetingDistance,
                            pd.targetPos.getY() - McTalkingConfig.INSTANCE.instance().pregeneratedGreetingDistance,
                            pd.targetPos.getZ() - McTalkingConfig.INSTANCE.instance().pregeneratedGreetingDistance,
                            pd.targetPos.getX() + McTalkingConfig.INSTANCE.instance().pregeneratedGreetingDistance,
                            pd.targetPos.getY() + McTalkingConfig.INSTANCE.instance().pregeneratedGreetingDistance,
                            pd.targetPos.getZ() + McTalkingConfig.INSTANCE.instance().pregeneratedGreetingDistance)).isEmpty()) {
                continue;
            }

            if (ConversationManager.isCitizenBusy(deliveryman)) continue;

            SpeakerAndPrompt sp = selectSpeakerAndPrompt(world, pd.targetPos, deliveryman,
                    pd.itemName, pd.itemCount);
            if (sp == null) continue;

            final String finalKey = entry.getKey();
            final UUID speakerUuid = sp.speaker().getUUID();
            pd.pregenStarted = true;

            McTalking.LOGGER.info("[DeliveryInteraction] Starting pregen for delivery {} (speaker: {}, distance: {})",
                    finalKey, sp.speaker().getCitizenData() != null ? sp.speaker().getCitizenData().getName() : "unknown",
                    Math.sqrt(distSq));

            boolean started = startPregeneration(sp.speaker(), sp.prompt(), audio -> {
                pregenCache.put(finalKey, new CachedAudio(audio, speakerUuid));
                McTalking.LOGGER.info("[DeliveryInteraction] Pregen complete for {}", finalKey);
            }, () -> {
                PendingDelivery current = pendingDeliveries.get(finalKey);
                if (current == pd) {
                    pd.pregenStarted = false;
                    pd.lastRetryTime = System.currentTimeMillis();
                }
            });
            if (!started) {
                pd.pregenStarted = false;
                pd.lastRetryTime = now;
            }
        }
    }

    public static void onDeliveryResolved(IColony colony, IRequest<?> request, IToken<?> token) {
        String key = token.toString();
        pendingDeliveries.remove(key);
        CachedAudio cached = pregenCache.remove(key);

        if (cached != null) {
            if (!(colony.getWorld() instanceof ServerLevel world)) return;
            Entity raw = world.getEntity(cached.speakerUuid);
            if (raw instanceof AbstractEntityCitizen speaker && speaker.isAlive()) {
                boolean played = PregenerationPlayback.playAudioIfPossible(speaker, cached.audio);
                if (played) {
                    McTalking.LOGGER.info("[DeliveryInteraction] Played cached audio for delivery {}", key);
                }
            }
        } else {
            // On-demand generation
            if (!McTalkingConfig.hasGeminiApiKey()) return;
            if (!(colony.getWorld() instanceof ServerLevel world)) return;

            if (!(request.getRequest() instanceof Delivery delivery)) return;

            BlockPos targetPos = delivery.getTarget().getInDimensionLocation();
            String itemName = delivery.getStack().getHoverName().getString();
            int itemCount = delivery.getStack().getCount();

            AbstractEntityCitizen deliveryman = null;
            for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
                if (cd.getJob() instanceof JobDeliveryman dman
                        && dman.getTaskQueue().contains(token)) {
                    deliveryman = cd.getEntity().orElse(null);
                    if (deliveryman != null) break;
                }
            }

            SpeakerAndPrompt sp = selectSpeakerAndPrompt(world, targetPos, deliveryman, itemName, itemCount);
            if (sp == null) return;

            final AbstractEntityCitizen speaker = sp.speaker();
            startPregeneration(sp.speaker(), sp.prompt(), audio -> {
                PregenerationPlayback.playAudioIfPossible(speaker, audio);
            }, () -> { });
        }
    }

    public static void onDeliveryCancelled(IToken<?> token) {
        String key = token.toString();
        pendingDeliveries.remove(key);
        pregenCache.remove(key);
    }

    public static void cleanup() {
        pendingDeliveries.clear();
        pregenCache.clear();
    }

    // -------------------------------------------------------------------------
    // Prompt builders
    // -------------------------------------------------------------------------

    private static String buildDeliverymanPrompt(AbstractEntityCitizen deliveryman, String itemName, int itemCount) {
        if (deliveryman.getCitizenData() == null) return null;
        String name = deliveryman.getCitizenData().getName();
        return String.format(
                "You are %s, a deliveryman in a Minecraft colony. You just finished a delivery: you brought %d %s to a fellow colonist. React briefly to acknowledge the completed delivery.",
                name, itemCount, itemName);
    }

    @Nullable
    private static String buildRecipientPrompt(AbstractEntityCitizen recipient, String itemName, int itemCount) {
        if (recipient.getCitizenData() == null) return null;
        ICitizenData data = recipient.getCitizenData();

        String name = data.getName();
        String job = extractJobName(data);
        if (job == null) job = "colonist";

        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(name).append(", a ").append(job).append(" in a Minecraft colony.");

        // Include work context for builders
        if (data.getWorkBuilding() != null) {
            IBuilding workBuilding = data.getWorkBuilding();
            if (workBuilding.getBuildingLevel() > 0) {
                sb.append(" You are currently working at ").append(workBuilding.getBuildingDisplayName())
                        .append(" (level ").append(workBuilding.getBuildingLevel()).append(").");
            }
        }

        sb.append(" You just received ").append(itemCount).append(" ").append(itemName)
                .append(" from the deliveryman. React briefly to acknowledge the delivery.");

        return sb.toString();
    }

    @Nullable
    private static SpeakerAndPrompt selectSpeakerAndPrompt(
            ServerLevel world,
            BlockPos targetPos,
            @Nullable AbstractEntityCitizen deliveryman,
            String itemName,
            int itemCount
    ) {
        double range = McTalkingConfig.INSTANCE.instance().pregeneratedGreetingDistance;
        if (world.getEntitiesOfClass(net.minecraft.server.level.ServerPlayer.class,
                new AABB(targetPos.getX() - range, targetPos.getY() - range, targetPos.getZ() - range,
                        targetPos.getX() + range, targetPos.getY() + range, targetPos.getZ() + range)).isEmpty()) {
            return null;
        }

        AbstractEntityCitizen recipient = findNearestCitizen(world, targetPos.getX(), targetPos.getY(), targetPos.getZ());

        if (deliveryman != null && recipient != null && recipient != deliveryman && recipient.isAlive()
                && !ConversationManager.isCitizenBusy(recipient)
                && ThreadLocalRandom.current().nextBoolean()) {
            String prompt = buildRecipientPrompt(recipient, itemName, itemCount);
            if (prompt != null) return new SpeakerAndPrompt(recipient, prompt);
        }

        if (deliveryman != null && deliveryman.isAlive() && !ConversationManager.isCitizenBusy(deliveryman)) {
            String prompt = buildDeliverymanPrompt(deliveryman, itemName, itemCount);
            if (prompt != null) return new SpeakerAndPrompt(deliveryman, prompt);
        }

        if (recipient != null && recipient.isAlive() && !ConversationManager.isCitizenBusy(recipient)) {
            if (recipient.getCitizenData() != null) {
                String name = recipient.getCitizenData().getName();
                String prompt = "You are " + name
                        + ". React briefly to acknowledge a just-completed delivery of " + itemCount + " " + itemName
                        + " — respond as someone who just received or delivered these items to a fellow colonist.";
                return new SpeakerAndPrompt(recipient, prompt);
            }
        }

        return null;
    }

    @Nullable
    private static String extractJobName(ICitizenData data) {
        if (data.getJob() == null) return null;
        return Component.translatable(data.getJob().getJobRegistryEntry().getTranslationKey()).getString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void tryRetryFindDeliveryman(PendingDelivery pd) {
        IColony colony = IMinecoloniesAPI.getInstance().getColonyManager().getColonyByDimension(pd.colonyId, pd.dimension);
        if (colony == null) return;

        for (ICitizenData cd : colony.getCitizenManager().getCitizens()) {
            if (cd.getJob() instanceof JobDeliveryman dman) {
                IRequest<?> task = dman.getCurrentTask();
                if (task != null && task.getId().equals(pd.token)) {
                    cd.getEntity().ifPresent(entity -> pd.deliverymanUuid = entity.getUUID());
                    return;
                }
            }
        }
    }

    private static AbstractEntityCitizen findNearestCitizen(ServerLevel world, double x, double y, double z) {
        double range = McTalkingConfig.INSTANCE.instance().citizenInteractionRange;
        AABB searchBox = new AABB(x - range, y - range, z - range, x + range, y + range, z + range);
        AbstractEntityCitizen nearest = null;
        double nearestDistSq = Double.MAX_VALUE;

        for (AbstractEntityCitizen citizen : world.getEntitiesOfClass(AbstractEntityCitizen.class, searchBox)) {
            if (!citizen.isAlive()) continue;
            double distSq = citizen.distanceToSqr(x, y, z);
            if (distSq < nearestDistSq) {
                nearest = citizen;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }

    private static boolean startPregeneration(
            AbstractEntityCitizen citizen,
            String prompt,
            java.util.function.Consumer<AudioChunk> onComplete,
            Runnable onError
    ) {
        // A stale citizen entity (citizen removed, or colony data not loaded) has no
        // prompt view to build; skip it instead of failing in the prompt factory.
        if (citizen.getCitizenData() == null) return false;
        if (QuotaTracker.isQuotaExceeded(McTalkingConfig.CHEAP_LIVE_MODEL.getName())) return false;
        ConversationManager.BackgroundReservation reservation =
                ConversationManager.reserveBackgroundSlot(citizen, BackgroundSlotType.PREGEN);
        if (reservation == null) return false;

        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable fail = () -> {
            if (!finished.compareAndSet(false, true)) return;
            reservation.close();
            onError.run();
        };
        var promptView = CitizenPromptViewFactory.create(citizen.getCitizenData(), Map.of(), null);
        PregenerationGeminiClient client = new PregenerationGeminiClient(citizen.getUUID(), promptView, prompt, McTalkingConfig.CHEAP_LIVE_MODEL,
                audio -> {
                    if (!finished.compareAndSet(false, true)) return;
                    reservation.close();
                    onComplete.accept(audio);
                }, fail);

        try {
            if (!reservation.attachClient(client)) {
                fail.run();
                return false;
            }
            client.connect();
            return true;
        } catch (Exception e) {
            McTalking.LOGGER.error("[DeliveryInteraction] Failed to connect for citizen {}", citizen.getUUID(), e);
            try { client.close(); } catch (Exception ignored) { }
            fail.run();
            return false;
        }
    }
}
