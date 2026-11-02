package me.sshcrack.mc_talking.conversations;

import me.sshcrack.gemini_live_lib.misc.GeminiTTS;
import me.sshcrack.gemini_live_lib.misc.UnexpectedResponseException;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.AvailableAI;
import me.sshcrack.mc_talking.manager.VoiceSelectionService;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded TTS request retry that changes voices only after voice-specific provider evidence. */
final class TtsVoiceRecovery {
    static final int MAX_FALLBACK_ATTEMPTS = 3;

    record Speaker(UUID citizenId, String speakerName, boolean female) {
        Speaker {
            Objects.requireNonNull(citizenId, "citizenId");
            Objects.requireNonNull(speakerName, "speakerName");
        }
    }

    @FunctionalInterface
    interface RequestAttempt {
        void generate(List<GeminiTTS.RequestPayload.SpeakerVoiceConfig> voices)
                throws IOException, InterruptedException, UnexpectedResponseException;
    }

    private TtsVoiceRecovery() {
    }

    static void generate(
            AvailableAI ai,
            String model,
            List<Speaker> speakers,
            RequestAttempt request
    ) throws IOException, InterruptedException, UnexpectedResponseException {
        Objects.requireNonNull(ai, "ai");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(speakers, "speakers");
        Objects.requireNonNull(request, "request");

        int fallbackAttempts = 0;
        while (true) {
            List<GeminiTTS.RequestPayload.SpeakerVoiceConfig> voices = selectVoices(ai, model, speakers);
            try {
                request.generate(voices);
                if (fallbackAttempts > 0) {
                    McTalking.LOGGER.info(
                            "Voice recovery backend=tts model={} attempts={} result=success",
                            model, fallbackAttempts);
                }
                return;
            } catch (UnexpectedResponseException error) {
                String rejectedVoice = uniquelyRejectedVoice(error, voices);
                if (rejectedVoice == null
                        || !VoiceSelectionService.noteTtsRejected(
                                ai,
                                model,
                                rejectedVoice,
                                error.getStatusCode(),
                                error.getResponseBody())) {
                    throw error;
                }

                if (fallbackAttempts >= MAX_FALLBACK_ATTEMPTS) {
                    throw new UnexpectedResponseException(
                            "Gemini TTS voice recovery exhausted retry limit " + MAX_FALLBACK_ATTEMPTS
                                    + " for model=" + model + " lastVoice=" + rejectedVoice);
                }

                fallbackAttempts++;
                McTalking.LOGGER.warn(
                        "Voice recovery backend=tts model={} rejected={} attempt={}/{} result=retry",
                        model, rejectedVoice, fallbackAttempts, MAX_FALLBACK_ATTEMPTS);
            }
        }
    }

    private static List<GeminiTTS.RequestPayload.SpeakerVoiceConfig> selectVoices(
            AvailableAI ai,
            String model,
            List<Speaker> speakers
    ) {
        List<GeminiTTS.RequestPayload.SpeakerVoiceConfig> configs = new ArrayList<>(speakers.size());
        for (Speaker speaker : speakers) {
            String voiceName = VoiceSelectionService.select(
                    VoiceSelectionService.Backend.TTS,
                    model,
                    ai,
                    speaker.citizenId(),
                    speaker.female());
            var config = new GeminiTTS.RequestPayload.SpeakerVoiceConfig();
            config.speaker = speaker.speakerName();
            config.voice_config = new GeminiTTS.RequestPayload.VoiceConfig();
            config.voice_config.prebuilt_voice_config = new GeminiTTS.RequestPayload.PrebuiltVoiceConfig();
            config.voice_config.prebuilt_voice_config.voice_name = voiceName;
            configs.add(config);
        }
        return configs;
    }

    @Nullable
    private static String uniquelyRejectedVoice(
            UnexpectedResponseException error,
            List<GeminiTTS.RequestPayload.SpeakerVoiceConfig> voices
    ) {
        String rejected = null;
        for (var config : voices) {
            if (config == null || config.voice_config == null
                    || config.voice_config.prebuilt_voice_config == null) continue;
            String voice = config.voice_config.prebuilt_voice_config.voice_name;
            if (!VoiceSelectionService.isExplicitTtsVoiceRejection(
                    error.getStatusCode(), error.getResponseBody(), voice)) continue;
            if (rejected != null && !rejected.equals(voice)) return null;
            rejected = voice;
        }
        return rejected;
    }

}
