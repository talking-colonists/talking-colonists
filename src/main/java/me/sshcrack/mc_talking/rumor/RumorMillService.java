package me.sshcrack.mc_talking.rumor;

import me.sshcrack.mc_talking.broadcast.GossipMoments;
import com.minecolonies.api.IMinecoloniesAPI;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.AmbientSessions;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;

import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jetbrains.annotations.Nullable;

public class RumorMillService {
    private RumorMillService() {
    }

    public static void tick(MinecraftServer server) {
        var cfg = McTalkingConfig.INSTANCE.instance();
        if (!cfg.enableRumorMill) return;

        int maxPropagations = cfg.rumorMillMaxPropagationsPerTick;
        double range = cfg.rumorMillRange;
        double chance = cfg.rumorMillChancePerPair;

        int propagated = 0;
        Set<Long> processedPairs = new HashSet<>();

        for (ServerLevel level : server.getAllLevels()) {
            if (propagated >= maxPropagations) break;

            for (IColony colony : IMinecoloniesAPI.getInstance().getColonyManager().getColonies(level)) {
                if (propagated >= maxPropagations) break;

                List<ICitizenData> allCitizens = new ArrayList<>(colony.getCitizenManager().getCitizens());
                if (allCitizens.size() < 2) continue;

                List<AbstractEntityCitizen> entities = new ArrayList<>();
                for (ICitizenData data : allCitizens) {
                    var entityOpt = data.getEntity();
                    if (entityOpt.isPresent() && entityOpt.get().isAlive()) {
                        entities.add(entityOpt.get());
                    }
                }

                for (int i = 0; i < entities.size() && propagated < maxPropagations; i++) {
                    AbstractEntityCitizen c1 = entities.get(i);
                    for (int j = i + 1; j < entities.size() && propagated < maxPropagations; j++) {
                        AbstractEntityCitizen c2 = entities.get(j);

                        PairMode mode = pairMode(ConversationManager.isAsleep(c1), ConversationManager.isAsleep(c2),
                                sameHome(c1, c2), c1.distanceToSqr(c2) <= range * range);
                        if (mode == PairMode.SKIP) continue;

                        long pairKey = ((long) Math.min(c1.getId(), c2.getId()) << 32)
                                | (Math.max(c1.getId(), c2.getId()) & 0xFFFFFFFFL);
                        if (!processedPairs.add(pairKey)) continue;

                        if (ConversationManager.isCitizenBusy(c1) || ConversationManager.isCitizenBusy(c2)) continue;
                        if (c1.getCitizenData() == null || c2.getCitizenData() == null) continue;

                        if (ThreadLocalRandom.current().nextDouble() >= chance) continue;

                        if (propagated < maxPropagations) {
                            Rumor rumor = propagateRumor(c1, c2);
                            if (rumor != null) {
                                propagated++;
                                McTalking.LOGGER.info("[RumorMill] {} shared a rumor with {}",
                                        c1.getCitizenData().getName(), c2.getCitizenData().getName());
                                if (mode == PairMode.AWAKE && !attemptRumorTalking(c1, c2, rumor, server, cfg)) {
                                    GossipMoments.start(c1, c2, GossipMoments.Kind.RUMOR, null);
                                }
                            }
                        }

                        if (propagated < maxPropagations) {
                            Rumor rumor = propagateRumor(c2, c1);
                            if (rumor != null) {
                                propagated++;
                                McTalking.LOGGER.info("[RumorMill] {} shared a rumor with {}",
                                        c2.getCitizenData().getName(), c1.getCitizenData().getName());
                                if (mode == PairMode.AWAKE && !attemptRumorTalking(c2, c1, rumor, server, cfg)) {
                                    GossipMoments.start(c2, c1, GossipMoments.Kind.RUMOR, null);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** How two citizens may pass rumors on right now. */
    enum PairMode {
        /** Not together. */
        SKIP,
        /** Awake and close: the rumor may be voiced or shown as a gossip moment. */
        AWAKE,
        /** Housemates going to sleep: the rumor passes silently, as pillow talk, without voice or bubble. */
        PILLOW_TALK
    }

    /**
     * Awake citizens gossip when they are close. When either one is asleep, only housemates share
     * anything, and only silently: they spend the night under the same roof.
     */
    static PairMode pairMode(boolean asleep1, boolean asleep2, boolean housemates, boolean inRange) {
        if (asleep1 || asleep2) return housemates ? PairMode.PILLOW_TALK : PairMode.SKIP;
        return inRange ? PairMode.AWAKE : PairMode.SKIP;
    }

    private static boolean sameHome(AbstractEntityCitizen c1, AbstractEntityCitizen c2) {
        var d1 = c1.getCitizenData();
        var d2 = c2.getCitizenData();
        if (d1 == null || d2 == null || d1.getHomeBuilding() == null || d2.getHomeBuilding() == null) return false;
        return d1.getHomeBuilding().getPosition().equals(d2.getHomeBuilding().getPosition());
    }

    /** Voices the rumor when a player is near; returns whether a spoken line started. */
    private static boolean attemptRumorTalking(AbstractEntityCitizen source, AbstractEntityCitizen target, Rumor rumor, MinecraftServer server, McTalkingConfig cfg) {
        if (!cfg.enableRumorTalking) return false;
        if (ThreadLocalRandom.current().nextDouble() >= cfg.rumorTalkingChance) return false;
        if (!ConversationManager.hasPlayerNearby(source, server, cfg.rumorTalkingRange)) return false;

        String targetName    = target.getCitizenData().getName();
        String originatorName = rumor.getOriginatorName();
        String content       = rumor.getContent();

        String prompt = String.format(
                "## CURRENT TASK\n" +
                "Turn to %s and tell them a piece of news you heard. " +
                "You originally heard it from %s: \"%s\". " +
                "Say it naturally in character — keep it to one or two sentences. " +
                "Do not repeat the source attribution verbatim; weave it into conversation.",
                targetName, originatorName, content
        );

        return AmbientSessions.startLowPrioritySession(source, prompt);
    }

    @Nullable
    private static Rumor propagateRumor(AbstractEntityCitizen source, AbstractEntityCitizen target) {
        var sourceData = (CitizenDataMemoryExtended) source.getCitizenData();
        var targetData = (CitizenDataMemoryExtended) target.getCitizenData();

        var sourceMem = sourceData.mc_talking$getMemory();
        if (sourceMem == null) return null;

        var targetMem = targetData.mc_talking$getOrInitializeMemory();

        // Try to share a rumor the target hasn't heard yet.
        for (Rumor r : sourceMem.getReceivedRumors()) {
            if (!targetMem.hasHeardRumor(r.getId())) {
                targetMem.addRumor(r);
                return r;
            }
        }

        // No unheard rumors — promote a first-hand event to a new rumor and
        // remove it from the events list to prevent the same event being
        // rumorized twice.  The event is now reflected in receivedRumors.
        String content = takeFirstHandEvent(sourceMem);
        if (content == null) return null;
        String originatorName = source.getCitizenData().getName();
        String id = UUID.randomUUID().toString();

        Rumor newRumor = new Rumor(id, originatorName, content);
        sourceMem.addRumor(newRumor);
        targetMem.addRumor(newRumor);
        return newRumor;
    }

    /** Consume one firsthand recollection before promoting it into the rumor collection. */
    @Nullable
    static String takeFirstHandEvent(CitizenMemories sourceMem) {
        List<String> events = sourceMem.getEvents();
        List<Integer> firstHandIndices = new ArrayList<>();
        for (int idx = 0; idx < events.size(); idx++) {
            if (!events.get(idx).startsWith("Rumor:") && !sourceMem.isPrivateEvent(events.get(idx))) {
                firstHandIndices.add(idx);
            }
        }
        if (firstHandIndices.isEmpty()) return null;

        int pickIdx = firstHandIndices.get(ThreadLocalRandom.current().nextInt(firstHandIndices.size()));
        String content = events.get(pickIdx);
        // getEvents() is a snapshot. Mutate the owner so its provenance entries and
        // pending compaction validation stay consistent with the event collection.
        return sourceMem.removeEvent(content) ? content : null;
    }
}
