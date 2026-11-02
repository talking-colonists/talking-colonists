package me.sshcrack.mc_talking.conversations.memory.gson;

import com.google.gson.*;
import me.sshcrack.mc_talking.api.memory.CitizenRelationshipDimension;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("NullAway.Init") // Gson fills these fields by reflection; absent keys stay null.
public class GsonMemoryResponse {
    public static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(CitizenRelationshipDimension.class, new SafeEnumDeserializer())
            .create();

    public List<GsonCitizenMemory> citizens = new ArrayList<>();

    public static class GsonCitizenMemory {
        public String name;
        public GsonMemoryData memories = new GsonMemoryData();
    }

    public static class GsonMemoryData {
        public List<GsonRelationshipMemory> relationships = new ArrayList<>();
        public List<String> facts = new ArrayList<>();
        public List<String> events = new ArrayList<>();
    }

    public static class GsonRelationshipMemory {
        public String target;
        @Nullable
        public CitizenRelationshipDimension type;
        public float change;
    }

    // Optional: Safe enum deserializer (prevents crashes on bad LLM output)
    public static class SafeEnumDeserializer implements JsonDeserializer<CitizenRelationshipDimension> {
        @Override
        @Nullable
        public CitizenRelationshipDimension deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            try {
                return CitizenRelationshipDimension.valueOf(json.getAsString());
            } catch (Exception e) {
                return null; // or default like TRUST
            }
        }
    }
}
