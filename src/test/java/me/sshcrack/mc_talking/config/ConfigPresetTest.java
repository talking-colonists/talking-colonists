package me.sshcrack.mc_talking.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPresetTest {
    /** Has every preset key; field defaults match Free Tier like {@link McTalkingConfig}. */
    public static final class FakeConfig {
        public ConfigPreset configPreset = ConfigPreset.FREE_TIER;
        public ConfigPreset appliedConfigPreset = ConfigPreset.FREE_TIER;
        public int maxConcurrentAgents = 2;
        public int maxConcurrentBackground = 1;
        public ConversationMode conversationMode = ConversationMode.AUTO;
        public boolean enableConversationSummaryAndMemorize = false;
        public boolean enableCitizenToCitizenConversation = true;
        public boolean enableRandomConversations = true;
        public double randomConversationChance = 0.08;
        public int randomConversationCheckIntervalTicks = 400;
        public double mumblingChance = 0.08;
        public int mumblingCheckIntervalTicks = 200;
        public boolean enablePregeneration = true;
        public boolean enablePlayerGreetingPregen = true;
        public boolean enableCitizenInitiatedContact = true;
        public boolean enableRumorTalking = true;
        public double rumorTalkingChance = 0.5;
        public boolean enableBroadcastYelling = true;
        public int ambientSpeechBudgetMaxLines = 3;
        public int citizenCooldownSeconds = 120;
        public int unrelated = 7;
    }

    @Test
    void everyPresetSetsTheSameKeys() {
        var keys = ConfigPreset.FREE_TIER.settings().keySet();
        assertEquals(keys, ConfigPreset.PAID_KEY.settings().keySet());
        assertEquals(keys, ConfigPreset.QUIET_COLONY.settings().keySet());
        assertTrue(ConfigPreset.CUSTOM.settings().isEmpty());
    }

    @Test
    void presetValuesHaveTheConfigFieldTypes() throws Exception {
        // Not initialised: McTalkingConfig's static handler needs a running game.
        Class<?> config = Class.forName("me.sshcrack.mc_talking.config.McTalkingConfig", false,
                getClass().getClassLoader());
        for (ConfigPreset preset : ConfigPreset.values()) {
            for (Map.Entry<String, Object> entry : preset.settings().entrySet()) {
                Field field = config.getField(entry.getKey());
                assertEquals(box(field.getType()), entry.getValue().getClass(), preset + "." + entry.getKey());
            }
        }
        assertEquals(ConfigPreset.class, config.getField(ConfigPresets.PRESET_FIELD).getType());
        assertEquals(ConfigPreset.class, config.getField(ConfigPresets.APPLIED_FIELD).getType());
    }

    /**
     * The real {@code McTalkingConfig} field initialisers, not {@link FakeConfig}, which is a
     * hand-written copy that can drift from the real defaults unnoticed. The unit build cannot
     * instantiate {@code McTalkingConfig} (its static handler needs a running game), so the
     * initialisers are read from the source. A changed default previously shipped with a stale
     * Free Tier preset and only failed in the server GameTest job.
     */
    @Test
    void realConfigDefaultsAreFreeTier() throws IOException {
        Map<String, String> declared = new HashMap<>();
        for (String line : Files.readAllLines(repositoryRoot().resolve(
                "src/main/java/me/sshcrack/mc_talking/config/McTalkingConfig.java"))) {
            Matcher m = Pattern.compile("^\\s*public\\s+\\w+(?:\\s*<[^>]*>)?\\s+(\\w+)\\s*=\\s*(.+);$")
                    .matcher(line);
            if (m.matches()) {
                // Enum defaults are written qualified in the source (ConversationMode.AUTO) but held
                // as the bare enum constant by the preset. Only a trailing identifier qualifies it:
                // a numeric literal's dot is its decimal point.
                String value = m.group(2).trim();
                Matcher qualified = Pattern.compile("^[A-Za-z_]\\w*\\.([A-Za-z_]\\w*)$").matcher(value);
                declared.put(m.group(1), qualified.matches() ? qualified.group(1) : value);
            }
        }
        for (Map.Entry<String, Object> entry : ConfigPreset.FREE_TIER.settings().entrySet()) {
            assertEquals(String.valueOf(entry.getValue()), declared.get(entry.getKey()),
                    "McTalkingConfig." + entry.getKey() + " default differs from the Free Tier preset");
        }
    }

    @Test
    void defaultsAreFreeTier() {
        FakeConfig config = new FakeConfig();
        assertTrue(ConfigPresets.matches(config, ConfigPreset.FREE_TIER));
        assertEquals(ConfigPreset.FREE_TIER, ConfigPresets.detect(config));
        assertFalse(ConfigPresets.reconcile(config));
    }

    @Test
    void choosingAPresetWritesItsValues() {
        FakeConfig config = new FakeConfig();
        config.configPreset = ConfigPreset.QUIET_COLONY;

        assertTrue(ConfigPresets.reconcile(config));

        assertEquals(ConfigPreset.QUIET_COLONY, config.appliedConfigPreset);
        assertFalse(config.enableCitizenToCitizenConversation);
        assertEquals(0.0, config.mumblingChance);
        assertEquals(7, config.unrelated);
        assertFalse(ConfigPresets.reconcile(config));
    }

    @Test
    void editingAPresetValueMakesItCustom() {
        FakeConfig config = new FakeConfig();
        config.maxConcurrentAgents = 4;

        assertTrue(ConfigPresets.reconcile(config));

        assertEquals(ConfigPreset.CUSTOM, config.configPreset);
        assertEquals(ConfigPreset.CUSTOM, config.appliedConfigPreset);
        assertEquals(4, config.maxConcurrentAgents);
    }

    @Test
    void editingAnUnrelatedValueKeepsThePreset() {
        FakeConfig config = new FakeConfig();
        config.unrelated = 1;

        assertFalse(ConfigPresets.reconcile(config));
        assertEquals(ConfigPreset.FREE_TIER, config.configPreset);
    }

    @Test
    void choosingCustomWritesNothing() {
        FakeConfig config = new FakeConfig();
        config.configPreset = ConfigPreset.CUSTOM;

        assertTrue(ConfigPresets.reconcile(config));

        assertEquals(ConfigPreset.CUSTOM, config.appliedConfigPreset);
        assertTrue(ConfigPresets.matches(config, ConfigPreset.FREE_TIER));
    }

    @Test
    void presetsDiffer() {
        assertNotEquals(ConfigPreset.FREE_TIER.settings(), ConfigPreset.PAID_KEY.settings());
        assertNotEquals(ConfigPreset.FREE_TIER.settings(), ConfigPreset.QUIET_COLONY.settings());
    }

    @Test
    void docsTableMatchesThePresets() throws IOException {
        List<String> rows = new ArrayList<>();
        for (String line : Files.readAllLines(repositoryRoot().resolve("docs/config-presets.md"))) {
            if (line.startsWith("| `")) {
                rows.add(line);
            }
        }
        List<String> expected = new ArrayList<>();
        for (String key : ConfigPreset.FREE_TIER.settings().keySet()) {
            expected.add("| `" + key + "` | " + ConfigPreset.FREE_TIER.settings().get(key)
                    + " | " + ConfigPreset.PAID_KEY.settings().get(key)
                    + " | " + ConfigPreset.QUIET_COLONY.settings().get(key) + " |");
        }
        assertEquals(expected, rows);
    }

    private static Class<?> box(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == double.class) return Double.class;
        if (type == boolean.class) return Boolean.class;
        return type;
    }

    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("stonecutter.gradle.kts"))) {
            dir = dir.getParent();
        }
        if (dir == null) throw new IllegalStateException("Could not find the repository root");
        return dir;
    }
}
