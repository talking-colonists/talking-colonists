package me.sshcrack.mc_talking.manager.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
/*? if forge {*/
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
*//*?}*/
/*? if neoforge {*/
import net.minecraft.core.component.DataComponents;
/*?}*/
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Collectors;

public class GetInventoryAction extends GeneralFunctionAction {
    public GetInventoryAction() {
        super("get_inventory", "Lists the current items in your inventory.");
    }

    @Override
    public @NotNull JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        var obj = new JsonObject();

        var inv = citizen.getInventoryCitizen();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) {
                continue;
            }

            JsonObject itemObj = new JsonObject();
            itemObj.addProperty("slot", i);
            /*? if forge {*/
            /*ResourceLocation registryName = ForgeRegistries.ITEMS.getKey(stack.getItem());
            itemObj.addProperty("registry_id", registryName != null ? registryName.toString() : "unknown");
            *//*?}*/
            /*? if neoforge {*/
            itemObj.addProperty("registry_id", stack.getItemHolder().getRegisteredName());
            /*?}*/
            itemObj.addProperty("name", stack.getDisplayName().getString());
            itemObj.addProperty("count", stack.getCount());
            itemObj.addProperty("max_count", stack.getMaxStackSize());

            /*? if forge {*/
            /*CompoundTag tag = stack.getTag();
            if (tag != null && tag.contains("display", 10)) {
                CompoundTag displayTag = tag.getCompound("display");
                if (displayTag.contains("Lore", 9)) {
                    ListTag loreTag = displayTag.getList("Lore", 8);
                    StringBuilder lore = new StringBuilder();
                    for (int j = 0; j < loreTag.size(); j++) {
                        if (j > 0) lore.append("\n");
                        lore.append(loreTag.getString(j));
                    }
                    itemObj.addProperty("lore", lore.toString());
                }
            }
            *//*?}*/
            /*? if neoforge {*/
            var lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                itemObj.addProperty("lore", lore
                        .lines()
                        .stream()
                        .map(Component::getString)
                        .collect(Collectors.joining("\n"))
                );
            }
            /*?}*/

            obj.add("item_" + i, itemObj);
        }
        return obj;
    }
}
