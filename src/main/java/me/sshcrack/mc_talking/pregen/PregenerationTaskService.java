package me.sshcrack.mc_talking.pregen;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.api.pregen.PregenerationKind;
import me.sshcrack.mc_talking.api.pregen.PregenerationPromptContext;
import me.sshcrack.mc_talking.internal.api.PregenerationPromptRuntime;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.AvailableAI;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.manager.CitizenPromptViewFactory;
import me.sshcrack.mc_talking.onboarding.MissingApiKeyLogger;
import me.sshcrack.mc_talking.config.QuotaTracker;
import me.sshcrack.mc_talking.util.BackgroundSlotType;
import me.sshcrack.mc_talking.util.CitizenHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import me.sshcrack.gemini_live_lib.misc.GeminiTTS.AudioChunk;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class PregenerationTaskService {
    private static final AtomicInteger generatingCount = new AtomicInteger(0);
    private static final AtomicInteger playerGreetingActiveCount = new AtomicInteger(0);
    private static int tickCounter = 0;
    private static int decayTickCounter = 0;
    private static final Map<UUID, Long> lastThreatPlayTime = new ConcurrentHashMap<>();

    /**
     * Tracks the last time (System.currentTimeMillis()) a greeting was successfully
     * played for each ordered citizen pair (greeter -> greeted).  Used to enforce a
     * per-pair cooldown so that a dense group of citizens does not fire the same
     * greeting over and over while they remain close to each other.
     * <p>
     * The key is {@code greeterUUID + ":" + greetedUUID}.
     */
    private static final Map<String, Long> lastGreetingPlayTime = new ConcurrentHashMap<>();

    /**
     * How long (ms) before the same citizen pair may greet each other again. Default: 60 s.
     */
    private static final long GREETING_PLAY_COOLDOWN_MS = 60_000L;

    // Caches merged into the service
    // citizen UUID -> (friend UUID -> AudioChunk) - insertion ordered map for eviction
    private static final Map<UUID, Map<UUID, AudioChunk>> greetingCache = new ConcurrentHashMap<>();

    private static final Map<String, Long> lastPlayerGreetingPlayTime = new ConcurrentHashMap<>();
    private static final long PLAYER_GREETING_PLAY_COOLDOWN_MS = 60_000L;
    private static final Map<String, AudioChunk> playerGreetingCache = new ConcurrentHashMap<>();

    /**
     * Tracks which greeting cache keys are currently being regenerated.
     * Key format is {@code "greeterUUID:greetedUUID"}. Prevents duplicate
     * concurrent regeneration for the same pair. The entry is removed when
     * {@link #replaceGreeting} or {@link #replacePlayerGreeting} is called.
     */
    private static final Set<String> pendingRegen = ConcurrentHashMap.newKeySet();

    private static final String PLAYER_GREETING_PROMPT = "You are greeting %s who has just approached you. "
            + "Generate a single brief greeting sentence that authentically reflects your current mood, "
            + "health, concerns, and relationship with this person. "
            + "Do not use markdown. Stay in character.";

    public static void tick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter % 200 != 0) return;

        decayTickCounter++;
        if (decayTickCounter % 600 == 0) {
            HeatmapTracker.decayScores();
        }

        if (!McTalkingConfig.hasGeminiApiKey()) {
            MissingApiKeyLogger.warnOnce("pregeneration");
            return;
        }

        // Priority 1: Player greeting pregen — ensure at least 1 is running if possible
        if (McTalkingConfig.INSTANCE.instance().enablePlayerGreetingPregen && playerGreetingActiveCount.get() < 2) {
            List<PlayerHeatmapTracker.CitizenPlayerPair> topPlayerPairs = PlayerHeatmapTracker.getTopPairs(5);
            for (PlayerHeatmapTracker.CitizenPlayerPair pair : topPlayerPairs) {
                AbstractEntityCitizen citizen = findCitizen(server, pair.citizenId());
                ServerPlayer player = findPlayer(server, pair.playerId());

                if (citizen != null && player != null) {
                    if (citizen.getCitizenData() == null) continue;
                    if (ConversationManager.isCitizenBusy(citizen)) continue;

                    if (!hasPlayerGreeting(pair.citizenId(), pair.playerId())) {
                        String playerName = player.getName().getString();
                        startPregenerationIfPossible(citizen,
                                PLAYER_GREETING_PROMPT.formatted(playerName),
                                (audio) -> replacePlayerGreeting(pair.citizenId(), pair.playerId(), audio),
                                false, true);
                        return;
                    }
                }
            }

            // Fallback: no heatmap data yet or all pairs were busy/cached — pick any
            // online player and any non-busy citizen to seed the greeting cache.
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                for (ServerLevel level : server.getAllLevels()) {
                    for (Entity entity : level.getEntities().getAll()) {
                        if (!(entity instanceof AbstractEntityCitizen citizen)) continue;
                        // The entity can outlive its citizen data (e.g. the citizen was
                        // removed while its colony was far away); it has no prompt view.
                        if (citizen.getCitizenData() == null) continue;
                        if (ConversationManager.isCitizenBusy(citizen)) continue;

                        if (!hasPlayerGreeting(citizen.getUUID(), player.getUUID())) {
                            String playerName = player.getName().getString();
                            startPregenerationIfPossible(citizen,
                                    PLAYER_GREETING_PROMPT.formatted(playerName),
                                    (audio) -> replacePlayerGreeting(citizen.getUUID(), player.getUUID(), audio),
                                    false, true);
                            return;
                        }
                    }
                }
            }
        }

        // Priority 2: Citizen↔citizen greeting pregen — if background pool has capacity
        if (ConversationManager.hasFreeBackgroundCapacity(1)) {
            List<HeatmapTracker.UUIDPair> topPairs = HeatmapTracker.getTopPairs(5);
            for (HeatmapTracker.UUIDPair pair : topPairs) {
                AbstractEntityCitizen c1 = findCitizen(server, pair.id1());
                AbstractEntityCitizen c2 = findCitizen(server, pair.id2());

                if (c1 != null && c2 != null) {
                    if (ConversationManager.isCitizenBusy(c1) || ConversationManager.isCitizenBusy(c2)) continue;
                    if (c1.getCitizenData() == null || c2.getCitizenData() == null) continue;

                    if (!hasGreeting(c1.getUUID(), c2.getUUID())) {
                        startPregenerationIfPossible(c1,
                                "Generate a brief 1-sentence passing greeting for your friend " + c2.getCitizenData().getName() + ".",
                                (audio) -> replaceGreeting(c1.getUUID(), c2.getUUID(), audio),
                                false, false);
                        return;
                    }

                    if (!hasGreeting(c2.getUUID(), c1.getUUID())) {
                        startPregenerationIfPossible(c2,
                                "Generate a brief 1-sentence passing greeting for your friend " + c1.getCitizenData().getName() + ".",
                                (audio) -> replaceGreeting(c2.getUUID(), c1.getUUID(), audio),
                                false, false);
                        return;
                    }
                }
            }
        }
    }

    private static boolean startPregenerationIfPossible(AbstractEntityCitizen citizen, String prompt,
                                                         java.util.function.Consumer<AudioChunk> onComplete,
                                                         boolean isThreat, boolean isPlayerGreeting) {
        // Pregeneration builds a prompt view from the citizen data, which a stale entity
        // (removed citizen, or citizen data not loaded) does not have.
        if (citizen.getCitizenData() == null) return false;

        if (!McTalkingConfig.hasGeminiApiKey()) {
            MissingApiKeyLogger.warnOnce("pregeneration");
            return false;
        }

        PregenerationKind kind = isThreat
                ? PregenerationKind.THREAT
                : (isPlayerGreeting ? PregenerationKind.PLAYER_GREETING : PregenerationKind.CITIZEN_GREETING);
        prompt = PregenerationPrompts.withCacheSafetyNote(prompt, kind);
        prompt = PregenerationPromptRuntime.apply(new PregenerationPromptContext(citizen, kind), prompt);

        AvailableAI model = isThreat
                ? McTalkingConfig.INSTANCE.instance().currentAiModel
                : McTalkingConfig.CHEAP_LIVE_MODEL;

        if (QuotaTracker.isQuotaExceeded(model.getName())) return false;

        // Generation itself is silent background work, even for a threat line.
        // Keeping it out of the foreground pool means a player can always preempt
        // it cleanly without a late completion callback releasing the player's slot.
        if (isThreat) {
            ConversationManager.cancelBackgroundSlot(citizen.getUUID(), "replaced by immediate threat pregeneration");
        }
        ConversationManager.BackgroundReservation reservation =
                ConversationManager.reserveBackgroundSlot(citizen, BackgroundSlotType.PREGEN);
        if (reservation == null) return false;

        generatingCount.incrementAndGet();
        if (isPlayerGreeting) playerGreetingActiveCount.incrementAndGet();
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (!finished.compareAndSet(false, true)) return;
            reservation.close();
            generatingCount.decrementAndGet();
            if (isPlayerGreeting) playerGreetingActiveCount.decrementAndGet();
        };

        McTalking.LOGGER.info("[Pregeneration] Starting {} for citizen {} (threat={}, model={})",
                isThreat ? "threat" : "pregen", citizen.getUUID(), isThreat, model.getName());

        var promptView = CitizenPromptViewFactory.create(citizen.getCitizenData(), Map.of(), null);
        PregenerationGeminiClient client = new PregenerationGeminiClient(citizen.getUUID(), promptView, prompt, model,
                audio -> {
                    finish.run();
                    onComplete.accept(audio);
                },
                finish);

        try {
            if (!reservation.attachClient(client)) {
                finish.run();
                return false;
            }
            client.connect();
            return true;
        } catch (Exception e) {
            McTalking.LOGGER.error("[Pregeneration] Failed to connect for citizen {}", citizen.getUUID(), e);
            try { client.close(); } catch (Exception ignored) { }
            finish.run();
            return false;
        }
    }

    /**
     * Attempts to play a pregenerated threat for the given citizen. If none exists,
     * optionally starts a pregeneration that includes the attacker's name in the prompt.
     */
    public static void playThreatNow(AbstractEntityCitizen citizen, Entity attacker) {
        if (ConversationManager.isCitizenBusy(citizen)) {
            return;
        }

        if (citizen.getCitizenData() == null)
            return;

        long now = System.currentTimeMillis();
        int cfg = McTalkingConfig.INSTANCE.instance().threatPlayCooldownMs;
        long cooldownMs = Math.max(0, cfg);
        Long lastPlay = lastThreatPlayTime.get(citizen.getUUID());
        if (lastPlay != null && cooldownMs > 0 && now - lastPlay < cooldownMs) {
            return;
        }

        McTalking.LOGGER.info("Generating new threat audio for citizen {}, difference is {}", citizen.getUUID(), lastPlay == null ? "null" : now - lastPlay);
        // Build attacker-aware prompt
        String attackerName = (attacker != null) ? attacker.getName().getString() : null;
        String prompt;
        if (CitizenHelper.isCitizenGuard(citizen)) {
            if (attackerName != null && !attackerName.isEmpty()) {
                prompt = "Generate a brief 1-sentence exclaim because you are fighting a " + attackerName + " as a guard right now and are helping your fellow colonists";
            } else {
                prompt = "Generate a brief 1-sentence exclaim because you are defending your fellow colonists as a guard from a monster";
            }
        } else {
            if (attackerName != null && !attackerName.isEmpty()) {
                prompt = "Generate a brief 1-sentence panic or cry for help because you are being attacked by " + attackerName + ".";
            } else {
                prompt = "Generate a brief 1-sentence panic or cry for help because you are being attacked by a monster.";
            }
        }

        startPregenerationIfPossible(citizen, prompt, audio -> {
            long playedAt = System.currentTimeMillis();
            boolean played = PregenerationPlayback.playAudioIfPossible(citizen, audio);
            if (played) {
                lastThreatPlayTime.put(citizen.getUUID(), playedAt);
            } else {
                lastThreatPlayTime.remove(citizen.getUUID());
            }
        }, true, false);
    }

    // Cache management methods

    /**
     * Returns {@code true} if the (greeter, greeted) pair is still within its greeting play cooldown.
     */
    public static boolean isGreetingOnCooldown(UUID greeterId, UUID greetedId) {
        String key = greeterId + ":" + greetedId;
        Long last = lastGreetingPlayTime.get(key);
        return last != null && (System.currentTimeMillis() - last) < GREETING_PLAY_COOLDOWN_MS;
    }

    /**
     * Records that a greeting was just successfully played from greeter to greeted.
     */
    public static void recordGreetingPlayed(UUID greeterId, UUID greetedId) {
        lastGreetingPlayTime.put(greeterId + ":" + greetedId, System.currentTimeMillis());
    }

    public static boolean hasGreeting(UUID citizenId, UUID friendId) {
        Map<UUID, AudioChunk> friends = greetingCache.get(citizenId);
        return friends != null && friends.containsKey(friendId);
    }

    /**
     * Returns the cached greeting audio WITHOUT removing it from the cache.
     * The entry stays alive until {@link #replaceGreeting} atomically swaps it.
     */
    public static AudioChunk peekGreeting(UUID citizenId, UUID friendId) {
        Map<UUID, AudioChunk> friends = greetingCache.get(citizenId);
        if (friends == null) return null;
        synchronized (friends) {
            return friends.get(friendId);
        }
    }

    /**
     * Atomically replaces the greeting for (citizenId, friendId) with newAudio,
     * or simply stores it if none existed. Also clears the pendingRegen flag.
     */
    public static void replaceGreeting(UUID citizenId, UUID friendId, AudioChunk newAudio) {
        String regenKey = citizenId + ":" + friendId;
        Map<UUID, AudioChunk> friends = greetingCache.computeIfAbsent(citizenId, k -> Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (friends) {
            friends.put(friendId, newAudio);
            int max = McTalkingConfig.INSTANCE.instance().maxPregeneratedGreetingsPerCitizen;
            if (max > 0 && friends.size() > max) {
                Iterator<UUID> it = friends.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
        pendingRegen.remove(regenKey);
    }

    /**
     * Removes and returns the cached greeting for (citizenId, friendId), or null.
     * The caller is expected to re-insert via {@link #putGreeting} if playback fails.
     */
    public static AudioChunk popGreeting(UUID citizenId, UUID friendId) {
        Map<UUID, AudioChunk> friends = greetingCache.get(citizenId);
        if (friends == null) return null;
        synchronized (friends) {
            return friends.remove(friendId);
        }
    }

    /**
     * Stores a greeting back into the cache after a failed playback attempt.
     */
    public static void putGreeting(UUID citizenId, UUID friendId, AudioChunk audio) {
        Map<UUID, AudioChunk> friends = greetingCache.computeIfAbsent(citizenId, k -> Collections.synchronizedMap(new LinkedHashMap<>()));
        synchronized (friends) {
            friends.put(friendId, audio);
        }
    }

    public static boolean hasPlayerGreeting(UUID citizenId, UUID playerId) {
        return playerGreetingCache.containsKey(citizenId + ":" + playerId);
    }

    /**
     * Returns the cached player greeting audio WITHOUT removing it from the cache.
     * The entry stays alive until {@link #replacePlayerGreeting} atomically swaps it.
     */
    public static AudioChunk peekPlayerGreeting(UUID citizenId, UUID playerId) {
        return playerGreetingCache.get(citizenId + ":" + playerId);
    }

    /**
     * Atomically replaces the player greeting for (citizenId, playerId) with newAudio,
     * or simply stores it if none existed. Also clears the pendingRegen flag.
     */
    public static void replacePlayerGreeting(UUID citizenId, UUID playerId, AudioChunk newAudio) {
        playerGreetingCache.put(citizenId + ":" + playerId, newAudio);
        pendingRegen.remove(citizenId + ":" + playerId);
    }

    /**
     * Removes and returns the cached player greeting for (citizenId, playerId), or null.
     * The caller is expected to re-insert via {@link #putPlayerGreeting} if playback fails.
     */
    public static AudioChunk popPlayerGreeting(UUID citizenId, UUID playerId) {
        return playerGreetingCache.remove(citizenId + ":" + playerId);
    }

    /**
     * Stores a player greeting back into the cache after a failed playback attempt.
     */
    public static void putPlayerGreeting(UUID citizenId, UUID playerId, AudioChunk audio) {
        playerGreetingCache.put(citizenId + ":" + playerId, audio);
    }

    public static boolean isPlayerGreetingOnCooldown(UUID citizenId, UUID playerId) {
        String key = citizenId + ":" + playerId;
        Long last = lastPlayerGreetingPlayTime.get(key);
        return last != null && (System.currentTimeMillis() - last) < PLAYER_GREETING_PLAY_COOLDOWN_MS;
    }

    public static void recordPlayerGreetingPlayed(UUID citizenId, UUID playerId) {
        lastPlayerGreetingPlayTime.put(citizenId + ":" + playerId, System.currentTimeMillis());
    }

    public static boolean generatePlayerGreetingNow(AbstractEntityCitizen citizen, String playerName, java.util.function.Consumer<AudioChunk> onComplete) {
        return startPregenerationIfPossible(citizen, PLAYER_GREETING_PROMPT.formatted(playerName), onComplete, false, true);
    }

    /**
     * Schedules a background regeneration of the citizen-to-citizen greeting.
     * The existing cached greeting (if any) is kept intact until the new one
     * arrives and is atomically swapped in via {@link #replaceGreeting}.
     * No-ops if a regen for this pair is already in flight.
     */
    public static void scheduleGreetingRegen(AbstractEntityCitizen greeter, AbstractEntityCitizen greeted) {
        if (greeter.getCitizenData() == null || greeted.getCitizenData() == null) return;

        String key = greeter.getUUID() + ":" + greeted.getUUID();
        if (!pendingRegen.add(key)) return;

        String prompt = PregenerationPrompts.citizenGreeting(greeted.getCitizenData().getName());
        boolean started = startPregenerationIfPossible(greeter, prompt, audio -> {
            replaceGreeting(greeter.getUUID(), greeted.getUUID(), audio);
        }, false, false);
        if (!started) pendingRegen.remove(key);
    }

    /**
     * Schedules a background regeneration of the citizen-to-player greeting.
     * The existing cached greeting (if any) is kept intact until the new one
     * arrives and is atomically swapped in via {@link #replacePlayerGreeting}.
     * No-ops if a regen for this pair is already in flight.
     */
    public static void schedulePlayerGreetingRegen(AbstractEntityCitizen citizen, ServerPlayer player) {
        String key = citizen.getUUID() + ":" + player.getUUID();
        if (!pendingRegen.add(key)) return;

        String playerName = player.getName().getString();
        boolean started = startPregenerationIfPossible(citizen, PLAYER_GREETING_PROMPT.formatted(playerName), audio -> {
            replacePlayerGreeting(citizen.getUUID(), player.getUUID(), audio);
        }, false, true);
        if (!started) pendingRegen.remove(key);
    }

    public static boolean isPregenerating() {
        return generatingCount.get() > 0;
    }

    public static int getGreetingCount(UUID citizenId) {
        Map<UUID, AudioChunk> friends = greetingCache.get(citizenId);
        return friends != null ? friends.size() : 0;
    }

    public static int getPlayerGreetingCount(UUID citizenId) {
        return (int) playerGreetingCache.keySet().stream()
                .filter(k -> k.startsWith(citizenId + ":"))
                .count();
    }

    public static void cleanup() {
        lastThreatPlayTime.clear();
        generatingCount.set(0);
        playerGreetingActiveCount.set(0);
        tickCounter = 0;
        decayTickCounter = 0;
        lastGreetingPlayTime.clear();
        greetingCache.clear();
        lastPlayerGreetingPlayTime.clear();
        playerGreetingCache.clear();
        pendingRegen.clear();
        PlayerHeatmapTracker.clear();
    }

    private static AbstractEntityCitizen findCitizen(MinecraftServer server, java.util.UUID uuid) {
        return CitizenHelper.findCitizen(server, uuid);
    }

    private static ServerPlayer findPlayer(MinecraftServer server, java.util.UUID uuid) {
        return server.getPlayerList().getPlayer(uuid);
    }
}
