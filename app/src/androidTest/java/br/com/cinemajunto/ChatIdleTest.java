package br.com.cinemajunto;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ChatIdleTest {
    Instrumentation instrumentation;
    MainActivity activity;
    HostChatOverlay overlay;
    UiDevice device;

    interface Condition { boolean ready(); }

    void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    boolean read(Condition condition) {
        AtomicBoolean value = new AtomicBoolean();
        instrumentation.runOnMainSync(() -> value.set(condition.ready()));
        return value.get();
    }

    void until(String message, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync();
            if (condition.ready()) return;
            Thread.sleep(100);
        }
        fail(message);
    }

    @Before public void launch() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity)instrumentation.startActivitySync(intent);
        main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        awaitStableSetup();
    }

    void awaitStableSetup() throws Exception {
        // A freshly booted Android 15 can apply its theme assets and recreate the activity.
        // Finish that setup before opening chat, keeping all inactivity assertions unchanged.
        long deadline = SystemClock.elapsedRealtime() + 10000, stableSince = 0;
        MainActivity previous = null;
        String previousConfiguration = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            AtomicReference<MainActivity> current = new AtomicReference<>();
            AtomicReference<String> configuration = new AtomicReference<>();
            main(() -> {
                for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (!(candidate instanceof MainActivity)) continue;
                    MainActivity resumed = (MainActivity)candidate;
                    if (resumed.destroyed || !resumed.hasWindowFocus() || resumed.root.getWidth() == 0) continue;
                    if (resumed.getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) {
                        resumed.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); continue;
                    }
                    current.set(resumed);
                    configuration.set(resumed.getResources().getConfiguration().toString());
                }
            });
            long now = SystemClock.elapsedRealtime();
            MainActivity resumed = current.get();
            String stamp = configuration.get();
            if (resumed == null) stableSince = 0;
            else {
                activity = resumed;
                if (resumed != previous || !stamp.equals(previousConfiguration) || stableSince == 0) stableSince = now;
                else if (now - stableSince >= 2000) return;
            }
            previous = resumed; previousConfiguration = stamp;
            Thread.sleep(50);
        }
        fail("The resumed portrait activity did not reach a stable setup before the chat test");
    }

    @After public void close() {
        main(() -> {
            if (overlay != null) overlay.close();
            if (activity != null) {
                activity.closeChat();
                activity.watching = false;
                activity.finish();
            }
        });
    }

    void assertReadableInviteField(EditText field) {
        assertEquals("Field text must stay black with either system theme", Color.BLACK, field.getCurrentTextColor());
        assertTrue("Field must have an explicit white background", field.getBackground() instanceof ColorDrawable);
        assertEquals(Color.WHITE, ((ColorDrawable)field.getBackground()).getColor());
        int hint = field.getCurrentHintTextColor();
        assertEquals("Hint must be gray", Color.red(hint), Color.green(hint));
        assertEquals("Hint must be gray", Color.green(hint), Color.blue(hint));
        assertTrue("Hint must contrast with white", Color.red(hint) > 0 && Color.red(hint) <= 128);
    }

    @Test public void invitationAndEndpointHaveExplicitReadableColors() {
        main(() -> {
            assertReadableInviteField(activity.invitation);
            assertReadableInviteField(activity.endpoint);
            boolean hostChatButton = false;
            for (int i = 0; i < activity.controls.getChildCount(); i++) {
                if (activity.controls.getChildAt(i) instanceof Button
                        && "Chat de quem transmite".contentEquals(((Button)activity.controls.getChildAt(i)).getText()))
                    hostChatButton = true;
            }
            assertTrue("Host chat must be available on every supported Android version", hostChatButton);
        });
    }

    @Test public void viewerIdleKeyboardClosesAndKeepsDraftWithTimerReset() throws Exception {
        long opened = SystemClock.elapsedRealtime();
        main(() -> {
            activity.watching = true;
            activity.updateLayout();
            activity.openChat();
        });
        until("Viewer keyboard never opened", 4000, () -> read(() -> activity.imeVisible));
        // Editing after the initial opening proves that inactivity restarts from the edit.
        Thread.sleep(Math.max(0, opened + 2500 - SystemClock.elapsedRealtime()));
        String draft = "Vou continuar assistindo 😀";
        main(() -> {
            assertTrue("Chat closed while preparing a draft", activity.chatOpen);
            activity.chatInput.setText(draft);
            activity.chatInput.setSelection(draft.length());
        });
        long edited = SystemClock.elapsedRealtime();
        Thread.sleep(3000);
        assertTrue("Editing did not restart the five-second timer", read(() -> activity.chatOpen));
        until("Viewer chat did not close after five seconds idle", 4000, () -> read(() -> !activity.chatOpen));
        assertTrue("Viewer keyboard closed too early", SystemClock.elapsedRealtime() - edited >= 4500);
        until("Viewer keyboard stayed visible after idle close", 2500, () -> read(() -> !activity.imeVisible));
        main(() -> {
            assertEquals("Idle close erased the viewer draft", draft, activity.chatInput.getText().toString());
            activity.openChat();
            assertTrue(activity.chatOpen);
            assertEquals("Reopening erased the viewer draft", draft, activity.chatInput.getText().toString());
        });
    }

    @Test public void hostIdleKeyboardClosesAboveLauncherAndKeepsDraft() throws Exception {
        assertTrue("Overlay permission missing", Settings.canDrawOverlays(activity));
        main(() -> {
            overlay = new HostChatOverlay(activity.getApplicationContext(), text -> true);
            overlay.show();
        });
        device.pressHome();
        until("Host button is not visible above the launcher", 4000,
            () -> device.hasObject(By.desc("Abrir chat flutuante")));
        main(() -> overlay.open());
        until("Host keyboard did not appear", 4000,
            () -> read(() -> overlay.input != null && overlay.input.hasWindowFocus())
                && device.hasObject(By.pkg("com.android.inputmethod.latin")));
        String draft = "Estou aqui ❤️";
        main(() -> {
            assertNotNull(overlay.input);
            overlay.input.setText(draft);
            overlay.input.setSelection(draft.length());
        });
        long edited = SystemClock.elapsedRealtime();
        Thread.sleep(3000);
        assertTrue("Host keyboard closed before five seconds idle", read(() -> overlay.composer != null));
        until("Host composer did not close after five seconds idle", 5000,
            () -> read(() -> overlay.composer == null));
        assertTrue("Host composer closed too early", SystemClock.elapsedRealtime() - edited >= 4500);
        until("Host keyboard stayed visible after idle close", 2500,
            () -> !device.hasObject(By.pkg("com.android.inputmethod.latin")));
        main(() -> {
            assertEquals("Idle close erased the host draft", draft, overlay.draft);
            assertNotNull("Idle close removed the compact chat button", overlay.bubble);
            overlay.open();
            assertNotNull("Host composer did not reopen", overlay.composer);
            assertEquals("Reopening erased the host draft", draft, overlay.input.getText().toString());
            assertEquals("Reopening lost the cursor position", draft.length(), overlay.input.getSelectionStart());
        });
    }
}
