package me.sshcrack.mc_talking.mixin;

import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.core.entity.ai.minimal.EntityAIEatTask;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import me.sshcrack.mc_talking.duck.CitizenRecentActionsProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityAIEatTask.class, remap = false)
@SuppressWarnings("NullAway.Init") // @Shadow fields are injected by Minecraft.
public class EntityAIEatTaskMixin {

    @Shadow
    @Final
    private EntityCitizen citizen;

    @Inject(
        method = "eat",
        at = @At(
            value = "INVOKE",
            target = "Lcom/minecolonies/api/colony/ICitizenData;setJustAte(Z)V"
        )
    )
    private void mc_talking$onEatComplete(CallbackInfoReturnable<IState> cir) {
        var data = citizen.getCitizenData();
        if (data == null) return;
        ((CitizenRecentActionsProvider) data).mc_talking$pushRecentAction("Finished eating and returned to full saturation.");
    }
}
