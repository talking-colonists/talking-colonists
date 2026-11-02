package me.sshcrack.mc_talking.mixin;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.core.entity.ai.workers.service.EntityAIWorkHealer;
import com.minecolonies.core.entity.ai.workers.util.Patient;
import me.sshcrack.mc_talking.duck.CitizenRecentActionsProvider;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;

@Mixin(value = EntityAIWorkHealer.class, remap = false)
@SuppressWarnings("NullAway.Init") // @Shadow fields are injected by MineColonies.
public class EntityAIWorkHealerMixin {

    @Shadow
    private Patient currentPatient;

    @Shadow
    private Player playerToHeal;

    @Shadow
    private ICitizenData remotePatient;

    @Unique
    @Nullable
    private String mc_talking$treatmentType;

    @Unique
    @Nullable
    private String mc_talking$wanderPatientName;

    @Inject(method = "cure", at = @At("HEAD"))
    private void mc_talking$flagCure(CallbackInfoReturnable<IAIState> cir) {
        mc_talking$treatmentType = "medicine";
    }

    @Inject(method = "cure", at = @At("RETURN"))
    private void mc_talking$cleanupCure(CallbackInfoReturnable<IAIState> cir) {
        mc_talking$treatmentType = null;
    }

    @Inject(method = "freeCure", at = @At("HEAD"))
    private void mc_talking$flagFreeCure(CallbackInfoReturnable<IAIState> cir) {
        mc_talking$treatmentType = "magic";
    }

    @Inject(method = "freeCure", at = @At("RETURN"))
    private void mc_talking$cleanupFreeCure(CallbackInfoReturnable<IAIState> cir) {
        mc_talking$treatmentType = null;
    }

    @Inject(method = "wander", at = @At("HEAD"))
    private void mc_talking$captureWander(CallbackInfoReturnable<IAIState> cir) {
        mc_talking$wanderPatientName = remotePatient != null ? remotePatient.getName() : null;
    }

    @Inject(
        method = { "cure", "freeCure" },
        at = @At(
            value = "INVOKE",
            target = "Lcom/minecolonies/core/entity/ai/workers/service/EntityAIWorkHealer;recordTreatmentStats(Lcom/minecolonies/core/entity/citizen/EntityCitizen;)V"
        )
    )
    private void mc_talking$onCureComplete(CallbackInfoReturnable<IAIState> cir) {
        if (currentPatient == null) return;

        var accessor = (AbstractAISkeletonAccessor) this;
        var worker = accessor.getWorker();
        var citizenData = worker.getCitizenData();
        if (citizenData == null) return;

        IColony colony = worker.getCitizenColonyHandler().getColonyOrRegister();
        ICitizenData patientData = colony.getCitizenManager().getCivilian(currentPatient.getId());
        String patientName = patientData != null ? patientData.getName() : "a colonist";

        String entry;
        if ("magic".equals(mc_talking$treatmentType)) {
            entry = "Just magically cured " + patientName + ".";
        } else {
            entry = "Just treated " + patientName + " with medicine.";
        }

        ((CitizenRecentActionsProvider) citizenData)
            .mc_talking$pushRecentAction(entry);

        mc_talking$treatmentType = null;
    }

    @Inject(method = "curePlayer", at = @At("RETURN"))
    private void mc_talking$onCurePlayer(CallbackInfoReturnable<IAIState> cir) {
        if (playerToHeal == null) return;
        if (cir.getReturnValue() != AIWorkerState.DECIDE) return;

        var accessor = (AbstractAISkeletonAccessor) this;
        var worker = accessor.getWorker();
        var citizenData = worker.getCitizenData();
        if (citizenData == null) return;

        String playerName = playerToHeal.getScoreboardName();
        String entry = "Just healed " + playerName + ".";

        ((CitizenRecentActionsProvider) citizenData)
            .mc_talking$pushRecentAction(entry);
    }

    @Inject(method = "wander", at = @At("RETURN"))
    private void mc_talking$onWander(CallbackInfoReturnable<IAIState> cir) {
        if (mc_talking$wanderPatientName == null) return;
        if (cir.getReturnValue() != AIWorkerState.START_WORKING) return;

        var accessor = (AbstractAISkeletonAccessor) this;
        var worker = accessor.getWorker();
        var citizenData = worker.getCitizenData();
        if (citizenData == null) return;

        String entry = "Just provided medical aid to " + mc_talking$wanderPatientName + ".";

        ((CitizenRecentActionsProvider) citizenData)
            .mc_talking$pushRecentAction(entry);

        mc_talking$wanderPatientName = null;
    }
}
