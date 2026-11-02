package me.sshcrack.mc_talking.mixin;

import com.minecolonies.core.entity.ai.minimal.EntityAICitizenWander;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityAICitizenWander.class, remap = false)
@SuppressWarnings("NullAway.Init")
public class EntityAICitizenWanderMixin {

    @Shadow
    @Final
    protected EntityCitizen citizen;

    @Inject(method = "canUse", at = @At("HEAD"), cancellable = true)
    private void mc_talking$suppressWanderingDuringConversation(CallbackInfoReturnable<Boolean> cir) {
        if (McTalkingConfig.INSTANCE.instance().continueWorkDuringConversation) return;
        if (ConversationManager.shouldPauseRoutine(citizen)) {
            cir.setReturnValue(false);
        }
    }
}
