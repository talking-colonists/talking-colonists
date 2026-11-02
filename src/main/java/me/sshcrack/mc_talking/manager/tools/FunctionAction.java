package me.sshcrack.mc_talking.manager.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.gson.properties.Property;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class FunctionAction {
    private final String name;
    private final String description;
    private final @Nullable Property property;
    private final boolean isPlayerOnly;

    public FunctionAction(String name, String description, boolean isPlayerOnly) {
        this(name, description, null, isPlayerOnly);
    }

    public FunctionAction(String name, String description, @Nullable Property property, boolean isPlayerOnly) {
        this.name = name;
        this.description = description;
        this.property = property;
        this.isPlayerOnly = isPlayerOnly;
    }

    @Nullable
    public Property getProperty() {
        return property;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isPlayerOnly() {
        return isPlayerOnly;
    }

    public boolean isEnabled() {
        return true;
    }

    @NotNull
    public abstract JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters);
}
