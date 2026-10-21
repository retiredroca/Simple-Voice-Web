package com.retiredroca.svcweb.voice;

import com.retiredroca.svcweb.SimpleVoiceWeb;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Mixes the packets one web listener can hear into a stereo Opus stream.
 *
 * <p>Simple Voice Chat exposes a mono Opus codec, not a stereo one. The mixer therefore encodes the
 * left and right channels with two independent encoders and the browser joins the two mono streams.
 * No third-party codec is needed.
 */
public final class VoiceMixer implements AutoCloseable {

    // Simple Voice Chat's codec is 48 kHz mono; 960 samples is one 20 ms frame.
    private static final int FRAME = 960;
    private static final long FRAME_NANOS = 20_000_000L;
    private static final int MAX_QUEUE = 3;
    private static final int MAX_DELAY = 32;
    private static final float CENTER_GAIN = 0.70710677F;

    private final AudioBridge audio;
    private final Consumer<byte[]> output;
    private final Map<UUID, SpeakerQueue> queues = new ConcurrentHashMap<>();
    private final Map<UUID, Float> volumes = new ConcurrentHashMap<>();
    private final Map<UUID, OpusDecoder> decoders = new HashMap<>();
    private final Map<UUID, SpatialState> spatial = new HashMap<>();

    private volatile boolean running = true;
    private Thread thread;
    private OpusEncoder leftEncoder;
    private OpusEncoder rightEncoder;

    public VoiceMixer(AudioBridge audio, Consumer<byte[]> output) {
        this.audio = audio;
        this.output = output;
    }

    public synchronized void start() {
        if (thread != null || !running) {
            return;
        }
        thread = new Thread(this::run, "SimpleVoiceWeb-Mixer");
        thread.setDaemon(true);
        thread.start();
    }

    /** Called on the voice chat thread; only queues work, never blocks on a codec. */
    public void offer(AudioBridge.AudioFrame frame) {
        if (!running || frame == null || frame.speaker() == null) {
            return;
        }
        if (frame.end()) {
            SpeakerQueue queue = queues.get(frame.speaker());
            if (queue != null) {
                queue.clear();
            }
            return;
        }
        queues.computeIfAbsent(frame.speaker(), id -> new SpeakerQueue()).offer(frame);
    }

    /** Per-listener, per-speaker volume from the browser. */
    public void setVolume(UUID speaker, float volume) {
        if (speaker == null) {
            return;
        }
        volumes.put(speaker, Math.max(0F, Math.min(2F, volume)));
    }

    private void run() {
        long next = System.nanoTime();
        while (running) {
            next += FRAME_NANOS;
            long wait = next - System.nanoTime();
            if (wait > 0L) {
                LockSupport.parkNanos(wait);
            } else if (wait < -FRAME_NANOS * 5L) {
                next = System.nanoTime();
            }
            if (!running) {
                return;
            }
            try {
                mix();
            } catch (Throwable t) {
                SimpleVoiceWeb.LOGGER.warn("Web audio mixer failed: {}", t.getMessage());
            }
        }
    }

    private void mix() {
        if (leftEncoder == null) {
            leftEncoder = createEncoder();
        }
        if (rightEncoder == null) {
            rightEncoder = createEncoder();
        }
        if (leftEncoder == null || rightEncoder == null) {
            return;
        }

        float[] left = new float[FRAME];
        float[] right = new float[FRAME];
        boolean heard = false;

        for (Map.Entry<UUID, SpeakerQueue> entry : queues.entrySet()) {
            AudioBridge.AudioFrame frame = entry.getValue().poll();
            if (frame == null) {
                continue;
            }
            heard = true;
            short[] pcm = decode(entry.getKey(), frame);
            if (pcm == null || pcm.length == 0) {
                continue;
            }
            if (frame.group()) {
                mixCentered(left, right, pcm, frame, entry.getKey());
            } else {
                mixSpatial(left, right, pcm, frame, entry.getKey());
            }
        }

        if (!heard) {
            return;
        }

        byte[] leftOpus;
        byte[] rightOpus;
        try {
            leftOpus = leftEncoder.encode(toPcm(left));
            rightOpus = rightEncoder.encode(toPcm(right));
        } catch (RuntimeException e) {
            SimpleVoiceWeb.LOGGER.warn("Web audio encoding failed: {}", e.getMessage());
            return;
        }
        if (leftOpus == null || leftOpus.length == 0 || rightOpus == null
                || rightOpus.length == 0) {
            return;
        }
        output.accept(packet(leftOpus, rightOpus));
    }

    private short[] decode(UUID speaker, AudioBridge.AudioFrame frame) {
        OpusDecoder decoder = decoders.get(speaker);
        if (decoder == null) {
            decoder = createDecoder();
            if (decoder == null) {
                return null;
            }
            decoders.put(speaker, decoder);
        }
        try {
            return decoder.decode(frame.opus());
        } catch (RuntimeException e) {
            SimpleVoiceWeb.LOGGER.warn("Web audio decoding failed: {}", e.getMessage());
            return null;
        }
    }

    private OpusDecoder createDecoder() {
        try {
            return audio.createDecoder();
        } catch (RuntimeException e) {
            SimpleVoiceWeb.LOGGER.warn("Could not create web Opus decoder: {}", e.getMessage());
            return null;
        }
    }

    private OpusEncoder createEncoder() {
        try {
            return audio.createEncoder();
        } catch (RuntimeException e) {
            SimpleVoiceWeb.LOGGER.warn("Could not create web Opus encoder: {}", e.getMessage());
            return null;
        }
    }

    private void mixCentered(float[] left, float[] right, short[] pcm,
                             AudioBridge.AudioFrame frame, UUID speaker) {
        float gain = frame.gain() * volumes.getOrDefault(speaker, 1F) * CENTER_GAIN;
        if (gain <= 0F) {
            return;
        }
        int count = Math.min(FRAME, pcm.length);
        for (int i = 0; i < count; i++) {
            float sample = pcm[i] / 32768F * gain;
            left[i] += sample;
            right[i] += sample;
        }
    }

    private void mixSpatial(float[] left, float[] right, short[] pcm,
                            AudioBridge.AudioFrame frame, UUID speaker) {
        float gain = frame.gain() * volumes.getOrDefault(speaker, 1F);
        if (gain <= 0F) {
            return;
        }
        SpatialState state = spatial.computeIfAbsent(speaker, id -> new SpatialState());
        float pan = Math.max(-1F, Math.min(1F, frame.pan()));
        float elevation = Math.max(-1F, Math.min(1F, frame.elevation()));
        int delay = Math.round(Math.abs(pan) * MAX_DELAY);
        double angle = (pan + 1F) * Math.PI / 4D;
        float leftGain = (float) Math.cos(angle);
        float rightGain = (float) Math.sin(angle);

        int count = Math.min(FRAME, pcm.length);
        float[] mono = new float[count];
        for (int i = 0; i < count; i++) {
            float sample = pcm[i] / 32768F * gain;

            // A gentle high-frequency shelf makes an elevated speaker brighter and a speaker
            // below duller, without an HRTF.
            float low = state.elevation + 0.12F * (sample - state.elevation);
            state.elevation = low;
            sample = low + (sample - low) * (1F + 0.35F * elevation);

            // Rear sources lose their high frequencies, which is the strongest simple front/back
            // cue available without measuring a head.
            if (frame.rear()) {
                state.rear += 0.18F * (sample - state.rear);
                sample = state.rear;
            }
            mono[i] = sample;
        }

        for (int i = 0; i < count; i++) {
            float leftSample = mono[i];
            float rightSample = mono[i];
            if (delay > 0) {
                if (pan > 0F) {
                    leftSample = delayed(state.history, mono, i, delay);
                } else if (pan < 0F) {
                    rightSample = delayed(state.history, mono, i, delay);
                }
            }
            left[i] += leftSample * leftGain;
            right[i] += rightSample * rightGain;
        }
        updateHistory(state.history, mono);
    }

    private static float delayed(float[] history, float[] current, int index, int delay) {
        if (index < delay) {
            return history[MAX_DELAY - delay + index];
        }
        return current[index - delay];
    }

    private static void updateHistory(float[] history, float[] mono) {
        if (mono.length >= MAX_DELAY) {
            System.arraycopy(mono, mono.length - MAX_DELAY, history, 0, MAX_DELAY);
            return;
        }
        System.arraycopy(history, mono.length, history, 0, MAX_DELAY - mono.length);
        System.arraycopy(mono, 0, history, MAX_DELAY - mono.length, mono.length);
    }

    private static short[] toPcm(float[] samples) {
        short[] pcm = new short[FRAME];
        for (int i = 0; i < FRAME; i++) {
            float sample = Math.max(-1F, Math.min(1F, samples[i]));
            pcm[i] = (short) Math.round(sample * 32767F);
        }
        return pcm;
    }

    private static byte[] packet(byte[] left, byte[] right) {
        ByteBuffer packet = ByteBuffer.allocate(5 + left.length + right.length)
                .order(ByteOrder.BIG_ENDIAN);
        packet.put((byte) 0x03);
        packet.putShort((short) left.length);
        packet.putShort((short) right.length);
        packet.put(left);
        packet.put(right);
        return packet.array();
    }

    @Override
    public synchronized void close() {
        running = false;
        Thread current = thread;
        thread = null;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
            try {
                current.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        for (OpusDecoder decoder : decoders.values()) {
            try {
                decoder.close();
            } catch (RuntimeException ignored) {
                // The codec is being discarded; a close failure must not stop the socket closing.
            }
        }
        decoders.clear();
        if (leftEncoder != null) {
            try {
                leftEncoder.close();
            } catch (RuntimeException ignored) {
                // See above.
            }
            leftEncoder = null;
        }
        if (rightEncoder != null) {
            try {
                rightEncoder.close();
            } catch (RuntimeException ignored) {
                // See above.
            }
            rightEncoder = null;
        }
        queues.clear();
        spatial.clear();
    }

    private static final class SpeakerQueue {
        private final Deque<AudioBridge.AudioFrame> frames = new ArrayDeque<>();

        synchronized void offer(AudioBridge.AudioFrame frame) {
            while (frames.size() >= MAX_QUEUE) {
                frames.removeFirst();
            }
            frames.addLast(frame);
        }

        synchronized AudioBridge.AudioFrame poll() {
            return frames.pollFirst();
        }

        synchronized void clear() {
            frames.clear();
        }
    }

    private static final class SpatialState {
        private final float[] history = new float[MAX_DELAY];
        private float elevation;
        private float rear;
    }
}
