package me.sshcrack.mc_talking.onboarding;

/**
 * What a citizen is told when introducing something to a player, and what they remember of it. Pure
 * text on plain values, so unit tests never load Minecraft types.
 */
public final class IntroductionTexts {
    private IntroductionTexts() {
    }

    /** The one-off line. The citizen's memory is written afterwards by Talking Colonists, so the model is asked not to. */
    public static String directive(String player, boolean welcome, String lineHint, boolean mentionHandbook) {
        return "You just walked up to " + player + ", who belongs to your colony"
                + (welcome ? ", and handed them a Colony Handbook. " : ". ")
                + lineHint
                + (mentionHandbook ? " If it fits, mention that the Colony Handbook has more about it." : "")
                + " Say it in one or two short sentences, at most 30 words, in your own voice."
                + " Do not call any functions for this.";
    }

    /** What the citizen remembers afterwards, so later conversations and stories don't repeat it. */
    public static String memory(String player, boolean welcome, String topic) {
        if (welcome) {
            return "I welcomed " + player + " to the colony and gave them the Colony Handbook. That is done: from now on I greet "
                    + player + " like anyone else I know and don't welcome them again.";
        }
        return "I told " + player + " once about " + topic + ". I only bring it up again if they ask; it is not "
                + "something to tell stories about.";
    }
}
