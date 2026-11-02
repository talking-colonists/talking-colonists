package me.sshcrack.mc_talking.manager.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class DropItemAction extends PlayerFunctionAction {

    public DropItemAction() {
        super("drop_item", "Drops an item with the given count at the specified slot in your inventory. Use -1 to drop the entire stack (maximum count).",
                new ObjectProperty(new HashMap<>() {{
                    put("slot_index", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, true));
                    put("count", new PrimitiveProperty(PrimitiveProperty.Type.INTEGER, true));
                }})
        );
    }

    @Override
    public @NotNull JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        var obj = new JsonObject();
        if (parameters == null || !parameters.has("slot_index") || !parameters.has("count")) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Missing or invalid parameters.");
            return obj;
        }

        var slotIndex = parameters.get("slot_index").getAsInt();
        var rCount = parameters.get("count").getAsInt();

        if (slotIndex < 0 || slotIndex >= citizen.getInventoryCitizen().getSlots()) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Invalid slot index.");
            return obj;
        }

        ItemStack stack = citizen.getInventoryCitizen().getStackInSlot(slotIndex);
        if (stack.isEmpty()) {
            obj.addProperty("success", false);
            obj.addProperty("error", "No item in the specified slot.");
            return obj;
        }

        var count = rCount < 0 ? stack.getCount() : rCount; // If count is negative, drop the entire stack (max 64)
        if (count == 0 || count > stack.getCount()) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Invalid count. Must be between 1 and " + stack.getCount() + " or negative to drop the entire stack.");
            return obj;
        }

        // Create a new stack with the specified count
        ItemStack droppedStack = stack.copy();
        var level = citizen.level();

        droppedStack.setCount(count);
        // Remove the specified count from the original stack
        stack.shrink(count);


        double d0 = citizen.getEyeY() - 0.3F;
        ItemEntity itemEntity = new ItemEntity(level, citizen.getX(), d0, citizen.getZ(), droppedStack);
        itemEntity.setPickUpDelay(40);

        float sinRotX = Mth.sin(citizen.getXRot() * (float) (Math.PI / 180.0));
        float cosRotX = Mth.cos(citizen.getXRot() * (float) (Math.PI / 180.0));
        float sinRotY = Mth.sin(citizen.getYRot() * (float) (Math.PI / 180.0));
        float cosRotY = Mth.cos(citizen.getYRot() * (float) (Math.PI / 180.0));
        float random1 = citizen.getRandom().nextFloat() * (float) (Math.PI * 2);
        float random2 = 0.02F * citizen.getRandom().nextFloat();
        itemEntity.setDeltaMovement(
                (double) (-sinRotY * cosRotX * 0.3F) + Math.cos(random1) * (double) random2,
                -sinRotX * 0.3F + 0.1F + (citizen.getRandom().nextFloat() - citizen.getRandom().nextFloat()) * 0.1F,
                (double) (cosRotY * cosRotX * 0.3F) + Math.sin(random1) * (double) random2
        );

        var res = level.addFreshEntity(itemEntity);
        if (!res) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Failed to drop the item.");
            return obj;
        }


        obj.addProperty("success", true);
        return obj;
    }
}
