package me.sshcrack.mc_talking.broadcast;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What players said to each citizen in the last few minutes (typed or transcribed), so a tool can tell
 * the player's own words from something the model made up. Pure, in memory, forgotten after a while.
 */
public final class PlayerWords {
    static final long KEEP_MILLIS = 5 * 60 * 1000L;
    private static final int MAX_LINES = 12;
    private static final Map<UUID, Deque<Line>> LINES = new ConcurrentHashMap<>();
    /** What the player is saying right now: speech is transcribed in pieces, finished when the turn is. */
    private static final Map<UUID, StringBuilder> SAYING = new ConcurrentHashMap<>();

    private record Line(long at, String text) {
    }

    private PlayerWords() {
    }

    /** Remembers that a player said {@code text} to the citizen; it completes what {@link #hearing} collected. */
    public static void record(UUID citizenId, String text, long now) {
        SAYING.remove(citizenId);
        if (text == null || text.isBlank()) return;
        Deque<Line> lines = LINES.computeIfAbsent(citizenId, id -> new ArrayDeque<>());
        synchronized (lines) {
            lines.addLast(new Line(now, text.strip()));
            while (lines.size() > MAX_LINES) lines.removeFirst();
        }
    }

    /** A piece of what a player is saying to the citizen right now (speech transcription). */
    public static void hearing(UUID citizenId, String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        StringBuilder saying = SAYING.computeIfAbsent(citizenId, id -> new StringBuilder());
        synchronized (saying) {
            saying.append(chunk);
        }
    }

    /** Everything players said to the citizen in the last few minutes, oldest first; empty if nothing. */
    public static String recent(UUID citizenId, long now) {
        Deque<Line> lines = LINES.getOrDefault(citizenId, new ArrayDeque<>());
        StringBuilder text = new StringBuilder();
        synchronized (lines) {
            lines.removeIf(line -> now - line.at() > KEEP_MILLIS);
            for (Line line : lines) text.append(line.text()).append('\n');
        }
        StringBuilder saying = SAYING.get(citizenId);
        if (saying != null) {
            synchronized (saying) {
                text.append(saying.toString().strip()).append('\n');
            }
        }
        return text.toString().strip();
    }

    public static void clear() {
        LINES.clear();
        SAYING.clear();
    }
}
