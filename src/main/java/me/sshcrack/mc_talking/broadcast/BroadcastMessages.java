package me.sshcrack.mc_talking.broadcast;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    /** Common words that say nothing about what an announcement is about. */
    private static final Set<String> FILLER = Set.of(
            "the", "and", "for", "that", "this", "with", "you", "your", "our", "are", "was", "will", "have", "has",
            "all", "everyone", "everybody", "colony", "please", "tell", "announce", "announcement", "know", "let",
            "wants", "want", "about", "from", "they", "their", "there", "who", "what", "can", "could", "would",
            "should", "just", "also", "message", "hear", "attention", "citizens", "folks");
    /** Share of an announcement's own words that must come from what the player said. */
    static final double MIN_SHARE_FROM_PLAYER = 0.5;

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

    /**
     * Whether {@code message} is made of what the player said ({@code playerWords}), not made up: at least
     * half of its meaningful words (or their stems) occur there. A paraphrase keeps its key words; an
     * invented announcement ("the colony is running low on supplies") does not.
     */
    public static boolean isFromPlayer(String message, String playerWords) {
        Set<String> said = stems(playerWords);
        Set<String> meant = stems(message);
        if (meant.isEmpty()) return false;
        long found = meant.stream().filter(said::contains).count();
        return found >= Math.ceil(meant.size() * MIN_SHARE_FROM_PLAYER);
    }

    /** Lower-case word stems (first five letters) of the meaningful words. */
    private static Set<String> stems(String text) {
        Set<String> stems = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() < 3 || FILLER.contains(word)) continue;
            stems.add(word.length() > 5 ? word.substring(0, 5) : word);
        }
        return stems;
    }
}
