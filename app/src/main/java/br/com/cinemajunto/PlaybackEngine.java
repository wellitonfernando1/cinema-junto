package br.com.cinemajunto;

import android.media.*;
import android.os.*;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/** Bounded receiver buffer. The audio actually played by the device is the video clock. */
final class PlaybackEngine implements AutoCloseable {
    static final int RATE = 44100;
    static final long PRELOAD_US = 3000000, MAX_BACKLOG_US = 12000000;
    interface Listener {
        void buffering(boolean value);
        void requestKeyFrame();
        void error(String message);
    }
    final Thread worker;
    final FilmView view;
    final Listener listener;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ArrayBlockingQueue<StreamPacket.Packet> incoming = new ArrayBlockingQueue<>(1024);
    final ArrayDeque<StreamPacket.Packet> audio = new ArrayDeque<>(), video = new ArrayDeque<>();
    volatile boolean running = true, buffering = true;
    volatile boolean filmMuted;
    private boolean appliedMute;
    volatile long renderedFrames, lastRenderedTimestampUs;
    private volatile long currentClockUs;
    MediaCodec decoder;
    AudioTrack track;
    StreamPacket.VideoConfig config;
    byte[] configBytes;
    int surfaceGeneration, outputIndex = -1;
    final MediaCodec.BufferInfo output = new MediaCodec.BufferInfo();
    StreamPacket.Packet pcm;
    int pcmOffset;
    long audioOriginUs = Long.MIN_VALUE, writtenFrames, firstVideoUs, lastKeyRequestMs;
    int audioAheadFrames;
    boolean waitingForKeyFrame = true, started;
    final AudioTimestamp audioTimestamp = new AudioTimestamp();
    long timestampPollNs, timestampFrame, timestampNs, observedHead = -1, observedHeadNs;
    boolean timestampValid;
    volatile long droppedVideoFrames, scheduledVideoFrames;
    volatile long lastScheduledNs, maxScheduleSpacingErrorUs;
    long previousScheduledPtsUs = Long.MIN_VALUE;

    PlaybackEngine(FilmView view, Listener listener) {
        this.view = view; this.listener = listener;
        worker = new Thread(this::loop, "CinemaReproducao"); worker.start();
    }
    void offer(byte[] data) {
        if (!running) return;
        try {
            StreamPacket.Packet packet = StreamPacket.unpack(data);
            if (packet.type != StreamPacket.PCM && packet.type != StreamPacket.AVC_CONFIG && packet.type != StreamPacket.AVC_FRAME) return;
            if (packet.type == StreamPacket.PCM && (packet.payload.length == 0 || packet.payload.length > 17640 || (packet.payload.length & 1) != 0)) return;
            if (packet.type == StreamPacket.AVC_CONFIG) StreamPacket.parseVideoConfig(packet.payload);
            if (!incoming.offer(packet)) {
                incoming.clear(); incoming.offer(new StreamPacket.Packet(0, 0, 0, new byte[0])); incoming.offer(packet);
            }
        } catch (IllegalArgumentException ignored) {}
    }
    void resetStream() {
        // Keep the reset ordered with configuration/keyframes that immediately follow it.
        StreamPacket.Packet marker = new StreamPacket.Packet(0, 0, 0, new byte[0]);
        if (!incoming.offer(marker)) { incoming.clear(); incoming.offer(marker); }
    }
    void setFilmMuted(boolean muted) { filmMuted = muted; }
    long clockUs() { return currentClockUs; }
    private void state(boolean value) {
        if (buffering != value) { buffering = value; listener.buffering(value); }
    }
    private void requestKeyFrame() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastKeyRequestMs >= 1000) { lastKeyRequestMs = now; listener.requestKeyFrame(); }
    }
    private void loop() {
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min, 17640)).setTransferMode(AudioTrack.MODE_STREAM).build();
            // A streaming track may not start until its device buffer is full.
            // Feeding less than that threshold would leave the playback clock at zero forever.
            audioAheadFrames = track.getBufferSizeInFrames();
            while (running && !Thread.currentThread().isInterrupted()) {
                if (appliedMute != filmMuted) { appliedMute = filmMuted; track.setVolume(appliedMute ? 0f : 1f); }
                StreamPacket.Packet packet;
                while ((packet = incoming.poll()) != null) accept(packet);
                if (decoder != null && surfaceGeneration != view.surfaceGeneration()) { restart(); requestKeyFrame(); }
                if (decoder == null && config != null && view.decoderSurface() != null) configure();
                if (!audio.isEmpty() && !video.isEmpty() && audio.peekLast().timestampUs - audio.peekFirst().timestampUs > MAX_BACKLOG_US) {
                    restart(); requestKeyFrame();
                }
                if (video.size() > 900 || audio.size() > 700) { restart(); requestKeyFrame(); }
                // Warm the decoder while the multi-second reserve fills, before starting the movie clock.
                if (buffering && !waitingForKeyFrame && decoder != null) feedVideo(firstVideoUs);
                if (buffering && !waitingForKeyFrame && decoder != null && bufferedAudioUs() >= PRELOAD_US) {
                    if (audioOriginUs == Long.MIN_VALUE) {
                        // Skip sound recorded before the first decodable movie frame.
                        while (audio.size() > 1 && audio.peekFirst().timestampUs + duration(audio.peekFirst()) < firstVideoUs) audio.poll();
                        if (bufferedAudioUs() < PRELOAD_US) { Thread.sleep(4); continue; }
                        audioOriginUs = audio.peekFirst().timestampUs; writtenFrames = 0;
                    }
                    fillAudio();
                    if (waitingForKeyFrame) { Thread.sleep(4); continue; }
                    resetVideoClock(); track.play(); started = true; state(false);
                }
                if (!buffering) {
                    fillAudio();
                    if (buffering) { Thread.sleep(4); continue; }
                    currentClockUs = videoClockUs(System.nanoTime());
                    decodeVideo(currentClockUs);
                    if (pcm == null && audio.isEmpty() && writtenFrames - playedFrames() < RATE / 100) {
                        track.pause(); timestampValid = false; state(true);
                    }
                }
                if (waitingForKeyFrame) requestKeyFrame();
                Thread.sleep(4);
            }
        } catch (InterruptedException ignored) {
        } catch (Exception error) {
            listener.error("Não foi possível reproduzir o vídeo e o som. Encerre e entre na sala novamente.");
        } finally {
            running = false; releaseDecoder();
            if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
            incoming.clear(); audio.clear(); video.clear();
        }
    }
    private static long duration(StreamPacket.Packet p) { return (p.payload.length / 2) * 1000000L / RATE; }
    private long bufferedAudioUs() {
        long frames = pcm == null ? 0 : (pcm.payload.length - pcmOffset) / 2;
        for (StreamPacket.Packet p : audio) frames += p.payload.length / 2;
        return frames * 1000000L / RATE;
    }
    private void accept(StreamPacket.Packet p) {
        if (p.type == 0) { restart(); requestKeyFrame(); }
        else if (p.type == StreamPacket.AVC_CONFIG) {
            if (!Arrays.equals(configBytes, p.payload)) {
                restart(); config = StreamPacket.parseVideoConfig(p.payload); configBytes = p.payload;
            }
        } else if (p.type == StreamPacket.AVC_FRAME) {
            if (config == null || p.payload.length == 0) return;
            boolean key = (p.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if (waitingForKeyFrame) {
                if (!key) return;
                waitingForKeyFrame = false; firstVideoUs = p.timestampUs;
            }
            video.addLast(p);
        } else if (p.type == StreamPacket.PCM) audio.addLast(p);
    }
    private void configure() throws Exception {
        Surface surface = view.decoderSurface(); if (surface == null) return;
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, config.width, config.height);
        format.setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0));
        if (config.csd1.length > 0) format.setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1));
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, StreamPacket.MAX_PACKET_SIZE);
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        decoder.configure(format, surface, null, 0);
        decoder.setOnFrameRenderedListener((codec, pts, ns) -> {
            if (running && codec == decoder) view.markVideoFrame();
        }, ui);
        decoder.start(); surfaceGeneration = view.surfaceGeneration();
        int w = config.width, h = config.height; ui.post(() -> { if (running) view.setVideoSize(w, h); });
    }
    private long playedFrames() { return track.getPlaybackHeadPosition() & 0xffffffffL; }
    private void fillAudio() {
        // Fill the device's startup threshold; the remainder stays in the jitter buffer.
        long head = playedFrames();
        while (running && writtenFrames - head < audioAheadFrames) {
            if (pcm == null) {
                pcm = audio.pollFirst(); pcmOffset = 0; if (pcm == null) return;
                long expectedUs = audioOriginUs + writtenFrames * 1000000L / RATE;
                long gapUs = pcm.timestampUs - expectedUs;
                if (gapUs < -40000) { pcm = null; continue; }
                if (gapUs > 250000) { restart(); requestKeyFrame(); return; }
                if (gapUs > 40000) {
                    // A lost small PCM block becomes silence, so the movie clock does not jump.
                    int samples = (int)(gapUs * RATE / 1000000L);
                    audio.addFirst(pcm); pcm = new StreamPacket.Packet(StreamPacket.PCM, expectedUs, 0, new byte[samples * 2]);
                }
            }
            int count = track.write(pcm.payload, pcmOffset, pcm.payload.length - pcmOffset, AudioTrack.WRITE_NON_BLOCKING);
            if (count < 0) throw new IllegalStateException("AudioTrack write failed");
            if (count == 0) return;
            writtenFrames += count / 2; pcmOffset += count;
            if (pcmOffset == pcm.payload.length) { pcm = null; pcmOffset = 0; }
        }
    }
    private void resetVideoClock() {
        timestampValid = false; timestampPollNs = 0; observedHead = -1;
        previousScheduledPtsUs = Long.MIN_VALUE; lastScheduledNs = 0;
    }
    private long videoClockUs(long nowNs) {
        // Read the existing AudioTrack clock; audio samples, volume and routing are unchanged.
        if (nowNs - timestampPollNs >= (timestampValid ? 10000000000L : 250000000L)) {
            timestampPollNs = nowNs;
            if (track.getTimestamp(audioTimestamp) && audioTimestamp.framePosition > 0
                    && audioTimestamp.framePosition <= writtenFrames
                    && Math.abs(nowNs - audioTimestamp.nanoTime) < 1000000000L) {
                timestampFrame = audioTimestamp.framePosition; timestampNs = audioTimestamp.nanoTime;
                timestampValid = true;
            }
        }
        long frames;
        if (timestampValid) frames = timestampFrame + (nowNs - timestampNs) * RATE / 1000000000L;
        else {
            long head = playedFrames();
            if (head != observedHead) { observedHead = head; observedHeadNs = nowNs; }
            frames = head + Math.max(0, nowNs - observedHeadNs) * RATE / 1000000000L;
        }
        frames = Math.max(0, Math.min(writtenFrames, frames));
        return Math.max(currentClockUs, audioOriginUs + frames * 1000000L / RATE);
    }
    private void feedVideo(long clockUs) {
        if (decoder == null) return;
        // Decode ahead of presentation so device decoding latency does not become a motion stutter.
        for (int n = 0; n < 12 && !video.isEmpty(); n++) {
            StreamPacket.Packet p = video.peekFirst(); if (p.timestampUs > clockUs + 500000) break;
            int index = decoder.dequeueInputBuffer(0); if (index < 0) break;
            ByteBuffer input = decoder.getInputBuffer(index);
            if (input == null || input.capacity() < p.payload.length) throw new IllegalStateException("Frame too large");
            input.clear(); input.put(p.payload);
            decoder.queueInputBuffer(index, 0, p.payload.length, p.timestampUs, p.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME);
            video.pollFirst();
        }
    }
    private void decodeVideo(long clockUs) {
        if (decoder == null) return;
        feedVideo(clockUs);
        for (int n = 0; n < 8; n++) {
            if (outputIndex < 0) {
                int index = decoder.dequeueOutputBuffer(output, 0);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue;
                if (index < 0) break;
                outputIndex = index;
            }
            long nowNs = System.nanoTime();
            clockUs = videoClockUs(nowNs);
            long untilUs = output.presentationTimeUs - clockUs;
            // SurfaceView presents at the requested VSYNC. Submit about two refreshes in advance.
            if (untilUs > 35000) break;
            boolean render = untilUs >= -80000;
            if (render) {
                long targetNs;
                if (timestampValid) targetNs = timestampNs + (output.presentationTimeUs - audioOriginUs) * 1000L
                    - timestampFrame * 1000000000L / RATE;
                else targetNs = nowNs + untilUs * 1000L;
                targetNs = Math.max(nowNs, targetNs);
                if (previousScheduledPtsUs != Long.MIN_VALUE && output.presentationTimeUs > previousScheduledPtsUs) {
                    long errorUs = Math.abs((targetNs - lastScheduledNs) / 1000L
                        - (output.presentationTimeUs - previousScheduledPtsUs));
                    maxScheduleSpacingErrorUs = Math.max(maxScheduleSpacingErrorUs, errorUs);
                }
                decoder.releaseOutputBuffer(outputIndex, targetNs);
                previousScheduledPtsUs = output.presentationTimeUs; lastScheduledNs = targetNs;
                scheduledVideoFrames++; renderedFrames++; lastRenderedTimestampUs = output.presentationTimeUs;
            } else { decoder.releaseOutputBuffer(outputIndex, false); droppedVideoFrames++; }
            outputIndex = -1;
        }
    }
    private void restart() {
        if (track != null) { try { track.pause(); track.flush(); } catch (Exception ignored) {} }
        releaseDecoder(); audio.clear(); video.clear(); pcm = null; pcmOffset = 0;
        audioOriginUs = Long.MIN_VALUE; writtenFrames = 0; currentClockUs = 0; resetVideoClock();
        waitingForKeyFrame = true; started = false; state(true);
    }
    private void releaseDecoder() {
        outputIndex = -1;
        if (decoder != null) { try { decoder.stop(); } catch (Exception ignored) {} decoder.release(); decoder = null; }
    }
    @Override public void close() {
        running = false; worker.interrupt();
        try { worker.join(500); } catch (InterruptedException ignored) {}
        ui.removeCallbacksAndMessages(null);
    }
}
