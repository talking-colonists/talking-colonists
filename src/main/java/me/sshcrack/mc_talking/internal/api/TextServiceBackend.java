package me.sshcrack.mc_talking.internal.api;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.misc.GeminiFlash;
import me.sshcrack.gemini_live_lib.misc.UnexpectedResponseException;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.mc_talking.api.text.TextResult;
import me.sshcrack.mc_talking.api.service.TextService;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.QuotaRetryInfo;
import me.sshcrack.mc_talking.config.QuotaTracker;
import me.sshcrack.mc_talking.internal.text.LiveTextRequest;
import me.sshcrack.mc_talking.internal.text.TextGenerationRuntime;
import me.sshcrack.mc_talking.internal.text.TextPrompts;
import me.sshcrack.mc_talking.manager.CitizenPromptViewFactory;
import me.sshcrack.mc_talking.manager.prompt.ColonyPromptViewFactory;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

final class TextServiceBackend implements TextService {
    static final TextGenerationRuntime RUNTIME = new TextGenerationRuntime(
            request -> GeminiFlash.sendFlashRequest(McTalkingConfig.FLASH_MODEL,
                    McTalkingConfig.INSTANCE.instance().geminiApiKey, request, 1),
            new TextGenerationRuntime.Fallback() {
                @Override
                public boolean available() {
                    return McTalkingConfig.INSTANCE.instance().enableLiveTextFallback
                            && !QuotaTracker.isQuotaExceeded(McTalkingConfig.CHEAP_LIVE_MODEL.getName());
                }

                @Override
                public String send(String systemPrompt, String userText) throws Exception {
                    // Its own slot owner per request: the slot capacity still limits how many run.
                    return LiveTextRequest.send(UUID.randomUUID(), null, false, systemPrompt, userText, "[TextGeneration]");
                }
            },
            new TextGenerationRuntime.QuotaGate() {
                @Override
                public boolean exhausted() {
                    return QuotaTracker.isQuotaExceeded(McTalkingConfig.FLASH_MODEL);
                }

                @Override
                public void reportQuotaExceeded(Exception error) {
                    Long retryAfter = error instanceof UnexpectedResponseException unexpected
                            ? QuotaRetryInfo.parseRetryDelayMs(unexpected.getResponseBody())
                            : null;
                    QuotaTracker.reportQuotaExceeded(McTalkingConfig.FLASH_MODEL, retryAfter);
                }

                @Override
                public void reportSuccess() {
                    QuotaTracker.reportSuccess(McTalkingConfig.FLASH_MODEL);
                }
            },
            McTalkingConfig::hasGeminiApiKey,
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "mc-talking-text-generation");
                thread.setDaemon(true);
                return thread;
            }));

    @Override
    public @NotNull CompletableFuture<TextResult> generate(@NotNull AbstractEntityCitizen citizen,
                                                           @NotNull TextRequest request) {
        Objects.requireNonNull(citizen, "citizen");
        Objects.requireNonNull(request, "request");
        return withPrompt(citizen.level().getServer(), () -> {
            var data = citizen.getCitizenData();
            return data == null ? null : TextPrompts.citizen(CitizenPromptViewFactory.create(data, Map.of(), null));
        }, request);
    }

    @Override
    public @NotNull CompletableFuture<TextResult> generateColonyVoice(@NotNull IColony colony,
                                                                      @NotNull TextRequest request) {
        Objects.requireNonNull(colony, "colony");
        Objects.requireNonNull(request, "request");
        var level = colony.getWorld();
        return withPrompt(level == null ? null : level.getServer(), () -> TextPrompts.colony(
                ColonyPromptViewFactory.createColonyView(colony, level),
                CitizenPromptViewFactory.getLanguageNameFromCode(McTalkingConfig.INSTANCE.instance().language)), request);
    }

    /** Builds the prompt on the server thread (it reads live game state), then submits it. */
    private static CompletableFuture<TextResult> withPrompt(@Nullable MinecraftServer server, Supplier<String> prompt,
                                                            TextRequest request) {
        if (server == null) {
            return CompletableFuture.completedFuture(TextResult.failure(TextResult.Status.UNAVAILABLE, "No server is running"));
        }
        CompletableFuture<String> systemPrompt = server.isSameThread()
                ? CompletableFuture.completedFuture(prompt.get())
                : CompletableFuture.supplyAsync(prompt, server);
        return systemPrompt.thenCompose(text -> text == null
                ? CompletableFuture.completedFuture(TextResult.failure(TextResult.Status.UNAVAILABLE, "Citizen data is not available"))
                : RUNTIME.submit(text, request));
    }
}
