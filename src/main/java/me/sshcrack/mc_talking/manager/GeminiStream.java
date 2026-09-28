package me.sshcrack.mc_talking.manager;

import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import me.sshcrack.mc_talking.internal.audio.PlaybackTurnGate;
import me.sshcrack.mc_talking.internal.audio.VoicechatAccess;
import me.sshcrack.mc_talking.util.AudioHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.UUID;
import java.util.function.Supplier;

import static me.sshcrack.mc_talking.McTalkingVoicechatPlugin.TARGET_SAMPLE_RATE;
import me.sshcrack.mc_talking.internal.audio.SpeechTimeline;

public class GeminiStream implements Supplier<short[]> {
    public static final int FRAME_SIZE_SAMPLES = 960;
    // Minimum buffer size for effective pitch shifting (about 100ms of audio).
    private static final int MIN_BUFFER_SIZE_FOR_PITCH = TARGET_SAMPLE_RATE / 10;
    // 960 samples at 48 kHz is 20ms, so 25 frames is about 500ms.
    private static final int MIN_FRAMES_BEFORE_PLAYBACK = 25;

    private final Queue<short[]> audioFrames = new ConcurrentLinkedQueue<>();
    private final PlaybackTurnGate turnGate = new PlaybackTurnGate();
    private final AudioChannel channel;
    @Nullable AudioPlayer player;
    private short[] remainingSamples = new short[0]; // For storing leftover samples
    private float pitchFactor = 1.0f;
    private boolean isPreBuffering = true;

    private int lastSampleRate = TARGET_SAMPLE_RATE; // Last processed sample rate

    // Single buffer for incoming audio data
    private final List<byte[]> incomingData = Collections.synchronizedList(new ArrayList<>());
    private int totalBufferedBytes = 0;

    private Runnable onPause;
    private OpusEncoder encoder;
    private @Nullable SpeechTimeline.Tracker timeline;

    public GeminiStream(AudioChannel channel) {
        this.channel = channel;
    }

    /** Development diagnostics: report audible segments of this stream (see {@code SpeechTimeline}). */
    public void setTimeline(@Nullable SpeechTimeline.Tracker timeline) {
        this.timeline = timeline;
    }

    public void setOnPause(Runnable onPause) {
        this.onPause = onPause;
    }

    public void beginTurn(UUID turnId) {
        turnGate.begin(turnId);
    }

    public boolean flushAudio(UUID turnId) {
        boolean draining = turnGate.beginDrain(turnId, () -> {
            // Seal the producer side before moving the tail into playback so a racing late
            // provider chunk cannot sneak in behind the final flush.
            processBufferedData(lastSampleRate, true);
        });
        // A turn without audio has nothing to play, so playback never reports it drained: it would
        // stay DRAINING and the session's next turn could not begin.
        if (draining && !hasPendingPlayback()) turnGate.completeDrainedTurn();
        return draining;
    }

    /**
     * Add audio data with pitch shifting applied
     *
     * @param data       Raw PCM audio data as byte array
     * @param sampleRate Sample rate of the audio data
     */
    public boolean addGeminiPcmWithPitch(UUID turnId, byte[] data, int sampleRate) {
        final boolean[] started = {false};
        acceptedBytes += data.length;
        boolean accepted = turnGate.accept(turnId, () -> {
            lastSampleRate = sampleRate;

            if (data.length > 0) {
                synchronized (incomingData) {
                    incomingData.add(data);
                    totalBufferedBytes += data.length;
                }
            }

            int bufferedBytes = totalBufferedBytes;
            if (bufferedBytes >= MIN_BUFFER_SIZE_FOR_PITCH * 2) {
                started[0] = processBufferedData(sampleRate, false);
            }
        });
        if (!accepted) {
            acceptedBytes -= data.length;
            rejectedBytes += data.length;
        }
        return accepted && started[0];
    }

    private long acceptedBytes;
    private long rejectedBytes;

    /** Diagnostics: audio bytes {accepted, rejected by the turn gate} since the last call. */
    public long[] takeAudioCounters() {
        long[] counters = {acceptedBytes, rejectedBytes};
        acceptedBytes = 0;
        rejectedBytes = 0;
        return counters;
    }

    /**
     * Process the buffered data, applying pitch shifting and preparing for playback
     *
     * @param sampleRate Sample rate of the audio data
     */
    private boolean processBufferedData(int sampleRate, boolean flushed) {
        byte[] combined;

        synchronized (incomingData) {
            if (incomingData.isEmpty()) {
                return flushed && remainingSamples.length > 0
                        && processAudioSamples(new short[0], true);
            }

            // Combine all buffered chunks into a single array
            int totalBytes = totalBufferedBytes;
            combined = new byte[totalBytes];
            int offset = 0;
            for (byte[] chunk : incomingData) {
                System.arraycopy(chunk, 0, combined, offset, chunk.length);
                offset += chunk.length;
            }

            incomingData.clear();
            totalBufferedBytes = 0;
        }

        short[] samples = VoicechatAccess.require().getAudioConverter().bytesToShorts(combined);

        samples = AudioHelper.changePitch(samples, sampleRate, pitchFactor);
        // Apply sample rate conversion if needed
        if (sampleRate != TARGET_SAMPLE_RATE) {
            samples = AudioHelper.resampleAudio(samples, sampleRate, TARGET_SAMPLE_RATE);
        }

        // Process the audio samples
        return processAudioSamples(samples, flushed);
    }

    /**
     * Process audio samples and prepare them for playback
     *
     * @param samples Audio samples to process
     */
    private boolean processAudioSamples(short[] samples, boolean flushed) {
        // Combine with any remaining samples from previous calls
        if (remainingSamples.length > 0) {
            short[] combined = new short[remainingSamples.length + samples.length];
            System.arraycopy(remainingSamples, 0, combined, 0, remainingSamples.length);
            System.arraycopy(samples, 0, combined, remainingSamples.length, samples.length);
            samples = combined;
            remainingSamples = new short[0];
        }

        // Split into frames of FRAME_SIZE_SAMPLES
        int frameCount = samples.length / FRAME_SIZE_SAMPLES;
        int remainingCount = samples.length % FRAME_SIZE_SAMPLES;

        for (int i = 0; i < frameCount; i++) {
            short[] frame = new short[FRAME_SIZE_SAMPLES];
            System.arraycopy(samples, i * FRAME_SIZE_SAMPLES, frame, 0, FRAME_SIZE_SAMPLES);
            audioFrames.add(frame);
        }

        // Store any remaining samples for next time. On a final flush, pad the
        // last partial frame with silence so the tail of the sentence is not lost.
        if (remainingCount > 0) {
            if (flushed) {
                short[] frame = new short[FRAME_SIZE_SAMPLES];
                System.arraycopy(samples, frameCount * FRAME_SIZE_SAMPLES, frame, 0, remainingCount);
                audioFrames.add(frame);
                remainingSamples = new short[0];
            } else {
                remainingSamples = new short[remainingCount];
                System.arraycopy(samples, frameCount * FRAME_SIZE_SAMPLES, remainingSamples, 0, remainingCount);
            }
        }

        // Initialize or restart the player when needed
        if (player == null || player.isStopped()) {
            if (player != null)
                player.stopPlaying();

            // Only start playing when we have enough buffered frames
            if (!audioFrames.isEmpty() && (!isPreBuffering || audioFrames.size() >= MIN_FRAMES_BEFORE_PLAYBACK || flushed)) {
                var vcApi = VoicechatAccess.require();
                OpusEncoder createdEncoder = vcApi.createEncoder(OpusEncoderMode.AUDIO);
                AudioPlayer createdPlayer = vcApi.createAudioPlayer(channel, createdEncoder, this);
                encoder = createdEncoder;
                player = createdPlayer;
                createdPlayer.setOnStopped(() -> releaseStoppedPlayer(createdPlayer, createdEncoder));

                isPreBuffering = false;
                createdPlayer.startPlaying();
                return true;
            }
        }

        return false;
    }

    /**
     * Drops audio that has been generated but not yet consumed by the voice-chat player.
     * The currently playing frame is allowed to finish; future queued frames and buffered
     * producer chunks are discarded. This is used when a Gemini session token is invalidated
     * and the same prompt must be replayed, preventing the stale answer from being heard twice.
     *
     * @return approximate number of queued chunks/frames discarded
     */
    public int discardPendingAudio() {
        int dropped = audioFrames.size();
        audioFrames.clear();
        remainingSamples = new short[0];
        synchronized (incomingData) {
            dropped += incomingData.size();
            incomingData.clear();
            totalBufferedBytes = 0;
        }
        isPreBuffering = true;
        return dropped;
    }

    /** Immediately cancels one exact turn, stopping playback and rejecting all late chunks. */
    public boolean cancelTurn(UUID turnId) {
        return turnGate.cancel(turnId, this::stopAndDiscard);
    }

    public void stop() {
        UUID turnId = turnGate.turnId();
        if (turnId != null && turnGate.cancel(turnId, this::stopAndDiscard)) return;
        stopAndDiscard();
    }

    private void stopAndDiscard() {
        var currentTimeline = timeline;
        if (currentTimeline != null) currentTimeline.frame(false); // a stopped voice ends its segment too
        audioFrames.clear();
        remainingSamples = new short[0];
        synchronized (incomingData) {
            incomingData.clear();
            totalBufferedBytes = 0;
        }
        isPreBuffering = true;
        AudioPlayer currentPlayer = player;
        OpusEncoder currentEncoder = encoder;
        player = null;
        encoder = null;
        if (currentPlayer != null) {
            // Voice-chat stop is asynchronous; do not block the server/barge-in path waiting for
            // the player thread. Its on-stopped hook owns encoder cleanup.
            currentPlayer.stopPlaying();
        } else {
            closeEncoder(currentEncoder);
        }
    }

    private synchronized void releaseStoppedPlayer(AudioPlayer stoppedPlayer, OpusEncoder stoppedEncoder) {
        if (player == stoppedPlayer) player = null;
        if (encoder == stoppedEncoder) encoder = null;
        closeEncoder(stoppedEncoder);
    }

    private static void closeEncoder(@Nullable OpusEncoder encoder) {
        if (encoder == null) return;
        try { encoder.close(); } catch (Exception ignored) { }
    }

    public void close() {
        stop();
    }

    @Override
    public short[] get() {
        short[] frame = audioFrames.poll();
        var currentTimeline = timeline;
        if (currentTimeline != null) currentTimeline.frame(frame != null);
        if (frame != null) {
            return frame;
        }

        // Queue is empty — pause playback and notify the client. A draining turn becomes
        // terminal here; cancelled turns remain cancelled and cannot be resurrected.
        isPreBuffering = true;
        if (remainingSamples.length == 0 && incomingData.isEmpty() && totalBufferedBytes == 0) {
            turnGate.completeDrainedTurn();
        }
        if (onPause != null) {
            onPause.run();
        }
        return null;
    }

    /**
     * Returns whether generated audio is still buffered or actively playing.
     * Callers use this after the producer has finished and {@link #flushAudio(UUID)}
     * has been called, so no new frames are expected to arrive.
     */
    public boolean hasPendingPlayback() {
        if (!audioFrames.isEmpty() || remainingSamples.length > 0) return true;
        synchronized (incomingData) {
            if (!incomingData.isEmpty() || totalBufferedBytes > 0) return true;
        }
        AudioPlayer currentPlayer = player;
        return currentPlayer != null && !currentPlayer.isStopped();
    }

    public void setPitch(float pitch) {
        this.pitchFactor = pitch;
    }
}
