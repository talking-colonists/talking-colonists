package me.sshcrack.mc_talking.manager.tools;

import me.sshcrack.mc_talking.api.memory.MemoryProvenance;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.duck.CitizenDataMemoryExtended;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class AddEventToMemory extends GeneralFunctionAction {
    public AddEventToMemory() {
        super("add_event_to_memory", """
                        Adds a message to your memory, so you remember it in ongoing conversations.
                        Use for important events only and be concise.
                        """,
                new ObjectProperty(new HashMap<>() {{
                    put("event", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                }}));
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && !McTalkingConfig.INSTANCE.instance().enableConversationSummaryAndMemorize;
    }

    @NotNull
    @Override
    public JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        var obj = new JsonObject();
        if (parameters == null || !parameters.has("event")) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Missing or invalid parameters.");
            return obj;
        }

        var event = parameters.get("event").getAsString();
        var citizenData = citizen.getCitizenData();
        if (citizenData == null) {
            obj.addProperty("success", false);
            obj.addProperty("error", "Citizen data not available.");
            return obj;
        }
        var memory = ((CitizenDataMemoryExtended) citizenData).mc_talking$getOrInitializeMemory();

        memory.addEvent(event, MemoryProvenance.CITIZEN_STATEMENT, citizen.getUUID(), null, null);
        obj.addProperty("success", true);

        return obj;
    }
}
