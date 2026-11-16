package me.sshcrack.mc_talking.manager.tools;

import me.sshcrack.mc_talking.api.memory.MemoryProvenance;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.ICivilianData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.gson.properties.EnumProperty;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.api.memory.CitizenRelationshipDimension;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;

public class RecordRelationshipChange extends GeneralFunctionAction {
    public RecordRelationshipChange() {
        super("record_relationship_change", """
                        Updates your emotional feelings and relationship in your memory targeting a citizen or the player you are talking to. Leave the citizen name, if you want to update the memory to the player (or manager)
                        so you can remember it later.
                        The change must be between -1.0 and 1.0.
                        """,
                new ObjectProperty(new HashMap<>() {{
                    put("citizen_name", new PrimitiveProperty(PrimitiveProperty.Type.STRING, false));
                    put("change", new PrimitiveProperty(PrimitiveProperty.Type.NUMBER, true));
                    put("type", new EnumProperty(Arrays.stream(CitizenRelationshipDimension.values())
                            .map(Enum::name)
                            .toList(),
                            true
                    ));
                }}));
    }

    @NotNull
    @Override
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        var obj = new JsonObject();
        if (parameters == null) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Missing parameters.");
            return obj;
        }

        @Nullable
        var citizenName = parameters.has("citizen_name") ? parameters.get("citizen_name").getAsString() : null;
        var change = parameters.get("change").getAsFloat();
        var typeStr = parameters.get("type").getAsString();

        var typeOpt = Arrays.stream(CitizenRelationshipDimension.values())
                .filter(t -> t.name().equalsIgnoreCase(typeStr))
                .findFirst();

        if (typeOpt.isEmpty()) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Invalid relationship change type.");
            return obj;
        }

        var type = typeOpt.get();

        if (change < -1.0 || change > 1.0) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Change must be between -1.0 and 1.0.");
            return obj;
        }

        var targetUUID = colony.getCitizenManager().getCitizens().stream()
                .filter(c -> c.getName().equals(citizenName))
                .findFirst()
                .map(ICivilianData::getUUID)
                .orElseGet(() -> ConversationManager.getPlayerForEntity(citizen.getUUID()));
        if (targetUUID == null) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Target citizen and player not found.");
            return obj;
        }


        var citizenData = citizen.getCitizenData();
        if (citizenData == null) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Citizen data not available.");
            return obj;
        }
        var memory = ((CitizenDataMemoryExtended) citizenData).mc_talking$getOrInitializeMemory();

        McTalking.LOGGER.info("Recording relationship change for citizen {}: target={}, type={}, change={}", citizen.getName(), targetUUID, type, change);
        memory.addRelationshipChange(targetUUID, type, change,
                MemoryProvenance.CITIZEN_STATEMENT, citizen.getUUID(), null, null);
        obj.addProperty("success", true);
        return obj;
    }
}
