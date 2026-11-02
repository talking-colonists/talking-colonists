package me.sshcrack.mc_talking.util;

import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ThreadLocalRandom;

/**
 * The prompt for a citizen mumbling to themselves: a status topic (see {@link MumbleStatusTopics}), a job
 * thought (see {@link MumbleJobThoughts}) and sometimes a colony milestone, blended into one thought.
 */
public final class MumblingTopicHelper {

    private MumblingTopicHelper() {
    }

    private static final String FALLBACK =
            "You feel the urge to mutter something under your breath. " +
                    "Speak your thought aloud briefly, as if absent-mindedly talking to yourself.";

    public static String buildPrompt(AbstractEntityCitizen citizen) {
        if (citizen.getCitizenData() == null) return FALLBACK;

        var data = citizen.getCitizenData();
        double happiness = data.getCitizenHappinessHandler().getHappiness(data.getColony(), data);

        String statusTopic = MumbleStatusTopics.topic(citizen);
        boolean critical = MumbleStatusTopics.isCritical(citizen);

        // Critical statuses (grief, illness, raid) take full priority — no blending.
        if (critical && statusTopic != null) {
            return compose(statusTopic);
        }

        String jobTopic = buildJobTopic(citizen, happiness);

        // Colony milestone — rolls independently so it can supplement any topic.
        String rawMilestone = null;
        if (chance(0.35)) {
            rawMilestone = ColonyStatsHelper.getColonyMilestoneText(data);
        }

        // Non-critical status + job: blend them so both colour the thought.
        if (statusTopic != null && jobTopic != null) {
            String blended = statusTopic + " Still, " +
                    Character.toLowerCase(jobTopic.charAt(0)) + jobTopic.substring(1);
            if (rawMilestone != null) {
                blended = blended + " You also find yourself thinking: " + rawMilestone.toLowerCase();
            }
            return compose(blended);
        }

        if (statusTopic != null) {
            if (rawMilestone != null) {
                return compose(statusTopic + " You also notice: " + rawMilestone.toLowerCase());
            }
            return compose(statusTopic);
        }

        if (jobTopic != null) {
            if (rawMilestone != null) {
                return compose(jobTopic + " You're also aware that " + rawMilestone.toLowerCase());
            }
            return compose(jobTopic);
        }

        if (rawMilestone != null) {
            return compose(standaloneColonyTopic(rawMilestone));
        }

        return FALLBACK;
    }

    private static String compose(String topic) {
        return "You feel the urge to mutter something under your breath. " +
                topic + " " +
                "Keep it very brief — one or two sentences at most, as if absent-mindedly talking to yourself.";
    }

    // ── Job-specific thoughts ─────────────────────────────────────────────────

    @Nullable
    private static String buildJobTopic(AbstractEntityCitizen citizen, double happiness) {
        var data = citizen.getCitizenData();
        if (data.getJob() == null) return null;

        var job = data.getJob().getJobRegistryEntry();
        String base = MumbleJobThoughts.thought(job);
        if (base == null) return null;

        return MumbleJobThoughts.moodTint(base, happiness);
    }

    /**
     * Wraps a raw colony milestone in an intro phrase for standalone use
     * (no other topic available to blend with).
     */
    private static String standaloneColonyTopic(String milestone) {
        return MiscUtil.pick(
                "You're thinking about something — " + milestone.toLowerCase(),
                "A thought crosses your mind: " + milestone.toLowerCase(),
                "You can't help but notice that " + milestone.toLowerCase()
        );
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean chance(double probability) {
        return ThreadLocalRandom.current().nextDouble() < probability;
    }
}
