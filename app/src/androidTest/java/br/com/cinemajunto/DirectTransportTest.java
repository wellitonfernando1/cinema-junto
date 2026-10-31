package br.com.cinemajunto;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

@RunWith(AndroidJUnit4.class)
public class DirectTransportTest {
    @Test public void setupShowsFourLetterWordAndClearInviteButton() throws Exception {
        try (androidx.test.core.app.ActivityScenario<MainActivity> scenario = androidx.test.core.app.ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals(android.view.View.GONE, activity.endpoint.getVisibility());
                assertEquals("https://cinema-junto-welliton.onrender.com/", activity.endpoint.getText().toString());
                assertTrue(activity.invitation.getHint().toString().contains("4 letras"));
                assertEquals(android.view.View.VISIBLE, activity.setupScroll.getVisibility());
            });
            androidx.test.uiautomator.UiDevice device = androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation());
            device.waitForIdle();
            Context context = ApplicationProvider.getApplicationContext();
            java.io.File folder = new java.io.File(context.getExternalFilesDir(null), "direct-test"); folder.mkdirs();
            assertTrue(device.takeScreenshot(new java.io.File(folder, "setup.png")));
        }
    }
    static final class Events extends WebSocketListener {
        final BlockingQueue<String> text = new LinkedBlockingQueue<>();
        final BlockingQueue<ByteString> binary = new LinkedBlockingQueue<>();
        final CountDownLatch open = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final String role;
        Events(String role) { this.role = role; }
        public void onOpen(WebSocket ws, Response response) {
            assertTrue(ws.send("{\"role\":\"" + role + "\"}")); open.countDown();
        }
        public void onMessage(WebSocket ws, String value) { text.add(value); }
        public void onMessage(WebSocket ws, ByteString value) { binary.add(value); }
        public void onFailure(WebSocket ws, Throwable t, Response response) { error.set(t); }
        JSONObject type(String type) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < until) {
                String value = text.poll(1, TimeUnit.SECONDS);
                if (value != null) { JSONObject data = new JSONObject(value); if (type.equals(data.optString("type"))) return data; }
            }
            fail("Missing event " + type + "; error=" + error.get()); return null;
        }
    }
    @Test public void nativePeersExchangeLargeFramesChatVoiceAndKeyframeRequests() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        BlockingQueue<String> offers = new LinkedBlockingQueue<>(), answers = new LinkedBlockingQueue<>();
        Events h = new Events("host"), v = new Events("viewer");
        DirectSocket host = new DirectSocket(context, true, null, h, offers::add), viewer = null;
        try {
            String offer = offers.poll(30, TimeUnit.SECONDS); assertNotNull("Offer: " + h.error.get(), offer);
            assertEquals("offer", DirectInvite.decode(offer, "offer").getString("type"));
            viewer = new DirectSocket(context, false, offer, v, answers::add);
            String answer = answers.poll(30, TimeUnit.SECONDS); assertNotNull("Answer: " + v.error.get(), answer);
            host.acceptAnswer(answer);
            assertTrue("Host open: " + h.error.get(), h.open.await(30, TimeUnit.SECONDS));
            assertTrue("Viewer open: " + v.error.get(), v.open.await(30, TimeUnit.SECONDS));
            h.type("request-keyframe");
            byte[] frame = new byte[400000]; frame[0] = 6;
            for (int i = 1; i < frame.length; i++) frame[i] = (byte)(i * 17);
            for (int n = 0; n < 6; n++) {
                assertTrue(host.send(ByteString.of(frame)));
                assertEquals(ByteString.of(frame), v.binary.poll(15, TimeUnit.SECONDS));
            }
            assertFalse("Viewer cannot send movie packets", viewer.send(ByteString.of(frame)));
            assertTrue(viewer.send("{\"type\":\"chat\",\"text\":\"Olá 🍿\"}"));
            assertEquals("viewer", h.type("chat").getString("from"));
            assertEquals("Olá 🍿", v.type("chat").getString("text"));
            assertTrue(host.send("{\"type\":\"talk\",\"active\":true}"));
            assertEquals("host", h.type("talk").getString("from"));
            assertEquals("host", v.type("talk").getString("from"));
            assertTrue(host.send("{\"type\":\"talk\",\"active\":false}"));
            assertFalse(h.type("talk").getBoolean("active")); assertFalse(v.type("talk").getBoolean("active"));
            assertTrue(viewer.send("{\"type\":\"talk\",\"active\":true}"));
            assertEquals("viewer", h.type("talk").getString("from"));
            assertEquals("viewer", v.type("talk").getString("from"));
            byte[] speech = new byte[6413]; speech[0] = 7;
            assertTrue(viewer.send(ByteString.of(speech))); assertEquals(ByteString.of(speech), h.binary.poll(10, TimeUnit.SECONDS));
            assertNull(h.error.get()); assertNull(v.error.get());
        } finally { host.cancel(); if (viewer != null) viewer.cancel(); }
    }
    @Test public void rejectsInvalidExpiredAndWrongStepCodes() throws Exception {
        String session = java.util.UUID.randomUUID().toString();
        String code = DirectInvite.encode(session, "offer", "v=0\r\n", System.currentTimeMillis() + DirectInvite.VALID_MS);
        assertEquals(session, DirectInvite.decode(code, "offer").getString("session"));
        reject(code, "answer"); reject("pipoca42", "offer"); reject("CJ19:invalid", "offer");
        reject(DirectInvite.encode(session, "offer", "v=0\r\n", System.currentTimeMillis() - 1000), "offer");
        reject(DirectInvite.encode(session, "offer", "v=0\r\n", System.currentTimeMillis() + DirectInvite.VALID_MS * 3), "offer");
        reject(DirectInvite.encode(session, "offer", "not SDP", System.currentTimeMillis() + 10000), "offer");
        StringBuilder oversized = new StringBuilder("CJ19:"); for (int n = 0; n < 70000; n++) oversized.append('A');
        reject(oversized.toString(), "offer");
    }
    private void reject(String value, String type) throws Exception {
        try { DirectInvite.decode(value, type); fail("Accepted invalid invite"); }
        catch (IllegalArgumentException | java.io.IOException | org.json.JSONException expected) {}
    }
    @Test public void wrongSessionAnswerCannotAttachToHost() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        BlockingQueue<String> offers = new LinkedBlockingQueue<>(); Events h = new Events("host");
        DirectSocket host = new DirectSocket(context, true, null, h, offers::add);
        try {
            assertNotNull(offers.poll(30, TimeUnit.SECONDS));
            host.acceptAnswer(DirectInvite.encode(java.util.UUID.randomUUID().toString(), "answer", "v=0\r\n", System.currentTimeMillis() + 10000));
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (h.error.get() == null && System.nanoTime() < until) Thread.sleep(20);
            assertNotNull(h.error.get()); assertTrue(h.error.get().getMessage().contains("outro convite"));
        } finally { host.cancel(); }
    }
}
