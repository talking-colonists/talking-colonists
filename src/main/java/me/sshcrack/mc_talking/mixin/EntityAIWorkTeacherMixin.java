package me.sshcrack.mc_talking.mixin;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.ai.workers.education.EntityAIWorkTeacher;
import me.sshcrack.mc_talking.duck.CitizenRecentActionsProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.minecolonies.api.entity.ai.statemachine.states.IAIState;

@Mixin(value = EntityAIWorkTeacher.class, remap = false)
@SuppressWarnings("NullAway.Init") // @Shadow fields are injected by MineColonies.
public class EntityAIWorkTeacherMixin {

    @Shadow
    private AbstractEntityCitizen pupilToTeach;

    @Inject(method = "teach", at = @At("RETURN"))
    private void mc_talking$onTeach(CallbackInfoReturnable<IAIState> cir) {
        if (pupilToTeach == null) return;

        var accessor = (AbstractAISkeletonAccessor) this;
        var worker = accessor.getWorker();
        var citizenData = worker.getCitizenData();
        if (citizenData == null) return;

        var pupilData = pupilToTeach.getCitizenData();
        if (pupilData == null) return;

        String pupilName = pupilData.getName();
        String entry = "Just finished teaching " + pupilName + ".";

        ((CitizenRecentActionsProvider) citizenData)
            .mc_talking$pushRecentAction(entry);
    }
}
