package me.sshcrack.mc_talking.config;

import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.network.chat.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Named sets of values for the config entries that decide how often citizens talk and how
 * many Gemini sessions run at once. Choosing a preset writes all of {@link #settings()};
 * editing one of those entries afterwards turns the preset into {@link #CUSTOM}.
 *
 * <p>Every preset sets the same keys, so switching between presets fully decides them.
 * {@link #FREE_TIER} equals the field defaults in {@link McTalkingConfig}. The keys are
 * listed in {@code docs/config-presets.md}.
 */
public enum ConfigPreset implements NameableEnum {
    /** The values were edited by hand; nothing is written. */
    CUSTOM,
    /** The defaults: fits a free AI Studio key (3 concurrent Live sessions, about 10 TTS requests a day). */
    FREE_TIER,
    /** More concurrent sessions and more ambient chatter, for a paid key. */
    PAID_KEY,
    /** Citizens only speak when a player talks to them: no ambient chatter, no urgent contact. */
    QUIET_COLONY;

    private static final Map<ConfigPreset, Map<String, Object>> SETTINGS = new LinkedHashMap<>();

    static {
        Map<String, Object> free = new LinkedHashMap<>();
        free.put("maxConcurrentAgents", 2);
        free.put("maxConcurrentBackground", 1);
        free.put("conversationMode", ConversationMode.AUTO);
        free.put("enableConversationSummaryAndMemorize", false);
        free.put("enableCitizenToCitizenConversation", true);
        free.put("enableRandomConversations", true);
        free.put("randomConversationChance", 0.08);
        free.put("randomConversationCheckIntervalTicks", 400);
        free.put("mumblingChance", 0.08);
        free.put("mumblingCheckIntervalTicks", 200);
        free.put("enablePregeneration", true);
        free.put("enablePlayerGreetingPregen", true);
        free.put("enableCitizenInitiatedContact", true);
        free.put("enableRumorTalking", true);
        free.put("rumorTalkingChance", 0.5);
        free.put("enableBroadcastYelling", true);
        free.put("ambientSpeechBudgetMaxLines", 3);
        free.put("citizenCooldownSeconds", 120);

        Map<String, Object> paid = new LinkedHashMap<>(free);
        paid.put("maxConcurrentAgents", 6);
        paid.put("maxConcurrentBackground", 3);
        paid.put("enableConversationSummaryAndMemorize", true);
        paid.put("randomConversationChance", 0.1);
        paid.put("randomConversationCheckIntervalTicks", 300);
        paid.put("mumblingChance", 0.12);
        paid.put("mumblingCheckIntervalTicks", 160);
        paid.put("rumorTalkingChance", 0.7);
        paid.put("ambientSpeechBudgetMaxLines", 5);
        paid.put("citizenCooldownSeconds", 60);

        Map<String, Object> quiet = new LinkedHashMap<>(free);
        quiet.put("enableCitizenToCitizenConversation", false);
        quiet.put("enableRandomConversations", false);
        quiet.put("mumblingChance", 0.0);
        quiet.put("enablePregeneration", false);
        quiet.put("enablePlayerGreetingPregen", false);
        quiet.put("enableCitizenInitiatedContact", false);
        quiet.put("enableRumorTalking", false);
        quiet.put("enableBroadcastYelling", false);

        SETTINGS.put(FREE_TIER, Collections.unmodifiableMap(free));
        SETTINGS.put(PAID_KEY, Collections.unmodifiableMap(paid));
        SETTINGS.put(QUIET_COLONY, Collections.unmodifiableMap(quiet));
    }

    /** Config field name to value, in a stable order. Empty for {@link #CUSTOM}. */
    public Map<String, Object> settings() {
        return SETTINGS.getOrDefault(this, Map.of());
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mc_talking.config_preset." + name().toLowerCase(Locale.ROOT));
    }
}
