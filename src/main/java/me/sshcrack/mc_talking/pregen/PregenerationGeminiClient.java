package me.sshcrack.mc_talking.pregen;

import com.google.gson.JsonObject;
import me.sshcrack.gemini_live_lib.GeminiLiveClient;
import me.sshcrack.gemini_live_lib.gson.BidiGenerateContentSetup;
import me.sshcrack.gemini_live_lib.gson.ClientMessages;
import me.sshcrack.gemini_live_lib.gson.RealtimeInput;
import me.sshcrack.gemini_live_lib.websocket.handshake.ServerHandshake;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.AvailableAI;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.ModalityModes;
import me.sshcrack.mc_talking.config.QuotaTracker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import me.sshcrack.gemini_live_lib.misc.GeminiTTS.AudioChunk;
import me.sshcrack.mc_talking.internal.audio.VoicechatAccess;
import me.sshcrack.mc_talking.internal.prompt.PromptRuntime;
import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import me.sshcrack.mc_talking.manager.GeminiWsClient;
import me.sshcrack.mc_talking.manager.VoiceSelectionService;
import me.sshcrack.mc_talking.util.AudioHelper;
import org.jetbrains.annotations.Nullable;

import static me.sshcrack.mc_talking.McTalkingVoicechatPlugin.TARGET_SAMPLE_RATE;

public class PregenerationGeminiClient extends GeminiLiveClient {
    /** What to do when a turn completes: sometimes the model answers with text only and no audio. */
    enum TurnOutcome {
        DELIVER, RETRY, FAIL;

        /** Audio is delivered; a silent turn is asked for once more, like live turns (#249); a second one fails. */
        static TurnOutcome after(int audioBytes, boolean retried) {
            if (audioBytes > 0) return DELIVER;
            return retried ? FAIL : RETRY;
        }
    }

    private final UUID citizenId;
    private final CitizenPromptView promptView;
    private final String promptText;
    private final String modelName;
    private final AvailableAI modelAi;
    private volatile @Nullable String selectedVoiceName;
    private final Consumer<AudioChunk> onComplete;
    private final AtomicReference<Runnable> onError;
    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private static final int MAX_BUFFER_SIZE = 50 * 1024 * 1024; // 50 MB max
    private static final int MAX_VOICE_RECOVERY_ATTEMPTS = 3;
    private final AtomicInteger voiceRecoveryAttempts = new AtomicInteger();
    private final AtomicBoolean voiceRecoveryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private boolean retriedSilentTurn;

    /**
     * Set to {@code true} once {@link #onTurnComplete} fires (successfully or not).
     * Used by {@link #onClose} to decide whether the session ended normally — if
     * {@code false}, the close is treated as an error and the cleanup callback is
     * invoked to release the slot and decrement counters.
     */
    private boolean completed = false;

    public PregenerationGeminiClient(
            UUID citizenId,
            CitizenPromptView promptView,
            String promptText,
            AvailableAI model,
            Consumer<AudioChunk> onComplete,
            Runnable onError
    ) {
        super(McTalkingConfig.INSTANCE.instance().geminiApiKey);
        this.citizenId = citizenId;
        this.promptView = promptView;
        this.promptText = promptText;
        this.modelAi = model;
        this.modelName = model.getName();
        this.onComplete = onComplete;
        this.onError = new AtomicReference<>(onError);
    }

    @Override
    public BidiGenerateContentSetup getSetup() {
        var setup = new BidiGenerateContentSetup("models/" + modelName);
        setup.generationConfig.responseModalities = ModalityModes.AUDIO.getModalities();
        setup.generationConfig.speechConfig = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig();
        setup.generationConfig.speechConfig.language_code = McTalkingConfig.INSTANCE.instance().language;

        var female = promptView.identity().female();
        var uuid = citizenId;
        setup.generationConfig.speechConfig.voice_config = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig.VoiceConfig();
        setup.generationConfig.speechConfig.voice_config.prebuiltVoiceConfig = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig.PrebuiltVoiceConfig();
        selectedVoiceName = VoiceSelectionService.select(
                VoiceSelectionService.Backend.LIVE,
                modelName,
                modelAi,
                uuid,
                female);
        setup.generationConfig.speechConfig.voice_config.prebuiltVoiceConfig.voice_name = selectedVoiceName;

        var sys = new BidiGenerateContentSetup.SystemInstruction();
        var prompt = PromptRuntime.generateSystemControlledRoleplayPrompt(promptView);
        var p = new BidiGenerateContentSetup.SystemInstruction.Part(prompt);
        sys.parts.add(p);
        setup.systemInstruction = sys;

        return setup;
    }

    @Override
    public void onOpen(ServerHandshake data) {
        voiceRecoveryScheduled.set(false);
        try {
            super.onOpen(data);
        } catch (VoiceSelectionService.VoiceCandidatesExhaustedException e) {
            String message = e.getMessage();
            failTerminalVoiceRecovery(message == null ? e.getClass().getSimpleName() : message);
        }
    }

    @Override
    public void onSetupComplete() {
        var input = new RealtimeInput();
        input.text = promptText;
        send(ClientMessages.input(input));
    }

    private byte[] resampleToTarget(byte[] data, int currentRate) {
        if (currentRate == TARGET_SAMPLE_RATE) return data;

        short[] shorts = VoicechatAccess.require().getAudioConverter().bytesToShorts(data);
        short[] resampled = AudioHelper.resampleAudio(shorts, currentRate, TARGET_SAMPLE_RATE);

        return VoicechatAccess.require().getAudioConverter().shortsToBytes(resampled);
    }

    @Override
    public void onGeneratedAudio(byte[] data, int sampleRate) {
        byte[] processed = resampleToTarget(data, sampleRate);
        try {
            if (audioBuffer.size() + processed.length > MAX_BUFFER_SIZE) {
                McTalking.LOGGER.error("Pregenerated audio buffer exceeded maximum size, aborting");
                runOnErrorOnce();
                close();
                return;
            }
            audioBuffer.write(processed);
        } catch (IOException e) {
            McTalking.LOGGER.error("Failed to write pregenerated audio", e);
        }
    }

    @Override
    public void onTurnComplete() {
        byte[] audioData = audioBuffer.toByteArray();
        switch (TurnOutcome.after(audioData.length, retriedSilentTurn)) {
            case RETRY -> {
                // The model answered without speaking: ask once more in the same session.
                retriedSilentTurn = true;
                McTalking.LOGGER.info("Pregeneration turn produced no audio; asking once more");
                var input = new RealtimeInput();
                input.text = GeminiWsClient.SILENT_TURN_RETRY_PROMPT;
                send(ClientMessages.input(input));
                return;
            }
            case DELIVER -> {
                if (retriedSilentTurn) McTalking.LOGGER.info("Pregeneration produced audio after the retry");
                QuotaTracker.reportSuccess(modelName);
                onComplete.accept(new AudioChunk(audioData, TARGET_SAMPLE_RATE));
            }
            case FAIL -> {
                McTalking.LOGGER.warn("Pregeneration completed without producing audio, also after a retry");
                runOnErrorOnce();
            }
        }
        completed = true;
        terminal.set(true);
        close();
    }

    @Override
    public void onGeneratedText(String text) {
        // Not used for pregen
    }

    @Override
    public void onOutputTranscription(String transcription) {
        // Not used
    }

    @Override
    @Nullable
    public JsonObject onFunctionCall(String name, JsonObject args) {
        return null; // Tools disabled for pregen
    }

    @Override
    public void onQuotaExceeded() {
        terminal.set(true);
        McTalking.LOGGER.warn("Quota exceeded during audio pregeneration");
        QuotaTracker.reportQuotaExceeded(modelName);
        runOnErrorOnce();
        close();
    }

    @Override
    public void onError(Exception ex) {
        if (voiceRecoveryScheduled.get() && !terminal.get()) {
            McTalking.LOGGER.debug("Ignoring transient pregeneration websocket error while voice recovery is scheduled", ex);
            return;
        }
        terminal.set(true);
        McTalking.LOGGER.error("Error during pregeneration", ex);
        runOnErrorOnce();
        close();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        try {
            super.onClose(code, reason, remote);
        } catch (Exception e) {
            McTalking.LOGGER.error("Error in PregenerationGeminiClient.onClose", e);
        }

        if (!terminal.get()
                && VoiceSelectionService.noteLiveRejected(modelAi, selectedVoiceName, code, reason)) {
            if (voiceRecoveryAttempts.get() >= MAX_VOICE_RECOVERY_ATTEMPTS) {
                failTerminalVoiceRecovery("Voice recovery exhausted backend=live model=" + modelName
                        + " voice=" + selectedVoiceName
                        + " attempts=" + MAX_VOICE_RECOVERY_ATTEMPTS);
                return;
            }
            if (!voiceRecoveryScheduled.compareAndSet(false, true)) return;

            int attempt = voiceRecoveryAttempts.incrementAndGet();
            audioBuffer.reset();
            final String rejectedVoice = selectedVoiceName;
            McTalking.LOGGER.warn(
                    "Voice recovery backend=live model={} rejected={} attempt={}/{} result=retry-pregeneration",
                    modelName, rejectedVoice, attempt, MAX_VOICE_RECOVERY_ATTEMPTS);

            Thread retryThread = new Thread(() -> {
                if (terminal.get()) return;
                try {
                    PregenerationGeminiClient.super.reconnect();
                } catch (RuntimeException e) {
                    failTerminalVoiceRecovery("Voice recovery reconnect failed backend=live model=" + modelName
                            + " voice=" + rejectedVoice + " cause=" + e.getClass().getSimpleName());
                }
            }, "mc_talking_pregen_voice_recovery");
            retryThread.setDaemon(true);
            retryThread.start();
            return;
        }

        // If the session closed before onTurnComplete fired (e.g. auth failure,
        // server-side error during setup, or an abnormal WebSocket close), clean
        // up the slot and counters so no stale entries are left behind.
        if (!completed && onError.get() != null) {
            terminal.set(true);
            McTalking.LOGGER.warn("Pregeneration session closed before completion (code={}, reason={})", code, reason);
            runOnErrorOnce();
        }
    }

    private void runOnErrorOnce() {
        Runnable callback = onError.getAndSet(null);
        if (callback != null) callback.run();
    }

    private void failTerminalVoiceRecovery(String detail) {
        if (!terminal.compareAndSet(false, true)) return;
        voiceRecoveryScheduled.set(false);
        McTalking.LOGGER.error("{} result=terminal", detail);
        runOnErrorOnce();
        try {
            super.close();
        } catch (RuntimeException e) {
            McTalking.LOGGER.debug("Error closing terminal pregeneration voice recovery", e);
        }
    }

    @Override
    public void close() {
        terminal.set(true);
        voiceRecoveryScheduled.set(false);
        super.close();
    }

    @Override
    public void addPromptAudio(short[] audio) {
        // Not used for pregen
    }
}
