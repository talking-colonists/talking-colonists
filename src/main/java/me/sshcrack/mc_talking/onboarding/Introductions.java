package me.sshcrack.mc_talking.onboarding;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.config.PersonalityArchetype;
import me.sshcrack.mc_talking.duck.CitizenDataPersonalityExtended;
import me.sshcrack.mc_talking.api.conversation.AmbientLineResult;
import me.sshcrack.mc_talking.api.conversation.CitizenActivityReservation;
import me.sshcrack.mc_talking.api.conversation.CitizenConversationService;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.api.guide.AddonGuide;
import me.sshcrack.mc_talking.api.intro.Introduction;
import me.sshcrack.mc_talking.api.memory.AddonConfirmedOutcome;
import me.sshcrack.mc_talking.api.memory.CitizenMemoryService;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.internal.api.GuideServiceBackend;
import me.sshcrack.mc_talking.internal.api.IntroductionServiceBackend;
import me.sshcrack.mc_talking.internal.session.AddressCooldowns;
import me.sshcrack.mc_talking.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-world onboarding: when a player is in their colony, a citizen walks up and tells them about
 * something once. First the welcome, which hands over the Colony Handbook, then the addons'
 * introductions as they become relevant. One at a time per player, and only when the shared cooldown
 * for unprompted lines to that player allows it. Which ones a player heard is kept in the player's
 * persisted data. Server thread only.
 */
public final class Introductions {
    private static final String HEARD_KEY = "mc_talking_introductions_heard";
    private static final String OWNER_ID = "mc_talking:introduction";
    private static final int CHECK_INTERVAL_TICKS = 100;
    private static final double SEARCH_RANGE = 48;
    private static final double ARRIVAL_DISTANCE = 2.5;
    private static final int TIMEOUT_TICKS = 20 * 60;
    private static final int STUCK_TICKS = 20 * 10;
    private static final double WALK_SPEED = 0.8;

    private static final class Walk {
        final ServerPlayer player;
        final AbstractEntityCitizen citizen;
        final CitizenActivityReservation reservation;
        final Introduction introduction;
        int ticks;
        int lastProgressTick;
        double bestDistanceSq = Double.MAX_VALUE;

        Walk(ServerPlayer player, AbstractEntityCitizen citizen, CitizenActivityReservation reservation, Introduction introduction) {
            this.player = player;
            this.citizen = citizen;
            this.reservation = reservation;
            this.introduction = introduction;
        }
    }

    private static final Map<UUID, Walk> WALKS = new ConcurrentHashMap<>();
    private static int ticks;

    private Introductions() {
    }

    public static void tick(MinecraftServer server) {
        ticks++;
        for (Walk walk : List.copyOf(WALKS.values())) {
            step(server, walk);
        }
        tickLines();
        if (ticks % CHECK_INTERVAL_TICKS != 0) return;
        if (!McTalkingConfig.INSTANCE.instance().enableIntroductions) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                consider(player);
            } catch (RuntimeException e) {
                McTalking.LOGGER.warn("[Introductions] Could not check introductions for {}", player.getName().getString(), e);
            }
        }
    }

    private static void consider(ServerPlayer player) {
        if (WALKS.containsKey(player.getUUID()) || player.isSpectator()) return;
        if (LINES.stream().anyMatch(line -> line.player.getUUID().equals(player.getUUID()))) return;
        if (ConversationManager.isPlayerInConversation(player.getUUID())) return;
        AddressCooldowns cooldowns = ConversationManager.addressCooldowns();
        var config = McTalkingConfig.INSTANCE.instance();
        if (!cooldowns.mayAddress(player.getUUID(), config.playerAddressCooldownSeconds)) return;
        IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), player.blockPosition());
        if (colony == null || !colony.getPermissions().isColonyMember(player)) return;

        Set<String> heard = heard(player);
        Optional<Introduction> next = IntroductionQueue.next(IntroductionServiceBackend.registered(), heard,
                introduction -> isDue(introduction, player, colony));
        if (next.isEmpty()) return;

        boolean welcome = next.get().id().equals(IntroductionServiceBackend.WELCOME_ID);
        for (AbstractEntityCitizen citizen : candidates(colony, player, welcome)) {
            CitizenActivityReservation reservation = CitizenConversationService
                    .reserveActivity(citizen, OWNER_ID, Duration.ofSeconds(TIMEOUT_TICKS / 20 + 30)).orElse(null);
            if (reservation == null) continue;
            WALKS.put(player.getUUID(), new Walk(player, citizen, reservation, next.get()));
            cooldowns.recordAddressed(player.getUUID());
            cooldowns.recordCitizenContact(citizen.getUUID());
            McTalking.LOGGER.info("[Introductions] {} walks up to {} about {}",
                    name(citizen), player.getName().getString(), next.get().id());
            return;
        }
    }

    private static boolean isDue(Introduction introduction, ServerPlayer player, IColony colony) {
        try {
            return introduction.trigger().isDue(player, colony);
        } catch (RuntimeException | LinkageError e) {
            McTalking.LOGGER.debug("[Introductions] Trigger of {} failed", introduction.id(), e);
            return false;
        }
    }

    /**
     * Citizens of the colony near the player who are free, nearest first. For the welcome the colony
     * sends someone friendly: warm personalities first, sarcastic and grumpy ones last.
     */
    private static List<AbstractEntityCitizen> candidates(IColony colony, ServerPlayer player, boolean welcome) {
        List<AbstractEntityCitizen> citizens = new ArrayList<>();
        for (ICitizenData data : colony.getCitizenManager().getCitizens()) {
            AbstractEntityCitizen citizen = data.getEntity().orElse(null);
            if (citizen == null || !citizen.isAlive() || citizen.isRemoved() || citizen.isSleeping()) continue;
            if (citizen.level() != player.level() || citizen.distanceToSqr(player) > SEARCH_RANGE * SEARCH_RANGE) continue;
            // Free to walk over and able to speak now: not in a conversation, a campfire circle or another controlled session.
            if (ConversationManager.shouldPauseRoutine(citizen)
                    || !ConversationManager.canCitizenSpeak(citizen, ConversationKind.ADDON_AMBIENT)) continue;
            citizens.add(citizen);
        }
        Comparator<AbstractEntityCitizen> nearest = Comparator.comparingDouble(citizen -> citizen.distanceToSqr(player));
        citizens.sort(welcome ? Comparator.comparingInt(Introductions::warmth).thenComparing(nearest) : nearest);
        return citizens;
    }

    private static void step(MinecraftServer server, Walk walk) {
        walk.ticks++;
        ServerPlayer player = server.getPlayerList().getPlayer(walk.player.getUUID());
        AbstractEntityCitizen citizen = walk.citizen;
        if (player == null || !citizen.isAlive() || citizen.isRemoved() || citizen.level() != player.level()
                || walk.ticks > TIMEOUT_TICKS || walk.reservation.isClosed()) {
            finish(walk);
            return;
        }
        double distanceSq = citizen.distanceToSqr(player);
        if (distanceSq <= ARRIVAL_DISTANCE * ARRIVAL_DISTANCE) {
            arrive(server, walk, player);
            return;
        }
        if (distanceSq < walk.bestDistanceSq - 1) {
            walk.bestDistanceSq = distanceSq;
            walk.lastProgressTick = walk.ticks;
        } else if (walk.ticks - walk.lastProgressTick > STUCK_TICKS) {
            McTalking.LOGGER.info("[Introductions] {} got stuck {} blocks from {}",
                    name(citizen), Math.round(Math.sqrt(distanceSq)), player.getName().getString());
            finish(walk);
            return;
        }
        // Aim above what the player stands on (their own block is not walkable while they jump), and
        // take the path back whenever the citizen's own AI sent them elsewhere.
        BlockPos feet = player.getOnPos().above();
        BlockPos heading = citizen.getNavigation().getTargetPos();
        boolean offCourse = heading == null || heading.distSqr(feet) > 4 || citizen.getNavigation().isDone();
        if (walk.ticks % 10 == 1 && offCourse) {
            citizen.getNavigation().moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, WALK_SPEED);
        }
        citizen.getLookControl().setLookAt(player, 30, 30);
    }

    private static void arrive(MinecraftServer server, Walk walk, ServerPlayer player) {
        AbstractEntityCitizen citizen = walk.citizen;
        Introduction introduction = walk.introduction;
        boolean welcome = introduction.id().equals(IntroductionServiceBackend.WELCOME_ID);
        citizen.getNavigation().stop();
        citizen.getLookControl().setLookAt(player, 30, 30);
        if (welcome) {
            citizen.swing(InteractionHand.MAIN_HAND);
            ItemStack handbook = new ItemStack(ModItems.COLONY_HANDBOOK.get());
            if (!player.getInventory().add(handbook)) player.drop(handbook, false);
        }
        markHeard(player, introduction.id());
        // Release the reservation first: it marks the citizen busy, and busy citizens cannot speak.
        finish(walk);

        MutableComponent chat = welcome
                ? Component.translatable("mc_talking.introduction.welcome", name(citizen))
                : Component.translatable("mc_talking.introduction.topic", name(citizen), introduction.topic());
        AddonGuide guide = guide(introduction.guideId());
        if (guide != null && !welcome) {
            chat.append(" ").append(Component.translatable("mc_talking.introduction.handbook", guide.title()));
        }
        Component fallback = chat.withStyle(ChatFormatting.GRAY);
        String directive = IntroductionTexts.directive(player.getGameProfile().getName(), welcome, introduction.lineHint(),
                guide != null && !welcome);
        LINES.add(new PendingLine(server, player, citizen, introduction, directive, fallback, ticks + LINE_WAIT_TICKS));
    }

    /**
     * An introduction the citizen came to say. When someone nearby is talking, they wait by the player for a
     * quiet moment (up to {@link #LINE_WAIT_TICKS}) instead of leaving only a chat line: in a playtest,
     * three of six introductions ended as chat because another citizen was speaking.
     */
    private static final class PendingLine {
        final MinecraftServer server;
        final ServerPlayer player;
        final AbstractEntityCitizen citizen;
        final Introduction introduction;
        final String directive;
        final Component fallback;
        final int deadline;
        int nextTry;
        boolean asking;

        PendingLine(MinecraftServer server, ServerPlayer player, AbstractEntityCitizen citizen, Introduction introduction,
                    String directive, Component fallback, int deadline) {
            this.server = server;
            this.player = player;
            this.citizen = citizen;
            this.introduction = introduction;
            this.directive = directive;
            this.fallback = fallback;
            this.deadline = deadline;
        }
    }

    private static final int LINE_WAIT_TICKS = 20 * 30;
    private static final int LINE_RETRY_TICKS = 40;
    private static final List<PendingLine> LINES = new ArrayList<>();

    private static void tickLines() {
        for (PendingLine line : List.copyOf(LINES)) {
            if (line.asking) continue;
            if (!line.citizen.isAlive() || line.citizen.isRemoved() || line.player.isRemoved()) {
                LINES.remove(line);
                continue;
            }
            // Waiting by the player: stay put and look at them.
            if (!line.citizen.getNavigation().isDone()) line.citizen.getNavigation().stop();
            line.citizen.getLookControl().setLookAt(line.player, 30, 30);
            if (ticks < line.nextTry) continue;
            line.asking = true;
            try {
                CitizenConversationService.requestAmbientLine(line.citizen, line.directive)
                        .whenComplete((result, error) -> line.server.execute(() -> {
                            line.asking = false;
                            boolean wait = error == null && result != null && result.status() == AmbientLineResult.Status.REJECTED
                                    && WAIT_FOR.contains(result.rejectionReason()) && ticks + LINE_RETRY_TICKS < line.deadline;
                            if (wait) {
                                line.nextTry = ticks + LINE_RETRY_TICKS;
                                return;
                            }
                            LINES.remove(line);
                            // Only a line someone heard counts; otherwise the player gets the chat line.
                            boolean spoke = error == null && result != null && result.completed() && !result.transcript().isBlank();
                            if (!spoke) line.player.sendSystemMessage(line.fallback);
                            remember(line.citizen, line.player, line.introduction);
                        }));
            } catch (RuntimeException e) {
                LINES.remove(line);
                line.player.sendSystemMessage(line.fallback);
            }
        }
    }

    /** Reasons that pass: someone nearby is talking, or a slot or budget frees up soon. */
    private static final Set<AmbientLineResult.RejectionReason> WAIT_FOR = Set.of(AmbientLineResult.RejectionReason.BUSY,
            AmbientLineResult.RejectionReason.CAPACITY_EXHAUSTED, AmbientLineResult.RejectionReason.BUDGET_EXCEEDED);

    /** The citizen remembers what they told the player, so a later conversation doesn't repeat it. */
    private static void remember(AbstractEntityCitizen citizen, ServerPlayer player, Introduction introduction) {
        ICitizenData data = citizen.getCitizenData();
        if (data == null) return;
        try {
            CitizenMemoryService.confirmOutcome(data, new AddonConfirmedOutcome(McTalking.MODID,
                    CitizenMemories.PRIVATE_OUTCOME_PREFIX + introduction.id() + ":" + player.getUUID(),
                    IntroductionTexts.memory(player.getGameProfile().getName(), introduction.id().equals(IntroductionServiceBackend.WELCOME_ID), introduction.topic()), player.getUUID(), List.of(), List.of()));
        } catch (RuntimeException e) {
            McTalking.LOGGER.warn("Could not store the introduction as {}'s memory", name(citizen), e);
        }
    }

    /** 0 for warm personalities, 2 for sarcastic or grumpy ones, 1 for everyone else. */
    static int warmth(AbstractEntityCitizen citizen) {
        if (!(citizen.getCitizenData() instanceof CitizenDataPersonalityExtended data)) return 1;
        PersonalityArchetype personality = data.mc_talking$getPersonality();
        if (personality == null) return 1;
        return switch (personality) {
            case OPTIMIST, NURTURING, CURIOUS -> 0;
            case SARCASTIC, GRUMP, COMPETITIVE, BOASTFUL -> 2;
            default -> 1;
        };
    }

    private static @Nullable AddonGuide guide(@Nullable String id) {
        if (id == null) return null;
        return GuideServiceBackend.registered().stream().filter(guide -> guide.id().equals(id)).findFirst().orElse(null);
    }

    private static void finish(Walk walk) {
        WALKS.remove(walk.player.getUUID(), walk);
        walk.reservation.close();
        if (walk.citizen.isAlive()) walk.citizen.getNavigation().stop();
    }

    /** Ends every walk (server stop); nobody counts as introduced. */
    public static void stopAll() {
        for (Walk walk : List.copyOf(WALKS.values())) {
            finish(walk);
        }
        LINES.clear();
        ticks = 0;
    }

    static Set<String> heard(ServerPlayer player) {
        ListTag list = persisted(player).getList(HEARD_KEY, Tag.TAG_STRING);
        Set<String> heard = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            heard.add(list.getString(i));
        }
        return heard;
    }

    private static void markHeard(ServerPlayer player, String id) {
        CompoundTag data = player.getPersistentData();
        CompoundTag persisted = data.getCompound(Player.PERSISTED_NBT_TAG);
        ListTag list = persisted.getList(HEARD_KEY, Tag.TAG_STRING);
        list.add(StringTag.valueOf(id));
        persisted.put(HEARD_KEY, list);
        data.put(Player.PERSISTED_NBT_TAG, persisted);
    }

    private static CompoundTag persisted(ServerPlayer player) {
        return player.getPersistentData().getCompound(Player.PERSISTED_NBT_TAG);
    }

    private static String name(AbstractEntityCitizen citizen) {
        return citizen.getCitizenData() != null ? citizen.getCitizenData().getName() : citizen.getName().getString();
    }
}
