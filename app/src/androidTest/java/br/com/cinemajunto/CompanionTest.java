package br.com.cinemajunto;
import android.content.Context;
import android.content.pm.*;
import android.media.AudioManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;

public class CompanionTest {
    @Test public void opensWithoutJoiningOrRecording() {
        try (ActivityScenario<CompanionActivity> scenario = ActivityScenario.launch(CompanionActivity.class)) {
            scenario.onActivity(a -> {
                assertNotNull(a.history);
                assertEquals("Chat Filme", ((android.widget.TextView)a.layout.getChildAt(0)).getText().toString());
                assertNull(CompanionService.instance);
            });
        }
    }
    @Test public void textRoomDoesNotRequireMicrophonePermission() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();
        try(ActivityScenario<CompanionActivity> scenario=ActivityScenario.launch(CompanionActivity.class)){
            scenario.onActivity(a->{a.password.setText("textonlytest");a.join("host");});
            long until=android.os.SystemClock.elapsedRealtime()+5000;
            while(CompanionService.instance==null && android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            assertNotNull("A text room must start without microphone access",CompanionService.instance);
            assertEquals(PackageManager.PERMISSION_DENIED,c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO));
        } finally {c.stopService(new android.content.Intent(c,CompanionService.class));Thread.sleep(300);}
    }
    @Test public void companionDoesNotDeclareScreenCapture() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();
        PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),PackageManager.GET_PERMISSIONS|PackageManager.GET_ACTIVITIES);
        for(String permission:p.requestedPermissions) assertNotEquals("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",permission);
        assertEquals(2,p.activities.length);
        assertTrue(java.util.Arrays.stream(p.activities).anyMatch(a -> a.name.equals("br.com.cinemajunto.SplitChatActivity") && !a.exported));
    }
    @Test public void voiceCanBeCancelledBeforeGrantWithoutChangingAudioMode() {
        Context c=ApplicationProvider.getApplicationContext();
        AudioManager audio=c.getSystemService(AudioManager.class);
        int mode=audio.getMode();
        ArrayList<String> controls=new ArrayList<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CompanionVoice voice=new CompanionVoice(c,"host",new CompanionVoice.Listener(){
                public boolean sendText(String json){controls.add(json);return true;}
                public boolean sendBinary(byte[] data){fail("No audio before the server grants the turn");return false;}
                public void muteMovie(boolean muted){assertFalse(muted);}
                public void error(String message){}
            });
            try {
                voice.press(); assertTrue(voice.pending); assertFalse(voice.isTalking());
                voice.release(); assertFalse(voice.pending); assertFalse(voice.held);
                assertTrue(controls.get(0).contains("true"));
                assertTrue(controls.get(1).contains("false"));
                assertEquals(mode,audio.getMode());
            } finally {voice.close();}
        });
    }
}
