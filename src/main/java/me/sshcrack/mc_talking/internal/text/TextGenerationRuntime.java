package me.sshcrack.mc_talking.internal.text;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import me.sshcrack.gemini_live_lib.misc.GeminiFlash;
import me.sshcrack.gemini_live_lib.misc.UnexpectedResponseException;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.api.text.TextRequest;
import me.sshcrack.mc_talking.api.text.TextResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/**
 * Runs addon text generation (roadmap A3) as plain Flash requests, with an optional {@link Fallback}
 * (the cheap Live model) when Flash is out of quota or failing. Flash requests use their own small
 * concurrency limit; only the fallback takes a Live background slot.
 */
public final class TextGenerationRuntime {
    /** Concurrent text requests; the free tier allows about 15 Flash-Lite requests per minute. */
    public static final int MAX_CONCURRENT = 2;

    /** Sends one Flash request and returns the concatenated candidate text. */
    public interface Transport {
        String send(GeminiFlash.GenerateContentRequest request) throws Exception;
    }

    /** Quota state for the Flash model. */
    public interface QuotaGate {
        boolean exhausted();

        void reportQuotaExceeded(Exception error);

        void reportSuccess();
    }

    /**
     * Answers the same request through another model when Flash is used up or failing (the cheap
     * Live model in production). Returns the raw text; the runtime interprets it like a Flash answer.
     */
    public interface Fallback {
        /** Whether the fallback can be tried now (enabled and not out of quota itself). */
        boolean available();

        String send(String systemPrompt, String userText) throws Exception;
    }

    private final Transport transport;
    private final @Nullable Fallback fallback;
    private final QuotaGate quota;
    private final BooleanSupplier hasApiKey;
    private final Executor executor;
    private final Semaphore permits = new Semaphore(MAX_CONCURRENT);
    private final Set<CompletableFuture<TextResult>> active = ConcurrentHashMap.newKeySet();

    public TextGenerationRuntime(@NotNull Transport transport, @NotNull QuotaGate quota,
                                 @NotNull BooleanSupplier hasApiKey, @NotNull Executor executor) {
        this(transport, null, quota, hasApiKey, executor);
    }

    public TextGenerationRuntime(@NotNull Transport transport, @Nullable Fallback fallback, @NotNull QuotaGate quota,
                                 @NotNull BooleanSupplier hasApiKey, @NotNull Executor executor) {
        this.transport = transport;
        this.fallback = fallback;
        this.quota = quota;
        this.hasApiKey = hasApiKey;
        this.executor = executor;
    }

    /**
     * Starts a request. The returned future always completes with a result. Cancelling it frees the
     * slot as soon as the provider call returns, and the answer is discarded.
     */
    public @NotNull CompletableFuture<TextResult> submit(@NotNull String systemPrompt, @NotNull TextRequest request) {
        if (!hasApiKey.getAsBoolean()) {
            return CompletableFuture.completedFuture(TextResult.failure(TextResult.Status.UNAVAILABLE, "No Gemini API key is configured"));
        }
        boolean flashUsedUp = quota.exhausted();
        if (flashUsedUp && !fallbackAvailable()) {
            return CompletableFuture.completedFuture(TextResult.failure(TextResult.Status.QUOTA, "The Flash model quota is exhausted"));
        }
        if (!permits.tryAcquire()) {
            return CompletableFuture.completedFuture(TextResult.failure(TextResult.Status.NO_CAPACITY,
                    "Too many text requests are running"));
        }

        CompletableFuture<TextResult> future = new CompletableFuture<>();
        active.add(future);
        future.whenComplete((result, error) -> active.remove(future));
        GeminiFlash.GenerateContentRequest providerRequest = buildRequest(systemPrompt, request);
        try {
            executor.execute(() -> {
                try {
                    if (future.isDone()) return;
                    future.complete(flashUsedUp
                            ? viaFallback(systemPrompt, request, TextResult.Status.QUOTA, "The Flash model quota is exhausted")
                            : call(systemPrompt, providerRequest, request));
                } catch (Throwable t) {
                    future.complete(TextResult.failure(TextResult.Status.PROVIDER_ERROR, t.getClass().getSimpleName()));
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException rejected) {
            permits.release();
            future.complete(TextResult.failure(TextResult.Status.NO_CAPACITY, "Text generation executor rejected the request"));
        }
        return future;
    }

    /** Completes every pending request with {@link TextResult.Status#CANCELLED}, e.g. on server stop. */
    public void cancelAll(@NotNull String reason) {
        for (CompletableFuture<TextResult> future : List.copyOf(active)) {
            future.complete(TextResult.failure(TextResult.Status.CANCELLED, reason));
        }
    }

    int pendingCount() {
        return active.size();
    }

    private TextResult call(String systemPrompt, GeminiFlash.GenerateContentRequest providerRequest, TextRequest request) {
        String raw;
        try {
            raw = transport.send(providerRequest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TextResult.failure(TextResult.Status.CANCELLED, "Interrupted");
        } catch (Exception e) {
            if (isQuotaError(e)) {
                quota.reportQuotaExceeded(e);
                return viaFallback(systemPrompt, request, TextResult.Status.QUOTA, "The Flash model quota is exhausted");
            }
            McTalking.LOGGER.warn("[TextGeneration] {} request failed: {}", request.purpose(), e.getClass().getSimpleName());
            if (isServiceError(e)) return viaFallback(systemPrompt, request, TextResult.Status.PROVIDER_ERROR, e.getClass().getSimpleName());
            return TextResult.failure(TextResult.Status.PROVIDER_ERROR, e.getClass().getSimpleName());
        }
        quota.reportSuccess();
        return interpret(raw, request);
    }

    /** Tries the fallback, or reports why Flash failed when there is none. */
    private TextResult viaFallback(String systemPrompt, TextRequest request, TextResult.Status flashStatus,
                                   String flashFailure) {
        Fallback fallbackClient = fallback;
        if (fallbackClient == null || !fallbackClient.available()) {
            return TextResult.failure(flashStatus, flashFailure);
        }
        McTalking.LOGGER.info("[TextGeneration] {} falling back to the Live model", request.purpose());
        String system = request.responseSchema() == null
                ? systemPrompt : LiveTextRequest.withSchema(systemPrompt, request.responseSchema());
        try {
            return interpret(fallbackClient.send(system, userText(request)), request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TextResult.failure(TextResult.Status.CANCELLED, "Interrupted");
        } catch (Exception e) {
            McTalking.LOGGER.warn("[TextGeneration] {} Live fallback failed: {}", request.purpose(), e.getMessage());
            return TextResult.failure(flashStatus, flashFailure + "; the Live fallback failed too");
        }
    }

    private boolean fallbackAvailable() {
        Fallback fallbackClient = fallback;
        return fallbackClient != null && fallbackClient.available();
    }

    /** Server errors and network failures, which another model may not share. */
    static boolean isServiceError(Exception e) {
        if (e instanceof UnexpectedResponseException unexpected) return unexpected.getStatusCode() >= 500;
        return e instanceof java.io.IOException;
    }

    static boolean isQuotaError(Exception e) {
        if (e instanceof UnexpectedResponseException unexpected && unexpected.getStatusCode() == 429) return true;
        String message = e.getMessage();
        if (message == null) return false;
        message = message.toLowerCase(Locale.ROOT);
        return message.contains("resource_exhausted") || message.contains("resource exhausted")
                || message.contains("quota") || message.contains("429") || message.contains("too many requests");
    }

    static GeminiFlash.GenerateContentRequest buildRequest(String systemPrompt, TextRequest request) {
        GeminiFlash.GenerateContentRequest providerRequest = new GeminiFlash.GenerateContentRequest();
        GeminiFlash.GenerateContentRequest.SystemInstruction system = new GeminiFlash.GenerateContentRequest.SystemInstruction();
        system.parts = List.of(part(systemPrompt));
        providerRequest.system_instruction = system;

        if (request.responseSchema() != null) {
            providerRequest.generationConfig = GeminiFlash.GenerateContentRequest.GenerationConfig.json(request.responseSchema());
        }
        GeminiFlash.GenerateContentRequest.Content content = new GeminiFlash.GenerateContentRequest.Content();
        content.parts = List.of(part(userText(request)));
        providerRequest.contents = content;
        return providerRequest;
    }

    static String userText(TextRequest request) {
        StringBuilder user = new StringBuilder(request.directive());
        if (request.maxChars() != null) {
            user.append("\n\nKeep it under ").append(request.maxChars()).append(" characters.");
        }
        if (request.responseSchema() != null) {
            user.append("\n\nAnswer only with JSON that matches the response schema. Write every text value in the language named above.");
        }
        return user.toString();
    }

    static TextResult interpret(@Nullable String raw, TextRequest request) {
        if (raw == null || raw.isBlank()) {
            return TextResult.failure(TextResult.Status.INVALID_OUTPUT, "The model returned no text");
        }
        String text = raw.strip();
        JsonObject schema = request.responseSchema();
        if (schema == null) {
            return TextResult.success(trim(text, request.maxChars()), null);
        }

        JsonObject json;
        try {
            JsonElement parsed = JsonParser.parseString(stripCodeFence(text));
            if (!parsed.isJsonObject()) {
                return TextResult.failure(TextResult.Status.INVALID_OUTPUT, "Expected a JSON object");
            }
            json = parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return TextResult.failure(TextResult.Status.INVALID_OUTPUT, "The model did not return valid JSON");
        }
        String missing = missingRequiredField(schema, json);
        if (missing != null) {
            return TextResult.failure(TextResult.Status.INVALID_OUTPUT, "JSON is missing required field '" + missing + "'");
        }
        return TextResult.success(json.toString(), json);
    }

    /** Checks the top-level {@code required} list, the part of the schema callers rely on most. */
    private static @Nullable String missingRequiredField(JsonObject schema, JsonObject json) {
        if (!schema.has("required") || !schema.get("required").isJsonArray()) return null;
        for (JsonElement field : schema.getAsJsonArray("required")) {
            if (!field.isJsonPrimitive()) continue;
            String name = field.getAsString();
            if (!json.has(name) || json.get(name).isJsonNull()) return name;
        }
        return null;
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) return text;
        int firstLine = text.indexOf('\n');
        int end = text.lastIndexOf("```");
        return firstLine < 0 || end <= firstLine ? text : text.substring(firstLine + 1, end).strip();
    }

    /** Cuts over-long text at the last sentence end that fits, else at the last space. */
    static String trim(String text, @Nullable Integer maxChars) {
        if (maxChars == null || text.length() <= maxChars) return text;
        String cut = text.substring(0, maxChars);
        int sentence = Math.max(cut.lastIndexOf(". "), Math.max(cut.lastIndexOf("! "), cut.lastIndexOf("? ")));
        if (cut.endsWith(".") || cut.endsWith("!") || cut.endsWith("?")) return cut;
        if (sentence >= maxChars / 2) return cut.substring(0, sentence + 1);
        int space = cut.lastIndexOf(' ');
        return (space >= maxChars / 2 ? cut.substring(0, space) : cut).strip();
    }

    private static GeminiFlash.GenerateContentRequest.Part part(String text) {
        GeminiFlash.GenerateContentRequest.Part part = new GeminiFlash.GenerateContentRequest.Part();
        part.text = text;
        return part;
    }
}
