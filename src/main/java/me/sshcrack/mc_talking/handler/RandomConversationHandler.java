package me.sshcrack.mc_talking.handler;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.conversations.CitizenConversation;
import me.sshcrack.mc_talking.network.AiStatus;
import me.sshcrack.mc_talking.onboarding.MissingApiKeyLogger;
import me.sshcrack.mc_talking.util.AiStatusHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles citizen-to-citizen random conversations triggered when two
 * unrelated citizens are near each other (and near a player).
 */
public class RandomConversationHandler {
    private RandomConversationHandler() {
    }

    /** Running random conversations, so one stops when a participant falls asleep. */
    private static final List<Running> RUNNING = new ArrayList<>();

    private record Running(CitizenConversation conversation, AbstractEntityCitizen first, AbstractEntityCitizen second) {
    }

    /**
     * Ends running pair chats that should stop: when either citizen fell asleep, or when a player starts
     * talking with a citizen within earshot (player conversations never wait for the floor, so the
     * pair gives way instead of talking over them). Server thread, every tick.
     */
    public static void endInterrupted() {
        if (RUNNING.isEmpty()) return;
        RUNNING.removeIf(running -> {
            if (running.conversation().isEnded()) return true;
            List<AbstractEntityCitizen> pair = List.of(running.first(), running.second());
            String reason;
            if (ConversationManager.isAsleep(running.first()) || ConversationManager.isAsleep(running.second())) {
                reason = "fell asleep";
            } else if (pair.stream().anyMatch(citizen -> ConversationManager.isPlayerConversationWithinEarshot(citizen, pair))) {
                reason = "gave way to a player conversation nearby";
            } else {
                return false;
            }
            McTalking.LOGGER.info("[RandomConv] {} and {} {}; their conversation ends", name(running.first()), name(running.second()), reason);
            running.conversation().abort();
            // abort() leaves the status to whoever takes over; nobody does here.
            for (AbstractEntityCitizen citizen : pair) {
                if (!ConversationManager.isCitizenBusy(citizen)) AiStatusHelper.setAiStatusSynced(citizen, AiStatus.NONE);
            }
            return true;
        });
    }

    private static String name(AbstractEntityCitizen citizen) {
        return citizen.getCitizenData() != null ? citizen.getCitizenData().getName() : citizen.getName().getString();
    }

    public static void checkForRandomConversations(MinecraftServer server) {
        if (!McTalkingConfig.hasGeminiApiKey()) {
            MissingApiKeyLogger.warnOnce("random citizen-to-citizen conversations");
            return;
        }

        double range = McTalkingConfig.INSTANCE.instance().citizenInteractionRange * 2;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            var nearbyBox = player.getBoundingBox().inflate(range);
            var citizens = player.serverLevel().getEntitiesOfClass(AbstractEntityCitizen.class, nearbyBox);

            // At night citizens head for bed: no new chats that would go on while they sleep.
            if (!player.serverLevel().isDay()) continue;
            for (AbstractEntityCitizen citizen : citizens) {
                if (!ConversationManager.canCitizenSpeak(citizen, ConversationKind.RANDOM_CITIZEN))
                    continue;

                if (Math.random() >= McTalkingConfig.INSTANCE.instance().randomConversationChance)
                    continue;

                List<AbstractEntityCitizen> partners = new ArrayList<>();
                for (AbstractEntityCitizen candidate : citizens) {
                    if (candidate == citizen)
                        continue;
                    if (!ConversationManager.canCitizenSpeak(candidate, ConversationKind.RANDOM_CITIZEN))
                        continue;
                    partners.add(candidate);
                }

                if (partners.isEmpty())
                    continue;

                AbstractEntityCitizen partner = partners.get((int) (Math.random() * partners.size()));

                if (citizen.getCitizenData() == null || partner.getCitizenData() == null)
                    continue;

                McTalking.LOGGER.info("[RandomConv] Starting conversation between {} and {}",
                        citizen.getCitizenData().getName(), partner.getCitizenData().getName());

                var conversation = new CitizenConversation(server, List.of(citizen, partner));
                conversation.setOnStateChanged(newState -> {
                    AiStatus status = switch (newState) {
                        case GENERATING -> AiStatus.THINKING;
                        case PLAYING_AUDIO -> AiStatus.IN_CONVERSATION;
                        case ENDED -> AiStatus.NONE;
                    };
                    // A player may have taken over one participant while the old
                    // paired session is finishing. Do not overwrite the new session's
                    // status with NONE from delayed cleanup.
                    if (newState != CitizenConversation.ConversationState.ENDED
                            || !ConversationManager.isCitizenBusy(citizen)) {
                        AiStatusHelper.setAiStatusSynced(citizen, status);
                    }
                    if (newState != CitizenConversation.ConversationState.ENDED
                            || !ConversationManager.isCitizenBusy(partner)) {
                        AiStatusHelper.setAiStatusSynced(partner, status);
                    }
                });
                conversation.performConversation();
                RUNNING.add(new Running(conversation, citizen, partner));

                return;
            }
        }
    }
}
