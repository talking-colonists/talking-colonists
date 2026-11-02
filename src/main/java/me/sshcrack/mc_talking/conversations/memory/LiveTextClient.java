package me.sshcrack.mc_talking.conversations.memory;

import com.google.gson.JsonObject;
import me.sshcrack.gemini_live_lib.GeminiLiveClient;
import me.sshcrack.gemini_live_lib.gson.BidiGenerateContentSetup;
import me.sshcrack.gemini_live_lib.gson.ClientMessages;
import me.sshcrack.gemini_live_lib.gson.RealtimeInput;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.ModalityModes;
import me.sshcrack.mc_talking.config.QuotaTracker;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One text request answered by the cheap Gemini Live model, used like a Flash text call. The Live
 * models only answer in audio, so the session asks for audio plus an output transcript and returns
 * the transcript; the audio is dropped. One prompt, one turn, then the owner closes the session.
 */
public class LiveTextClient extends GeminiLiveClient {
    private final String systemPrompt;
    private final String prompt;
    private final @Nullable UUID voiceSeed;
    private final boolean female;
    private final String logTag;
    private final Consumer<String> onComplete;
    private final Runnable onError;
    private final StringBuilder buffer = new StringBuilder();
    private final AtomicBoolean completed = new AtomicBoolean();

    /**
     * @param voiceSeed citizen UUID the voice is chosen from (any voice works; nothing is played), or null
     * @param logTag    log prefix such as {@code "[PlayerMemory]"}
     */
    public LiveTextClient(String systemPrompt, String prompt, @Nullable UUID voiceSeed, boolean female, String logTag,
                          Consumer<String> onComplete, Runnable onError) {
        super(McTalkingConfig.INSTANCE.instance().geminiApiKey);
        this.systemPrompt = systemPrompt;
        this.prompt = prompt;
        this.voiceSeed = voiceSeed;
        this.female = female;
        this.logTag = logTag;
        this.onComplete = onComplete;
        this.onError = onError;
    }

    @Override
    public BidiGenerateContentSetup getSetup() {
        var setup = new BidiGenerateContentSetup("models/" + McTalkingConfig.CHEAP_LIVE_MODEL.getName());

        setup.generationConfig.responseModalities = ModalityModes.TEXT_AND_AUDIO.getModalities();
        setup.outputAudioTranscription = new JsonObject();

        if (voiceSeed != null) {
            setup.generationConfig.speechConfig = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig();
            setup.generationConfig.speechConfig.language_code = McTalkingConfig.INSTANCE.instance().language;
            setup.generationConfig.speechConfig.voice_config = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig.VoiceConfig();
            setup.generationConfig.speechConfig.voice_config.prebuiltVoiceConfig = new BidiGenerateContentSetup.GenerationConfig.SpeechConfig.PrebuiltVoiceConfig();
            setup.generationConfig.speechConfig.voice_config.prebuiltVoiceConfig.voice_name =
                    McTalkingConfig.CHEAP_LIVE_MODEL.getRandomVoice(voiceSeed, female);
        }

        setup.realtimeInputConfig = new BidiGenerateContentSetup.RealtimeInputConfig();

        var sys = new BidiGenerateContentSetup.SystemInstruction();
        sys.parts.add(new BidiGenerateContentSetup.SystemInstruction.Part(systemPrompt));
        setup.systemInstruction = sys;
        return setup;
    }

    @Override
    public void onSetupComplete() {
        var input = new RealtimeInput();
        input.text = prompt;
        send(ClientMessages.input(input));
    }

    @Override
    public void onGeneratedText(String text) {
        buffer.append(text);
    }

    @Override
    public void onGeneratedAudio(byte[] data, int sampleRate) {
        // Only the transcript is used.
    }

    @Override
    public void onOutputTranscription(String transcription) {
        buffer.append(transcription);
    }

    @Override
    public void onTurnComplete() {
        if (!completed.compareAndSet(false, true)) return;
        String text = buffer.toString().trim();
        if (text.isEmpty()) {
            McTalking.LOGGER.warn("{} Live text request produced an empty answer", logTag);
            onError.run();
        } else {
            QuotaTracker.reportSuccess(McTalkingConfig.CHEAP_LIVE_MODEL.getName());
            onComplete.accept(text);
        }
        // The owner closes the transport after using the result, so a background slot is not
        // released before the result is applied.
    }

    @Override
    @Nullable
    public JsonObject onFunctionCall(String name, JsonObject args) {
        return null;
    }

    @Override
    public void onQuotaExceeded() {
        if (!completed.compareAndSet(false, true)) return;
        McTalking.LOGGER.warn("{} Live model quota exceeded during a text request", logTag);
        QuotaTracker.reportQuotaExceeded(McTalkingConfig.CHEAP_LIVE_MODEL.getName());
        onError.run();
        close();
    }

    @Override
    public void onError(Exception ex) {
        if (!completed.compareAndSet(false, true)) return;
        McTalking.LOGGER.error("{} Error during a Live text request", logTag, ex);
        onError.run();
        close();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        super.onClose(code, reason, remote);
        if (completed.compareAndSet(false, true)) {
            McTalking.LOGGER.warn("{} Live text session closed early (code={}, reason={}, remote={})",
                    logTag, code, reason, remote);
            onError.run();
        }
    }

    @Override
    public void addPromptAudio(short[] audio) {
        // Text only.
    }
}
