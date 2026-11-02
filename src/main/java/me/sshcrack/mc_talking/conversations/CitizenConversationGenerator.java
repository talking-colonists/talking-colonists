package me.sshcrack.mc_talking.conversations;

import me.sshcrack.gemini_live_lib.misc.GeminiFlash;
import me.sshcrack.gemini_live_lib.misc.GeminiTTS;
import me.sshcrack.gemini_live_lib.misc.UnexpectedResponseException;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.internal.prompt.PromptRuntime;
import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.TtsQuotaManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class CitizenConversationGenerator {
    /** Immutable server-thread snapshot used by background Flash/TTS generation. */
    public record PromptParticipant(@NotNull UUID citizenId, @NotNull CitizenPromptView view) {
    }

    private static final String CONVERSATION_SYSTEM_PROMPT = """
            Generate a dialogue transcript using the structured format below.
            Participants in this conversation are citizens from the MineColonies mod, each with unique personalities, needs, and relationships. Use the provided citizen information to create an immersive and realistic conversation that reflects their current states and emotional profiles.
            The citizens can not give each other blocks or items, the "manager" of the colony can however.
            Try to incorporate talking about the manager in the colony in this conversation.

            # Audio Profile
            For each character: Describe their vocal identity (tone, personality, emotional baseline).

            # Director's Note
            For each character include:
            - Style (e.g., empathetic, authoritative, unstable, etc.)
            - Pace (e.g., slow, staccato, rushed)
            - Accent (e.g., British RP, American Gen, regional, etc.)

            ## Scene:
            Describe the environment and atmosphere in 1-2 sentences.

            ## Sample Context:
            Define genre, tone, pacing behavior, and emotional tension.

            ## Transcript:
            Write a multi-character dialogue using the provided citizen names instead of generic speaker labels.

            Formatting rules:
            - Use character names exactly as given (e.g., "Tomas Reed:")
            - Include emotional/action cues in square brackets before or within lines (e.g., [shouting], [weakly], [suspicion])
            - Keep dialogue concise but expressive
            - Reflect each character's emotional state, needs, and personality
            - Escalate tension naturally toward the end if appropriate
            - Ensure characters reference their personal conditions, needs, and relationships when relevant
            - Maintain immersive, roleplay-style dialogue

            ---

            ## Input Data:
            You will receive one or more citizens in this format:

            # CITIZEN INFO
            Name: <Full Name>

            Type: <Description>

            ## RELATIONSHIPS
            <List of relationships>

            ## CURRENT STATE
            <List of physical and emotional conditions>

            ## NEEDS (first person)
            <List of urgent needs>

            ## EMOTIONAL PROFILE
            <Behavioral tendencies>

            ---

            ## Task:
            - Generate a realistic, immersive conversation between the given citizens
            - Ensure each character speaks according to their emotional profile and current condition
            - Incorporate their needs naturally into dialogue
            - Match tone and pacing to the context

            ## Rules
            - The transcript is the exact words the model will speak. An audio tag is a word in square brackets that indicates either how something should be said, a change of tone, or an interjection.
            - You can control style, tone, accent, and pace using natural language prompts or audio tags.
            - For audio tags, make sure to only focus on voices, not physical actions or sounds.
            Example:
            ```
            Thomas: I know right, [sarcastically] I couldn't believe it. [whispers] She should have totally left
            at that point.

            Nils: [cough] Well, [sighs] I guess it doesn't matter now.
            ```
            """;

    /** Script-generation system prompt pinned to the configured response language. */
    static String conversationSystemPrompt(String languageName) {
        return CONVERSATION_SYSTEM_PROMPT + """

                ## Language
                - Write every transcript line in %1$s, even though these instructions are in English.
                - Speaker names, audio tags in square brackets and section headings may stay in English.
                - Pick accents that fit %1$s speakers.
                """.formatted(languageName);
    }

    static GeminiTTS.RequestPayload getTTSPrompt(String conversation, String languageName, List<GeminiTTS.RequestPayload.SpeakerVoiceConfig> speakerVoiceConfigs) {
        GeminiTTS.RequestPayload payload = new GeminiTTS.RequestPayload();

        GeminiTTS.RequestPayload.Content content = new GeminiTTS.RequestPayload.Content();

        GeminiTTS.RequestPayload.Part part = new GeminiTTS.RequestPayload.Part();
        part.text = """
                Synthesize the multi-speaker dialogue below as natural speech. Follow the configured
                speaker voices and bracketed vocal cues. Do not read section headings, scene notes,
                director notes, or formatting instructions aloud; only speak the lines under the
                transcript section. The dialogue is spoken in %s.

                %s
                """.formatted(languageName, conversation);

        content.parts = List.of(part);
        content.role = "user";

        payload.contents = List.of(content);

        GeminiTTS.RequestPayload.GenerationConfig generationConfig =
                new GeminiTTS.RequestPayload.GenerationConfig();

        generationConfig.responseModalities = List.of("AUDIO");
        generationConfig.temperature = 1.0;

        GeminiTTS.RequestPayload.SpeechConfig speechConfig = new GeminiTTS.RequestPayload.SpeechConfig();

        GeminiTTS.RequestPayload.MultiSpeakerVoiceConfig multiSpeakerVoiceConfig = new GeminiTTS.RequestPayload.MultiSpeakerVoiceConfig();
        multiSpeakerVoiceConfig.speaker_voice_configs = speakerVoiceConfigs;

        speechConfig.multi_speaker_voice_config = multiSpeakerVoiceConfig;

        generationConfig.speech_config = speechConfig;

        payload.generationConfig = generationConfig;

        return payload;
    }

    static GeminiFlash.GenerateContentRequest getFlashPrompt(String citizenInfo, String languageName) {
        GeminiFlash.GenerateContentRequest request = new GeminiFlash.GenerateContentRequest();

        GeminiFlash.GenerateContentRequest.SystemInstruction systemInstruction = new GeminiFlash.GenerateContentRequest.SystemInstruction();
        GeminiFlash.GenerateContentRequest.Part systemPart = new GeminiFlash.GenerateContentRequest.Part();
        systemPart.text = conversationSystemPrompt(languageName);
        systemInstruction.parts = List.of(systemPart);
        request.system_instruction = systemInstruction;

        GeminiFlash.GenerateContentRequest.Content content = new GeminiFlash.GenerateContentRequest.Content();
        GeminiFlash.GenerateContentRequest.Part contentPart = new GeminiFlash.GenerateContentRequest.Part();
        contentPart.text = citizenInfo;
        content.parts = List.of(contentPart);
        request.contents = content;

        return request;
    }

    /** Conversational info for every participant, separated the way the script prompt expects. */
    static StringBuilder participantInfo(List<CitizenPromptView> views) {
        StringBuilder citizenInfo = new StringBuilder("-----\n");
        for (CitizenPromptView view : views) {
            citizenInfo.append(PromptRuntime.generateConversationalInfoPrompt(view)).append("\n-----\n");
        }
        return citizenInfo;
    }

    public static String generateConversation(
            List<PromptParticipant> participants,
            Consumer<GeminiTTS.AudioChunk> chunkConsumer
    ) throws ConversationGenerationException {
        StringBuilder citizenInfo = participantInfo(participants.stream().map(PromptParticipant::view).toList());
        List<TtsVoiceRecovery.Speaker> ttsSpeakers = new ArrayList<>();

        for (PromptParticipant participant : participants) {
            CitizenPromptView view = participant.view();
            ttsSpeakers.add(new TtsVoiceRecovery.Speaker(
                    participant.citizenId(),
                    view.identity().name(),
                    view.identity().female()));
        }

        // Every view is built from the same server config, so the first participant's language applies to all.
        String languageName = participants.get(0).view().conversation().responseLanguageName();
        String conversation = generateConversationScript(participants.size(), citizenInfo, languageName);

        String apiKey = McTalkingConfig.INSTANCE.instance().geminiApiKey;
        var selectedAi = McTalkingConfig.INSTANCE.instance().currentAiModel;
        try {
            McTalking.LOGGER.info("Sending TTS generation request to Gemini TTS for {} citizens", participants.size());
            TtsVoiceRecovery.generate(
                    selectedAi,
                    McTalkingConfig.TTS_MODEL,
                    ttsSpeakers,
                    speakerVoiceConfigs -> GeminiTTS.streamGenerateAudioConversation(
                            McTalkingConfig.TTS_MODEL,
                            apiKey,
                            getTTSPrompt(conversation, languageName, speakerVoiceConfigs),
                            chunkConsumer));
            TtsQuotaManager.reportSuccess();
        } catch (IOException | UnexpectedResponseException e) {
            McTalking.LOGGER.error("Failed to generate conversation audio using Gemini TTS", e);
            TtsQuotaManager.reportFailure(e);
            throw new ConversationGenerationException("Failed to generate conversation audio using Gemini TTS", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            McTalking.LOGGER.error("Conversation audio generation was interrupted", e);
            TtsQuotaManager.reportFailure(e);
            throw new ConversationGenerationException("Conversation audio generation was interrupted", e);
        } catch (me.sshcrack.mc_talking.manager.VoiceSelectionService.VoiceCandidatesExhaustedException e) {
            String message = e.getMessage();
            McTalking.LOGGER.error("TTS voice recovery exhausted before generation: {}", message);
            throw new ConversationGenerationException(
                    message == null ? e.getClass().getSimpleName() : message, e);
        }

        return conversation;
    }

    @NotNull
    private static String generateConversationScript(int participantCount, StringBuilder citizenInfo, String languageName) throws ConversationGenerationException {
        String apiKey = McTalkingConfig.INSTANCE.instance().geminiApiKey;
        String rawConversationOutput;
        try {
            McTalking.LOGGER.info("Sending conversation generation request to Gemini Flash for {} citizens", participantCount);
            rawConversationOutput = GeminiFlash.sendFlashRequest(McTalkingConfig.FLASH_MODEL, apiKey, getFlashPrompt(citizenInfo.toString(), languageName));
        } catch (IOException | UnexpectedResponseException e) {
            McTalking.LOGGER.error("Failed to generate conversation using Gemini Flash", e);
            TtsQuotaManager.reportFailure(e);
            throw new ConversationGenerationException("Failed to generate conversation using Gemini Flash", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            McTalking.LOGGER.error("Conversation generation was interrupted", e);
            TtsQuotaManager.reportFailure(e);
            throw new ConversationGenerationException("Conversation generation was interrupted", e);
        }

        return rawConversationOutput;
    }
}
