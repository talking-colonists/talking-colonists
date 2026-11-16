package me.sshcrack.mc_talking.conversations.memory;

import com.minecolonies.api.colony.IVisitorData;
import me.sshcrack.mc_talking.api.memory.MemoryProvenance;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.api.memory.CitizenRelationshipDimension;
import me.sshcrack.mc_talking.conversations.memory.gson.GsonMemoryResponse;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import net.minecraft.server.MinecraftServer;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Generates and saves memories for a citizen after a conversation with a player.
 * Runs on a background thread to avoid blocking the server tick.
 */
public class PlayerConversationMemoryGenerator extends Thread {

    private static final String PROMPT_TEMPLATE = """
            Extract persistent memories for citizen %s from their conversation with player %s (colony rank: %s).

            The following is a partial transcript of what the citizen said during the conversation (the player's voice is not captured):
            ---
            %s
            ---

            Return ONLY valid JSON.

            Rules:
            - This transcript contains ONLY the citizen's captured speech. It contains no captured player words.
            - NEVER infer or record that the player said, promised, agreed to, offered, admitted, or committed to anything.
            - A citizen saying "you promised me X" is only the citizen's claim and cannot establish a player promise.
            - Write facts/events only about what the citizen themself said, felt, noticed, or discussed.
            - Only include meaningful information (ignore small talk).
            - Relationship changes may reflect the citizen's own feelings toward the player, as a float between -1.0 and 1.0.
            - Allowed relationship types: %s

            Format:
            {
              "citizens": [
                {
                  "name": "%s",
                  "memories": {
                    "relationships": [
                      {"target": "%s", "type": "trust", "change": 0.1}
                    ],
                    "facts": [
                      "I told %s that I care about the colony's well-being."
                    ],
                    "events": [
                      "Had a conversation with %s about colony supplies"
                    ]
                  }
                }
              ]
            }
            """;

    private final AbstractEntityCitizen citizen;
    private final UUID playerUuid;
    private final String playerName;
    private final String playerRank;
    private final String transcript;
    private final MinecraftServer server;

    private PlayerConversationMemoryGenerator(
            AbstractEntityCitizen citizen,
            UUID playerUuid,
            String playerName,
            String playerRank,
            String transcript,
            MinecraftServer server) {
        this.citizen = citizen;
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.playerRank = playerRank;
        this.transcript = transcript;
        this.server = server;
        setDaemon(true);
        setName("mc-talking-player-memory-" + citizen.getStringUUID());
    }

    @Override
    public void run() {
        try {
            if (citizen.getCitizenData() == null) {
                McTalking.LOGGER.warn("[PlayerMemory] Citizen {} has no citizen data — skipping memory generation", citizen.getUUID());
                return;
            }
            String citizenName = citizen.getCitizenData().getName();

            if (transcript.isBlank()) {
                McTalking.LOGGER.warn("[PlayerMemory] No transcript available for conversation with {} — skipping memory generation for citizen {}", playerName, citizenName);
                return;
            }

            McTalking.LOGGER.debug("[PlayerMemory] Generating memories for {} after conversation with {}", citizenName, playerName);

            String allowedTypes = Arrays.stream(CitizenRelationshipDimension.values())
                    .map(Enum::name)
                    .collect(Collectors.joining(", "));

            String prompt = String.format(PROMPT_TEMPLATE,
                    citizenName, playerName, playerRank,
                    transcript,
                    allowedTypes,
                    citizenName, playerName,
                    playerName, playerName);

            String responseJson;
            try {
                responseJson = MemoryTextRequest.generate(citizen, prompt, "Generate the memory JSON now.",
                        MemoryStructuredOutput.schema(MemoryResponseParser.ValidationContext.playerConversation(citizenName, playerName)),
                        "[PlayerMemory]");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                McTalking.LOGGER.debug("[PlayerMemory] Thread interrupted for citizen {}", citizenName);
                return;
            } catch (MemoryTextRequest.FailedException e) {
                McTalking.LOGGER.error("[PlayerMemory] Failed to generate player memories for citizen {}", citizenName, e);
                return;
            }

            GsonMemoryResponse response;
            try {
                response = MemoryResponseParser.parse(responseJson,
                        MemoryResponseParser.ValidationContext.playerConversation(citizenName, playerName));
            } catch (MemoryResponseParser.ValidationException e) {
                McTalking.LOGGER.warn("[PlayerMemory] Rejected invalid memory response for citizen {}: {}",
                        citizenName, e.getMessage());
                return;
            }

            server.execute(() -> {
                saveMemories(response, citizenName);
                McTalking.LOGGER.info("[PlayerMemory] Saved player-interaction memories for citizen {}", citizenName);
            });
        } finally {
            activeGenerators.remove(this);
        }
    }

    /** Facts and events a visitor keeps; older ones are forgotten. */
    public static final int VISITOR_MAX_MEMORIES = 10;

    private void saveMemories(GsonMemoryResponse response, String citizenName) {
        for (var gsonCitizen : response.citizens) {
            if (!gsonCitizen.name.equals(citizenName)) continue;

            if (citizen.getCitizenData() == null) continue;
            var data = (CitizenDataMemoryExtended) citizen.getCitizenData();
            var memory = data.mc_talking$getOrInitializeMemory();

            for (String fact : gsonCitizen.memories.facts) {
                memory.addFact(fact, MemoryProvenance.CITIZEN_STATEMENT, citizen.getUUID(), null, null);
            }
            for (String event : gsonCitizen.memories.events) {
                memory.addEvent(event, MemoryProvenance.CITIZEN_STATEMENT, citizen.getUUID(), null, null);
            }
            for (var rel : gsonCitizen.memories.relationships) {
                if (rel.target.equals(playerName) && rel.type != null) {
                    memory.addRelationshipChange(playerUuid, rel.type, rel.change,
                            MemoryProvenance.CITIZEN_STATEMENT, citizen.getUUID(), null, null);
                }
            }
            if (citizen.getCitizenData() instanceof IVisitorData) {
                // Visitors only keep a short-term memory; it moves with them if they are recruited.
                memory.keepNewest(VISITOR_MAX_MEMORIES);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Static lifecycle
    // -------------------------------------------------------------------------

    private static final List<PlayerConversationMemoryGenerator> activeGenerators = new CopyOnWriteArrayList<>();

    /**
     * Creates and starts a memory generation thread for a completed player conversation.
     *
     * @param citizen    the citizen who had the conversation
     * @param playerUuid the UUID of the player
     * @param playerName the display name of the player
     * @param playerRank the colony rank name of the player (e.g. "manager", "visitor")
     * @param transcript the partial transcript of the citizen's speech (can be blank for audio-only)
     * @param server     the Minecraft server for thread-safe NBT writes
     */
    public static void generateAndSave(
            AbstractEntityCitizen citizen,
            UUID playerUuid,
            String playerName,
            String playerRank,
            String transcript,
            MinecraftServer server) {
        var generator = new PlayerConversationMemoryGenerator(
                citizen, playerUuid, playerName, playerRank, transcript, server);
        activeGenerators.add(generator);
        generator.start();
    }

    /**
     * Interrupts all running generators. Called on server stop.
     */
    public static void stopAllGenerators() {
        for (var generator : activeGenerators) {
            generator.interrupt();
        }
        activeGenerators.clear();
    }
}
