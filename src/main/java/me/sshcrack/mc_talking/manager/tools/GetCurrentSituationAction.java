package me.sshcrack.mc_talking.manager.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import me.sshcrack.mc_talking.api.prompt.view.CitizenActivityCategory;
import me.sshcrack.mc_talking.manager.CitizenPromptViewFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;

public class GetCurrentSituationAction extends GeneralFunctionAction {

    public GetCurrentSituationAction() {
        super(
                "get_current_situation",
                "Refreshes your knowledge of your own current situation — what you are doing right now, "
                + "what items you are waiting for, and your current status. "
                + "Call this when you suspect something may have changed since the start of the conversation "
                + "(e.g. the player tells you your supplies have arrived, or asks why you are not working)."
        );
    }

    @Override
    public @NotNull JsonObject execute(AbstractEntityCitizen citizen, IColony colony, @Nullable JsonObject parameters) {
        var data = citizen.getCitizenData();
        if (data == null) {
            var err = new JsonObject();
            err.addProperty("error", "Citizen data not available.");
            return err;
        }

        var view = CitizenPromptViewFactory.create(data, new HashMap<>(), null);
        var result = new JsonObject();

        String activity = view.activity().description();
        if (activity != null) result.addProperty("current_activity", activity);

        result.addProperty("work_status", data.getJobStatus().name());

        var verified = view.verifiedFacts();
        result.addProperty("fact_snapshot_game_time", verified.capturedAtGameTime());
        result.addProperty("housing_status", verified.housingStatus().name());
        result.addProperty("builder_activity", verified.builderActivity().name());

        result.addProperty("request_observation_state", verified.requests().state().name());
        if (verified.requests().value() != null) {
            if (!verified.requests().value().waitingForResolver().isEmpty()) {
                result.add("requests_waiting_for_resolver",
                        toJsonArray(verified.requests().value().waitingForResolver()));
            }
            if (!verified.requests().value().assignedOrInProgress().isEmpty()) {
                result.add("requests_assigned_or_in_progress",
                        toJsonArray(verified.requests().value().assignedOrInProgress()));
            }
        }

        result.addProperty("saturation_level_0_to_20", view.wellbeing().saturation());
        result.addProperty("health_observation_state", verified.healthPercent().state().name());
        if (verified.healthPercent().value() != null) {
            result.addProperty("health_percent", verified.healthPercent().value());
        }

        result.addProperty("equipment_observation_state", verified.equipment().state().name());
        if (verified.equipment().value() != null) {
            result.add("worn_armor", toJsonArray(verified.equipment().value().wornArmor()));
            result.add("carried_items", toJsonArray(verified.equipment().value().carriedItems()));
        }

        if (view.work().jobName() != null) result.addProperty("job_name", view.work().jobName());
        if (!view.activity().recentActions().isEmpty()) {
            result.add("recent_actions", toJsonArray(view.activity().recentActions()));
        }

        return result;
    }

    private static JsonArray toJsonArray(List<String> items) {
        var arr = new JsonArray();
        for (String item : items) arr.add(item);
        return arr;
    }
}
