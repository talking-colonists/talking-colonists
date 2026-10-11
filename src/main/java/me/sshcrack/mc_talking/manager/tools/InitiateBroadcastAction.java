package me.sshcrack.mc_talking.manager.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.api.memory.BroadcastRequest;
import me.sshcrack.mc_talking.api.memory.BroadcastSource;
import me.sshcrack.mc_talking.broadcast.BroadcastMessages;
import me.sshcrack.mc_talking.broadcast.BroadcastPublisher;
import me.sshcrack.mc_talking.broadcast.PlayerWords;
import me.sshcrack.mc_talking.broadcast.MineColoniesBroadcastColony;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.UUID;

public class InitiateBroadcastAction extends PlayerFunctionAction {
    public InitiateBroadcastAction() {
        super("initiate_broadcast", "Records a message to broadcast across the colony for other citizens to hear. Only invoke this when the player explicitly requests a formal colony-wide announcement AND has told you what it should say: 'message' is their announcement in their own words, never a template or placeholder. If they only asked how to tell everyone something, explain it (or offer to pass it on) and wait until they say the message. In addition to this tool you'll also need to shout out the message",
                new ObjectProperty(new HashMap<>() {{
                    put("message", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                }}));
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && McTalkingConfig.INSTANCE.instance().enableBroadcastPropagation;
    }

    @NotNull
    @Override
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        var obj = new JsonObject();
        if (parameters == null || !parameters.has("message")) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Missing or invalid parameters.");
            return obj;
        }

        String message = parameters.get("message").getAsString();
        if (BroadcastMessages.isPlaceholder(message)) {
            obj.addProperty("success", false);
            obj.addProperty("error", "That is not the player's announcement. Ask the player what the whole colony should hear, then call this with their words.");
            return obj;
        }
        String playerWords = PlayerWords.recent(citizen.getUUID(), System.currentTimeMillis());
        if (!playerWords.isBlank() && !BroadcastMessages.isFromPlayer(message, playerWords)) {
            obj.addProperty("success", false);
            obj.addProperty("error", "The player did not say that. Only announce what the player told you to announce, in their "
                    + "words. If they have not said what the colony should hear, ask them.");
            return obj;
        }
        UUID playerUUID = ConversationManager.getPlayerForEntity(citizen.getUUID());
        String senderPlayerName = "Unknown Player";
        if (playerUUID != null) {
            var server = citizen.level().getServer();
            if (server != null) {
                var player = server.getPlayerList().getPlayer(playerUUID);
                if (player != null) {
                    senderPlayerName = player.getScoreboardName();
                }
            }
        }

        BroadcastRequest request;
        try {
            request = BroadcastRequest.fromCitizen(BroadcastSource.player(playerUUID, senderPlayerName), message, citizen.getUUID());
        } catch (IllegalArgumentException e) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Invalid message: " + e.getMessage());
            return obj;
        }

        var config = McTalkingConfig.INSTANCE.instance();
        var result = BroadcastPublisher.INSTANCE.publish(
                new MineColoniesBroadcastColony(colony, config.broadcastPropagationRange),
                request,
                new BroadcastPublisher.Settings(config.enableBroadcastPropagation, config.maxBroadcastsStored));
        if (!result.isPublished()) {
            obj.addProperty("success", false);
            obj.addProperty("error", switch (result.status()) {
                case RATE_LIMITED -> "Too many colony announcements recently. Tell the player to try again later.";
                case DISABLED -> "Colony announcements are disabled on this server.";
                default -> "The announcement could not be recorded.";
            });
            return obj;
        }
        String broadcastId = result.broadcastId();

        obj.addProperty("success", true);
        obj.addProperty("broadcast_id", broadcastId);

        return obj;
    }
}
