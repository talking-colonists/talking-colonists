package me.sshcrack.mc_talking.devtools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ConversationUtteranceEvent;
import me.sshcrack.mc_talking.api.conversation.PlayerConversationOptions;
import me.sshcrack.mc_talking.api.conversation.PlayerTextResult;
import me.sshcrack.mc_talking.api.registration.AddonRegistration;
import me.sshcrack.mc_talking.api.text.CitizenTextService;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.mc_talking.api.text.TextResult;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.QuotaTracker;
import me.sshcrack.mc_talking.conversations.memory.PlayerConversationMemoryGenerator;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import me.sshcrack.mc_talking.manager.GeminiWsClient;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Optional live-provider check for typed player input (A4) and chat-to-citizen (Q10), run by the
 * auto-quit client after {@link DevRuntimeVerification}. Only runs when the environment variable
 * {@code MC_TALKING_LIVE_KEY_FILE} names a file holding a Gemini key. The key is read into memory
 * for this run only: it is never logged, printed or saved to the config file.
 */
final class DevLiveConversationVerification {
    private static final String KEY_FILE_ENV = "MC_TALKING_LIVE_KEY_FILE";
    private static final long REPLY_TIMEOUT_SECONDS = 45;

    private DevLiveConversationVerification() {
    }

    static boolean isConfigured() {
        String path = System.getenv(KEY_FILE_ENV);
        return path != null && !path.isBlank() && Files.isReadable(Path.of(path));
    }

    static void verify(MinecraftServer server, AbstractEntityCitizen citizen) throws Exception {
        var config = McTalkingConfig.INSTANCE.instance();
        String savedKey = config.geminiApiKey;
        boolean savedMemory = config.enableConversationSummaryAndMemorize;
        LinkedBlockingQueue<ConversationUtteranceEvent> events = new LinkedBlockingQueue<>();
        AddonRegistration registration = null;
        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        try {
            String key = Files.readString(Path.of(System.getenv(KEY_FILE_ENV))).strip();
            require(!key.isEmpty(), "live key file is empty");
            registration = server.submit(() -> {
                config.geminiApiKey = key;
                // Turns on output transcription so the citizen's answer can be read.
                config.enableConversationSummaryAndMemorize = true;
                return CitizenConversationService.registerUtteranceListener("mc_talking_dev:live", 0, events::add);
            }).get(5, TimeUnit.SECONDS);

            // No memory extraction at the end: that would be an extra Flash-Lite request. The check
            // then uses only the Live model.
            var started = server.submit(() -> CitizenConversationService.startPlayerConversation(player, citizen,
                    PlayerConversationOptions.defaults().withMemoryExtraction(false))).get(10, TimeUnit.SECONDS);
            require(started.started(), "live player conversation start: " + started);
            waitForReady(server, citizen);
            McTalking.LOGGER.info("MC_TALKING_LIVE: provider ready");

            // A4: typed text is a player turn the citizen answers, and it is recorded for memory.
            String typed = "Please answer in one short sentence and use the word pumpkin.";
            PlayerTextResult sent = server.submit(() -> CitizenConversationService.sendPlayerText(player, citizen, typed))
                    .get(5, TimeUnit.SECONDS);
            require(sent.isDelivered(), "A4 sendPlayerText delivered: " + sent);
            ConversationUtteranceEvent typedEvent = next(events, ConversationUtteranceEvent.Speaker.PLAYER);
            require(typedEvent.source() == ConversationUtteranceEvent.Source.TYPED && typed.equals(typedEvent.text())
                    && player.getUUID().equals(typedEvent.speakerId()), "A4 typed utterance event: " + typedEvent);
            String answer = next(events, ConversationUtteranceEvent.Speaker.CITIZEN).text();
            McTalking.LOGGER.info("MC_TALKING_LIVE: A4 citizen answered: {}", answer);
            require(answer.toLowerCase(Locale.ROOT).contains("pumpkin"), "A4 citizen answer uses the typed request");
            GeminiWsClient client = Objects.requireNonNull(ConversationManager.getClientForEntity(citizen.getUUID()),
                    "A4 active provider client");
            require(client.getSessionTranscriptSnapshot().contains(player.getName().getString() + ": " + typed),
                    "A4 typed line recorded in the session transcript");

            PlayerTextResult note = server.submit(() -> CitizenConversationService.addContext(player, citizen,
                    "The player just handed you a shiny golden apple.")).get(5, TimeUnit.SECONDS);
            require(note.isDelivered(), "A4 addContext delivered: " + note);
            String reaction = next(events, ConversationUtteranceEvent.Speaker.CITIZEN).text();
            McTalking.LOGGER.info("MC_TALKING_LIVE: A4 citizen reacted to the context note: {}", reaction);

            // Q10: the client really sends chat; the server routes it to the citizen instead of chat.
            String chat = "Please answer in one short sentence and use the word lantern.";
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> minecraft.player.connection.sendChat("@ " + chat));
            ConversationUtteranceEvent chatEvent = next(events, ConversationUtteranceEvent.Speaker.PLAYER);
            require(chatEvent.source() == ConversationUtteranceEvent.Source.TYPED && chat.equals(chatEvent.text()),
                    "Q10 chat line routed as a typed utterance: " + chatEvent);
            String chatAnswer = next(events, ConversationUtteranceEvent.Speaker.CITIZEN).text();
            McTalking.LOGGER.info("MC_TALKING_LIVE: Q10 citizen answered: {}", chatAnswer);
            require(chatAnswer.toLowerCase(Locale.ROOT).contains("lantern"), "Q10 citizen answer uses the chat request");

            List<String> chatLog = minecraft.submit(DevLiveConversationVerification::clientChat).get(5, TimeUnit.SECONDS);
            require(chatLog.stream().anyMatch(line -> line.startsWith("You → ") && line.endsWith(chat)),
                    "Q10 local echo shown: " + chatLog);
            require(chatLog.stream().noneMatch(line -> line.contains("@ " + chat)),
                    "Q10 line not broadcast to server chat: " + chatLog);

            // Memory fallback: with Flash-Lite marked used up (in memory, this run only), the real
            // player-memory generator must write memories through the Live model instead.
            String transcript = client.getSessionTranscriptSnapshot();
            server.submit(() -> ConversationManager.endConversation(player.getUUID(), false)).get(5, TimeUnit.SECONDS);
            var memory = server.submit(() -> ((CitizenDataMemoryExtended) citizen.getCitizenData())
                    .mc_talking$getOrInitializeMemory()).get(5, TimeUnit.SECONDS);
            int before = memoryCount(server, memory);
            QuotaTracker.reportQuotaExceeded(McTalkingConfig.FLASH_MODEL, TimeUnit.MINUTES.toMillis(10));
            TextResult headline;
            try {
                server.submit(() -> PlayerConversationMemoryGenerator.generateAndSave(citizen, player.getUUID(),
                        player.getName().getString(), "leader", transcript, server)).get(5, TimeUnit.SECONDS);
                long memoryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
                while (memoryCount(server, memory) <= before && System.nanoTime() < memoryDeadline) Thread.sleep(500);

                // A3 text generation takes the same fallback; a schema request checks the JSON path.
                JsonObject schema = JsonParser.parseString("{\"type\":\"object\",\"properties\":{\"headline\":"
                        + "{\"type\":\"string\"}},\"required\":[\"headline\"]}").getAsJsonObject();
                headline = server.submit(() -> CitizenTextService.generate(citizen, TextRequest.of("mc_talking_dev:headline",
                                "Write a short newspaper headline about the colony's pumpkin harvest.").withResponseSchema(schema)))
                        .get(5, TimeUnit.SECONDS).get(150, TimeUnit.SECONDS);
            } finally {
                QuotaTracker.reportSuccess(McTalkingConfig.FLASH_MODEL);
            }
            int after = memoryCount(server, memory);
            require(after > before, "memory written through the Live fallback (" + before + " -> " + after + ")");
            McTalking.LOGGER.info("MC_TALKING_LIVE: memory fallback saved facts={} events={}",
                    server.submit(memory::getFacts).get(5, TimeUnit.SECONDS),
                    server.submit(memory::getEvents).get(5, TimeUnit.SECONDS));
            JsonObject headlineJson = headline.json();
            require(headline.isSuccess() && headlineJson != null && headlineJson.has("headline"),
                    "A3 text generated through the Live fallback: " + headline);
            McTalking.LOGGER.info("MC_TALKING_LIVE: A3 text fallback headline: {}",
                    Objects.requireNonNull(headlineJson).get("headline"));
            McTalking.LOGGER.info("MC_TALKING_LIVE_SUCCESS:a4-text,a4-transcript,a4-context,q10-route,q10-echo,q10-no-broadcast,memory-live-fallback,a3-text-live-fallback");
        } finally {
            AddonRegistration toClose = registration;
            server.submit(() -> {
                if (ConversationManager.isPlayerInConversation(player.getUUID())) {
                    ConversationManager.endConversation(player.getUUID(), false);
                }
                if (toClose != null) toClose.close();
                config.geminiApiKey = savedKey;
                config.enableConversationSummaryAndMemorize = savedMemory;
            }).get(10, TimeUnit.SECONDS);
        }
    }

    private static int memoryCount(MinecraftServer server, CitizenMemories memory) throws Exception {
        return server.submit(() -> memory.getFacts().size() + memory.getEvents().size()).get(5, TimeUnit.SECONDS);
    }

    private static void waitForReady(MinecraftServer server, AbstractEntityCitizen citizen) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            boolean ready = server.submit(() -> CitizenConversationService.providerStatus(citizen)
                    .map(status -> status.readyForInput()).orElse(false)).get(5, TimeUnit.SECONDS);
            if (ready) return;
            Thread.sleep(200);
        }
        throw new IllegalStateException("Verification failed: live provider never became ready");
    }

    /** The next utterance from {@code speaker}; other speakers' lines are skipped. */
    private static ConversationUtteranceEvent next(LinkedBlockingQueue<ConversationUtteranceEvent> events,
                                                   ConversationUtteranceEvent.Speaker speaker) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REPLY_TIMEOUT_SECONDS);
        while (true) {
            long left = deadline - System.nanoTime();
            ConversationUtteranceEvent event = left <= 0 ? null : events.poll(left, TimeUnit.NANOSECONDS);
            if (event == null) throw new IllegalStateException("Verification failed: no " + speaker + " utterance");
            if (event.speaker() == speaker) return event;
        }
    }

    /** Plain text of every line in the chat HUD, read without depending on field names. */
    private static List<String> clientChat() {
        List<String> lines = new ArrayList<>();
        ChatComponent chat = Minecraft.getInstance().gui.getChat();
        for (Field field : ChatComponent.class.getDeclaredFields()) {
            if (!List.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                for (Object entry : (List<?>) field.get(chat)) {
                    if (entry instanceof GuiMessage message) lines.add(message.content().getString());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Only the full-message list matters; skip anything else.
            }
        }
        return lines;
    }

    private static void require(boolean condition, String step) {
        if (!condition) throw new IllegalStateException("Verification failed: " + step);
    }
}
