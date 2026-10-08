package br.com.cinemajunto;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.os.Build;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import java.util.Locale;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Short passwords are the user-facing invitation; hashes and device IDs stay internal. */
@RunWith(AndroidJUnit4.class)
public class RoomPasswordTest {
    Instrumentation instrumentation;
    MainActivity activity;
    UiDevice device;

    void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    interface Condition { boolean ready(); }

    void until(String message, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 4000;
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync();
            if (condition.ready()) return;
            Thread.sleep(50);
        }
        try {
            File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "ui-test");
            directory.mkdirs();
            device.dumpWindowHierarchy(new File(directory, "room-password-api" + Build.VERSION.SDK_INT + ".xml"));
        } catch (Exception error) { Log.e("CinemaPasswordTest", "Could not save dialog evidence", error); }
        fail(message);
    }

    UiObject2 dialogButton(String resource, String label) throws Exception {
        // Dialog buttons can expose uppercase text depending on the Android theme.
        Pattern text = Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE);
        until("Dialog button was not visible: " + label,
            () -> device.hasObject(By.res("android", resource).pkg(activity.getPackageName()).text(text)));
        return device.findObject(By.res("android", resource).pkg(activity.getPackageName()).text(text));
    }

    @Before public void launch() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        activity = (MainActivity)instrumentation.startActivitySync(new Intent(
            instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @After public void close() {
        if (activity != null) main(() -> { activity.endSession(); activity.finish(); });
    }

    @Test public void equivalentWordsUseOneStableInternalRoomRegardlessOfLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("cinema2026", RoomPassword.normalize("  CINÉMA2026  "));
            assertEquals("cinema2026", RoomPassword.normalize("CINE\u0301MA2026"));
            String room = RoomPassword.room("  CINÉMA2026  ");
            assertEquals("e9c3f8090221ca1e5a20d3a2863b4323c0928f6f387a1de6a2a31af5e320b0a4", room);
            assertEquals(room, RoomPassword.room("cinema2026"));
            assertTrue("Only the internal relay room is a long hexadecimal value", room.matches("[a-f0-9]{64}"));
            assertNotEquals(room, RoomPassword.room("cinema2027"));
            assertEquals(4, RoomPassword.normalize("AB12").length());
            assertEquals(24, RoomPassword.normalize("abcdefghijklmnopqrstuvwx").length());
        } finally { Locale.setDefault(previous); }
    }

    @Test public void malformedPasswordsAreRejectedBeforeJoining() {
        String[] invalid = { null, "", "   ", "abc", "abcdefghijklmnopqrstuvwxy",
            "filme em casa", "casa!", "casa\namigo", "\u200bcinema", "cinema😀", "Straße" };
        for (String password : invalid) {
            try { RoomPassword.room(password); fail("Malformed password was accepted: " + password); }
            catch (IllegalArgumentException expected) { /* User can correct the password instead of joining a wrong room. */ }
        }
        main(() -> {
            activity.endpoint.setText("https://cinema-junto-welliton.onrender.com/");
            assertTrue("The invitation field must describe the short password",
                activity.invitation.getHint().toString().toLowerCase(Locale.ROOT).contains("senha"));
            for (String password : new String[]{"abc", "filme em casa", "filme!"}) {
                activity.invitation.setText(password);
                activity.startViewer();
                assertFalse("Invalid password started a viewer session", activity.watching);
                assertNull("Invalid password created a decoder", activity.playback);
                assertNull("Invalid password opened a relay socket", activity.socket);
                assertTrue("An invalid password needs a visible explanation of its limits",
                    activity.status.getText().toString().contains("4 a 24"));
            }
            activity.invitation.setText("pipoca42");
            boolean wasStarting = CaptureService.starting;
            try {
                CaptureService.starting = true; activity.startViewer();
                assertFalse("Connecting a host must block a simultaneous viewer", activity.watching);
                assertNull(activity.socket);
                CaptureService.starting = false; activity.hostFlowPending = true; activity.startViewer();
                assertFalse("Pending capture consent must block a simultaneous viewer", activity.watching);
                assertNull(activity.socket);
            } finally { CaptureService.starting = wasStarting; activity.hostFlowPending = false; }
        });
    }

    @Test public void deviceIdentityIsUuidAndPersistsForBothAppContexts() {
        Context context = instrumentation.getTargetContext();
        String first = RoomPassword.device(context);
        assertEquals(first, UUID.fromString(first).toString());
        assertEquals("The identity must survive another lookup", first, RoomPassword.device(activity));
        assertEquals("The identity must be stored across app restarts", first,
            context.getSharedPreferences("pairing", Context.MODE_PRIVATE).getString("device", null));
    }

    @Test public void hostChoosesTheWordAndSharingContainsOnlyThatWord() throws Exception {
        main(() -> {
            activity.endpoint.setText("https://cinema-junto-welliton.onrender.com/");
            activity.startHost();
        });
        until("The create-password dialog was missing", () -> device.hasObject(By.text("Criar senha da sala")));
        UiObject2 input = device.findObject(By.desc("Senha que você vai criar"));
        assertNotNull("The host needs a field for choosing the password", input);
        input.setText("  CINÉMA2026  ");
        dialogButton("button1", "Criar sala").click();
        dialogButton("button1", "Continuar");
        main(() -> {
            assertEquals("cinema2026", activity.pendingPassword);
            assertEquals(RoomPassword.room("cinema2026"), activity.pendingRoom);
        });
        dialogButton("button2", "Cancelar").click();

        AtomicReference<Intent> shared = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                if (!Intent.ACTION_CHOOSER.equals(intent.getAction())) return null;
                Intent send = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                shared.set(send);
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        boolean wasActive = CaptureService.active;
        SharedPreferences preferences = activity.getPreferences(Context.MODE_PRIVATE);
        String previousInvite = preferences.getString("hostInvite", null);
        try {
            main(() -> {
                // SourceCaptureTest covers this stored invitation after real projection consent.
                preferences.edit().putString("hostInvite", activity.pendingPassword).apply();
                activity.invitation.setText("outrasenha");
                CaptureService.active = true;
                activity.share();
            });
            Intent send = shared.get();
            assertNotNull("Sharing did not produce an invitation", send);
            assertEquals(Intent.ACTION_SEND, send.getAction());
            assertEquals("text/plain", send.getType());
            assertEquals("The friend should receive only the simple password", "cinema2026",
                send.getStringExtra(Intent.EXTRA_TEXT));
        } finally {
            main(() -> {
                CaptureService.active = wasActive;
                preferences.edit().putString("hostInvite", previousInvite).apply();
            });
            instrumentation.removeMonitor(monitor);
        }
    }
}
