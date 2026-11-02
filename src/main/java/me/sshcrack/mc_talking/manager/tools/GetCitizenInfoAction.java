package me.sshcrack.mc_talking.manager.tools;

import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.gemini_live_lib.gson.properties.ObjectProperty;
import me.sshcrack.gemini_live_lib.gson.properties.PrimitiveProperty;
import me.sshcrack.mc_talking.internal.prompt.PromptRuntime;
import me.sshcrack.mc_talking.manager.CitizenPromptViewFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

public class GetCitizenInfoAction extends GeneralFunctionAction {
    public GetCitizenInfoAction() {
        super(
                "get_citizen_info",
                "Get information about a specific citizen by their name. This can be something like outer appearance, their skills, their current saturation, job, family, work places, quests and more.",
                new ObjectProperty(new HashMap<>() {{
                    put("citizen_name", new PrimitiveProperty(PrimitiveProperty.Type.STRING, true));
                }})
        );
    }

    @Override
    public @NotNull JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        if (parameters == null || !parameters.has("citizen_name")) {
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("success", false);
            errorResponse.addProperty("error", "Missing or invalid parameters.");
            return errorResponse;
        }

        var name = parameters.get("citizen_name").getAsString();

        var foundOpt = colony
                .getCitizenManager()
                .getCitizens()
                .stream().filter(e -> e.getName().equals(name))
                .findFirst();

        if (foundOpt.isEmpty()) {
            var obj = new JsonObject();
            obj.addProperty("error", "Citizen not found.");

            return obj;
        }

        var found = foundOpt.get();
        var view = CitizenPromptViewFactory.create(found, new HashMap<>(), null);
        var generalPrompt = PromptRuntime.getDetailedCitizenInfoPrompt(view);

        var obj = new JsonObject();
        obj.addProperty("info", generalPrompt);

        return obj;
    }
}
