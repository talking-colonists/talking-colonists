package me.sshcrack.mc_talking.conversations;

import com.google.gson.JsonObject;
import me.sshcrack.gemini_live_lib.GeminiLiveClient;
import me.sshcrack.gemini_live_lib.gson.BidiGenerateContentSetup;
import me.sshcrack.gemini_live_lib.gson.ClientMessages;
import me.sshcrack.gemini_live_lib.gson.RealtimeInput;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * One text turn against the real Live model the game uses, with the same kind of setup: audio out
 * with a transcript, a system prompt and tool declarations. Tool calls are answered by
 * {@code answer} and the turn continues, as in the game, until the model finishes speaking.
 * Errors never include the exception message, which can contain the authenticated URI.
 */
final class LiveModelTurn {
    static final String MODEL = "gemini-3.1-flash-live-preview";
    private static final int MAX_TURNS = 4;

    record ToolCall(String name, @Nullable JsonObject args) {
    }

    /** {@code resumeHandle} is the session-resumption handle the provider gave at the end, or null. */
    record Result(String transcript, List<ToolCall> calls, @Nullable String resumeHandle) {
        boolean called(String name) {
            return calls.stream().anyMatch(call -> call.name().equals(name));
        }

        String describe() {
            StringBuilder out = new StringBuilder();
            for (ToolCall call : calls) out.append("[").append(call.name()).append(" ").append(call.args()).append("] ");
            return out.append(transcript.strip()).toString();
        }
    }

    private LiveModelTurn() {
    }

    static Result run(String key, String system, List<BidiGenerateContentSetup.Tool> tools, String userText,
                      BiFunction<String, JsonObject, JsonObject> answer) throws InterruptedException, IOException {
        return run(key, system, tools, userText, answer, null);
    }

    /** Like the game with session resumption on: {@code resume} continues an earlier session (null starts fresh). */
    static Result run(String key, String system, List<BidiGenerateContentSetup.Tool> tools, String userText,
                      BiFunction<String, JsonObject, JsonObject> answer, @Nullable String resume)
            throws InterruptedException, IOException {
        AtomicReference<String> handle = new AtomicReference<>();
        CountDownLatch setup = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder transcript = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        AtomicReference<String> failure = new AtomicReference<>("");
        GeminiLiveClient client = new GeminiLiveClient(key) {
            private int turns;

            @Override
            public BidiGenerateContentSetup getSetup() {
                var result = new BidiGenerateContentSetup("models/" + MODEL);
                result.generationConfig.responseModalities = List.of("AUDIO");
                result.outputAudioTranscription = new JsonObject();
                result.systemInstruction = new BidiGenerateContentSetup.SystemInstruction();
                result.systemInstruction.parts.add(new BidiGenerateContentSetup.SystemInstruction.Part(system));
                result.tools.addAll(tools);
                result.sessionResumption = resume == null ? new BidiGenerateContentSetup.SessionResumptionConfig()
                        : new BidiGenerateContentSetup.SessionResumptionConfig(resume);
                return result;
            }

            @Override
            public void onSessionResumptionUpdate(String newHandle, boolean resumable) {
                if (resumable) handle.set(newHandle);
            }

            @Override
            public void onSetupComplete() {
                setup.countDown();
            }

            @Override
            public JsonObject onFunctionCall(String name, @Nullable JsonObject args) {
                synchronized (calls) {
                    calls.add(new ToolCall(name, args));
                }
                return answer.apply(name, args);
            }

            @Override
            public void onOutputTranscription(String text) {
                synchronized (transcript) {
                    transcript.append(text);
                }
            }

            @Override
            public void onTurnComplete() {
                // A turn that only called tools is followed by the spoken answer in the next turn.
                boolean spoke;
                synchronized (transcript) {
                    spoke = !transcript.isEmpty();
                }
                if (spoke || ++turns >= MAX_TURNS) done.countDown();
            }

            @Override
            public void addPromptAudio(short[] audio) {
            }

            @Override
            public void onError(Exception error) {
                failure.set(error.getClass().getSimpleName());
                setup.countDown();
                done.countDown();
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                failure.compareAndSet("", "closed with code " + code);
                setup.countDown();
                done.countDown();
                super.onClose(code, reason, remote);
            }
        };
        client.setDaemon(true);
        try {
            client.connect();
            if (!setup.await(20, TimeUnit.SECONDS) || !client.isSetupComplete()) {
                throw new AssertionError("Live setup failed: " + failure.get());
            }
            var input = new RealtimeInput();
            input.text = userText;
            client.send(ClientMessages.input(input));
            if (!done.await(60, TimeUnit.SECONDS) && calls.isEmpty()) throw new AssertionError("Live turn timed out");
            // The resumption handle for the finished turn arrives just after it.
            if (handle.get() == null) Thread.sleep(1_500);
            if (!failure.get().isEmpty() && transcript.isEmpty() && calls.isEmpty()) {
                throw new AssertionError("Live turn failed: " + failure.get());
            }
            synchronized (transcript) {
                synchronized (calls) {
                    return new Result(transcript.toString(), List.copyOf(calls), handle.get());
                }
            }
        } finally {
            if (client.getSocket() != null) client.getSocket().close();
            client.close();
        }
    }
}
