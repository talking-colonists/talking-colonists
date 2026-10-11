package me.sshcrack.mc_talking;

import me.sshcrack.mc_talking.broadcast.GossipMoments;
import me.sshcrack.mc_talking.conversations.memory.ProgressMemories;
import me.sshcrack.mc_talking.conversations.complaints.Complaints;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.commands.CitizenChatCommand;
import me.sshcrack.mc_talking.internal.api.ProviderStatusServiceBackend;
import me.sshcrack.mc_talking.commands.McTalkingDebugCommand;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.conversations.memory.MemoryCompactionService;
import me.sshcrack.mc_talking.broadcast.BroadcastPropagationService;
import me.sshcrack.mc_talking.internal.audio.SpeechTimeline;
import me.sshcrack.mc_talking.handler.CasualGreetingHandler;
import me.sshcrack.mc_talking.handler.ChatToCitizenHandler;
import me.sshcrack.mc_talking.handler.CitizenMumblingHandler;
import me.sshcrack.mc_talking.handler.MissingApiKeyOnboardingHandler;
import me.sshcrack.mc_talking.handler.PregeneratedGreetingHandler;
import me.sshcrack.mc_talking.handler.RandomConversationHandler;
import me.sshcrack.mc_talking.handler.UrgentContactHandler;
import me.sshcrack.mc_talking.internal.api.TalkingColonistsApiBackend;
import me.sshcrack.mc_talking.onboarding.MissingApiKeyLogger;
import me.sshcrack.mc_talking.onboarding.Introductions;
import me.sshcrack.mc_talking.interaction.TalkToCitizenHandler;
import me.sshcrack.mc_talking.rumor.RumorMillService;
import me.sshcrack.mc_talking.item.CitizenTalkingDevice;
import me.sshcrack.mc_talking.pregen.DeliveryInteractionManager;
import me.sshcrack.mc_talking.pregen.HeatmapTracker;
import me.sshcrack.mc_talking.pregen.PlayerHeatmapTracker;
import me.sshcrack.mc_talking.pregen.PregenerationTaskService;
import me.sshcrack.mc_talking.pregen.PregenerationPlayback;
import me.sshcrack.mc_talking.util.CitizenHelper;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


/*? if neoforge {*/
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.core.component.DataComponents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
/*?}*/
/*? if forge {*/
/*import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

*//*?}*/
/*? if neoforge {*/
/*?}*/

/**
 * Orchestrates server-side events for citizen interactions.
 * Delegates domain logic to focused handler classes.
 */
public class ServerEventHandler {
    private int tickCounter = 0;

    public ServerEventHandler() {
    }

    @SubscribeEvent
    public void onServerStart(ServerStartingEvent event) {
        MissingApiKeyOnboardingHandler.onServerStart();
        MissingApiKeyLogger.reset();

        if (!McTalkingConfig.hasGeminiApiKey()) {
            McTalking.LOGGER.error("======================");
            McTalking.LOGGER.error("Gemini API key not set. McTalking is disabled.");
            McTalking.LOGGER.error("======================");
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            MissingApiKeyOnboardingHandler.onPlayerLoggedIn(player);
        }
    }

    /** Q10: chat lines addressed to the citizen the player is talking to never reach server chat. */
    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        if (ChatToCitizenHandler.onChat(event.getPlayer(), event.getRawText())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        TalkingColonistsApiBackend.onPlayerLoggedOut(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        McTalkingDebugCommand.register(event.getDispatcher());
        CitizenChatCommand.register(event.getDispatcher());
        MissingApiKeyOnboardingHandler.registerServerFallback(event.getDispatcher(), event.getCommandSelection());
    }

    @SubscribeEvent
    public void onServerStop(ServerStoppingEvent event) {
        MemoryCompactionService.cleanup();
        me.sshcrack.mc_talking.internal.api.TextGenerationShutdown.cancelAll();
        PregenerationTaskService.cleanup();
        PregenerationPlayback.cleanup();
        DeliveryInteractionManager.cleanup();

        MinecraftServer server = event.getServer();
        TalkingColonistsApiBackend.onServerStopping(server);
        UrgentContactHandler.onServerStop(server);
        Introductions.stopAll();
        ConversationManager.cleanup();

        CasualGreetingHandler.onServerStop();
        AmbientSessions.onServerStop();
        GossipMoments.clear();
        ProgressMemories.clear();
        PlayerHeatmapTracker.clear();
    }

    @SubscribeEvent
    public void onPlayerJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        for (ItemStack item : player.getInventory().items) {
            if (item.getItem() instanceof CitizenTalkingDevice) {
                /*? if forge {*/
				/*CompoundTag tag = item.getOrCreateTag();
				tag.putInt("CustomModelData", 0);

				*//*?}*/
                /*? if neoforge {*/
                item.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(0));
                /*?}*/
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            ConversationManager.endConversationForDisconnect(player.getUUID());

            MinecraftServer server = player.getServer();
            UrgentContactHandler.onPlayerLeave(player.getUUID(), server);
            CasualGreetingHandler.onPlayerLeave(player.getUUID());
            PlayerHeatmapTracker.removePlayer(player.getUUID());
        }
    }

    /**
     * Sneak + left-click (empty main hand) toggles a direct conversation with the targeted
     * citizen, mirroring {@code CitizenTalkingDevice}'s left-click gesture but without
     * requiring the item. Plain right-click on a citizen is left untouched: MineColonies uses
     * it for its own citizen window, and sneak + right-click for colony inventory access, so
     * this gesture deliberately uses left-click instead to avoid conflicting with either.
     */
    @SubscribeEvent
    public void onTalkGestureAttack(AttackEntityEvent event) {
        if (!TalkToCitizenHandler.isEnabled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()) return;
        if (!(event.getTarget() instanceof AbstractEntityCitizen citizen)) return;

        event.setCanceled(true);
        TalkToCitizenHandler.attempt(player, citizen, true);
    }

    private void onServerTickCommon(MinecraftServer server) {
        tickCounter++;
        GossipMoments.tick();
        if (tickCounter % 1200 == 0) ProgressMemories.tick(server);
        if (tickCounter % 1200 == 600) Complaints.tick(server);
        // Reap timed-out background/foreground reservations even when no feature-specific
        // interval fires or no players are online. This keeps lifecycle ownership in core.
        ConversationManager.tickMaintenance();
        UrgentContactHandler.tick(server);
        Introductions.tick(server);
        if (tickCounter % 20 == 0) ProviderStatusServiceBackend.RUNTIME.poll();
        if (tickCounter % 40 == 0) SpeechTimeline.listeners(server);

        boolean doDistanceCheck = (tickCounter % 5 == 0);
        boolean doMumblingCheck = (tickCounter % McTalkingConfig.INSTANCE.instance().mumblingCheckIntervalTicks == 0);
        boolean doRandomConvCheck = McTalkingConfig.INSTANCE.instance().enableCitizenToCitizenConversation
                && McTalkingConfig.INSTANCE.instance().enableRandomConversations
                && (tickCounter % McTalkingConfig.INSTANCE.instance().randomConversationCheckIntervalTicks == 0);
        boolean doContactCheck = McTalkingConfig.INSTANCE.instance().enableCitizenInitiatedContact
                && (tickCounter % McTalkingConfig.INSTANCE.instance().citizenContactCheckIntervalTicks == 0);
        boolean doRumorCheck = McTalkingConfig.INSTANCE.instance().enableRumorMill
                && (tickCounter % McTalkingConfig.INSTANCE.instance().rumorMillCheckIntervalTicks == 0);
        boolean doBroadcastPropagation = McTalkingConfig.INSTANCE.instance().enableBroadcastPropagation
                && (tickCounter % McTalkingConfig.INSTANCE.instance().broadcastPropagationIntervalTicks == 0);

        if (!doDistanceCheck && !doMumblingCheck && !doRandomConvCheck && !doContactCheck && !doRumorCheck && !doBroadcastPropagation) {
            return;
        }

        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }

        var activePlayers = server.getPlayerList().getPlayers();

        Set<Long> processedGreetingPairs = new HashSet<>();
        Set<UUID> mumbledCitizens       = new HashSet<>();
        Set<UUID> contactedCitizens     = new HashSet<>();
        Set<UUID> greetedCasually       = new HashSet<>();

        for (ServerPlayer player : activePlayers) {

            UUID playerId = player.getUUID();
            if (McTalkingVoicechatPlugin.shouldDisableColoniesTicks(player))
                continue;

            double range = McTalkingConfig.INSTANCE.instance().citizenInteractionRange;
            var aabb = player.getBoundingBox().inflate(range);
            var citizens = player.level().getEntitiesOfClass(AbstractEntityCitizen.class, aabb);

            if (doDistanceCheck) {
                trackCitizenProximityHeatmap(citizens, processedGreetingPairs);
                trackPlayerCitizenHeatmap(player, citizens);
                checkConversationDistance(player);
            }

            AbstractEntityCitizen talkingCitizen = ConversationManager.getActiveEntityForPlayer(player.getUUID());
            if (talkingCitizen != null
                    && !McTalkingConfig.INSTANCE.instance().continueWorkDuringConversation) {
                talkingCitizen.getLookControl().setLookAt(player, 30f, 30f);
                if (!talkingCitizen.getNavigation().isDone()) {
                    talkingCitizen.getNavigation().stop();
                }
            }

            if (ConversationManager.isPlayerInConversation(playerId))
                continue;

            if (doDistanceCheck && McTalkingConfig.INSTANCE.instance().enablePregeneration)
                PregeneratedGreetingHandler.playPregeneratedGreetings(citizens, processedGreetingPairs);

            if (doDistanceCheck && McTalkingConfig.INSTANCE.instance().enablePlayerGreetingPregen)
                PregeneratedGreetingHandler.playPregeneratedPlayerGreetings(player, citizens);

            if (doMumblingCheck) {
                CitizenMumblingHandler.checkForMumblingCitizens(citizens, mumbledCitizens);
            }

            if (doContactCheck) {
                UrgentContactHandler.checkForCitizenInitiatedContact(player, citizens, contactedCitizens);
            }

            if (doContactCheck && McTalkingConfig.INSTANCE.instance().citizenCasualGreetingWeight > 0) {
                CasualGreetingHandler.checkForCasualGreeting(player, citizens, greetedCasually);
            }
        }


        RandomConversationHandler.endInterrupted();
        ConversationManager.holdAddressedSpeakers();
        if (doRandomConvCheck) {
            RandomConversationHandler.checkForRandomConversations(server);
        }

        if (McTalkingConfig.INSTANCE.instance().enablePregeneration) {
            PregenerationTaskService.tick(server);
            DeliveryInteractionManager.tick(server);
        }

        if (McTalkingConfig.INSTANCE.instance().enableMemoryCompaction) {
            MemoryCompactionService.tick(server);
        }

        if (doRumorCheck) {
            RumorMillService.tick(server);
        }

        if (doBroadcastPropagation) {
            BroadcastPropagationService.tick(server);
        }
    }

    @SubscribeEvent
            /*? if forge {*/
	/*public void onServerTick(TickEvent.ServerTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
		onServerTickCommon(event.getServer());
	}
	*//*?}*/
            /*? if neoforge {*/
    public void onServerTick(ServerTickEvent.Post event) {
        onServerTickCommon(event.getServer());
    }
    /*?}*/

    private void checkConversationDistance(ServerPlayer player) {
        AbstractEntityCitizen activeEntity = ConversationManager.getActiveEntityForPlayer(player.getUUID());
        if (activeEntity == null || !activeEntity.isAlive()) {
            ConversationManager.endConversation(player.getUUID(), false);
            return;
        }

        double distanceSquared = player.distanceToSqr(activeEntity);
        if (distanceSquared > McTalkingConfig.INSTANCE.instance().maxConversationDistance * McTalkingConfig.INSTANCE.instance().maxConversationDistance) {
            ConversationManager.endConversation(player.getUUID(), true);
        }
    }

    private void trackCitizenProximityHeatmap(List<AbstractEntityCitizen> citizens,
                                               Set<Long> processedPairs) {
        for (int i = 0; i < citizens.size(); i++) {
            AbstractEntityCitizen citizenOne = citizens.get(i);
            for (int j = i + 1; j < citizens.size(); j++) {
                AbstractEntityCitizen citizenTwo = citizens.get(j);
                double distSq = citizenOne.distanceToSqr(citizenTwo);
                if (distSq < HeatmapTracker.DISTANCE_BETWEEN_CITIZENS_FOR_RECORDING) {
                    int idA = citizenOne.getId();
                    int idB = citizenTwo.getId();
                    long pairKey = ((long) Math.min(idA, idB) << 32) | (Math.max(idA, idB) & 0xFFFFFFFFL);
                    if (!processedPairs.add(pairKey)) {
                        continue;
                    }
                    HeatmapTracker.recordProximity(citizenOne.getUUID(), citizenTwo.getUUID());
                }
            }
        }
    }

    private void trackPlayerCitizenHeatmap(ServerPlayer player, List<AbstractEntityCitizen> citizens) {
        UUID playerId = player.getUUID();
        for (AbstractEntityCitizen citizen : citizens) {
            double distSq = player.distanceToSqr(citizen);
            if (distSq < PlayerHeatmapTracker.DISTANCE_FOR_RECORDING) {
                PlayerHeatmapTracker.recordProximity(citizen.getUUID(), playerId);
            }
        }
    }


    @SubscribeEvent
    public void onCitizenTargetChanged(LivingChangeTargetEvent event) {
        if (!McTalkingConfig.INSTANCE.instance().enablePregeneration) return;
        /*? if forge {*/
        /*LivingEntity newTarget = event.getNewTarget();
        *//*?}*/

        /*? if neoforge {*/
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        /*?}*/

        if (newTarget instanceof AbstractEntityCitizen citizen
                && !CitizenHelper.isCitizenGuard(citizen)) {
            PregenerationTaskService.playThreatNow(citizen, event.getEntity());
        }
    }
}
