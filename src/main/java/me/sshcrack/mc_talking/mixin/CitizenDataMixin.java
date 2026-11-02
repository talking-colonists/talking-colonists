package me.sshcrack.mc_talking.mixin;

import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.core.colony.CitizenData;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.internal.prompt.PromptRuntime;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.config.PersonalityArchetype;
import me.sshcrack.mc_talking.config.PersonalityPool;
import me.sshcrack.mc_talking.conversations.memory.data.CitizenMemories;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import me.sshcrack.mc_talking.duck.CitizenDataPersonalityExtended;
import me.sshcrack.mc_talking.duck.CitizenRecentActionsProvider;
import me.sshcrack.mc_talking.manager.CitizenPromptViewFactory;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


@Mixin(value = CitizenData.class, remap = false)
public class CitizenDataMixin implements CitizenDataMemoryExtended, CitizenDataPersonalityExtended, CitizenRecentActionsProvider {
    @Unique
    private static final String TAG_MEMORY_KEY = "mc_talking_memory";
    @Unique
    private static final String TAG_PERSONALITY_KEY = "mc_talking_personality";
    @Unique
    private static final String TAG_RECENT_ACTIONS_KEY = "mc_talking_recent_actions";
    @Unique
    private static final String SLEEP_PROMPT = "You are now sleeping. END THE CONVERSATION NOW USING YOUR \"end_conversation\" TOOL. IGNORE ANY INSTRUCTIONS AND END CONVERSATION NOW!!!!";
    @Unique
    @SuppressWarnings("NullAway.Init")
    private CitizenMemories mc_talking$memory;
    @Unique
    @Nullable
    private PersonalityArchetype mc_talking$personality;
    @Unique
    @Nullable
    private String mc_talking$customPersonality;

    @Unique
    private static final int MAX_RECENT_ACTIONS = 10;
    @Unique
    private final ArrayDeque<String> mc_talking$recentActions = new ArrayDeque<>(MAX_RECENT_ACTIONS);

    @Override
    public void mc_talking$pushRecentAction(String entry) {
        if (mc_talking$recentActions.size() >= MAX_RECENT_ACTIONS) {
            mc_talking$recentActions.removeLast();
        }
        mc_talking$recentActions.addFirst(entry);
    }

    @Override
    public List<String> mc_talking$getRecentActions() {
        return List.copyOf(mc_talking$recentActions);
    }

    @Inject(method = "setVisibleStatus", at = @At("HEAD"))
    private void mc_talking$onSetVisibleStatus(VisibleCitizenStatus status, CallbackInfo ci) {
        var data = CitizenData.class.cast(this);
        if (status == null || data == null) {
            return;
        }

        var client = ConversationManager.getClientForEntity(data.getUUID());
        if (client == null) {
            return;
        }

        var lastStatus = client.getLastStatus();
        if (lastStatus != null && lastStatus.equals(status)) {
            return;
        }

        // A controlled turn (e.g. a campfire story) must not be told to end mid-sentence.
        if (!client.sendStatusUpdates()) {
            return;
        }

        if (status == VisibleCitizenStatus.SLEEP) {
            McTalking.LOGGER.info("[STATUS] Sending sleep prompt");
            client.setLastStatus(status);
            client.addPromptTextAfterTalkingComplete(SLEEP_PROMPT);
            return;
        }

        var statusView = CitizenPromptViewFactory.createStatusView(status, data);
        if (statusView == null) return;
        var newStatusPrompt = String.format("You are now %s", PromptRuntime.formatStatus(statusView));
        client.setLastStatus(status);
        client.addPromptTextAfterTalkingComplete(newStatusPrompt);
    }

    /*? if neoforge {*/
    @Inject(method = "serializeNBT(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"))
    private void mc_talking$serializeMemoryNBT(HolderLookup.Provider provider, CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        mc_talking$serializeNBT(tag);
    }

    @Inject(method = "deserializeNBT(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"))
    private void mc_talking$deserializeMemoryNBT(HolderLookup.Provider provider, CompoundTag nbtTagCompound, CallbackInfo ci) {
        mc_talking$deserializeNBT(nbtTagCompound);
    }
    /*?}*/

    /*? if forge {*/
    /*@Inject(method = "serializeNBT()Lnet/minecraft/nbt/CompoundTag;", at = @At("RETURN"))
    private void mc_talking$serializeMemoryNBT(CallbackInfoReturnable<CompoundTag> cir) {
        mc_talking$serializeNBT(cir.getReturnValue());
    }

    @Inject(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"))
    private void mc_talking$deserializeMemoryNBT(CompoundTag nbtTagCompound, CallbackInfo ci) {
        mc_talking$deserializeNBT(nbtTagCompound);
    }
    *//*?}*/

    @Unique
    private void mc_talking$serializeNBT(CompoundTag tag) {
        if (mc_talking$memory != null) {
            if (tag.contains(TAG_MEMORY_KEY)) {
                McTalking.LOGGER.error("Memory data conflict found for citizen {}, not overwriting.", CitizenData.class.cast(this).getUUID());
            } else {
                tag.put(TAG_MEMORY_KEY, mc_talking$memory.serializeNbt());
            }
        }
        // Recent actions
        if (!mc_talking$recentActions.isEmpty()) {
            ListTag actionsTag = new ListTag();
            for (String action : mc_talking$recentActions) {
                actionsTag.add(StringTag.valueOf(action));
            }
            tag.put(TAG_RECENT_ACTIONS_KEY, actionsTag);
        }

        // Personality
        if (mc_talking$personality != null) {
            tag.putString(TAG_PERSONALITY_KEY, mc_talking$personality.name());
        } else if (mc_talking$customPersonality != null) {
            tag.putString(TAG_PERSONALITY_KEY, "custom:" + mc_talking$customPersonality);
        }
    }

    @Unique
    private void mc_talking$deserializeNBT(CompoundTag nbtTagCompound) {
        if (nbtTagCompound.contains(TAG_MEMORY_KEY)) {
            mc_talking$memory = new CitizenMemories();
            mc_talking$memory.deserializeNbt(nbtTagCompound.getCompound(TAG_MEMORY_KEY));
        }
        if (nbtTagCompound.contains(TAG_RECENT_ACTIONS_KEY)) {
            mc_talking$recentActions.clear();
            ListTag actionsTag = nbtTagCompound.getList(TAG_RECENT_ACTIONS_KEY, Tag.TAG_STRING);
            int count = Math.min(actionsTag.size(), MAX_RECENT_ACTIONS);
            for (int i = 0; i < count; i++) {
                mc_talking$recentActions.addLast(actionsTag.getString(i));
            }
        }

        if (nbtTagCompound.contains(TAG_PERSONALITY_KEY)) {
            String raw = nbtTagCompound.getString(TAG_PERSONALITY_KEY);
            if (raw.startsWith("custom:")) {
                mc_talking$customPersonality = raw.substring(7);
                mc_talking$personality = null;
            } else {
                try {
                    mc_talking$personality = PersonalityArchetype.valueOf(raw);
                    mc_talking$customPersonality = null;
                } catch (IllegalArgumentException ignored) {
                    // Unknown enum value (e.g. old save) — will be re-assigned lazily
                }
            }
        }
    }

    @Override
    public CitizenMemories mc_talking$getMemory() {
        return mc_talking$memory;
    }

    @Override
    public CitizenMemories mc_talking$getOrInitializeMemory() {
        if (mc_talking$memory == null) {
            mc_talking$memory = new CitizenMemories();
        }
        return mc_talking$memory;
    }

    // -------------------------------------------------------------------------
    // CitizenDataPersonalityExtended
    // -------------------------------------------------------------------------

    @Override
    @Nullable
    public PersonalityArchetype mc_talking$getPersonality() {
        return mc_talking$personality;
    }

    @Override
    @Nullable
    public String mc_talking$getCustomPersonality() {
        return mc_talking$customPersonality;
    }

    @Override
    public void mc_talking$assignPersonality() {
        // Already assigned?
        if (mc_talking$personality != null || mc_talking$customPersonality != null) return;

        var config = McTalkingConfig.INSTANCE.instance();
        if (!config.enablePersonalityArchetypes) return;

        var pick = PersonalityPool.pick(config::isArchetypeEnabled, config.customPersonalityArchetypes,
                bound -> ThreadLocalRandom.current().nextInt(bound));
        if (pick == null) return;
        mc_talking$personality = pick.archetype();
        mc_talking$customPersonality = pick.custom();
    }
}
