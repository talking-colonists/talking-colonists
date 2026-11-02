package me.sshcrack.mc_talking.mixin;

import com.minecolonies.api.colony.workorders.IWorkOrder;
import com.minecolonies.api.colony.workorders.WorkOrderType;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import com.minecolonies.core.colony.buildings.AbstractBuildingStructureBuilder;
import com.minecolonies.core.colony.jobs.JobBuilder;
import com.minecolonies.core.colony.jobs.JobMiner;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIStructure;
import me.sshcrack.mc_talking.duck.CitizenRecentActionsProvider;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractEntityAIStructure.class, remap = false)
public abstract class AbstractEntityAIStructureMixin {

    @Unique
    @Nullable
    private IWorkOrder mc_talking$workOrder;

    @Unique
    private int mc_talking$buildDepth;

    @Inject(method = "completeBuild", at = @At("HEAD"))
    private void mc_talking$captureWorkOrder(CallbackInfoReturnable<IAIState> cir) {
        mc_talking$workOrder = null;

        AbstractBuilding building = ((AbstractEntityAIInteractAccessor) this).getBuilding();
        if (building instanceof AbstractBuildingStructureBuilder builder) {
            mc_talking$workOrder = builder.getWorkOrder();
            mc_talking$buildDepth = mc_talking$workOrder != null
                ? mc_talking$workOrder.getLocation().getY()
                : 0;
        }
    }

    @Inject(method = "completeBuild", at = @At("RETURN"))
    private void mc_talking$onCompleteBuild(CallbackInfoReturnable<IAIState> cir) {
        if (mc_talking$workOrder == null) return;

        var accessor = (AbstractAISkeletonAccessor) this;
        var worker = accessor.getWorker();
        var citizenData = worker.getCitizenData();
        if (citizenData == null) return;

        var wo = mc_talking$workOrder;
        String buildingName = Component.translatable(wo.getTranslationKey()).getString();
        WorkOrderType type = wo.getWorkOrderType();
        var job = citizenData.getJob();

        String entry;
        if (job instanceof JobBuilder) {
            entry = switch (type) {
                case BUILD -> "Just finished building " + buildingName + ".";
                case UPGRADE -> "Just upgraded " + buildingName + " to level " + wo.getTargetLevel() + ".";
                case REPAIR -> "Just finished repairing " + buildingName + ".";
                case REMOVE -> "Just deconstructed " + buildingName + ".";
            };
        } else if (job instanceof JobMiner) {
            entry = "Just finished a mining shaft at depth " + mc_talking$buildDepth + ".";
        } else {
            entry = "Just completed work at " + buildingName + ".";
        }

        ((CitizenRecentActionsProvider) citizenData)
            .mc_talking$pushRecentAction(entry);

        mc_talking$workOrder = null;
    }
}
