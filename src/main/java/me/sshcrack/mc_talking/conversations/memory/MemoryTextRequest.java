package me.sshcrack.mc_talking.conversations.memory;

import com.google.gson.JsonObject;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.misc.GeminiFlash;
import me.sshcrack.gemini_live_lib.misc.UnexpectedResponseException;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.QuotaRetryInfo;
import me.sshcrack.mc_talking.config.QuotaTracker;
import me.sshcrack.mc_talking.internal.text.LiveTextRequest;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * A JSON memory request: Flash-Lite first, the cheap Live model as a fallback. Flash-Lite has a
 * daily limit; the Live model has none on the free tier, so when Flash-Lite's quota is used up (or
 * its service fails) memory is still written. The Live answer is not schema-constrained, so the
 * schema goes into the prompt and the caller's parser still validates the result.
 */
final class MemoryTextRequest {
    /** Why a request produced no text. */
    static final class FailedException extends Exception {
        FailedException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    interface Flash {
        String send() throws UnexpectedResponseException, IOException, InterruptedException;
    }

    interface Live {
        String send() throws FailedException, InterruptedException;
    }

    interface Quota {
        boolean flashExhausted();

        boolean liveExhausted();

        void flashExceeded(@Nullable Long retryAfterMillis);

        void flashSucceeded();
    }

    private MemoryTextRequest() {
    }

    /** Runs the request for a memory of {@code citizen} (its background Live slot is used for the fallback). */
    static String generate(AbstractEntityCitizen citizen, String systemPrompt, String userPrompt,
                           JsonObject schema, String logTag) throws FailedException, InterruptedException {
        String apiKey = McTalkingConfig.INSTANCE.instance().geminiApiKey;
        Flash flash = () -> GeminiFlash.sendSimpleFlashRequest(McTalkingConfig.FLASH_MODEL, apiKey, systemPrompt,
                userPrompt, GeminiFlash.GenerateContentRequest.GenerationConfig.json(schema));
        Live live = () -> viaLive(citizen, LiveTextRequest.withSchema(systemPrompt, schema), userPrompt, logTag);
        return run(flash, McTalkingConfig.INSTANCE.instance().enableLiveTextFallback ? live : null, new Quota() {
            @Override
            public boolean flashExhausted() {
                return QuotaTracker.isQuotaExceeded(McTalkingConfig.FLASH_MODEL);
            }

            @Override
            public boolean liveExhausted() {
                return QuotaTracker.isQuotaExceeded(McTalkingConfig.CHEAP_LIVE_MODEL.getName());
            }

            @Override
            public void flashExceeded(@Nullable Long retryAfterMillis) {
                QuotaTracker.reportQuotaExceeded(McTalkingConfig.FLASH_MODEL, retryAfterMillis);
            }

            @Override
            public void flashSucceeded() {
                QuotaTracker.reportSuccess(McTalkingConfig.FLASH_MODEL);
            }
        }, logTag);
    }

    /**
     * Flash first; the Live fallback (when given) on Flash quota exhaustion, a 429, a server error
     * or a network failure. Other Flash errors (bad request, bad key) are not retried on Live.
     */
    static String run(Flash flash, @Nullable Live live, Quota quota, String logTag)
            throws FailedException, InterruptedException {
        Exception flashError = null;
        if (quota.flashExhausted()) {
            McTalking.LOGGER.info("{} Flash-Lite quota is used up", logTag);
        } else {
            try {
                String text = flash.send();
                quota.flashSucceeded();
                return text;
            } catch (UnexpectedResponseException error) {
                if (error.getStatusCode() == 429) {
                    quota.flashExceeded(QuotaRetryInfo.parseRetryDelayMs(error.getResponseBody()));
                } else if (error.getStatusCode() < 500) {
                    throw new FailedException("Flash-Lite rejected the request (HTTP " + error.getStatusCode() + ")", error);
                }
                flashError = error;
            } catch (IOException error) {
                flashError = error;
            }
        }

        if (live == null) {
            throw new FailedException("Flash-Lite is unavailable and the Live fallback is off", flashError);
        }
        if (quota.liveExhausted()) {
            throw new FailedException("Flash-Lite and the Live model are both out of quota", flashError);
        }
        McTalking.LOGGER.info("{} Falling back to the Live model for this memory request", logTag);
        return live.send();
    }

    private static String viaLive(AbstractEntityCitizen citizen, String systemPrompt, String userPrompt, String logTag)
            throws FailedException, InterruptedException {
        var data = citizen.getCitizenData();
        try {
            return LiveTextRequest.send(citizen.getUUID(), citizen.getUUID(), data != null && data.isFemale(),
                    systemPrompt, userPrompt, logTag);
        } catch (LiveTextRequest.FailedException error) {
            String message = error.getMessage();
            throw new FailedException(message == null ? error.getClass().getSimpleName() : message, error.getCause());
        }
    }
}
