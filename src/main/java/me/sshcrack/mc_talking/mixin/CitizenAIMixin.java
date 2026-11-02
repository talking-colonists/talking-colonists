package me.sshcrack.mc_talking.mixin;

import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CitizenAI.class, remap = false)
@SuppressWarnings("NullAway.Init")
public class CitizenAIMixin {

    @Shadow
    @Final
    private EntityCitizen citizen;

    @Inject(method = "calculateNextState", at = @At("HEAD"), cancellable = true)
    private void mc_talking$suppressWorkDuringUrgentContact(CallbackInfoReturnable<IState> cir) {
        if (McTalkingConfig.INSTANCE.instance().continueWorkDuringConversation) return;
        if (ConversationManager.shouldPauseRoutine(citizen)) {
            cir.setReturnValue(CitizenAIState.IDLE);
        }
    }
}
