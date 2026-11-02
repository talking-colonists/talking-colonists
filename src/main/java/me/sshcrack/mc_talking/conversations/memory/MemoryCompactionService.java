package me.sshcrack.mc_talking.conversations.memory;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.misc.GeminiFlash;
import me.sshcrack.gemini_live_lib.misc.UnexpectedResponseException;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.MemoryMode;
import me.sshcrack.mc_talking.config.QuotaTracker;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;
import me.sshcrack.mc_talking.util.BackgroundSlotType;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class MemoryCompactionService {
    private MemoryCompactionService() {
        /* This utility class should not be instantiated */
    }

    public static final String SYSTEM_PROMPT = """
            You are a memory summarization assistant for a Minecraft colony citizen.\
            Given the citizen's events and facts, produce a concise first-person summary.\
            Retain solely permanent traits, lasting historical milestones, established relationships, and enduring social connections. \
            Ensure all captured information remains true regardless of the current moment.\
            OUTPUT THE SUMMARY IN PLAIN, UNFORMATTED TEXT""";
    private static final Set<UUID> activeCompactionCitizens = ConcurrentHashMap.newKeySet();

    private static int tickCounter = 0;

    public static void tick(MinecraftServer server) {
        tickCounter++;
        int interval = Math.max(1, McTalkingConfig.INSTANCE.instance().memoryCompactionIntervalTicks);
        if (tickCounter % interval != 0) return;
        if (!McTalkingConfig.INSTANCE.instance().enableMemoryCompaction) return;

        var candidate = findBestCandidate(server);
        if (candidate == null) return;

        startCompaction(candidate);
    }

    @Nullable
    private static AbstractEntityCitizen findBestCandidate(MinecraftServer server) {
        int threshold = McTalkingConfig.INSTANCE.instance().memoryCompactionThreshold;
        List<AbstractEntityCitizen> candidates = new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (!(entity instanceof AbstractEntityCitizen citizen)) continue;
                if (citizen.getCitizenData() == null) continue;
                if (ConversationManager.isCitizenBusy(citizen)) continue;
                if (activeCompactionCitizens.contains(citizen.getUUID())) continue;

                var data = (CitizenDataMemoryExtended) citizen.getCitizenData();
                var mem = data.mc_talking$getOrInitializeMemory();

                if (mem.getEvents().size() + mem.getFacts().size() >= threshold) {
                    candidates.add(citizen);
                }
            }
        }

        if (candidates.isEmpty()) return null;

        candidates.sort(Comparator.<AbstractEntityCitizen>comparingInt(
                        c -> {
                            var d = (CitizenDataMemoryExtended) c.getCitizenData();
                            var m = d.mc_talking$getOrInitializeMemory();
                            return m.getEvents().size() + m.getFacts().size();
                        })
                .reversed());

        return candidates.get(0);
    }

    private static void startCompaction(AbstractEntityCitizen citizen) {
        McTalking.LOGGER.info("[MemoryCompaction] Starting compaction for citizen {}",
                citizen.getCitizenData().getName());

        var data = (CitizenDataMemoryExtended) citizen.getCitizenData();
        var mem = data.mc_talking$getOrInitializeMemory();

        McTalking.LOGGER.info("[MemoryCompaction] Citizen {} has {} events and {} facts",
                citizen.getCitizenData().getName(), mem.getEvents().size(), mem.getFacts().size());

        var config = McTalkingConfig.INSTANCE.instance();
        boolean flashUsedUp = config.enableLiveTextFallback && QuotaTracker.isQuotaExceeded(McTalkingConfig.FLASH_MODEL);
        if (config.memoryMode == MemoryMode.FLASH && !flashUsedUp) {
            startFlashCompaction(citizen);
        } else {
            startLiveCompaction(citizen);
        }
    }

    private static void startFlashCompaction(AbstractEntityCitizen citizen) {
        UUID citizenId = citizen.getUUID();
        if (!activeCompactionCitizens.add(citizenId)) return;
        var data = (CitizenDataMemoryExtended) citizen.getCitizenData();
        var mem = data.mc_talking$getOrInitializeMemory();
        var snapshot = mem.snapshotCompaction();
        String prompt = MemoryCompactionPrompt.render(citizen.getCitizenData().getName(), snapshot);
        var server = citizen.level().getServer();
        String apiKey = McTalkingConfig.INSTANCE.instance().geminiApiKey;

        Thread thread = new Thread(() -> {
            boolean handedOff = false;
            try {

                String responseText;
                try {
                    responseText = GeminiFlash.sendSimpleFlashRequest(McTalkingConfig.FLASH_MODEL, apiKey, SYSTEM_PROMPT, prompt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (UnexpectedResponseException | IOException e) {
                    McTalking.LOGGER.error("[MemoryCompaction] Flash request failed for citizen {}", citizen.getCitizenData().getName(), e);
                    return;
                }

                String summary = extractSummaryFromResponse(responseText);
                if (summary == null || summary.isBlank()) {
                    McTalking.LOGGER.warn("[MemoryCompaction] Empty summary from Flash for citizen {}", citizen.getCitizenData().getName());
                    return;
                }

                if (server != null) {
                    server.execute(() -> {
                        try { applyCompaction(citizen, snapshot, summary); }
                        finally { activeCompactionCitizens.remove(citizenId); }
                    });
                    handedOff = true;
                }
            } finally {
                if (!handedOff) activeCompactionCitizens.remove(citizenId);
            }
        }, "mc-talking-memory-flash-compaction");

        thread.setDaemon(true);
        thread.start();
    }

    private static void startLiveCompaction(AbstractEntityCitizen citizen) {
        UUID citizenId = citizen.getUUID();

        if (QuotaTracker.isQuotaExceeded(McTalkingConfig.CHEAP_LIVE_MODEL.getName())) return;
        ConversationManager.BackgroundReservation reservation =
                ConversationManager.reserveBackgroundSlot(citizen, BackgroundSlotType.COMPACTION);
        if (reservation == null) return;
        if (!activeCompactionCitizens.add(citizenId)) {
            reservation.close();
            return;
        }

        var data = (CitizenDataMemoryExtended) citizen.getCitizenData();
        var mem = data.mc_talking$getOrInitializeMemory();
        var snapshot = mem.snapshotCompaction();
        String prompt = MemoryCompactionPrompt.render(citizen.getCitizenData().getName(), snapshot);
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (!finished.compareAndSet(false, true)) return;
            activeCompactionCitizens.remove(citizenId);
            reservation.close();
        };

        MemoryCompactionWsClient client = new MemoryCompactionWsClient(citizen, prompt,
                summary -> {
                    if (summary != null && !summary.isBlank()) {
                        var server = citizen.level().getServer();
                        if (server != null) {
                            server.execute(() -> {
                                try {
                                    if (!finished.get() && reservation.isActive()) applyCompaction(citizen, snapshot, summary);
                                } finally { finish.run(); }
                            });
                            return;
                        }
                    }
                    finish.run();
                },
                () -> {
                    boolean wasActive = !finished.get();
                    finish.run();
                    if (wasActive) {
                        McTalking.LOGGER.warn("[MemoryCompaction] Live compaction failed for citizen {}", citizen.getCitizenData().getName());
                    }
                });

        try {
            if (!reservation.attachClient(client)) {
                finish.run();
                return;
            }
            client.connect();
        } catch (Exception e) {
            McTalking.LOGGER.error("[MemoryCompaction] Failed to connect Live client for citizen {}", citizen.getCitizenData().getName(), e);
            try { client.close(); } catch (Exception ignored) { }
            finish.run();
        }
    }

    @Nullable
    private static String extractSummaryFromResponse(String response) {
        if (response == null) return null;
        response = response.trim();
        if (response.startsWith("```")) {
            int start = response.indexOf('\n');
            int end = response.lastIndexOf("```");
            if (start > 0 && end > start) {
                response = response.substring(start, end).trim();
            }
        }
        return response;
    }

    private static void applyCompaction(AbstractEntityCitizen citizen, CitizenMemories.CompactionSnapshot snapshot, String summary) {
        if (!citizen.isAlive()) return;
        var data = (CitizenDataMemoryExtended) citizen.getCitizenData();
        if (data == null) return;

        var mem = data.mc_talking$getOrInitializeMemory();
        int eventCount = mem.getEvents().size();
        int factCount = mem.getFacts().size();

        McTalking.LOGGER.info("[MemoryCompaction] Applied compaction for citizen {} ({} events + {} facts -> {} chars)",
                citizen.getCitizenData().getName(), eventCount, factCount, summary.length());

        mem.applyCompaction(snapshot, summary);
        // TODO: consider compacting stale relationship entries in the future
    }

    public static int getActiveCount() {
        return activeCompactionCitizens.size();
    }

    public static void cleanup() {
        for (UUID citizenId : activeCompactionCitizens) {
            // Can't reliably release slots without the entity reference on shutdown
            McTalking.LOGGER.info("[MemoryCompaction] Cleanup: dropping active task for citizen {}", citizenId);
        }
        activeCompactionCitizens.clear();
    }

}
