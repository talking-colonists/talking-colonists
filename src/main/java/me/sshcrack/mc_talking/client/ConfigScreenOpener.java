package me.sshcrack.mc_talking.client;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionEventListener;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import me.sshcrack.mc_talking.config.ConfigPreset;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Opens the Talking Colonists config screen. Client-only: never load this class on a dedicated server. */
public final class ConfigScreenOpener {
    private static final String OPTION_KEY_PREFIX = "yacl3.config.mc_talking:config.";

    private ConfigScreenOpener() {
    }

    /**
     * Opens the screen on the next client tick, so the chat screen that ran the click
     * finishes closing first. Closing the config returns to the game.
     */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(screen(null)));
    }

    /**
     * The config screen. Picking a preset fills in its values right away; changing one of
     * those values afterwards switches the preset to Custom.
     */
    public static Screen screen(@Nullable Screen parent) {
        YetAnotherConfigLib generated = McTalkingConfig.INSTANCE.generateGui();
        linkPresetOptions(generated);
        return YetAnotherConfigLib.createBuilder()
                .title(generated.title())
                .categories(generated.categories())
                .save(() -> {
                    generated.saveFunction().run();
                    McTalkingConfig.applyPresetChanges();
                })
                .screenInit(generated.initConsumer())
                .build()
                .generateScreen(parent);
    }

    @SuppressWarnings("unchecked")
    private static void linkPresetOptions(YetAnotherConfigLib gui) {
        Map<String, Option<Object>> options = new HashMap<>();
        for (ConfigCategory category : gui.categories()) {
            for (OptionGroup group : category.groups()) {
                for (Option<?> option : group.options()) {
                    if (option.name().getContents() instanceof TranslatableContents translatable
                            && translatable.getKey().startsWith(OPTION_KEY_PREFIX)) {
                        options.put(translatable.getKey().substring(OPTION_KEY_PREFIX.length()), (Option<Object>) option);
                    }
                }
            }
        }

        Option<Object> presetOption = options.get("configPreset");
        if (presetOption == null) {
            return;
        }
        boolean[] applying = {false};

        presetOption.addEventListener((option, event) -> {
            if (event != OptionEventListener.Event.STATE_CHANGE || applying[0]) {
                return;
            }
            ConfigPreset preset = (ConfigPreset) option.pendingValue();
            applying[0] = true;
            try {
                preset.settings().forEach((key, value) -> {
                    Option<Object> target = options.get(key);
                    if (target != null && !Objects.equals(target.pendingValue(), value)) {
                        target.requestSet(value);
                    }
                });
            } finally {
                applying[0] = false;
            }
        });

        for (String key : ConfigPreset.FREE_TIER.settings().keySet()) {
            Option<Object> target = options.get(key);
            if (target == null) {
                continue;
            }
            target.addEventListener((option, event) -> {
                if (event != OptionEventListener.Event.STATE_CHANGE || applying[0]) {
                    return;
                }
                ConfigPreset preset = (ConfigPreset) presetOption.pendingValue();
                if (preset != ConfigPreset.CUSTOM && !Objects.equals(option.pendingValue(), preset.settings().get(key))) {
                    applying[0] = true;
                    try {
                        presetOption.requestSet(ConfigPreset.CUSTOM);
                    } finally {
                        applying[0] = false;
                    }
                }
            });
        }
    }
}
