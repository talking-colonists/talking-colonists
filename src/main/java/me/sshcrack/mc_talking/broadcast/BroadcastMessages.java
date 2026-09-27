package me.sshcrack.mc_talking.broadcast;

import java.util.List;
import java.util.Locale;

/** Checks on what a citizen is asked to announce to the whole colony. */
public final class BroadcastMessages {
    /** Phrases of a template the model filled in instead of the player's words. */
    private static final List<String> PLACEHOLDERS = List.of(
            "change this message", "whatever broadcast", "whatever you want", "your message", "message here",
            "insert ", "placeholder", "enter message", "<message", "[message", "{message", "todo");

    private BroadcastMessages() {
    }

    /** True when {@code message} is empty, too short to say anything, or a template rather than the player's words. */
    public static boolean isPlaceholder(String message) {
        String text = message.strip().toLowerCase(Locale.ROOT);
        if (text.length() < 4) return true;
        for (String placeholder : PLACEHOLDERS) {
            if (text.contains(placeholder)) return true;
        }
        return false;
    }
}
