package me.sshcrack.mc_talking.mixin;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.core.network.messages.server.colony.UpdateRequestStateMessage;
import me.sshcrack.mc_talking.support.PlayerFulfillmentHandler;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/*? if neoforge {*/
import net.neoforged.neoforge.network.handling.IPayloadContext;
/*?}*/
/*? if forge {*/
/*import net.minecraftforge.network.NetworkEvent;
*//*?}*/

@Mixin(value = UpdateRequestStateMessage.class, remap = false)
@SuppressWarnings("NullAway.Init") // @Shadow fields are injected by MineColonies.
public class UpdateRequestStateMixin {

    @Shadow
    @Final
    private RequestState state;

    /*? if neoforge {*/
    @Inject(method = "onExecute", at = @At("HEAD"))
    private void mc_talking$onExecuteHead(IPayloadContext ctxIn, ServerPlayer player, IColony colony, CallbackInfo ci) {
        if (state == RequestState.OVERRULED) {
            PlayerFulfillmentHandler.setPendingFulfiller(player);
        }
    }

    @Inject(method = "onExecute", at = @At("RETURN"))
    private void mc_talking$onExecuteReturn(IPayloadContext ctxIn, ServerPlayer player, IColony colony, CallbackInfo ci) {
        if (state == RequestState.OVERRULED) {
            PlayerFulfillmentHandler.tryClearPending();
        }
    }
    /*?}*/

    /*? if forge {*/
    /*@Inject(method = "onExecute", at = @At("HEAD"))
    private void mc_talking$onExecuteHead(NetworkEvent.Context ctx, boolean isLogicalServer, IColony colony, CallbackInfo ci) {
        if (state == RequestState.OVERRULED) {
            PlayerFulfillmentHandler.setPendingFulfiller(ctx.getSender());
        }
    }

    @Inject(method = "onExecute", at = @At("RETURN"))
    private void mc_talking$onExecuteReturn(NetworkEvent.Context ctx, boolean isLogicalServer, IColony colony, CallbackInfo ci) {
        if (state == RequestState.OVERRULED) {
            PlayerFulfillmentHandler.tryClearPending();
        }
    }
    *//*?}*/
}
