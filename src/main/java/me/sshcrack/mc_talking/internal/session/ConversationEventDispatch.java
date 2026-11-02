package me.sshcrack.mc_talking.internal.session;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.api.conversation.ConversationLifecycleEvent;
import me.sshcrack.mc_talking.api.conversation.ConversationUtteranceEvent;
import me.sshcrack.mc_talking.internal.api.ConversationEventRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds conversation lifecycle and utterance events and delivers them to addon listeners on the
 * server thread. Callers decide whether an event should be sent; this class only builds and delivers.
 */
public final class ConversationEventDispatch {
    private ConversationEventDispatch() {
    }

    public static void lifecycle(ConversationLifecycleEvent.Phase phase, AbstractEntityCitizen citizen,
                                 ConversationKind kind, @Nullable UUID playerId, @Nullable UUID sessionId,
                                 @Nullable UUID turnId, @Nullable String purpose) {
        ConversationLifecycleEvent event = new ConversationLifecycleEvent(phase, kind, citizen, playerId, sessionId,
                turnId, citizen.level().getGameTime(), purpose);
        runOnServerThread(citizen, () -> ConversationEventRuntime.emit(event));
    }

    /**
     * One finished utterance of a provider session. The session context is passed in by the caller
     * (read when the utterance finished); names are resolved on the server thread.
     */
    public static void utterance(AbstractEntityCitizen citizen, ConversationKind kind,
                                 ConversationUtteranceEvent.Speaker speaker, @Nullable UUID playerId,
                                 @Nullable UUID sessionId, @Nullable UUID turnId, String text,
                                 ConversationUtteranceEvent.Source source) {
        runOnServerThread(citizen, () -> {
            UUID speakerId;
            String speakerName;
            if (speaker == ConversationUtteranceEvent.Speaker.PLAYER) {
                MinecraftServer server = citizen.level().getServer();
                ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(playerId);
                speakerId = playerId;
                speakerName = player == null ? "Player" : player.getName().getString();
            } else {
                speakerId = citizen.getUUID();
                speakerName = citizen.getName().getString();
            }
            ConversationEventRuntime.emitUtterance(new ConversationUtteranceEvent(kind, citizen, speaker, speakerId,
                    speakerName, text, sessionId, turnId, source, citizen.level().getGameTime()));
        });
    }

    /** Reports the heard lines of a Flash/TTS pair conversation script, one event per line. */
    public static void scriptUtterances(List<AbstractEntityCitizen> participants, String script) {
        if (!ConversationEventRuntime.hasUtteranceListeners() || participants.isEmpty()) return;
        AbstractEntityCitizen first = participants.get(0);
        runOnServerThread(first, () -> {
            Map<String, AbstractEntityCitizen> byName = new LinkedHashMap<>();
            for (AbstractEntityCitizen participant : participants) byName.put(participant.getName().getString(), participant);
            for (var line : ScriptUtterances.parse(script, List.copyOf(byName.keySet()))) {
                AbstractEntityCitizen speaker = byName.get(line.speaker());
                // A script may name somebody who is not a participant; nothing was heard there.
                if (speaker == null) continue;
                ConversationEventRuntime.emitUtterance(new ConversationUtteranceEvent(ConversationKind.CITIZEN_PAIR,
                        speaker, ConversationUtteranceEvent.Speaker.CITIZEN, speaker.getUUID(), line.speaker(),
                        line.text(), null, null, ConversationUtteranceEvent.Source.SCRIPT, speaker.level().getGameTime()));
            }
        });
    }

    /** Runs {@code action} on the citizen's server thread: now when already on it, otherwise queued. */
    public static void runOnServerThread(AbstractEntityCitizen citizen, Runnable action) {
        MinecraftServer server = citizen.level().getServer();
        if (server != null && !server.isSameThread()) server.execute(action);
        else action.run();
    }
}
