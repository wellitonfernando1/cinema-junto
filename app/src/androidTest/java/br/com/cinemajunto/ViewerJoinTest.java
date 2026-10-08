package br.com.cinemajunto;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the actual viewer and its decoder callbacks while WebSocket opening is delayed. */
@RunWith(AndroidJUnit4.class)
public class ViewerJoinTest {
    static final String ENDPOINT = "https://cinema-junto-welliton.onrender.com/";
    Instrumentation instrumentation;
    MainActivity activity;
    WebSocket host;
    final OkHttpClient hostClient = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build();
    final AtomicBoolean hostJoined = new AtomicBoolean(), friendJoined = new AtomicBoolean(), closing = new AtomicBoolean();
    final AtomicInteger keyframeRequests = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final AtomicReference<String> hostStatus = new AtomicReference<>("");
    final CountDownLatch handshakeStarted = new CountDownLatch(1), controlAttempted = new CountDownLatch(1);

    interface Condition { boolean ready(); }

    void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    String viewerStatus() {
        AtomicReference<String> result = new AtomicReference<>("");
        if (activity != null) instrumentation.runOnMainSync(() -> result.set(activity.status.getText().toString()));
        return result.get();
    }

    void until(String message, long timeoutMs, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            Throwable error = failure.get();
            if (error != null) throw new AssertionError("The temporary host failed: " + hostStatus.get(), error);
            String status = viewerStatus();
            if (status.toLowerCase(Locale.ROOT).contains("senha inválida"))
                fail("A decoder control preceded room identification; viewer status=" + status);
            if (condition.ready()) return;
            Thread.sleep(40);
        }
        fail(message + "; viewer status=" + viewerStatus() + "; host status=" + hostStatus.get()
            + "; keyframe requests=" + keyframeRequests.get());
    }

    @Before public void launch() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        activity = (MainActivity)instrumentation.startActivitySync(new Intent(
            instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @After public void close() {
        closing.set(true); controlAttempted.countDown();
        if (activity != null) main(() -> { activity.endSession(); activity.finish(); });
        if (host != null) host.cancel();
        hostClient.dispatcher().executorService().shutdown(); hostClient.connectionPool().evictAll();
    }

    void createTemporaryHost(String room, String device) {
        host = hostClient.newWebSocket(new Request.Builder().url(ENDPOINT.replaceFirst("^https", "wss") + "relay").build(),
            new WebSocketListener() {
                @Override public void onOpen(WebSocket socket, Response response) {
                    try {
                        if (!socket.send(new JSONObject().put("role", "host").put("room", room).put("device", device).toString()))
                            throw new IOException("The host identification was not queued");
                    } catch (Exception error) { failure.compareAndSet(null, error); }
                }
                @Override public void onMessage(WebSocket socket, String text) {
                    try {
                        JSONObject message = new JSONObject(text);
                        if ("error".equals(message.optString("type")))
                            throw new IOException(message.optString("message", "The room was rejected"));
                        if (message.has("status")) {
                            String status = message.optString("status"); hostStatus.set(status);
                            if (message.optBoolean("joined")) hostJoined.set(true);
                            if (status.contains("Seu amigo entrou")) friendJoined.set(true);
                        }
                        if ("request-keyframe".equals(message.optString("type"))) keyframeRequests.incrementAndGet();
                    } catch (Exception error) { failure.compareAndSet(null, error); }
                }
                @Override public void onFailure(WebSocket socket, Throwable error, Response response) {
                    if (!closing.get()) failure.compareAndSet(null, error);
                }
                @Override public void onClosing(WebSocket socket, int code, String reason) { socket.close(code, reason); }
                @Override public void onClosed(WebSocket socket, int code, String reason) {
                    if (!closing.get()) failure.compareAndSet(null, new IOException("The host closed: " + reason));
                }
            });
    }

    @Test public void delayedHandshakeIdentifiesRoomBeforeDecoderControlsAndThenRequestsVideo() throws Exception {
        String password = "entrada" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String hostDevice;
        do { hostDevice = UUID.randomUUID().toString(); }
        while (hostDevice.equals(RoomPassword.device(instrumentation.getTargetContext())));
        createTemporaryHost(RoomPassword.room(password), hostDevice);
        until("The isolated host did not create its room", 10000, hostJoined::get);

        main(() -> {
            activity.client = activity.client.newBuilder().addInterceptor(chain -> {
                if ("/relay".equals(chain.request().url().encodedPath())) {
                    handshakeStarted.countDown();
                    try {
                        Thread.sleep(1500);
                        // Ensure the same decoder callback runs before onOpen, even on a slow emulator.
                        if (!controlAttempted.await(5, TimeUnit.SECONDS))
                            throw new IOException("The decoder callback was not exercised before opening");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IOException("The delayed handshake was interrupted", interrupted);
                    }
                }
                return chain.proceed(chain.request());
            }).build();
            activity.endpoint.setText(ENDPOINT); activity.invitation.setText(password); activity.startViewer();
        });
        long viewerDeadline = SystemClock.elapsedRealtime() + 25000;
        until("The real viewer did not begin its delayed handshake", 5000, () -> handshakeStarted.getCount() == 0);
        main(() -> {
            assertTrue("The viewer must be connecting", activity.watching);
            assertFalse("Opening is delayed, so there cannot yet be a room acknowledgement", activity.viewerJoined);
            assertNotNull("The real decoder engine must already exist during connection", activity.playback);
            assertTrue("The real playback worker must remain alive", activity.playback.worker.isAlive());
            // Without the join gate, this queues request-keyframe ahead of the onOpen identification.
            activity.playback.listener.requestKeyFrame();
            controlAttempted.countDown();
        });
        until("A valid password was rejected when decoder controls preceded opening",
            Math.max(1, viewerDeadline - SystemClock.elapsedRealtime()), () -> activity.viewerJoined && friendJoined.get());
        // The relay itself requests one keyframe when a viewer joins. A second one proves the
        // actual PlaybackEngine continues asking for video after the acknowledgement unlocks it.
        until("The acknowledged viewer never requested video through its real playback worker",
            Math.max(1, viewerDeadline - SystemClock.elapsedRealtime()), () -> keyframeRequests.get() >= 2);
        main(() -> {
            assertTrue(activity.watching); assertTrue(activity.viewerJoined);
            assertNotNull(activity.playback); assertTrue(activity.playback.worker.isAlive());
        });
    }
}
