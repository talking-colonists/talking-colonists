package me.sshcrack.mc_talking.mixin;

import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.jobs.AbstractJobCrafter;
import me.sshcrack.mc_talking.duck.CitizenRecentActionsProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.jetbrains.annotations.Nullable;

import java.util.LinkedList;

@Mixin(value = AbstractJobCrafter.class, remap = false)
public abstract class AbstractJobCrafterMixin {

    @Shadow
    protected abstract LinkedList<IToken<?>> getTaskQueueFromDataStore();

    @Unique
    @Nullable
    private String mc_talking$requestedItemName;

    @Inject(method = "finishRequest", at = @At("HEAD"))
    private void mc_talking$captureRequest(boolean successful, CallbackInfo ci) {
        if (getTaskQueueFromDataStore().isEmpty()) {
            mc_talking$requestedItemName = null;
            return;
        }

        var self = AbstractJobCrafter.class.cast(this);
        var token = getTaskQueueFromDataStore().getFirst();
        var request = self.getColony().getRequestManager().getRequestForToken(token);
        if (request == null) {
            mc_talking$requestedItemName = null;
            return;
        }

        var requestable = request.getRequest();
        if (requestable instanceof Stack stack) {
            mc_talking$requestedItemName = stack.getStack().getHoverName().getString();
        } else {
            mc_talking$requestedItemName = null;
        }
    }

    @Inject(method = "finishRequest", at = @At("RETURN"))
    private void mc_talking$onFinishRequest(boolean successful, CallbackInfo ci) {
        if (mc_talking$requestedItemName == null) return;

        var self = AbstractJobCrafter.class.cast(this);
        var citizenData = self.getCitizen();
        if (citizenData == null) return;

        String itemName = mc_talking$requestedItemName;
        String entry = successful
            ? "Just finished crafting " + itemName + "."
            : "Failed to craft " + itemName + ".";

        ((CitizenRecentActionsProvider) citizenData)
            .mc_talking$pushRecentAction(entry);

        mc_talking$requestedItemName = null;
    }
}
