package me.sshcrack.mc_talking;

import com.minecolonies.api.entity.citizen.AbstractCivilianEntity;
import com.mojang.brigadier.Command;
import me.sshcrack.mc_talking.handler.MissingApiKeyOnboardingHandler;
import me.sshcrack.mc_talking.network.AiStatus;
import me.sshcrack.mc_talking.client.ConfigScreenOpener;
import me.sshcrack.mc_talking.client.ConversationPresentation;
import net.minecraft.commands.Commands;
import me.sshcrack.mc_talking.client.TalkKeybinds;
/*? if forge {*/
/*import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
*//*?}*/
/*? if neoforge {*/
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
/*?}*/
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import me.sshcrack.mc_talking.internal.audio.VoiceDucking;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side mod class for McTalking.
 * Handles client-specific functionality like rendering and UI.
 */
/*? if forge {*/
/*@Mod.EventBusSubscriber(modid = McTalking.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
*//*?}*/
/*? if neoforge {*/
@Mod(value = McTalking.MODID, dist = Dist.CLIENT)
/*?}*/
public class McTalkingClient {
    // Track AI status for each entity
    private static final Map<UUID, AiStatus> aiStatus = new ConcurrentHashMap<>();


    /**
     * Updates the AI status for a specific entity
     *
     * @param entityId The UUID of the entity
     * @param status   The AI status to set
     */
    public static void updateAiStatus(UUID entityId, AiStatus status) {
        if (status == AiStatus.NONE) {
            aiStatus.remove(entityId);
        } else {
            aiStatus.put(entityId, status);
        }
    }


    /*? if neoforge {*/
    public McTalkingClient(IEventBus modEventBus, ModContainer container) {
        NeoForge.EVENT_BUS.register(this);
        modEventBus.addListener(TalkKeybinds::register);
        /*? if devtools {*/
        /*me.sshcrack.mc_talking.devtools.DevAutoQuit.init();
        *//*?}*/
    }
    /*?}*/

    /**
     * Event handler for when the client disconnects from a server.
     * Clears all AI status data.
     *
     * @param event The level unload event
     */
    @SubscribeEvent
    /*? if forge {*/
    /*public static void onDisconnect(LevelEvent.Unload event) {
    *//*?}*/
    /*? if neoforge {*/
    public void onDisconnect(LevelEvent.Unload event) {
    /*?}*/
        if (event.getLevel().isClientSide()) {
            aiStatus.clear();
            partners.clear();
            me.sshcrack.mc_talking.internal.audio.SpeechEnvelope.clear();
            VoiceDucking.clear();
        }
    }

    private static final java.util.Map<UUID, UUID> partners = new java.util.concurrent.ConcurrentHashMap<>();

    public static void updateConversation(UUID citizen, AiStatus status, UUID partner) {
        updateAiStatus(citizen, status);
        if (partner.equals(new UUID(0, 0))) partners.remove(citizen);
        else partners.put(citizen, partner);
    }

    @Nullable
    public static UUID getConversationPartner(UUID citizen) { return partners.get(citizen); }

    /** The citizen the local player is talking to right now, or null. Safe off the render thread. */
    public static @Nullable UUID localConversationPartner() {
        var player = Minecraft.getInstance().player;
        if (player == null) return null;
        UUID self = player.getUUID();
        for (var entry : partners.entrySet()) {
            if (self.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    public static AiStatus getAiStatus(UUID entityId) {
        return aiStatus.getOrDefault(entityId, AiStatus.NONE);
    }

    @SubscribeEvent
    /*? if forge {*/
    /*public static void onEntityLeave(EntityLeaveLevelEvent event) {
    *//*?}*/
    /*? if neoforge {*/
    public void onEntityLeave(EntityLeaveLevelEvent event) {
    /*?}*/
        if (event.getLevel().isClientSide()) {
            aiStatus.remove(event.getEntity().getUUID());
            partners.remove(event.getEntity().getUUID());
            me.sshcrack.mc_talking.internal.audio.SpeechEnvelope.remove(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    /*? if forge {*/
    /*public static void onRenderCitizen(RenderLivingEvent.Post<?, ?> event) {
    *//*?}*/
    /*? if neoforge {*/
    public void onRenderCitizen(RenderLivingEvent.Post<?, ?> event) {
    /*?}*/
        if (event.getEntity() instanceof AbstractCivilianEntity citizen) {
            ConversationPresentation.renderBubble(citizen, event.getPoseStack(),
                    event.getMultiBufferSource(), event.getPartialTick());
        }
    }

    @SubscribeEvent
    /*? if forge {*/
    /*public static void onRenderGui(RenderGuiEvent.Post event) {
    *//*?}*/
    /*? if neoforge {*/
    public void onRenderGui(RenderGuiEvent.Post event) {
    /*?}*/
        ConversationPresentation.renderFocusHint(event.getGuiGraphics());
    }

    /**
     * Registers a client-only command that opens the Talking Colonists config
     * screen. On NeoForge, clicking the onboarding chat message's "open config" link
     * runs this locally. Forge 1.20.1 sends clicked commands straight to the server,
     * so {@link MissingApiKeyOnboardingHandler#registerServerFallback} covers that case.
     */
    @SubscribeEvent
    /*? if forge {*/
    /*public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
    *//*?}*/
    /*? if neoforge {*/
    public void onRegisterClientCommands(RegisterClientCommandsEvent event) {
    /*?}*/
        event.getDispatcher().register(
                Commands.literal(MissingApiKeyOnboardingHandler.OPEN_CONFIG_CLIENT_COMMAND)
                        .executes(ctx -> {
                            ConfigScreenOpener.open();
                            return Command.SINGLE_SUCCESS;
                        })
        );
    }
    @SubscribeEvent
    /*? if forge {*/
    /*public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        TalkKeybinds.tick();
    }
    *//*?}*/
    /*? if neoforge {*/
    public void onClientTick(ClientTickEvent.Post event) {
        TalkKeybinds.tick();
    }
    /*?}*/
}
