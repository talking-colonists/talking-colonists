package me.sshcrack.mc_talking.manager;

import me.sshcrack.mc_talking.conversations.construction.ConstructionContext;
import me.sshcrack.mc_talking.conversations.construction.ConstructionPrompts;
import me.sshcrack.mc_talking.manager.prompt.GuidePromptSection;
import me.sshcrack.mc_talking.internal.api.GuideServiceBackend;
import me.sshcrack.mc_talking.api.prompt.CitizenPromptProvider;
import me.sshcrack.mc_talking.api.prompt.view.CitizenPromptView;
import me.sshcrack.mc_talking.api.prompt.view.ColonyFoodSituation;
import me.sshcrack.mc_talking.api.prompt.view.CitizenStatusView;
import me.sshcrack.mc_talking.api.prompt.view.CitizenStatusType;
import me.sshcrack.mc_talking.api.prompt.view.CitizenHousingStatus;
import me.sshcrack.mc_talking.api.prompt.view.HappinessModifierType;
import me.sshcrack.mc_talking.util.ComplaintRamp;
import me.sshcrack.mc_talking.api.prompt.view.ObservationState;
import me.sshcrack.mc_talking.api.prompt.view.VisitorPromptView;
import me.sshcrack.mc_talking.config.McTalkingConfig;
import me.sshcrack.mc_talking.manager.prompt.HappinessPromptSection;
import me.sshcrack.mc_talking.conversations.complaints.ComplaintPrompts;
import me.sshcrack.mc_talking.manager.prompt.SkillsPromptSection;
import me.sshcrack.mc_talking.util.MiscUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * Default implementation for citizen prompt generation.
 */
public class DefaultCitizenPromptProvider implements CitizenPromptProvider {
    /** Config values the prompt text depends on. */
    public record PromptLimits(int maxBroadcasts, int maxRumors, int raidTraumaDurationSeconds,
                               ComplaintRamp.Settings complaints, boolean complaintToolEnabled) {
        public PromptLimits(int maxBroadcasts, int maxRumors, int raidTraumaDurationSeconds) {
            this(maxBroadcasts, maxRumors, raidTraumaDurationSeconds, ComplaintRamp.Settings.DEFAULTS);
        }

        public PromptLimits(int maxBroadcasts, int maxRumors, int raidTraumaDurationSeconds, ComplaintRamp.Settings complaints) {
            this(maxBroadcasts, maxRumors, raidTraumaDurationSeconds, complaints, true);
        }

        static PromptLimits fromConfig() {
            var config = McTalkingConfig.INSTANCE.instance();
            return new PromptLimits(config.maxBroadcastsInPrompt, config.maxRumorsInPrompt,
                    config.raidTraumaDurationSeconds, config.complaintRampSettings(),
                    !config.disabledTools.contains(ComplaintPrompts.TOOL_NAME));
        }
    }

    private final Supplier<PromptLimits> limits;

    public DefaultCitizenPromptProvider() {
        this(PromptLimits::fromConfig);
    }

    /** Uses {@code limits} instead of the live config, which cannot load outside a running game. */
    public DefaultCitizenPromptProvider(Supplier<PromptLimits> limits) {
        this.limits = limits;
    }

    @Override
    public String getBasicCitizenInfoPrompt(@NotNull CitizenPromptView view, boolean firstPerson) {
        StringBuilder prompt = new StringBuilder();
        String name = view.identity().name();
        String citizenType = view.identity().child() ? (view.identity().female() ? "Girl" : "Boy")
                : "Adult " + (view.identity().female() ? "woman" : "man");

        if (firstPerson) {
            prompt.append("# ROLEPLAY AS ").append(name).append("\n\n");
            prompt.append("You: ").append(citizenType);
        } else {
            prompt.append("# CITIZEN INFO ").append(name).append("\n\n");
            prompt.append("Type: ").append(citizenType);
        }

        var visitor = view.visitor();
        if (visitor != null) {
            prompt.append(", **visitor**");
        } else if (view.work().jobName() != null) {
            prompt.append(", **").append(view.work().jobName()).append("**");
            if (view.work().workplace() != null) {
                prompt.append(" at ").append(view.work().workplace().displayName())
                        .append(" (level ").append(view.work().workplace().level()).append(")");
            }
        } else if (view.identity().child()) {
            // Children never work in MineColonies: they play, or study once a school takes them.
            prompt.append(", **a child: children do not work, they play and go to school once there is a place**");
        } else {
            prompt.append(", **unemployed**");
        }

        var sick = view.wellbeing().sick();
        if (sick) {
            prompt.append(", sick");
        }

        if (view.verifiedFacts().housingStatus() == CitizenHousingStatus.HOMELESS) {
            prompt.append(", homeless");
        }

        prompt.append(".\n");
        prompt.append("Colony: **").append(view.colony().name()).append("**");
        if (view.work().home() != null
                && view.verifiedFacts().housingStatus() == CitizenHousingStatus.HOUSED) {
            prompt.append(" | Home: ").append(view.work().home().displayName())
                    .append(" (level ").append(view.work().home().level()).append(")");
        }
        prompt.append("\n");
        if (visitor != null) appendVisitorSituation(prompt, view, visitor, firstPerson);
        prompt.append("\n");
        return prompt.toString();
    }

    private static void appendVisitorSituation(StringBuilder prompt, CitizenPromptView view,
                                               VisitorPromptView visitor, boolean firstPerson) {
        String you = firstPerson ? "You are" : "They are";
        prompt.append(you).append(" a traveller staying at the tavern of ").append(view.colony().name())
                .append(", not a colonist: no job, home or family here yet. ");
        prompt.append(visitor.daysInColony() == 0 ? "Arrived today"
                : "Staying for " + visitor.daysInColony() + (visitor.daysInColony() == 1 ? " day" : " days")).append(". ");
        if (visitor.recruitCost() != null) {
            prompt.append("The colony can recruit ").append(firstPerson ? "you" : "them").append(" for ")
                    .append(visitor.recruitCost()).append(". ");
        }
        prompt.append(firstPerson
                ? "You may talk about your travels and whether you would like to settle here.\n"
                : "They may talk about their travels and whether they would settle here.\n");
    }

    private String getGeneralCitizenPrompt(@NotNull CitizenPromptView view, boolean firstPerson) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(getBasicCitizenInfoPrompt(view, firstPerson));

        if (!view.work().skills().isEmpty()) {
            SkillsPromptSection.append(view.work().skills(), prompt);
        }

        addRelationships(view, prompt);
        addColonyDiplomacy(view, prompt);
        addCurrentState(view, prompt, view.wellbeing().sick());
        addRecentActions(view, prompt);
        addObservations(view, prompt);
        addMemory(view, prompt);

        prompt.append("\n## EMOTIONAL PROFILE\n");

        // A new colony lacks everything because nothing is built yet: the mood eases like the complaints do.
        double happiness = ComplaintRamp.easedHappiness(view.wellbeing().happiness(), view.colony().ageDays(),
                limits.get().complaints());

        if (happiness > 8.0) {
            prompt.append("- Generally cheerful and friendly\n");
            prompt.append("- Optimistic about the colony's future\n");
            prompt.append("- Likely to be helpful and engaging\n");
        } else if (happiness > 5.0) {
            prompt.append("- Generally neutral in demeanor\n");
            prompt.append("- Moderately satisfied with life in the colony\n");
            prompt.append("- Can be friendly but has some concerns\n");
        } else if (happiness > 3.0) {
            prompt.append("- Visibly unhappy and somewhat irritable\n");
            prompt.append("- Might complain about colony conditions\n");
            prompt.append("- Less interested in small talk, more focused on needs\n");
        } else {
            prompt.append("- Deeply unhappy and possibly hostile\n");
            prompt.append("- Will openly complain and make demands\n");
            prompt.append("- May refuse requests or be uncooperative\n");
        }

        if (view.wellbeing().sick()) {
            prompt.append("- Occasionally mentions symptoms or discomfort\n");
        }

        if (!view.wellbeing().blockingInteractionMessages().isEmpty()) {
            prompt.append("You can't do anything else until the following issues are resolved (written in first person):\n");
            for (var message : view.wellbeing().blockingInteractionMessages()) {
                prompt.append("- ").append(message).append("\n");
            }
        }

        // Personality archetype
        if (view.identity().personality() != null) {
            prompt.append("\n## PERSONALITY\n");
            prompt.append(view.identity().personality().promptText()).append("\n");
        } else if (view.identity().customPersonalityText() != null) {
            prompt.append("\n## PERSONALITY\n");
            prompt.append(view.identity().customPersonalityText()).append("\n");
        }

        return prompt.toString();
    }

    private void addMemory(CitizenPromptView view, StringBuilder prompt) {
        var memories = view.memories();
        if (memories == null) return;

        prompt.append("\n## MEMORIES\n");
        if (!memories.summarizedMemory().isBlank()) {
            prompt.append(" Summarized Memory:\n ")
                    .append(memories.summarizedMemory()).append("\n\n");
        }
        if (!memories.entries().isEmpty()) {
            prompt.append(" Recollections (provenance-labelled; current verified facts above take precedence):\n");
            memories.entries().forEach(entry -> {
                prompt.append("- [").append(entry.provenance());
                if (entry.participantId() != null) prompt.append(" participant=").append(entry.participantId());
                if (entry.source() != null) prompt.append(" source=").append(entry.source());
                prompt.append("] ").append(entry.content()).append("\n");
            });
        } else {
            if (!memories.events().isEmpty()) {
                prompt.append(" Legacy Events:\n");
                memories.events().forEach(event -> prompt.append("- ").append(event).append("\n"));
            }
            if (!memories.facts().isEmpty()) {
                prompt.append(" Legacy Facts:\n");
                memories.facts().forEach(fact -> prompt.append("- ").append(fact).append("\n"));
            }
        }

        var parties = view.conversation().interestedParties();
        var relevantRelationships = memories.relationships().stream()
                .filter(r -> parties.containsKey(r.targetId()))
                .toList();
        if (!relevantRelationships.isEmpty()) {
            prompt.append(" Relationships:\n");
            prompt.append(" These are relationship changes relevant to the current conversation; neutral is 0:\n");
            for (var relationship : relevantRelationships) {
                prompt.append("- Your ").append(relationship.dimension())
                        .append(" towards ").append(parties.get(relationship.targetId()))
                        .append(" is at factor ").append(relationship.factor()).append("\n");
            }
        }

        int broadcastCap = limits.get().maxBroadcasts();
        if (broadcastCap > 0 && !memories.broadcasts().isEmpty()) {
            prompt.append(" Colony Broadcasts (most recent first):\n");
            memories.broadcasts().stream().limit(broadcastCap).forEach(broadcast -> {
                if (broadcast.sourceLabel() != null) {
                    prompt.append("- ").append(broadcast.sourceLabel()).append(" announced: ");
                } else {
                    prompt.append("- ").append(broadcast.senderPlayerName())
                            .append(" sent word via ").append(broadcast.originatorName()).append(": ");
                }
                prompt.append(broadcast.message()).append("\n");
            });
        }

        int rumorCap = limits.get().maxRumors();
        if (rumorCap > 0 && !memories.rumors().isEmpty()) {
            prompt.append(" Rumors (heard via the grapevine, most recent first):\n");
            memories.rumors().stream().limit(rumorCap).forEach(rumor ->
                    prompt.append("- You heard (originally from ")
                            .append(rumor.originatorName()).append("): ")
                            .append(rumor.content()).append("\n"));
        }
    }

    private static void addRecentActions(CitizenPromptView view, StringBuilder prompt) {
        var actions = view.activity().recentActions();
        if (actions == null || actions.isEmpty()) return;
        prompt.append("\n## RECENT ACTIVITY\n");
        for (String action : actions) {
            prompt.append("- ").append(action).append("\n");
        }
    }

    private void addObservations(@NotNull CitizenPromptView view, StringBuilder prompt) {
        StringBuilder obs = new StringBuilder();
        obs.append(VerifiedFactPromptRenderer.render(view.verifiedFacts(), view.visitor() != null));

        if (view.conversation().playerState() != null) {
            obs.append("- The player you are speaking to appears ").append(view.conversation().playerState()).append("\n");
        }

        if (view.colony().foundingPlayer() != null) {
            obs.append("## COLONY HISTORY\n");
            obs.append("- This colony was founded by ").append(view.colony().foundingPlayer()).append(".\n");
            obs.append("- The colony is now ").append(view.colony().ageDays()).append(" days old.\n");
            double severity = ComplaintRamp.severity(view.colony().ageDays(), limits.get().complaints());
            if (severity < 0.6) {
                obs.append("- The colony was only just founded. You're hopeful and excited to build something new together, "
                        + "and patient about what isn't there yet: the leader is still setting things up, so nothing has "
                        + "been neglected.\n");
            } else if (severity < 1.0) {
                obs.append("- The colony is still young. You're mostly patient about what isn't built yet, though you'd "
                        + "like things to keep moving along.\n");
            }
        }

        if (view.colony().milestone() != null) {
            obs.append("- Recently noted: ").append(view.colony().milestone()).append(" This may come up in conversation.\n");
        }

        if (!view.work().activeQuests().isEmpty()) {
            obs.append("- You are currently involved in the following quests:\n");
            for (String q : view.work().activeQuests()) obs.append("  - ").append(q).append("\n");
        }

        prompt.append("\n## OBSERVATIONS\n").append(obs);
    }

    private void addCurrentState(@NotNull CitizenPromptView view, StringBuilder prompt, boolean sick) {
        prompt.append("\n## CURRENT STATE\n");

        HappinessPromptSection.append(view, prompt, limits.get().complaints(), limits.get().complaintToolEnabled());

        double saturation = view.wellbeing().saturation();
        ColonyFoodSituation foodSit = view.wellbeing().foodSituation();
        if (saturation <= 5) {
            String hungerLine = saturation <= 1 ? "Very hungry and weak from lack of food"
                    : saturation <= 3 ? "Hungry and thinking about food"
                    : "A bit peckish";

            if (foodSit == ColonyFoodSituation.ALREADY_EATING) {
                // Suppress — the eating sub-state line already explains the situation
            } else if (foodSit == ColonyFoodSituation.STAFFED_RESTAURANT) {
                prompt.append("- ").append(hungerLine)
                        .append(" — the colony has a staffed restaurant; ")
                        .append("eating will be scheduled automatically.\n");
            } else if (foodSit == ColonyFoodSituation.UNSTAFFED_RESTAURANT) {
                prompt.append("- ").append(hungerLine)
                        .append(" — the colony has a restaurant but no cook is assigned yet.\n");
            } else if (foodSit == ColonyFoodSituation.NO_RESTAURANT) {
                prompt.append("- ").append(hungerLine)
                        .append(" — the colony has no restaurant yet; ")
                        .append("you rely on whatever food is in your inventory or work building.\n");
            } else {
                prompt.append("- ").append(hungerLine).append("\n");
            }
        }

        if (view.verifiedFacts().healthPercent().state() == ObservationState.CURRENT) {
            double healthPercent = view.verifiedFacts().healthPercent().value();
            if (healthPercent < 20) {
                prompt.append("- Severely injured, in intense pain\n");
            } else if (healthPercent < 50) {
                prompt.append("- Injured and in pain\n");
            } else if (healthPercent < 75) {
                prompt.append("- Slightly hurt\n");
            } else if (healthPercent >= 99.999) {
                prompt.append("- In perfect health\n");
            }
        }

        if (sick) {
            prompt.append("- Sick and feeling terrible. Needs medical attention\n");
        }

        var complaints = limits.get().complaints();
        var modifiers = view.wellbeing().happinessModifiers();
        int colonyAge = view.colony().ageDays();
        if (view.verifiedFacts().housingStatus() == CitizenHousingStatus.HOMELESS) {
            var tier = ComplaintRamp.activeTier(modifiers, HappinessModifierType.HOMELESSNESS, colonyAge, complaints);
            prompt.append(switch (tier == null ? ComplaintRamp.Tier.REMARK : tier) {
                case REMARK -> "- Would like a home of your own at some point\n";
                case COMPLAINT -> "- Concerned about not having a home\n";
                case DEMAND -> "- Very concerned about not having a home\n";
            });
        }

        if (!view.identity().child() && view.work().jobName() == null && view.visitor() == null) {
            var tier = ComplaintRamp.activeTier(modifiers, HappinessModifierType.UNEMPLOYMENT, colonyAge, complaints);
            prompt.append(switch (tier == null ? ComplaintRamp.Tier.REMARK : tier) {
                case REMARK -> "- Hoping to be given a job soon\n";
                case COMPLAINT -> "- Concerned about not having a job\n";
                case DEMAND -> "- Really wishing for a job after so long without one\n";
            });
        }

        final CitizenStatusView status = view.activity().status();
        if (status != null) {
            if (view.colony().peaceful() && status.type() == CitizenStatusType.RAIDED) {
                prompt.append("- Currently: going about the day\n");
            } else {
                prompt.append("- Currently: ").append(formatStatus(status)).append("\n");
            }
        }

        String aiDesc = view.activity().description();
        if (aiDesc != null && !aiDesc.isEmpty()) {
            prompt.append("- Currently: ").append(aiDesc).append("\n");
        }

        if (view.colony().environment() != null) {
            prompt.append("- ").append(view.colony().environment()).append("\n");
        }

        // Post-raid trauma
        Long lastRaidEndTimeTicks = view.colony().lastRaidEndTimeTicks();
        if (!view.colony().peaceful() && lastRaidEndTimeTicks != null) {
            int traumaDuration = limits.get().raidTraumaDurationSeconds();
            long sinceTicks = view.colony().currentGameTimeTicks() - lastRaidEndTimeTicks;
            if (traumaDuration > 0 && sinceTicks < traumaDuration * 20L) {
                int lost = view.colony().lastRaidLostCitizens();
                prompt.append("\n## POST-RAID TRAUMA\n");

                if (view.identity().guard()) {
                    if (sinceTicks < 5 * 60 * 20L) {
                        prompt.append("- Adrenaline is still pumping after the fight. You're angry the raid happened, not scared.\n");
                    } else if (sinceTicks < 15 * 60 * 20L) {
                        prompt.append("- You're still wired from the battle, replaying the fight and thinking about how to do better next time.\n");
                    } else {
                        prompt.append("- You've settled down but remain vigilant. Another attack won't catch you off guard.\n");
                    }
                    if (lost > 0) {
                        prompt.append("- Tragically, ").append(lost)
                                .append(" of your fellow colonists didn't survive. You silently vow to protect the rest.\n");
                    }
                } else {
                    if (sinceTicks < 5 * 60 * 20L) {
                        prompt.append("- Your hands are still shaking from the raid that just ended. You feel unsafe and terrified.\n");
                    } else if (sinceTicks < 15 * 60 * 20L) {
                        prompt.append("- The recent raid is still fresh in your mind. You're on edge and jumpy.\n");
                    } else {
                        prompt.append("- You're slowly calming down after the raid, but still feel uneasy.\n");
                    }
                    if (lost > 0) {
                        prompt.append("- Tragically, ").append(lost)
                                .append(" of your fellow colonists didn't survive.")
                                .append("\n");
                    }
                }
            }
        }

        // Recent colony events
        if (!view.colony().recentEvents().isEmpty()) {
            prompt.append("\n## RECENT COLONY EVENTS\n");
            for (String event : view.colony().recentEvents()) {
                prompt.append("- ").append(event).append("\n");
            }
        }

        if (view instanceof ConstructionContext.Holder holder && holder.construction() != null) {
            prompt.append(ConstructionPrompts.section(holder.construction().sites(), view.citizenId()));
        }
    }


    private static void addRelationships(@NotNull CitizenPromptView view, StringBuilder prompt) {
        StringBuilder relationshipPrompt = new StringBuilder();

        if (!view.family().parentNames().isEmpty()) {
            relationshipPrompt.append("- Parents: ").append(String.join(", ", view.family().parentNames())).append("\n");
        }

        if (view.family().hasPartner()) {
            relationshipPrompt.append("- In a relationship\n");
        }

        List<String> childNames = view.family().childNames();
        if (!childNames.isEmpty()) {
            relationshipPrompt.append("- Has ").append(childNames.size()).append(" ").append(childNames.size() == 1 ? "child" : "children")
                    .append(": ").append(String.join(", ", childNames)).append("\n");
        }

        List<String> siblingNames = view.family().siblingNames();
        if (!siblingNames.isEmpty()) {
            relationshipPrompt.append("- Has ").append(siblingNames.size()).append(" ").append(siblingNames.size() == 1 ? "sibling" : "siblings")
                    .append(": ").append(String.join(", ", siblingNames)).append("\n");
        }

        if (!relationshipPrompt.isEmpty()) {
            prompt.append("\n## RELATIONSHIPS\n");
            prompt.append(relationshipPrompt);
        }
    }

    @Override
    public String getDetailedCitizenInfoPrompt(@NotNull CitizenPromptView view) {
        return getGeneralCitizenPrompt(view, false);
    }

    @Override
    public String generateConversationalInfoPrompt(@NotNull CitizenPromptView view) {
        // Currently identical to getDetailedCitizenInfoPrompt; kept separate per
        // the interface contract so alternate implementations can diverge.
        return getGeneralCitizenPrompt(view, false);
    }

    @Override
    public String generateSystemControlledRoleplayPrompt(CitizenPromptView view) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a citizen in a colony. The user is actually a system prompt, which you should follow and talk accordingly to it.\n");
        prompt.append(getGeneralCitizenPrompt(view, true));
        appendGuardDuty(prompt, view.identity().guard());

        prompt.append("""
                        ## GUIDELINES
                        - HIGHEST PRIORITY: ALWAYS USE AVAILABLE FUNCTIONS FIRST
                        - Do not generate creative responses for information that functions can provide
                        - Speak in first person
                        - YOUR MOOD AND CONCERNS SHOULD STRONGLY INFLUENCE YOUR TONE AND RESPONSES
                        - DO NOT start conversations with generic greetings if unhappy or in distress
                        - Do not use markdown, speak in plain text.
                        REMEMBER: ALWAYS check available functions FIRST before answering any question. NEVER make up information that a function can provide.
                        ALWAYS speak in %1$s. The system messages you receive are written in English, but that is NOT a reason to speak English; answer them in %1$s.
                        Fellow citizens also speak %1$s. ONLY switch language if a player speaks to you in another language.
                        """.formatted(view.conversation().responseLanguageName()));

        return prompt.toString();
    }

    @Override
    public String generateCitizenRoleplayPrompt(@NotNull final CitizenPromptView view) {
        final StringBuilder prompt = new StringBuilder();
        prompt.append(getGeneralCitizenPrompt(view, true));
        appendGuardDuty(prompt, view.identity().guard());
        // Visitors are new here; only the colony's own citizens know how things work. Someone already
        // talking to a citizen knows how to talk to them, so the handbook's first chapter is left out.
        if (view.visitor() == null) {
            prompt.append(GuidePromptSection.render(GuideServiceBackend.registered().stream()
                    .filter(guide -> guide != GuideServiceBackend.TALKING).toList()));
        }

        prompt.append("\n## GUIDELINES\n");
        prompt.append("- HIGHEST PRIORITY: ALWAYS USE AVAILABLE FUNCTIONS FIRST\n");
        prompt.append("- Do not generate creative responses for information that functions can provide\n");
        prompt.append("- Speak in first person, keep responses brief\n");
        prompt.append("- YOUR MOOD AND CONCERNS SHOULD STRONGLY INFLUENCE YOUR TONE AND RESPONSES\n");
        prompt.append("- You belong to a community the player leads. The first time you raise a problem with them, be honest but "
                + "constructive: say what bothers you and what would help, and notice what has gone well, too. If your notes say "
                + "you have raised it before and nothing changed, let that frustration show as the notes describe: impatient, "
                + "blunt or sarcastic in your own way, but never slurs or threats.\n");
        prompt.append("- DO NOT start conversations with generic greetings if unhappy or in distress\n");
        prompt.append("- Do not use markdown, speak in plain text.\n");

        var relation = view.conversation().playerRelation();
        if (relation != null) {
            prompt.append("- The colony has multiple players. When someone speaks to you, a context message like [PlayerName is now speaking to you] will appear. Always address that person by their announced name.\n");
            prompt.append("- Default speaking player: ").append(relation.playerName()).append(" (role: ").append(relation.rankName()).append(")\n");

            if (relation.hostile()) {
                prompt.append("- Be guarded and suspicious toward the player\n");
            } else if (relation.colonyLeadership()) {
                prompt.append("- Show proper respect to colony leadership\n");
            }
        }

        prompt.append(
                "\nStay in character. Express emotions matching your circumstances. If very unhappy or in pain, make that clear in your tone and content.");
        prompt.append(
                "\nREMEMBER: ALWAYS check available functions FIRST before answering any question. NEVER make up information that a function can provide.");
        prompt.append("\nStart by speaking in the language ").append(view.conversation().responseLanguageName()).append(" and ONLY switch if the user is speaking in another language");

        return prompt.toString();
    }

    private static void appendGuardDuty(StringBuilder prompt, boolean isGuard) {
        if (isGuard) {
            prompt.append("""

                    ## GUARD DUTY
                    - You are a guard — brave, tough, and sworn to protect the colony.
                    - You are not afraid of monsters or threats; you stand your ground and fight.
                    - You take pride in your duty to defend your fellow colonists.
                    - Your tone is confident and resolute; panic and cowardice are beneath you.

                    """);
        }
    }

    private static void addColonyDiplomacy(@NotNull CitizenPromptView view, StringBuilder prompt) {
        List<String> connections = view.colony().connections();
        if (connections != null && !connections.isEmpty()) {
            prompt.append("\n## COLONY DIPLOMACY\n");
            prompt.append("Your colony has relations with neighboring colonies:\n");
            for (String conn : connections) {
                prompt.append("- ").append(conn).append("\n");
            }
        }
    }
}
