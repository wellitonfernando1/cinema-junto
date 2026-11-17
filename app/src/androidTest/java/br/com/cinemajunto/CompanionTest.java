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
                assertEquals("Cinema Conversa · experimental", ((android.widget.TextView)a.layout.getChildAt(0)).getText().toString());
                assertNull(CompanionService.instance);
            });
        }
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
