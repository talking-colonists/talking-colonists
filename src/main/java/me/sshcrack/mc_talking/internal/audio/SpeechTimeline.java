package me.sshcrack.mc_talking.internal.audio;

import com.google.gson.JsonObject;
import me.sshcrack.mc_talking.McTalking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Development diagnostics: one JSON log line whenever a citizen voice starts or stops streaming to
 * players, and one for every line a citizen was heard saying. {@code scripts/speech-timeline-report.py}
 * turns these into a timeline with overlaps, gaps and repeats, so playtests can be checked without
 * listening. Enabled with {@code -Dmc_talking.speechTimeline=true}; otherwise every call is a no-op.
 */
public final class SpeechTimeline {
    public static final String MARKER = "[SpeechTimeline] ";
    private static final boolean ENABLED = Boolean.getBoolean("mc_talking.speechTimeline");

    private SpeechTimeline() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    /** Who a stream speaks for: the entity (position, name) and a kind looked up when a segment starts. */
    public record Source(Entity speaker, @Nullable String label, Supplier<String> kind) {
        String name() {
            return label != null ? label : speaker.getDisplayName().getString();
        }
    }

    /** Tracks one stream's audible segments; call {@link #frame} for every frame handed to voice chat. */
    public static final class Tracker {
        private final Source source;
        private long segmentStart;
        private int frames;
        private String kind = "";

        public Tracker(Source source) {
            this.source = source;
        }

        /** {@code audible} is false when the stream had nothing to play (the segment ends). */
        public void frame(boolean audible) {
            long now = System.currentTimeMillis();
            if (audible) {
                if (frames == 0) {
                    segmentStart = now;
                    kind = safeKind();
                }
                frames++;
                return;
            }
            if (frames == 0) return;
            JsonObject line = base("segment");
            line.addProperty("kind", kind);
            line.addProperty("start", segmentStart);
            line.addProperty("end", now);
            line.addProperty("audioMs", frames * 20L);
            frames = 0;
            McTalking.LOGGER.info(MARKER + line);
        }

        private String safeKind() {
            try {
                return String.valueOf(source.kind().get());
            } catch (RuntimeException e) {
                return "UNKNOWN";
            }
        }

        private JsonObject base(String type) {
            return SpeechTimeline.base(type, source);
        }
    }

    public static @Nullable Tracker tracker(Entity speaker, @Nullable String label, Supplier<String> kind) {
        return ENABLED ? new Tracker(new Source(speaker, label, kind)) : null;
    }

    /** A line the citizen was heard saying (after its audio finished playing). */
    public static void said(Entity speaker, String kind, String text) {
        if (!ENABLED || text == null || text.isBlank()) return;
        JsonObject line = base("said", new Source(speaker, null, () -> kind));
        line.addProperty("kind", kind);
        line.addProperty("at", System.currentTimeMillis());
        line.addProperty("text", text.strip());
        McTalking.LOGGER.info(MARKER + line);
    }

    /**
     * A provider turn ended: how much audio arrived, how much the client dropped outside a turn,
     * how much the stream accepted or its turn gate rejected, and the transcript length.
     */
    public static void turn(Entity speaker, String kind, long receivedBytes, long droppedBytes, long acceptedBytes,
                            long rejectedBytes, int transcriptChars, boolean suppressed) {
        if (!ENABLED) return;
        JsonObject line = base("turn", new Source(speaker, null, () -> kind));
        line.addProperty("kind", kind);
        line.addProperty("at", System.currentTimeMillis());
        line.addProperty("receivedBytes", receivedBytes);
        line.addProperty("droppedBytes", droppedBytes);
        line.addProperty("acceptedBytes", acceptedBytes);
        line.addProperty("rejectedBytes", rejectedBytes);
        line.addProperty("transcriptChars", transcriptChars);
        line.addProperty("suppressed", suppressed);
        McTalking.LOGGER.info(MARKER + line);
    }

    /**
     * Where the players are, so the report counts two voices at once only where someone heard both. The
     * server calls it every couple of seconds.
     */
    public static void listeners(MinecraftServer server) {
        if (!ENABLED) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator()) continue;
            JsonObject line = new JsonObject();
            line.addProperty("type", "listener");
            line.addProperty("name", player.getGameProfile().getName());
            line.addProperty("x", Math.round(player.getX() * 10) / 10.0);
            line.addProperty("y", Math.round(player.getY() * 10) / 10.0);
            line.addProperty("z", Math.round(player.getZ() * 10) / 10.0);
            line.addProperty("at", System.currentTimeMillis());
            McTalking.LOGGER.info(MARKER + line);
        }
    }

    /** Something the report should show on the timeline, e.g. a scenario step or a started feature. */
    public static void mark(String what) {
        if (!ENABLED) return;
        JsonObject line = new JsonObject();
        line.addProperty("type", "mark");
        line.addProperty("at", System.currentTimeMillis());
        line.addProperty("what", what);
        McTalking.LOGGER.info(MARKER + line);
    }

    private static JsonObject base(String type, Source source) {
        JsonObject line = new JsonObject();
        line.addProperty("type", type);
        line.addProperty("speaker", source.name());
        line.addProperty("id", source.speaker().getUUID().toString());
        line.addProperty("x", Math.round(source.speaker().getX() * 10) / 10.0);
        line.addProperty("y", Math.round(source.speaker().getY() * 10) / 10.0);
        line.addProperty("z", Math.round(source.speaker().getZ() * 10) / 10.0);
        return line;
    }
}
