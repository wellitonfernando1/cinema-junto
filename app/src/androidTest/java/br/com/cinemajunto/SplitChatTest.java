package br.com.cinemajunto;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import androidx.test.core.app.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SplitChatTest {
    void main(Runnable r){InstrumentationRegistry.getInstrumentation().runOnMainSync(r);}
    void shell(String command) throws Exception {
        try(ParcelFileDescriptor p=InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command);
            java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(p)) { while(in.read()!=-1){} }
    }
    @Test public void typingResetsThreeSecondDeadlineAndCancelStopsIt() throws Exception {
        AtomicInteger fired=new AtomicInteger();
        ChatIdleTimer timer=new ChatIdleTimer(new Handler(Looper.getMainLooper()),fired::incrementAndGet);
        main(timer::touch);Thread.sleep(1800);main(timer::touch);Thread.sleep(1800);
        assertEquals(0,fired.get());Thread.sleep(1500);assertEquals(1,fired.get());
        main(timer::touch);main(timer::cancel);Thread.sleep(3200);assertEquals(1,fired.get());
    }
    @Test public void opensRealSplitAndReturnsToMovieWithDraft() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();
        shell("appops set "+c.getPackageName()+" SYSTEM_ALERT_WINDOW allow");
        shell("settings put secure enabled_accessibility_services "+c.getPackageName()+"/br.com.cinemajunto.SplitScreenService");
        shell("settings put secure accessibility_enabled 1");
        try(ActivityScenario<CompanionActivity> scenario=ActivityScenario.launch(CompanionActivity.class)){
            scenario.onActivity(a -> {
                a.getSharedPreferences("conversation-layout",0).edit().putString("movie-label","Settings").commit();
                a.startForegroundService(new Intent(a,CompanionService.class).putExtra("room","testchatdraft").putExtra("role","host"));
            });
            long until=SystemClock.elapsedRealtime()+6000;
            while((CompanionService.instance==null || SplitScreenService.instance==null) && SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            assertNotNull("Accessibility helper did not start",SplitScreenService.instance);
            assertNotNull(CompanionService.instance);
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(1000);main(()->CompanionService.instance.openComposer());
            until=SystemClock.elapsedRealtime()+9000;
            while((SplitChatActivity.instance==null || !SplitChatActivity.instance.confirmedSplit) && SystemClock.elapsedRealtime()<until)Thread.sleep(100);
            main(()->{
                SplitChatActivity a=SplitChatActivity.instance;
                assertNotNull("Android did not open the chat alongside the existing movie task; " + (SplitScreenService.instance==null ? "helper stopped" : SplitScreenService.instance.diagnostic),a);
                assertTrue(a.isInMultiWindowMode());
                android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
                a.getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
                assertTrue("Expected portrait for video above chat",metrics.heightPixels>metrics.widthPixels);
                a.input.setText("rascunho preservado");
            });
            Thread.sleep(1500);
            main(()->{assertNotNull(SplitChatActivity.instance);SplitChatActivity.instance.input.append("!");});
            Thread.sleep(1800);assertNotNull("Closed before 3 seconds after last edit",SplitChatActivity.instance);
            Thread.sleep(1700);
            assertNull("Chat task should be removed after typing stops",SplitChatActivity.instance);
            assertEquals("rascunho preservado!",CompanionService.instance.draft);
        } finally {
            main(()->{if(SplitChatActivity.instance!=null)SplitChatActivity.instance.returnToMovie();});
            c.stopService(new Intent(c,CompanionService.class));
            shell("settings delete secure enabled_accessibility_services");
        }
    }
}
