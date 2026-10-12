package br.com.cinemajunto;

import android.media.AudioManager;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class VoiceMovieFocusTest {
    @Test public void temporaryMovieDuckingPreservesSpeechVolumeAndReleasesFocus() {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        MainActivity activity = (MainActivity)instrumentation.startActivitySync(
            new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AudioManager manager = activity.getSystemService(AudioManager.class);
        int volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
        VoiceMovieFocus focus = new VoiceMovieFocus(manager, new Handler(Looper.getMainLooper()));
        try {
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, focus.request.getFocusGain());
            assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, focus.request.getAudioAttributes().getContentType());
            assertTrue(focus.setListening(true));
            assertTrue(focus.held);
            assertEquals(volume, manager.getStreamVolume(AudioManager.STREAM_MUSIC));
            assertTrue(focus.setListening(true));
            assertTrue(focus.setListening(false));
            assertFalse(focus.held);
            assertEquals(volume, manager.getStreamVolume(AudioManager.STREAM_MUSIC));
            assertTrue(focus.setListening(true));
            focus.close();
            assertFalse(focus.held);
        } finally {
            focus.close();
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
