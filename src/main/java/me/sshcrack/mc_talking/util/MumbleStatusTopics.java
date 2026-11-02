package me.sshcrack.mc_talking.util;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import net.minecraft.world.Difficulty;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/** What a citizen mumbles about their state: grief, illness, a raid, hunger, the weather, a meal. */
final class MumbleStatusTopics {
    private MumbleStatusTopics() {
    }

    /**
     * Returns true for statuses that are emotionally dominant and should not be blended.
     */
    static boolean isCritical(AbstractEntityCitizen citizen) {
        var data = citizen.getCitizenData();
        var status = data.getStatus();
        return status == VisibleCitizenStatus.MOURNING
                || status == VisibleCitizenStatus.SICK
                || data.getCitizenDiseaseHandler().isSick()
                || (status == VisibleCitizenStatus.RAIDED && !isPeaceful(citizen))
                || data.getSaturation() <= 1;
    }

    @Nullable
    static String topic(AbstractEntityCitizen citizen) {
        var data = citizen.getCitizenData();
        var status = data.getStatus();

        // ── Critical ─────────────────────────────────────────────────────────
        if (status == VisibleCitizenStatus.MOURNING)
            return "You're grieving quietly, your thoughts returning to someone you lost.";

        if (status == VisibleCitizenStatus.SICK || data.getCitizenDiseaseHandler().isSick())
            return MiscUtil.pick(
                    "You feel awful and can't ignore it.",
                    "You're unwell and finding it hard to concentrate.",
                    "You're trying to push through, but it's hard."
            );

        if (status == VisibleCitizenStatus.RAIDED && !isPeaceful(citizen))
            return MiscUtil.pick(
                    "You're still shaken by what happened.",
                    "You can't fully relax yet.",
                    "You're replaying recent events in your mind."
            );

        double saturation = data.getSaturation();
        if (saturation <= 1)
            return MiscUtil.pick(
                    "You're extremely hungry — it's getting hard to think straight.",
                    "The hunger is impossible to ignore now.",
                    "You're running on empty and you know it."
            );

        // ── Mild (blendable) ─────────────────────────────────────────────────
        // WORKING is intentionally omitted — job topic is always more specific.

        if (status == VisibleCitizenStatus.BAD_WEATHER && chance(0.5))
            return MiscUtil.pick(
                    "You're annoyed by the weather.",
                    "The rain is getting to you.",
                    "You'd rather be doing this in better conditions."
            );

        if (status == VisibleCitizenStatus.EAT && chance(0.6))
            return MiscUtil.pick(
                    "You're focused on your meal.",
                    "You're savouring a moment of rest.",
                    "The food is a welcome break."
            );

        if (saturation <= 3 && chance(0.5))
            return MiscUtil.pick(
                    "You're thinking about food.",
                    "Your stomach reminds you it's not been long enough since you last ate.",
                    "You're keeping an eye out for a chance to grab something."
            );

        return null;
    }

    private static boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    private static boolean isPeaceful(AbstractEntityCitizen citizen) {
        var level = citizen.level();
        return level != null && level.getDifficulty() == Difficulty.PEACEFUL;
    }
}
