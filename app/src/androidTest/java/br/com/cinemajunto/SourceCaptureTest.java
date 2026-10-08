package br.com.cinemajunto;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
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

/** Real MediaProjection, concurrent playback/microphone capture, and relayed chat over another app. */
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
    final AtomicReference<String> lastChatEcho = new AtomicReference<>();
    final String viewerDevice = UUID.randomUUID().toString();
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

    Bitmap screenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("The compositor screenshot was unavailable", bitmap);
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "ui-test");
        directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        return bitmap;
    }

    float topDifference(Bitmap before) {
        Bitmap after = instrumentation.getUiAutomation().takeScreenshot();
        if (after == null) return 0;
        try {
            assertEquals(before.getWidth(), after.getWidth());
            assertEquals(before.getHeight(), after.getHeight());
            int statusResource = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            int statusHeight = statusResource == 0 ? activity.dp(24) : activity.getResources().getDimensionPixelSize(statusResource);
            int different = 0, total = 0;
            // The banner is not touchable, so accessibility omits it. This crop is inside
            // its text/background, below system icons and above both floating buttons.
            for (int y = statusHeight + activity.dp(12); y < Math.min(after.getHeight(), statusHeight + activity.dp(36)); y += 4) {
                for (int x = activity.dp(16); x < after.getWidth() - activity.dp(96); x += 4) {
                    int a = after.getPixel(x, y), b = before.getPixel(x, y); total++;
                    if (Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                            + Math.abs(Color.blue(a) - Color.blue(b)) > 35) different++;
                }
            }
            return total == 0 ? 0 : different / (float)total;
        } finally { after.recycle(); }
    }

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
                try { socket.send(new JSONObject().put("room", room).put("role", "viewer").put("device", viewerDevice).toString()); }
                catch (Exception error) { failure.compareAndSet(null, error); }
            }
            @Override public void onMessage(WebSocket socket, String text) {
                try {
                    JSONObject message = new JSONObject(text);
                    if (message.optBoolean("joined")) joined.set(true);
                    if ("chat".equals(message.optString("type")) && "viewer".equals(message.optString("from")))
                        lastChatEcho.set(message.optString("text"));
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
        String password = "teste" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        until("The room password dialog did not open", 3000,
            () -> device.hasObject(By.text("Criar senha da sala")));
        UiObject2 passwordInput = device.findObject(By.desc("Senha que você vai criar"));
        assertNotNull("The host must be able to choose a short room password", passwordInput);
        passwordInput.setText(password);
        assertTrue("The create-room button was missing", clickText("Criar sala"));
        approveScreenCapture();
        until("MediaProjection foreground capture did not connect", 10000, () -> CaptureService.active);
        String[] invitation = new String[2];
        main(() -> {
            invitation[0] = activity.pendingEndpoint; invitation[1] = activity.pendingRoom;
            assertEquals("The shared invitation must be just the chosen password", password,
                activity.invitation.getText().toString());
            assertEquals("The relay must use the internal room hash", RoomPassword.room(password), invitation[1]);
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
        screenshot("source-host-speaking").recycle();
        main(() -> activity.startService(new Intent(activity, CaptureService.class).setAction("VOICE_RELEASE")));
        until("Releasing the host microphone did not end its voice turn", 3000, () -> !hostTalking.get());
        assertTrue("Releasing voice ended the movie transmission", CaptureService.active);

        assertTrue("Could not leave Cinema Junto for the real home application", device.pressHome());
        until("The home application did not appear under the capture overlays", 4000,
            () -> device.getCurrentPackageName() != null && !activity.getPackageName().equals(device.getCurrentPackageName())
                && !"com.android.systemui".equals(device.getCurrentPackageName()));
        assertNotEquals("The chat must be checked over another application", activity.getPackageName(), device.getCurrentPackageName());
        until("The capture service's compact chat button is missing over the home application", 3000,
            () -> device.hasObject(By.desc("Abrir chat flutuante")));

        Thread.sleep(250); // Let the launcher finish its transition before comparing its pixels.
        Bitmap beforeChat = screenshot("source-home-before-chat");
        int framesBeforeChat = frames.get(), pcmBeforeChat = pcm.get();
        String chatText = "Oi, estou vendo o filme";
        assertTrue("The existing viewer could not queue its message",
            viewer.send(new JSONObject().put("type", "chat").put("text", chatText).toString()));
        try {
            until("The relayed viewer message did not appear above the home application while media continued", 4500,
                () -> chatText.equals(lastChatEcho.get()) && device.hasObject(By.desc("Abrir chat flutuante")
                        .text(Pattern.compile("Chat •", Pattern.CASE_INSENSITIVE)))
                    && frames.get() > framesBeforeChat && pcm.get() > pcmBeforeChat && topDifference(beforeChat) > 0.3f);
            screenshot("source-chat-over-home").recycle();
            assertTrue("Receiving a floating message ended the movie transmission", CaptureService.active);

            main(() -> activity.endSession());
            assertTrue("Stopping capture did not remove its compact chat button",
                device.wait(Until.gone(By.desc("Abrir chat flutuante")), 3000));
            until("Stopping capture did not remove its visible message from the other application", 3000,
                () -> topDifference(beforeChat) < 0.1f);
        } finally { beforeChat.recycle(); }
    }
}
