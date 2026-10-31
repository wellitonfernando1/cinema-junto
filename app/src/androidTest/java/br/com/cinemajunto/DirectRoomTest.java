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
        JSONObject type(String type) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < end) {
                String text = texts.poll(1, TimeUnit.SECONDS);
                if (text != null) { JSONObject data = new JSONObject(text); if (type.equals(data.optString("type"))) return data; }
            }
            fail("Missing " + type + ": " + failure); return null;
        }
    }
    @Test public void threePeersAutomaticallyConnectAndExchangeMovieChatAndVoice() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        String key = RoomPassword.room("gato"); String endpoint = "http://127.0.0.1:10000/";
        Events h = new Events(), a = new Events(), b = new Events(), full = new Events();
        DirectRoomSocket host = new DirectRoomSocket(c, true, endpoint, key, h), one = null, two = null, fourth = null;
        try {
            assertTrue("Host: " + h.failure, h.open.await(30, TimeUnit.SECONDS));
            one = new DirectRoomSocket(c, false, endpoint, key, a);
            assertTrue("First: " + a.failure, a.open.await(60, TimeUnit.SECONDS));
            two = new DirectRoomSocket(c, false, endpoint, key, b);
            assertTrue("Second: " + b.failure, b.open.await(60, TimeUnit.SECONDS));
            fourth = new DirectRoomSocket(c, false, endpoint, key, full);
            assertTrue(full.failed.await(15, TimeUnit.SECONDS)); assertTrue(full.failure.contains("Sala cheia"));
            ByteString config = ByteString.of(StreamPacket.pack(StreamPacket.AVC_CONFIG, 0, 0, new byte[]{1, 2}));
            byte[] payload = new byte[400000];
            ByteString frame = ByteString.of(StreamPacket.pack(StreamPacket.AVC_FRAME, 1000, 1, payload));
            assertTrue(host.send(config)); assertTrue(host.send(frame));
            for (Events e : new Events[]{a, b}) {
                ByteString got; int n = 0;
                do { got = e.packets.poll(20, TimeUnit.SECONDS); assertNotNull(got); } while (!frame.equals(got) && ++n < 5);
                assertEquals(frame, got);
            }
            one.send("{\"type\":\"chat\",\"text\":\"Olá três!\"}");
            assertEquals("viewer", h.type("chat").getString("from"));
            assertEquals("viewer", a.type("chat").getString("from"));
            assertEquals("host", b.type("chat").getString("from"));
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
            assertEquals("Continuou", b.type("chat").getString("text"));
        } finally { host.cancel(); if (one != null) one.cancel(); if (two != null) two.cancel(); if (fourth != null) fourth.cancel(); }
    }
}
