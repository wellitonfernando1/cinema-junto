package br.com.cinemajunto;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Smoke test of the real MediaProjection surface and concurrent playback/microphone capture. */
@RunWith(AndroidJUnit4.class)
public class SourceCaptureTest {
    Instrumentation instrumentation;
    UiDevice device;
    MainActivity activity;
    WebSocket viewer;
    final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).build();
    final AtomicInteger configs = new AtomicInteger(), frames = new AtomicInteger(), pcm = new AtomicInteger(), voice = new AtomicInteger();
    final AtomicBoolean joined = new AtomicBoolean(), hostTalking = new AtomicBoolean();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicReference<StreamPacket.VideoConfig> format = new AtomicReference<>();
    final AtomicReference<StreamPacket.Packet> lastVoice = new AtomicReference<>();
    boolean evidenceSaved;

    @Rule public final TestWatcher evidence = new TestWatcher() {
        @Override protected void failed(Throwable error, Description description) {
            saveEvidence(error);
        }
    };

    void saveEvidence(Throwable error) {
        if (evidenceSaved || device == null || instrumentation == null) return;
        try {
            File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "ui-test");
            directory.mkdirs();
            File xml = new File(directory, "source-capture-api" + Build.VERSION.SDK_INT + ".xml");
            device.dumpWindowHierarchy(xml); evidenceSaved = true;
            Log.e("CinemaSourceTest", "Capture failure; UI hierarchy: " + xml.getAbsolutePath(), error);
        } catch (Exception dumpError) { Log.e("CinemaSourceTest", "Could not save consent UI evidence", dumpError); }
    }

    void failWithEvidence(String message) { saveEvidence(new AssertionError(message)); fail(message); }

    interface Condition { boolean ready(); }

    void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    void until(String message, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            Throwable error = failure.get();
            if (error != null) throw new AssertionError("Source relay failed", error);
            instrumentation.waitForIdleSync();
            if (condition.ready()) return;
            Thread.sleep(40);
        }
        failWithEvidence(message + (activity == null ? "" : "; status=" + activity.status.getText()));
    }

    @Before public void launch() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity)instrumentation.startActivitySync(intent);
    }

    @After public void close() {
        if (activity != null) main(() -> { activity.endSession(); activity.finish(); });
        if (viewer != null) viewer.cancel();
        client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll();
    }

    boolean clickText(String expression) {
        UiObject2 button = device.findObject(By.text(Pattern.compile(expression, Pattern.CASE_INSENSITIVE)));
        if (button == null) return false;
        button.click(); return true;
    }

    void approveScreenCapture() throws Exception {
        until("The app's capture explanation did not open", 3000,
            () -> device.hasObject(By.text(Pattern.compile("Continuar", Pattern.CASE_INSENSITIVE))));
        assertTrue(clickText("Continuar"));
        long deadline = SystemClock.elapsedRealtime() + 7000;
        boolean selectedEntireScreen = Build.VERSION.SDK_INT < 34;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (CaptureService.active) return;
            if (!selectedEntireScreen) {
                UiObject2 spinner = device.findObject(By.res("com.android.systemui", "screen_share_mode_spinner"));
                if (spinner == null) spinner = device.findObject(By.clazz("android.widget.Spinner"));
                if (spinner == null) spinner = device.findObject(By.res(Pattern.compile(".*screen_share_mode_options.*")));
                if (spinner != null) {
                    spinner.click();
                    long optionDeadline = SystemClock.elapsedRealtime() + 1500;
                    while (SystemClock.elapsedRealtime() < optionDeadline) {
                        if (clickText("(share )?(entire screen|whole screen|tela inteira|ecrã inteiro)")) {
                            selectedEntireScreen = true; break;
                        }
                        Thread.sleep(50);
                    }
                } else if (device.hasObject(By.text(Pattern.compile(".*(entire screen|whole screen|tela inteira).*", Pattern.CASE_INSENSITIVE)))) {
                    selectedEntireScreen = true;
                }
            }
            UiObject2 positive = device.findObject(By.res("android", "button1").pkg("com.android.systemui"));
            if (selectedEntireScreen && positive != null) { positive.click(); return; }
            if (clickText("(start now|start recording|start sharing|iniciar agora|começar agora|iniciar gravação)")) return;
            Thread.sleep(60);
        }
        failWithEvidence("Could not approve MediaProjection consent; see saved source-capture UI hierarchy");
    }

    void joinViewer(String endpoint, String room) {
        viewer = client.newWebSocket(new Request.Builder().url(endpoint.replaceFirst("^https", "wss") + "relay").build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket socket, Response response) {
                try { socket.send(new JSONObject().put("room", room).put("role", "viewer").toString()); }
                catch (Exception error) { failure.compareAndSet(null, error); }
            }
            @Override public void onMessage(WebSocket socket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    if (message.has("status")) joined.set(true);
                    if ("talk".equals(message.optString("type")) && "host".equals(message.optString("from")))
                        hostTalking.set(message.optBoolean("active"));
                } catch (Exception error) { failure.compareAndSet(null, error); }
            }
            @Override public void onMessage(WebSocket socket, ByteString data) {
                try {
                    StreamPacket.Packet packet = StreamPacket.unpack(data.toByteArray());
                    if (packet.type == StreamPacket.AVC_CONFIG) { format.set(StreamPacket.parseVideoConfig(packet.payload)); configs.incrementAndGet(); }
                    else if (packet.type == StreamPacket.AVC_FRAME) { assertTrue(packet.payload.length > 0); frames.incrementAndGet(); }
                    else if (packet.type == StreamPacket.PCM) { assertEquals(0, packet.payload.length & 1); pcm.incrementAndGet(); }
                    else if (packet.type == 7) { lastVoice.set(packet); voice.incrementAndGet(); }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            }
            @Override public void onFailure(WebSocket socket, Throwable error, Response response) { failure.compareAndSet(null, error); }
            @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
        });
    }

    @Test public void hostSurfaceCaptureAndMicrophoneRunTogetherThroughRealRelay() throws Exception {
        assertEquals("CI must grant RECORD_AUDIO", PackageManager.PERMISSION_GRANTED,
            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        assertTrue("CI must grant overlay access", Settings.canDrawOverlays(activity));
        main(() -> {
            activity.endpoint.setText("https://cinema-junto-welliton.onrender.com/");
            activity.startHost();
        });
        approveScreenCapture();
        until("MediaProjection foreground capture did not connect", 10000, () -> CaptureService.active);
        String[] invitation = new String[2];
        main(() -> {
            invitation[0] = activity.pendingEndpoint; invitation[1] = activity.pendingRoom;
            assertTrue(activity.invitation.getText().toString().contains(invitation[1]));
        });
        joinViewer(invitation[0], invitation[1]);
        until("The isolated test viewer did not join", 7000, joined::get);
        until("The real input Surface did not send AVC and playback PCM", 7000,
            () -> configs.get() > 0 && frames.get() >= 3 && pcm.get() >= 3);
        StreamPacket.VideoConfig video = format.get();
        assertNotNull(video); assertTrue(video.width > 0 && video.height > 0);
        assertTrue("Capture resolution was not bounded", Math.max(video.width, video.height) <= 960);
        int oldFrames = frames.get(), oldPcm = pcm.get();
        main(() -> activity.startService(new Intent(activity, CaptureService.class).setAction("VOICE_PRESS")));
        until("Foreground microphone did not coexist with video and playback capture", 5000,
            () -> hostTalking.get() && voice.get() >= 3 && frames.get() > oldFrames && pcm.get() > oldPcm);
        assertTrue("Microphone stopped the capture service", CaptureService.active);
        assertEquals(640, lastVoice.get().payload.length);
        assertTrue(lastVoice.get().timestampUs > 0);
        main(() -> activity.startService(new Intent(activity, CaptureService.class).setAction("VOICE_RELEASE")));
        until("Releasing the host microphone did not end its voice turn", 3000, () -> !hostTalking.get());
        assertTrue("Releasing voice ended the movie transmission", CaptureService.active);
    }
}
