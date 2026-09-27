package me.sshcrack.mc_talking.broadcast;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Checks on what a citizen is asked to announce to the whole colony. */
public final class BroadcastMessages {
    /** Phrases of a template the model filled in instead of the player's words. */
    private static final List<String> PLACEHOLDERS = List.of(
            "change this message", "whatever broadcast", "whatever you want", "your message", "message here",
            "insert ", "placeholder", "enter message", "<message", "[message", "{message", "todo");

    /**
     * A call for attention that announces nothing, e.g. "Attention everyone, Steve has an announcement!"
     * (seen when a player asked a citizen to announce something without saying what).
     */
    private static final String ATTENTION = "(attention|hear ye|listen up|listen|everyone|all|citizens|colonists|hello|hey|please|[ ,!.:])";
    private static final List<Pattern> EMPTY = List.of(
            Pattern.compile("^[\\p{L}' ,!.:]{0,60}\\b(has|have|got) (an announcement|a message|something to (say|share|announce|tell)[\\p{L} ]*|(some )?news)$"),
            Pattern.compile("^" + ATTENTION + "*(a message|an announcement|news) from [\\p{L}' ]{1,40}$"),
            Pattern.compile("^" + ATTENTION + "+$"));

    private BroadcastMessages() {
    }

    /**
     * True when {@code message} is empty, too short to say anything, a template, or a call for attention with no
     * announcement in it, rather than the player's words.
     */
    public static boolean isPlaceholder(String message) {
        String text = message.strip().toLowerCase(Locale.ROOT);
        if (text.length() < 4) return true;
        for (String placeholder : PLACEHOLDERS) {
            if (text.contains(placeholder)) return true;
        }
        String unpunctuated = text.replaceAll("[.!?\\s]+$", "");
        for (Pattern empty : EMPTY) {
            if (empty.matcher(unpunctuated).find()) return true;
        }
        return false;
    }
}
