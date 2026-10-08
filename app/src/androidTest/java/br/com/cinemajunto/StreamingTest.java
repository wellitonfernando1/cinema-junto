package br.com.cinemajunto;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises an actual AVC encoder, decoder surface, PCM clock and playback buffer. */
@RunWith(AndroidJUnit4.class)
public class StreamingTest {
    static final int WIDTH = 160, HEIGHT = 96, FPS = 24;
    static final long START_US = 1_000_000L;
    Instrumentation instrumentation;
    MainActivity activity;

    interface Condition { boolean ready(); }

    void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    boolean read(Condition condition) {
        AtomicBoolean result = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> result.set(condition.ready()));
        return result.get();
    }

    void until(String message, long timeoutMs, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync();
            if (condition.ready()) return;
            Thread.sleep(20);
        }
        fail(message);
    }

    @Before public void launch() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity)instrumentation.startActivitySync(intent);
        main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        until("Activity did not reach portrait", 8000,
            () -> read(() -> activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT));
    }

    @After public void close() {
        main(() -> {
            if (activity != null) {
                activity.stopViewer();
                activity.finish();
            }
        });
    }

    static final class EncodedMovie {
        byte[] config;
        final List<StreamPacket.Packet> frames = new ArrayList<>();
    }

    byte[] bytes(ByteBuffer source) {
        if (source == null) return new byte[0];
        ByteBuffer copy = source.duplicate();
        byte[] data = new byte[copy.remaining()];
        copy.get(data);
        return data;
    }

    void fillPlane(Image.Plane plane, int width, int height, int value) {
        ByteBuffer pixels = plane.getBuffer();
        int start = pixels.position();
        for (int y = 0; y < height; y++)
            for (int x = 0; x < width; x++)
                pixels.put(start + y * plane.getRowStride() + x * plane.getPixelStride(), (byte)value);
    }

    EncodedMovie encodeGreenMovie() throws Exception {
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        boolean started = false;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_PAL);
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start(); started = true;
            EncodedMovie movie = new EncodedMovie();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int submitted = 0;
            boolean inputEnded = false, outputEnded = false;
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    int index = encoder.dequeueInputBuffer(1000);
                    if (index >= 0) {
                        if (submitted < FPS) {
                            Image image = encoder.getInputImage(index);
                            assertNotNull("AVC encoder did not provide flexible YUV input", image);
                            assertEquals(3, image.getPlanes().length);
                            fillPlane(image.getPlanes()[0], WIDTH, HEIGHT, 145);
                            fillPlane(image.getPlanes()[1], WIDTH / 2, HEIGHT / 2, 54);
                            fillPlane(image.getPlanes()[2], WIDTH / 2, HEIGHT / 2, 34);
                            encoder.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2,
                                START_US + submitted * 1_000_000L / FPS, 0);
                            submitted++;
                        } else {
                            encoder.queueInputBuffer(index, 0, 0, START_US + 1_000_000L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        }
                    }
                }
                int index = encoder.dequeueOutputBuffer(info, 1000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = encoder.getOutputFormat();
                    movie.config = StreamPacket.videoConfig(WIDTH, HEIGHT,
                        bytes(output.getByteBuffer("csd-0")), bytes(output.getByteBuffer("csd-1")));
                } else if (index >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer output = encoder.getOutputBuffer(index).duplicate();
                        output.position(info.offset); output.limit(info.offset + info.size);
                        movie.frames.add(StreamPacket.unpack(StreamPacket.pack(StreamPacket.AVC_FRAME,
                            info.presentationTimeUs, info.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM, bytes(output))));
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(index, false);
                }
            }
            assertTrue("AVC encoder failed to finish one second of video", outputEnded);
            assertNotNull("AVC encoder did not publish SPS/PPS", movie.config);
            assertEquals("AVC encoder lost input frames", FPS, movie.frames.size());
            movie.frames.sort(Comparator.comparingLong(frame -> frame.timestampUs));
            assertTrue("First AVC frame must be a decoder entry point",
                (movie.frames.get(0).flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
            return movie;
        } finally {
            if (started) encoder.stop();
            encoder.release();
        }
    }

    PlaybackEngine startMovie() throws Exception {
        main(() -> {
            activity.watching = true;
            activity.updateLayout();
            activity.startPlayback();
        });
        until("Decoder surface was not available", 5000,
            () -> read(() -> activity.screen.decoderSurface() != null));
        assertNotNull("Playback engine did not start", activity.playback);
        return activity.playback;
    }

    void audio(PlaybackEngine engine, int startMs, int endMs, long baseUs) {
        byte[] silence = new byte[1764]; // 882 mono PCM16 samples = 20 ms at 44.1 kHz.
        for (int ms = startMs; ms < endMs; ms += 20)
            engine.offer(StreamPacket.pack(StreamPacket.PCM, baseUs + ms * 1000L, 0, silence));
    }

    void video(PlaybackEngine engine, EncodedMovie movie, int startMs, int endMs, long baseUs) {
        for (StreamPacket.Packet frame : movie.frames) {
            long offsetUs = frame.timestampUs - START_US;
            if (offsetUs >= startMs * 1000L && offsetUs < endMs * 1000L)
                engine.offer(StreamPacket.pack(StreamPacket.AVC_FRAME, baseUs + offsetUs, frame.flags, frame.payload));
        }
    }

    void assertRealGreenVideo(String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        int lastColor = Color.BLACK;
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(80);
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull("Screenshot failed", screenshot);
            int[] bounds = new int[4];
            main(() -> {
                activity.screen.getLocationOnScreen(bounds);
                bounds[2] = activity.screen.getWidth(); bounds[3] = activity.screen.getHeight();
            });
            // Probe inside the fitted movie, away from the centered buffering label.
            int movieHeight = Math.min(bounds[3], Math.round(bounds[2] * (float)HEIGHT / WIDTH));
            int x = Math.min(screenshot.getWidth() - 1, bounds[0] + bounds[2] / 2);
            int y = Math.min(screenshot.getHeight() - 1, bounds[1] + bounds[3] / 2 + movieHeight / 4);
            lastColor = screenshot.getPixel(Math.max(0, x), Math.max(0, y));
            screenshot.recycle();
            if (Color.green(lastColor) > 160 && Color.red(lastColor) < 90 && Color.blue(lastColor) < 90) return;
        }
        fail(message + ": " + Integer.toHexString(lastColor));
    }

    @Test public void realAvcWaitsForBufferTracksAudioAndSurvivesFullscreenStarvation() throws Exception {
        EncodedMovie movie = encodeGreenMovie();
        PlaybackEngine engine = startMovie();
        Thread worker = engine.worker;
        engine.offer(StreamPacket.pack(StreamPacket.AVC_CONFIG, START_US, 0, movie.config));
        audio(engine, 0, 200, START_US);
        video(engine, movie, 0, 200, START_US);
        Thread.sleep(250);
        assertTrue("Two hundred ms must not bypass the initial buffer", engine.buffering);
        assertEquals("Video rendered before sufficient audio was buffered", 0L, engine.renderedFrames);
        long sent = SystemClock.elapsedRealtime();
        audio(engine, 200, 1000, START_US);
        video(engine, movie, 200, 1000, START_US);
        assertTrue("Supplying one second took too long", SystemClock.elapsedRealtime() - sent < 1500);
        until("Real AVC never rendered after audio buffering", 5000,
            () -> engine.renderedFrames >= 2 && read(() -> activity.screen.hasFrame()));
        assertRealGreenVideo("Actual AVC decoder rendered a black or wrong-colored frame");
        assertTrue("Audio clock did not reach stream timestamps", engine.clockUs() >= START_US);
        assertTrue("Audio clock drifted ahead of supplied media", engine.clockUs() <= START_US + 1_200_000L);
        assertTrue("Rendered AVC frames lost their timestamps", engine.lastRenderedTimestampUs >= START_US);
        main(() -> activity.enterFullscreen());
        until("Fullscreen did not rotate the movie", 8000,
            () -> read(() -> activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE));
        assertSame("Rotation replaced the playback engine", engine, activity.playback);
        assertSame("Rotation replaced the playback worker", worker, engine.worker);
        assertRealGreenVideo("Fullscreen hid the decoded AVC video");
        until("Audio starvation never paused playback for buffering", 5000, () -> engine.buffering);
        // Allow an already-released surface frame to reach its presentation callback.
        Thread.sleep(120);
        long stopped = engine.renderedFrames;
        long stoppedClock = engine.clockUs();
        Thread.sleep(250);
        assertEquals("Video continued after the audio clock exhausted its buffer", stopped, engine.renderedFrames);
        assertTrue("Audio clock continued through starvation", Math.abs(engine.clockUs() - stoppedClock) < 30000);
        // Resets must stay ordered before immediately arriving configuration and keyframes.
        // Muting for push-to-talk must leave the film clock and image running.
        long resumeUs = START_US + 2_000_000L;
        engine.setFilmMuted(true);
        engine.resetStream();
        engine.offer(StreamPacket.pack(StreamPacket.AVC_CONFIG, resumeUs, 0, movie.config));
        video(engine, movie, 0, 1000, resumeUs);
        audio(engine, 0, 1000, resumeUs);
        until("Film did not resume after an ordered media reset", 5000,
            () -> engine.renderedFrames > stopped + 2 && engine.lastRenderedTimestampUs >= resumeUs);
        long mutedClock = engine.clockUs();
        until("Muting for voice paused the film", 1500, () -> engine.clockUs() > mutedClock + 100000);
        assertTrue(engine.filmMuted);
        engine.setFilmMuted(false);
        assertRealGreenVideo("Recovered AVC was hidden after voice muting");
    }
}
