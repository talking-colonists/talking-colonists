package me.sshcrack.mc_talking.pregen;

import me.sshcrack.mc_talking.util.AiStatusHelper;
import me.sshcrack.mc_talking.network.AiStatus;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import me.sshcrack.gemini_live_lib.misc.GeminiTTS.AudioChunk;
import me.sshcrack.mc_talking.ConversationManager;
import me.sshcrack.mc_talking.McTalking;
import me.sshcrack.mc_talking.api.conversation.ConversationKind;
import me.sshcrack.mc_talking.internal.audio.PcmSpeechDetector;
import me.sshcrack.mc_talking.internal.audio.VoicechatAccess;
import me.sshcrack.mc_talking.internal.session.AmbientSpeechBudget;
import me.sshcrack.mc_talking.internal.session.SpeechFloor;
import me.sshcrack.mc_talking.manager.GeminiStream;
import me.sshcrack.mc_talking.manager.audio.AudioProvider;
import me.sshcrack.mc_talking.manager.audio.CitizenEntityAudioProvider;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import me.sshcrack.mc_talking.internal.audio.SpeechTimeline;

/** Playback and interruption owner for cached/pregenerated citizen speech. */
public final class PregenerationPlayback {
    private static final Map<UUID, PlaybackEntry> ACTIVE_PREGENERATED_PLAYBACK = new ConcurrentHashMap<>();
    private static final Map<UUID, VoiceBurst> PLAYER_VOICE_BURSTS = new ConcurrentHashMap<>();

    private static final int BARGE_IN_PACKETS = 6;
    private static final long VOICE_BURST_RESET_MS = 900L;
    private static final long EARLY_PLAYBACK_PROTECTION_MS = 2_000L;
    private static final double BARGE_IN_RADIUS_SQR = 7.0 * 7.0;

    private PregenerationPlayback() {
    }

    private static final class PlaybackEntry {
        final AbstractEntityCitizen citizen;
        final UUID turnId = UUID.randomUUID();
        final long startedAtMs = System.currentTimeMillis();
        final AtomicBoolean cleaned = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile GeminiStream stream;
        volatile Runnable cleanup;

        PlaybackEntry(AbstractEntityCitizen citizen) {
            this.citizen = citizen;
        }

        void attach(GeminiStream stream, Runnable cleanup) {
            this.stream = stream;
            this.cleanup = cleanup;
            if (cancelled.get()) {
                stop("interrupted while starting");
            }
        }

        void stop(String reason) {
            cancelled.set(true);
            GeminiStream current = stream;
            if (current != null) {
                try {
                    current.cancelTurn(turnId);
                } catch (Throwable ignored) {
                }
                try {
                    current.close();
                } catch (Throwable ignored) {
                }
            }
            Runnable cleanupAction = cleanup;
            if (cleanupAction != null) cleanupAction.run();
            McTalking.LOGGER.debug("Interrupted pregenerated playback for {} ({})", citizen.getUUID(), reason);
        }
    }

    private static final class VoiceBurst {
        int packets;
        long lastPacketMs;
        long startedAtMs;
        OpusDecoder decoder;
    }

    public static boolean hasActivePlayback() {
        return !ACTIVE_PREGENERATED_PLAYBACK.isEmpty();
    }

    /** Interrupts a cached clip for one citizen, if one is active. */
    public static boolean interruptCitizen(UUID citizenId) {
        PlaybackEntry entry = ACTIVE_PREGENERATED_PLAYBACK.get(citizenId);
        if (entry == null) return false;
        entry.stop("citizen taken over by another conversation");
        return true;
    }

    /**
     * Feeds voice activity from Simple Voice Chat into pregenerated-playback barge-in.
     * A short debounce prevents coughs/noise from killing a clip, and a clip gets two
     * seconds to begin before deliberate speech can interrupt it.
     */
    public static void onPlayerOpusPacket(ServerPlayer player, byte[] opusData) {
        if (ACTIVE_PREGENERATED_PLAYBACK.isEmpty()) return;
        if (player == null || opusData == null || opusData.length == 0) return;
        var vcApi = VoicechatAccess.get();
        if (vcApi == null) return;
        VoiceBurst burst = PLAYER_VOICE_BURSTS.computeIfAbsent(player.getUUID(), ignored -> new VoiceBurst());
        short[] decoded;
        synchronized (burst) {
            if (burst.decoder == null || burst.decoder.isClosed()) burst.decoder = vcApi.createDecoder();
            if (burst.decoder == null) return;
            try {
                decoded = burst.decoder.decode(opusData);
            } catch (RuntimeException error) {
                return;
            }
        }
        onPlayerVoicePacket(player, PcmSpeechDetector.isSpeechCandidate(decoded));
    }

    public static void onPlayerVoicePacket(ServerPlayer player, boolean hasVoiceActivity) {
        if (player == null || !hasVoiceActivity) return;

        long now = System.currentTimeMillis();
        VoiceBurst burst = PLAYER_VOICE_BURSTS.computeIfAbsent(player.getUUID(), ignored -> new VoiceBurst());
        synchronized (burst) {
            if (now - burst.lastPacketMs > VOICE_BURST_RESET_MS) {
                burst.packets = 0;
                burst.startedAtMs = now;
            }
            burst.packets++;
            burst.lastPacketMs = now;
            if (burst.packets < BARGE_IN_PACKETS) return;
            interruptNearby(player, burst.startedAtMs, now);
        }
    }

    private static void interruptNearby(ServerPlayer player, long burstStartedAtMs, long now) {
        for (PlaybackEntry entry : ACTIVE_PREGENERATED_PLAYBACK.values()) {
            AbstractEntityCitizen citizen = entry.citizen;
            try {
                if (now - entry.startedAtMs < EARLY_PLAYBACK_PROTECTION_MS) continue;
                // If the clip appeared while the player was already talking, do not
                // instantly strangle it; barge-in is for speech that starts over a clip.
                if (entry.startedAtMs >= burstStartedAtMs - 300L) continue;
                if (citizen.isRemoved() || citizen.level() != player.level()) continue;
                if (player.distanceToSqr(citizen) <= BARGE_IN_RADIUS_SQR) {
                    entry.stop("nearby player barged in");
                }
            } catch (Throwable ignored) {
                entry.stop("invalid playback participant");
            }
        }
    }

    public static void onPlayerDisconnected(UUID playerId) {
        VoiceBurst burst = PLAYER_VOICE_BURSTS.remove(playerId);
        if (burst == null) return;
        synchronized (burst) {
            closeDecoder(burst.decoder);
            burst.decoder = null;
        }
    }

    public static boolean playAudioIfPossible(AbstractEntityCitizen citizen, AudioChunk audioData) {
        if (!ConversationManager.canCitizenSpeak(citizen, ConversationKind.PREGENERATED)) return false;
        if (audioData == null || audioData.audioBytes().length == 0) return false;

        UUID citizenId = citizen.getUUID();
        PlaybackEntry entry = new PlaybackEntry(citizen);
        if (ACTIVE_PREGENERATED_PLAYBACK.putIfAbsent(citizenId, entry) != null) return false;
        ConversationManager.CoreActivityReservation activity = ConversationManager.reserveCoreActivity(
                citizen,
                3,
                TimeUnit.MINUTES,
                () -> entry.stop("playback lifecycle deadline exceeded"),
                () -> entry.stop("higher-priority conversation started"));
        if (activity == null) {
            ACTIVE_PREGENERATED_PLAYBACK.remove(citizenId, entry);
            return false;
        }

        if (!AmbientSpeechBudget.trySpend(citizen)) {
            ACTIVE_PREGENERATED_PLAYBACK.remove(citizenId, entry);
            activity.close();
            return false;
        }
        // Held synchronously so other clips checked in the same tick wait for this one.
        SpeechFloor.hold(entry, ConversationKind.PREGENERATED, List.of(citizen));

        AtomicBoolean cleanedUp = entry.cleaned;
        Runnable cleanup = () -> {
            if (!cleanedUp.compareAndSet(false, true)) return;
            ACTIVE_PREGENERATED_PLAYBACK.remove(citizenId, entry);
            SpeechFloor.release(entry);
            activity.close();
            // Back to idle unless something else took the citizen over meanwhile (it sets its own status).
            AiStatusHelper.runOnServerThread(citizen, () -> {
                // A played clip counts as speaking: no mumble or greeting from the same citizen right after.
                ConversationManager.recordCooldown(citizen);
                if (!ConversationManager.isCitizenBusy(citizen)) {
                    AiStatusHelper.setAiStatusOnServerThread(citizen, AiStatus.NONE);
                }
            });
        };
        entry.cleanup = cleanup;

        try {
            McTalking.LOGGER.info("Playing back pregenerated audio for {}", citizen.getCitizenData().getName());
            AudioProvider audioProvider = new CitizenEntityAudioProvider(citizen, null);
            AudioChannel channel = audioProvider.createChannel();
            if (channel == null) {
                cleanup.run();
                return false;
            }

            GeminiStream stream = new GeminiStream(channel);
            stream.setTimeline(SpeechTimeline.tracker(citizen, null,
                    () -> "PREGENERATED"));
            stream.beginTurn(entry.turnId);
            entry.attach(stream, cleanup);
            if (entry.cancelled.get()) return false;

            var isFemale = citizen.getCitizenData().isFemale();
            var isChild = citizen.getCitizenData().isChild();
            if (isChild && !isFemale) stream.setPitch(1.2f);

            stream.setOnPause(() -> {
                try {
                    stream.close();
                } finally {
                    cleanup.run();
                }
            });
            stream.addGeminiPcmWithPitch(entry.turnId, audioData.audioBytes(), audioData.sampleRate());
            stream.flushAudio(entry.turnId);
            // Clips play outside any provider session, so nothing else shows the speaking animation.
            AiStatusHelper.setAiStatusSynced(citizen, AiStatus.TALKING);
            return true;
        } catch (RuntimeException e) {
            entry.stop("playback startup failed");
            cleanup.run();
            throw e;
        }
    }

    /** Clears transient voice-burst state on server shutdown. */
    public static void cleanup() {
        for (PlaybackEntry entry : ACTIVE_PREGENERATED_PLAYBACK.values()) {
            entry.stop("server shutdown");
        }
        ACTIVE_PREGENERATED_PLAYBACK.clear();
        SpeechFloor.clear();
        for (VoiceBurst burst : PLAYER_VOICE_BURSTS.values()) {
            synchronized (burst) {
                closeDecoder(burst.decoder);
                burst.decoder = null;
            }
        }
        PLAYER_VOICE_BURSTS.clear();
    }

    private static void closeDecoder(OpusDecoder decoder) {
        if (decoder == null || decoder.isClosed()) return;
        try { decoder.close(); } catch (Exception ignored) { }
    }
}
