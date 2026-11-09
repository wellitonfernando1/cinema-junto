package br.com.cinemajunto;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import java.util.concurrent.*;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

@RunWith(AndroidJUnit4.class)
public class DirectRoomTest {
    @Test public void wordMustHaveExactlyFourLetters() {
        assertEquals("gato", RoomPassword.fourLetters(" GATO "));
        for (String value : new String[]{"abc", "abcde", "1234", "a bc", "café", ""}) {
            try { RoomPassword.fourLetters(value); fail("Accepted " + value); } catch (IllegalArgumentException expected) {}
        }
    }
    static class Events extends WebSocketListener {
        final BlockingQueue<String> texts = new LinkedBlockingQueue<>();
        final BlockingQueue<ByteString> packets = new LinkedBlockingQueue<>();
        final CountDownLatch open = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        volatile String failure;
        public void onOpen(WebSocket ws, Response response) { open.countDown(); }
        public void onMessage(WebSocket ws, String text) { texts.add(text); }
        public void onMessage(WebSocket ws, ByteString data) { packets.add(data); }
        public void onFailure(WebSocket ws, Throwable t, Response r) { failure = t.getMessage(); failed.countDown(); }
        JSONObject type(String type) throws Exception { return matching(type, null); }
        JSONObject matching(String type, String expectedText) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < end) {
                String text = texts.poll(1, TimeUnit.SECONDS);
                if (text == null) continue;
                JSONObject data = new JSONObject(text);
                if (type.equals(data.optString("type")) && (expectedText == null || expectedText.equals(data.optString("text")))) return data;
            }
            fail("Missing " + type + ": " + failure); return null;
        }
    }
    @Test public void threePeersAutomaticallyConnectAndExchangeMovieChatAndVoice() throws Exception {
        verifyThreePeers("http://127.0.0.1:10001/");
    }
    @Test public void threePeersConnectThroughLivePublicSignaling() throws Exception {
        verifyThreePeers("https://0.peerjs.com/");
    }
    @Test public void connectedRoomSurvivesStartupDeadlineAndGuestPresenceExpiry() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String key = RoomPassword.room(java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16));
        Events h = new Events(), a = new Events(), b = new Events();
        DirectRoomSocket host = new DirectRoomSocket(context, true, "http://127.0.0.1:10001/", key, h);
        DirectRoomSocket one = null, two = null;
        try {
            assertTrue(h.open.await(30, TimeUnit.SECONDS));
            one = new DirectRoomSocket(context, false, "http://127.0.0.1:10001/", key, a);
            two = new DirectRoomSocket(context, false, "http://127.0.0.1:10001/", key, b);
            assertTrue(a.open.await(30, TimeUnit.SECONDS)); assertTrue(b.open.await(30, TimeUnit.SECONDS));
            Thread.sleep(42000); // Cross both the 25s presence lease and the 40s startup deadline.
            assertNull(h.failure); assertNull(a.failure); assertNull(b.failure);
            assertTrue(host.send("{\"type\":\"chat\",\"text\":\"Sala continua ativa\"}"));
            assertEquals("Sala continua ativa", a.type("chat").getString("text"));
            assertEquals("Sala continua ativa", b.type("chat").getString("text"));
        } finally { host.cancel(); if (one != null) one.cancel(); if (two != null) two.cancel(); }
    }
    private void disconnectBroker() throws Exception {
        OkHttpClient client = new OkHttpClient();
        try (Response response = client.newCall(new Request.Builder().url("http://127.0.0.1:10002/disconnect")
            .post(RequestBody.create("", MediaType.get("application/json"))).build()).execute()) {
            assertTrue(response.isSuccessful());
        } finally { client.dispatcher().executorService().shutdown(); }
    }
    private void closeDirectChannel(DirectRoomSocket room) throws Exception {
        java.lang.reflect.Field workerField = DirectRoomSocket.class.getDeclaredField("worker"); workerField.setAccessible(true);
        android.os.Handler worker = (android.os.Handler)workerField.get(room);
        CountDownLatch closed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
        worker.post(() -> {
            try {
                java.lang.reflect.Field peersField = DirectRoomSocket.class.getDeclaredField("peers"); peersField.setAccessible(true);
                java.util.Map<?, ?> peers = (java.util.Map<?, ?>)peersField.get(room);
                Object peer = peers.values().iterator().next();
                java.lang.reflect.Field socketField = peer.getClass().getDeclaredField("socket"); socketField.setAccessible(true);
                DirectSocket socket = (DirectSocket)socketField.get(peer);
                java.lang.reflect.Field channelField = DirectSocket.class.getDeclaredField("channel"); channelField.setAccessible(true);
                ((org.webrtc.DataChannel)channelField.get(socket)).close();
            } catch (Throwable t) { error.set(t); } finally { closed.countDown(); }
        });
        assertTrue(closed.await(5, TimeUnit.SECONDS)); assertNull(error.get());
    }
    @Test public void returningDeviceRecoversChannelWithoutKickingOtherViewer() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        String key = RoomPassword.room(java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16));
        String device = java.util.UUID.randomUUID().toString();
        Events h = new Events(), a = new Events(), b = new Events();
        DirectRoomSocket host = new DirectRoomSocket(c, true, "http://127.0.0.1:10001/", key, h);
        DirectRoomSocket one = null, two = null;
        try {
            assertTrue(h.open.await(20, TimeUnit.SECONDS));
            one = new DirectRoomSocket(c, false, "http://127.0.0.1:10001/", key, device, a);
            two = new DirectRoomSocket(c, false, "http://127.0.0.1:10001/", key, b);
            assertTrue(a.open.await(30, TimeUnit.SECONDS)); assertTrue(b.open.await(30, TimeUnit.SECONDS));
            for (int attempt = 0; attempt < 3; attempt++) {
                a.texts.clear();
                closeDirectChannel(one);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40); boolean reconnected = false;
                while (System.nanoTime() < deadline) {
                    String text = a.texts.poll(1, TimeUnit.SECONDS);
                    if (text != null && new JSONObject(text).optBoolean("joined")) { reconnected = true; break; }
                }
                assertTrue("Did not recover: " + a.failure, reconnected);
                assertNull(a.failure); assertNull(b.failure); assertNull(h.failure);
                String message = "Voltou " + attempt;
                assertTrue(host.send(new JSONObject().put("type", "chat").put("text", message).toString()));
                assertEquals(message, a.matching("chat", message).getString("text"));
                assertEquals(message, b.matching("chat", message).getString("text"));
            }
            // Leave through the app repeatedly; the same installation never consumes a second slot.
            for (int i = 0; i < 3; i++) {
                one.cancel(); a = new Events();
                one = new DirectRoomSocket(c, false, "http://127.0.0.1:10001/", key, device, a);
                assertTrue("Returning guest: " + a.failure, a.open.await(30, TimeUnit.SECONDS));
                assertNull(b.failure); assertNull(h.failure);
            }
        } finally { host.cancel(); if (one != null) one.cancel(); if (two != null) two.cancel(); }
    }
    @Test public void sevenMinuteStreamSurvivesBrokerOutageAfterFiveMinutes() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        String key = RoomPassword.room(java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16));
        Events h = new Events(), a = new Events(), b = new Events();
        DirectRoomSocket host = new DirectRoomSocket(c, true, "http://127.0.0.1:10001/", key, h), one = null, two = null;
        try {
            assertTrue(h.open.await(20, TimeUnit.SECONDS));
            one = new DirectRoomSocket(c, false, "http://127.0.0.1:10001/", key, a);
            two = new DirectRoomSocket(c, false, "http://127.0.0.1:10001/", key, b);
            assertTrue(a.open.await(30, TimeUnit.SECONDS)); assertTrue(b.open.await(30, TimeUnit.SECONDS));
            host.send(ByteString.of(StreamPacket.pack(5, 0, 0, new byte[]{1, 2})));
            byte[] payload = new byte[32768]; new java.util.Random(23).nextBytes(payload);
            long start = android.os.SystemClock.elapsedRealtime(), next = start; int frames = 0; boolean outage = false;
            while (android.os.SystemClock.elapsedRealtime() - start < 420000) {
                long elapsed = android.os.SystemClock.elapsedRealtime() - start;
                if (!outage && elapsed >= 300000) { disconnectBroker(); outage = true; }
                ByteString frame = ByteString.of(StreamPacket.pack(6, frames * 33333L, 1, payload));
                assertTrue(host.send(frame));
                for (Events event : new Events[]{a, b}) {
                    ByteString got;
                    do { got = event.packets.poll(5, TimeUnit.SECONDS); assertNotNull("Media stopped at " + elapsed + "ms: " + event.failure, got); } while (got.getByte(0) == 5);
                    assertEquals(frame, got);
                }
                assertNull(h.failure); assertNull(a.failure); assertNull(b.failure);
                frames++; next += 33; long delay = next - android.os.SystemClock.elapsedRealtime();
                if (delay > 0) Thread.sleep(delay);
            }
            assertTrue(outage); assertTrue("Insufficient sustained traffic: " + frames, frames > 9000);
            host.send("{\"type\":\"chat\",\"text\":\"Sete minutos\"}");
            assertEquals("Sete minutos", a.type("chat").getString("text"));
            assertEquals("Sete minutos", b.type("chat").getString("text"));
        } finally { host.cancel(); if (one != null) one.cancel(); if (two != null) two.cancel(); }
    }
    @Test public void chatOnlyReplaysNamedMessagesAndReconnectsWithoutMedia() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        String endpoint = "http://127.0.0.1:10001/";
        String key = RoomPassword.room(java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        String device = java.util.UUID.randomUUID().toString();
        Events h = new Events(), v = new Events(), initialChat = new Events(), reopenedChat = new Events();
        DirectRoomSocket host = new DirectRoomSocket(c, true, endpoint, key, h);
        DirectRoomSocket viewer = null, chat = null;
        try {
            assertTrue(h.open.await(20, TimeUnit.SECONDS));
            viewer = new DirectRoomSocket(c, false, endpoint, key, v);
            assertTrue(v.open.await(30, TimeUnit.SECONDS));
            assertTrue(host.send("{\"type\":\"chat\",\"text\":\"Boa sessão!\"}"));
            assertEquals("Boa sessão!", v.type("chat").getString("text"));

            chat = new DirectRoomSocket(c, false, endpoint, key, device, true, "Welliton", initialChat);
            assertTrue(initialChat.open.await(30, TimeUnit.SECONDS));
            JSONObject welcome = initialChat.type("chat");
            assertTrue(welcome.getBoolean("history"));
            assertEquals("Anfitrião", welcome.getString("name"));
            assertEquals("Boa sessão!", welcome.getString("text"));

            assertTrue(chat.send("{\"type\":\"chat\",\"text\":\"Estou no celular\"}"));
            JSONObject message = v.type("chat");
            assertEquals("Welliton", message.getString("name"));
            assertFalse(message.getBoolean("own"));
            assertTrue(initialChat.type("chat").getBoolean("own"));

            ByteString config = ByteString.of(StreamPacket.pack(5, 0, 0, new byte[]{1, 2}));
            ByteString frame = ByteString.of(StreamPacket.pack(6, 1000, 1, new byte[]{1, 2}));
            host.send(config); host.send(frame);
            ByteString received;
            do { received = v.packets.poll(10, TimeUnit.SECONDS); assertNotNull(received); } while (received.getByte(0) == 5);
            assertEquals(frame, received);
            host.send("{\"type\":\"talk\",\"active\":true}");
            assertTrue(v.type("talk").getBoolean("active"));
            ByteString speech = ByteString.of(StreamPacket.pack(7, 1000, 0, new byte[]{0, 0}));
            host.send(speech);
            assertEquals(speech, v.packets.poll(10, TimeUnit.SECONDS));
            assertNull(initialChat.packets.poll(300, TimeUnit.MILLISECONDS));

            chat.cancel();
            chat = new DirectRoomSocket(c, false, endpoint, key, device, true, "Welliton", reopenedChat);
            assertTrue(reopenedChat.open.await(30, TimeUnit.SECONDS));
            assertEquals(welcome.getLong("id"), reopenedChat.type("chat").getLong("id"));
            JSONObject restored = reopenedChat.type("chat");
            assertEquals(message.getLong("id"), restored.getLong("id"));
            assertTrue(restored.getBoolean("own"));
            assertTrue(restored.getBoolean("history"));

            closeDirectChannel(chat);
            assertEquals(welcome.getLong("id"), reopenedChat.type("chat").getLong("id"));
            assertEquals(message.getLong("id"), reopenedChat.type("chat").getLong("id"));
            assertNull(v.failure);
        } finally { host.cancel(); if (viewer != null) viewer.cancel(); if (chat != null) chat.cancel(); }
    }
    void verifyThreePeers(String endpoint) throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        String key = RoomPassword.room(java.util.UUID.randomUUID().toString().replace("-", "").substring(0,16));
        Events h = new Events(), a = new Events(), b = new Events(), extra = new Events();
        DirectRoomSocket host = new DirectRoomSocket(c, true, endpoint, key, h), one = null, two = null, fourth = null;
        try {
            assertTrue("Host: " + h.failure, h.open.await(30, TimeUnit.SECONDS));
            one = new DirectRoomSocket(c, false, endpoint, key, a);
            two = new DirectRoomSocket(c, false, endpoint, key, b);
            assertTrue("First: " + a.failure, a.open.await(30, TimeUnit.SECONDS));
            assertTrue("Second: " + b.failure, b.open.await(30, TimeUnit.SECONDS));
            fourth = new DirectRoomSocket(c, false, endpoint, key, extra);
            assertTrue("Third viewer: " + extra.failure, extra.open.await(30, TimeUnit.SECONDS));
            ByteString config = ByteString.of(StreamPacket.pack(StreamPacket.AVC_CONFIG, 0, 0, new byte[]{1, 2}));
            byte[] payload = new byte[400000];
            ByteString frame = ByteString.of(StreamPacket.pack(StreamPacket.AVC_FRAME, 1000, 1, payload));
            assertTrue(host.send(config)); assertTrue(host.send(frame));
            for (Events e : new Events[]{a, b, extra}) {
                ByteString got; int n = 0;
                do { got = e.packets.poll(20, TimeUnit.SECONDS); assertNotNull(got); } while (!frame.equals(got) && ++n < 5);
                assertEquals(frame, got);
            }
            one.send("{\"type\":\"chat\",\"text\":\"Olá três!\"}");
            JSONObject hostMessage = h.type("chat");
            JSONObject ownMessage = a.type("chat");
            assertFalse(hostMessage.getBoolean("own"));
            assertTrue(ownMessage.getBoolean("own"));
            assertFalse(b.type("chat").getBoolean("own"));
            assertEquals(hostMessage.getString("name"), ownMessage.getString("name"));
            one.send("{\"type\":\"talk\",\"active\":true}");
            assertEquals("viewer", h.type("talk").getString("from"));
            assertEquals("viewer", a.type("talk").getString("from"));
            assertEquals("host", b.type("talk").getString("from"));
            ByteString speech = ByteString.of(StreamPacket.pack(7, 1000, 0, new byte[]{0, 0}));
            one.send(speech); assertEquals(speech, h.packets.poll(10, TimeUnit.SECONDS));
            assertEquals(speech, b.packets.poll(10, TimeUnit.SECONDS));
            one.cancel(); one = null;
            assertFalse(h.type("talk").getBoolean("active")); assertFalse(b.type("talk").getBoolean("active"));
            host.send("{\"type\":\"chat\",\"text\":\"Continuou\"}");
            assertEquals("Continuou", b.matching("chat", "Continuou").getString("text"));
        } finally { host.cancel(); if (one != null) one.cancel(); if (two != null) two.cancel(); if (fourth != null) fourth.cancel(); }
    }
}
