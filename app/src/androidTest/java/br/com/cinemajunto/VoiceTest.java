package br.com.cinemajunto;

import android.Manifest;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoiceTest {
    Instrumentation instrumentation;
    MainActivity activity;
    final List<VoiceTalk> talks = new ArrayList<>();

    interface Condition { boolean ready(); }

    void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    void until(String message, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
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
        until("Voice test Activity did not reach portrait", 8000,
            () -> activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);
    }

    @After public void close() {
        for (VoiceTalk talk : talks) talk.close();
        main(() -> { if (activity != null) activity.finish(); });
    }

    static final class FakeListener implements VoiceTalk.Listener {
        final ConcurrentLinkedQueue<JSONObject> controls = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        final AtomicBoolean muted = new AtomicBoolean();
        final AtomicInteger packets = new AtomicInteger();
        final AtomicReference<StreamPacket.Packet> lastPacket = new AtomicReference<>();
        public boolean sendText(String json) {
            try { controls.add(new JSONObject(json)); return true; }
            catch (Exception error) { throw new AssertionError("Voice produced invalid control JSON", error); }
        }
        public boolean sendBinary(byte[] data) {
            lastPacket.set(StreamPacket.unpack(data)); packets.incrementAndGet(); return true;
        }
        public void muteMovie(boolean value) { muted.set(value); }
        public void error(String message) { errors.add(message); }
    }

    VoiceTalk talk(String role, FakeListener listener) {
        VoiceTalk talk = new VoiceTalk(activity, role, listener);
        talks.add(talk); return talk;
    }

    JSONObject control(String from, boolean active) throws Exception {
        return new JSONObject().put("type", "talk").put("from", from).put("active", active);
    }

    void touch(VoiceButton button, int action) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, 30, 20, 0);
        try { assertTrue(button.onTouchEvent(event)); }
        finally { event.recycle(); }
    }

    @Test public void buttonReleasesOnCancelUpAndDetach() {
        AtomicInteger presses = new AtomicInteger(), releases = new AtomicInteger();
        main(() -> {
            VoiceButton button = new VoiceButton(activity, presses::incrementAndGet, releases::incrementAndGet);
            activity.root.addView(button, new FrameLayout.LayoutParams(activity.dp(220), activity.dp(48)));
            assertTrue(button.getContentDescription().toString().contains("Segure"));
            touch(button, MotionEvent.ACTION_DOWN);
            assertEquals(1, presses.get()); assertTrue(button.held);
            touch(button, MotionEvent.ACTION_CANCEL);
            assertEquals(1, releases.get()); assertFalse(button.held);
            touch(button, MotionEvent.ACTION_DOWN);
            touch(button, MotionEvent.ACTION_UP);
            assertEquals(2, presses.get()); assertEquals(2, releases.get()); assertFalse(button.held);
            touch(button, MotionEvent.ACTION_DOWN);
            activity.root.removeView(button);
            assertEquals(3, presses.get()); assertEquals(3, releases.get()); assertFalse(button.held);
        });
    }

    @Test public void bothRolesRecordOnlyWhileHeldAfterGrantAndReleaseStopsPackets() throws Exception {
        assertEquals("CI must grant microphone permission", PackageManager.PERMISSION_GRANTED,
            activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO));
        for (String role : new String[]{"viewer", "host"}) {
            FakeListener listener = new FakeListener();
            VoiceTalk talk = talk(role, listener);
            main(talk::press);
            Thread.sleep(200);
            assertFalse("Microphone started before the server granted the turn", talk.isTalking());
            assertNull("Waiting for a grant opened the microphone", talk.microphone);
            assertEquals("Waiting for a grant sent voice audio", 0, listener.packets.get());
            assertTrue("Pending voice did not mute the film", listener.muted.get());
            JSONObject begin = listener.controls.peek(); assertNotNull(begin);
            assertEquals("talk", begin.optString("type")); assertTrue(begin.optBoolean("active"));
            talk.onControl(control(role, true));
            until("Actual microphone did not produce voice packets for " + role, 5000,
                () -> listener.packets.get() >= 3);
            assertTrue(talk.isTalking()); assertNotNull(talk.microphone);
            StreamPacket.Packet packet = listener.lastPacket.get();
            assertEquals(7, packet.type); assertEquals(640, packet.payload.length);
            assertTrue("Voice packet lost its capture timestamp", packet.timestampUs > 0);
            // Repeated acknowledgements must not stop the active recorder.
            int beforeAck = listener.packets.get();
            talk.onControl(control(role, true));
            until("A duplicate turn grant stopped the microphone", 1500,
                () -> listener.packets.get() >= beforeAck + 2);
            main(talk::release);
            int released = listener.packets.get();
            assertFalse(talk.isTalking()); assertFalse("Releasing voice did not restore film volume", listener.muted.get());
            Thread.sleep(160);
            assertTrue("Voice packets continued after the finger was released", listener.packets.get() <= released + 1);
            until("Microphone was not released", 1000, () -> talk.microphone == null);
            JSONObject last = null; for (JSONObject message : listener.controls) last = message;
            assertNotNull(last); assertFalse("Releasing did not end the room's voice turn", last.optBoolean("active"));
            assertTrue("Voice encountered an audio device failure: " + listener.errors, listener.errors.isEmpty());
            talk.onControl(control(role, true));
            assertFalse("A late grant reopened the microphone after release", talk.isTalking());
            assertNull(talk.microphone);
            talk.close();
            until("Voice player survived disconnect", 1000, () -> !talk.player.isAlive());
            assertFalse(listener.muted.get());
        }
    }

    @Test public void peerTurnMutesFilmWithoutOpeningMicrophoneAndDisconnectRestoresVolume() throws Exception {
        FakeListener listener = new FakeListener();
        VoiceTalk talk = talk("viewer", listener);
        main(talk::press);
        talk.onControl(control("host", true));
        assertTrue("Incoming voice did not mute the film", listener.muted.get());
        assertFalse(talk.isTalking()); assertNull("Busy turn opened our microphone", talk.microphone);
        talk.onAudio(StreamPacket.pack(7, 1_000_000L, 0, new byte[640]));
        Thread.sleep(120);
        assertEquals("Listening to a peer sent microphone packets", 0, listener.packets.get());
        assertNull(talk.microphone);
        main(talk::release);
        assertTrue("Releasing our button unmuted over the peer's speech", listener.muted.get());
        talk.onControl(control("host", false));
        assertFalse("Peer release did not restore film volume", listener.muted.get());
        talk.onControl(control("host", true));
        talk.close();
        assertFalse("Disconnect left the movie muted", listener.muted.get());
        assertFalse(talk.isTalking()); assertNull(talk.microphone);
        until("Voice player survived disconnect", 1000, () -> !talk.player.isAlive());
    }
}
