package me.sshcrack.mc_talking.internal.session;

import me.sshcrack.mc_talking.api.conversation.PlayerTextResult;
import me.sshcrack.mc_talking.api.conversation.PlayerTextResult.Status;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Typed player lines and addon context notes for direct player conversations (roadmap A4).
 * Checks that the caller owns the conversation, the length and the rate, then hands the text to
 * the session. Provider-independent so the rules are unit-tested.
 */
public final class PlayerTextInput {
    /** Lines or notes a player may send within {@link #WINDOW_NANOS}, each kind counted separately. */
    static final int MAX_PER_WINDOW = 5;
    static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** One live direct conversation, as seen by this module. */
    public interface Conversation {
        /** The player the conversation belongs to. */
        UUID playerId();

        /** Sends a player turn the citizen answers. */
        void sendPlayerTurn(String text);

        /** Sends a context note after the citizen's current turn. */
        void sendContextNote(String note);

        /** Records the typed line for memory extraction and utterance listeners. */
        void recordTypedLine(String playerName, String text);
    }

    @FunctionalInterface
    public interface Lookup {
        /** The citizen's current direct player conversation, or null. */
        @Nullable Conversation find(UUID citizenId);
    }

    private final Lookup lookup;
    private final LongSupplier nanoClock;
    private final Map<UUID, ArrayDeque<Long>> textTimes = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<Long>> noteTimes = new ConcurrentHashMap<>();

    public PlayerTextInput(Lookup lookup, LongSupplier nanoClock) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public PlayerTextResult sendText(UUID playerId, String playerName, UUID citizenId, String text) {
        String line = normalize(text);
        Conversation conversation = lookup.find(citizenId);
        if (conversation == null || !conversation.playerId().equals(playerId)) {
            return PlayerTextResult.rejected(Status.NOT_IN_CONVERSATION,
                    "The player is not in a conversation with this citizen");
        }
        PlayerTextResult rejected = check(conversation, line, textTimes);
        if (rejected != null) return rejected;
        conversation.recordTypedLine(playerName, line);
        conversation.sendPlayerTurn(line);
        return PlayerTextResult.delivered();
    }

    public PlayerTextResult addContext(UUID playerId, String playerName, UUID citizenId, String note) {
        String line = normalize(note);
        Conversation conversation = lookup.find(citizenId);
        if (conversation == null || !conversation.playerId().equals(playerId)) {
            return PlayerTextResult.rejected(Status.NOT_IN_CONVERSATION,
                    "The player is not in a conversation with this citizen");
        }
        PlayerTextResult rejected = check(conversation, line, noteTimes);
        if (rejected != null) return rejected;
        conversation.sendContextNote(contextFrame(playerName, line));
        return PlayerTextResult.delivered();
    }

    /** Forgets a player's rate-limit history, e.g. when they log out. */
    public void forget(UUID playerId) {
        textTimes.remove(playerId);
        noteTimes.remove(playerId);
    }

    /** Game events are framed so the model never takes them for the player's words. */
    static String contextFrame(String playerName, String note) {
        return "[Game event, not said by " + playerName + ": " + note + "]";
    }

    /** Trims, and turns line breaks and other control characters into single spaces. */
    static String normalize(String text) {
        Objects.requireNonNull(text, "text");
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                space = out.length() > 0;
                continue;
            }
            if (space) out.append(' ');
            space = false;
            out.append(c);
        }
        return out.toString();
    }

    private @Nullable PlayerTextResult check(Conversation conversation, String line,
                                             Map<UUID, ArrayDeque<Long>> history) {
        if (line.isEmpty()) return PlayerTextResult.rejected(Status.EMPTY, "Nothing to send");
        if (line.length() > PlayerTextResult.MAX_CHARS) {
            return PlayerTextResult.rejected(Status.TOO_LONG, "At most " + PlayerTextResult.MAX_CHARS + " characters");
        }
        long now = nanoClock.getAsLong();
        ArrayDeque<Long> times = history.computeIfAbsent(conversation.playerId(), ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_NANOS) times.removeFirst();
            if (times.size() >= MAX_PER_WINDOW) {
                return PlayerTextResult.rejected(Status.RATE_LIMITED, "Too many messages; wait a few seconds");
            }
            times.addLast(now);
        }
        return null;
    }
}
