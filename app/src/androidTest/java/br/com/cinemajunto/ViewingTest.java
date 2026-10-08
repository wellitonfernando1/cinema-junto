package br.com.cinemajunto;

import android.app.Instrumentation;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.By;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ViewingTest {
    Instrumentation instrumentation;
    MainActivity activity;
    UiDevice device;
    HostChatOverlay overlay;
    void main(Runnable action) { instrumentation.runOnMainSync(action); instrumentation.waitForIdleSync(); }
    interface Condition { boolean ready(); }
    void until(String message, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync(); if (condition.ready()) return; Thread.sleep(100);
        }
        fail(message);
    }
    @Before public void launch() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation(); device = UiDevice.getInstance(instrumentation);
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity)instrumentation.startActivitySync(intent);
        main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        until("Activity did not reach portrait", () -> activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT);
    }
    @After public void close() { main(() -> { if (overlay != null) overlay.close(); if (activity != null) activity.finish(); }); }
    void startFilm() throws Exception {
        Bitmap frame = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(frame); c.drawColor(Color.GREEN);
        Paint p = new Paint(); p.setColor(Color.BLUE); c.drawRect(0, 0, 160, 540, p);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); frame.compress(Bitmap.CompressFormat.JPEG, 90, output); frame.recycle();
        Bitmap poster = BitmapFactory.decodeByteArray(output.toByteArray(), 0, output.size());
        main(() -> { activity.watching = true; activity.updateLayout(); activity.startPlayback(); activity.screen.setFrame(poster); activity.loading.setVisibility(View.GONE); });
        until("Incoming JPEG was not drawn", () -> activity.screen.hasFrame());
    }
    void fullscreen() throws Exception {
        main(() -> activity.enterFullscreen());
        until("Full-screen rotation failed", () -> activity.screen.getWidth() > activity.screen.getHeight()
            && activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
    }
    Bitmap screenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(bitmap);
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "ui-test"); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        return bitmap;
    }
    void assertFilmVisible(String name) throws Exception {
        // View layout may complete before the system compositor releases its rotation snapshot.
        long deadline = SystemClock.elapsedRealtime() + 10000; int color = 0;
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync(); Thread.sleep(150);
            Bitmap shot = screenshot(name);
            int[] position = new int[2]; main(() -> activity.screen.getLocationOnScreen(position));
            int x = Math.min(shot.getWidth()-1, position[0] + activity.screen.getWidth()/2);
            int y = Math.min(shot.getHeight()-1, position[1] + activity.screen.getHeight()/2);
            color = shot.getPixel(x,y); shot.recycle();
            if (Color.green(color) > 160 && Color.red(color) < 70) return;
        }
        fail("The visible movie became black: " + Integer.toHexString(color));
    }
    @Test public void fullscreenKeepsDecodedMovieAcrossRotation() throws Exception {
        startFilm(); Thread receiver = activity.playback.worker; int generation = activity.playbackGeneration;
        fullscreen(); assertFilmVisible("fullscreen");
        assertSame("Rotation replaced the playback thread", receiver, activity.playback.worker);
        assertEquals(generation, activity.playbackGeneration);
        main(() -> activity.exitFullscreen()); assertFilmVisible("after-fullscreen");
        assertTrue(activity.watching);
    }
    @Test public void keyboardShrinksMovieAndBackRestoresFullscreen() throws Exception {
        startFilm(); fullscreen(); int height = activity.screen.getHeight();
        main(() -> activity.openChat());
        until("Keyboard never opened", () -> activity.imeVisible);
        until("Movie did not shrink above the keyboard", () -> activity.screen.getHeight() < height && activity.screen.getHeight() > activity.dp(70));
        assertTrue("Chat obscures too much of the film", activity.chatPanel.getHeight() <= activity.dp(100));
        assertFilmVisible("film-with-keyboard");
        device.pressBack(); until("Chat did not close with the keyboard", () -> !activity.chatOpen && !activity.imeVisible);
        until("Movie did not reclaim the screen", () -> activity.screen.getHeight() >= height - activity.dp(30));
        assertTrue(activity.fullscreen); assertTrue(activity.watching); assertFilmVisible("fullscreen-restored");
    }
    @Test public void incomingMessageIsSmallAndDoesNotInterruptMovie() throws Exception {
        startFilm(); fullscreen(); int height = activity.screen.getHeight();
        main(() -> activity.receiveChat("host", "Olá 😀"));
        until("Incoming message did not appear", () -> activity.messageBanner.getVisibility() == View.VISIBLE);
        assertTrue(activity.messageBanner.getText().toString().contains("Olá 😀"));
        assertTrue(activity.messageBanner.getHeight() < activity.dp(90));
        assertEquals(height, activity.screen.getHeight()); assertFilmVisible("message-on-film");
    }
    @Test public void hostReadsAndRepliesAboveAnotherApp() throws Exception {
        assertTrue("Overlay permission missing", Settings.canDrawOverlays(activity));
        AtomicReference<String> sent = new AtomicReference<>();
        main(() -> { overlay = new HostChatOverlay(activity.getApplicationContext(), text -> { sent.set(text); return true; }); overlay.show(); });
        device.pressHome();
        until("Host button did not attach", () -> overlay.bubble != null && overlay.bubble.isAttachedToWindow());
        until("Host button is not visible over the launcher", () -> device.hasObject(By.desc("Abrir chat flutuante")));
        Bitmap before = screenshot("host-before-message");
        main(() -> overlay.receive("viewer", "Estou assistindo ❤️"));
        until("Message is not above the other app", () -> overlay.banner != null && overlay.banner.isAttachedToWindow());
        // Non-touchable overlay windows are omitted from the accessibility tree; verify their pixels.
        until("Host message has no visible bounds", () -> overlay.banner.getHeight() > 0);
        int[] bannerPosition = new int[2]; main(() -> overlay.banner.getLocationOnScreen(bannerPosition));
        long deadline = SystemClock.elapsedRealtime() + 4000; boolean changed = false;
        while (!changed && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(150); Bitmap after = screenshot("host-message-over-launcher"); int different = 0, total = 0;
            for (int y = bannerPosition[1]; y < Math.min(after.getHeight(), bannerPosition[1]+overlay.banner.getHeight()); y += 4) {
                for (int x = 0; x < after.getWidth(); x += 4) {
                    int a = after.getPixel(x,y), b = before.getPixel(x,y); total++;
                    if (Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b)) > 35) different++;
                }
            }
            changed = different > total * 0.3f; after.recycle();
        }
        before.recycle(); assertTrue("Host banner was attached but hidden in the screenshot", changed);
        main(() -> overlay.open()); until("Overlay keyboard did not open", () -> overlay.input != null && overlay.input.hasWindowFocus());
        until("Host keyboard is not actually visible", () -> device.hasObject(By.pkg("com.android.inputmethod.latin")));
        assertNotNull("Keyboard closed the overlay unexpectedly", overlay.composer);
        main(() -> { overlay.input.setText("Oi 😀"); overlay.send(); });
        assertEquals("Oi 😀", sent.get()); assertEquals("", overlay.input.getText().toString());
        assertTrue("Host composer covers the entire screen", overlay.composer.getHeight() <= activity.dp(130));
        screenshot("host-reply-with-keyboard").recycle();
        device.pressBack(); until("Host chat did not close on Back", () -> overlay.composer == null);
        main(() -> overlay.close()); assertNull(overlay.bubble); assertNull(overlay.banner);
    }
}
