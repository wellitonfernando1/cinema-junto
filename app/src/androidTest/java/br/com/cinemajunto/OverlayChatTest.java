package br.com.cinemajunto;

import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import androidx.test.core.app.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class OverlayChatTest {
    void main(Runnable r){InstrumentationRegistry.getInstrumentation().runOnMainSync(r);}
    void shell(String command) throws Exception {
        try(ParcelFileDescriptor p=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
            java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(p)){while(in.read()!=-1){}}
    }
    @Test public void opensKeyboardWithoutSplitAndKeepsDraftAcrossReopen() throws Exception {
        Context c=ApplicationProvider.getApplicationContext();
        shell("appops set "+c.getPackageName()+" SYSTEM_ALERT_WINDOW allow");
        shell("appops set "+c.getPackageName()+" WRITE_SETTINGS deny");
        try(ActivityScenario<CompanionActivity> scenario=ActivityScenario.launch(CompanionActivity.class)){
            scenario.onActivity(a->a.startForegroundService(new Intent(a,CompanionService.class).putExtra("room","overlaytest").putExtra("role","host")));
            long end=SystemClock.elapsedRealtime()+5000;
            while(CompanionService.instance==null && SystemClock.elapsedRealtime()<end)Thread.sleep(100);
            assertNotNull(CompanionService.instance);
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Thread.sleep(1200);
            main(()->{CompanionService s=CompanionService.instance;s.disconnect();s.setStatus("Sala desconectada");s.openComposer();});
            end=SystemClock.elapsedRealtime()+6000;
            while(!CompanionService.instance.composerKeyboardReady && SystemClock.elapsedRealtime()<end)Thread.sleep(100);
            main(()->{
                CompanionService s=CompanionService.instance;
                assertNotNull("Chat must open even while disconnected",s.composer);
                assertTrue("Native keyboard must be visible",s.composerKeyboardReady);
                assertTrue("History needs visible space",s.composerScroll.getHeight()>s.dp(24));
                assertFalse("No split transition is pending",s.openingChat);
                assertEquals(0,s.composer.getVisibility());
                s.input.setText("rascunho de teste");s.sendComposer();
                assertEquals("rascunho de teste",s.input.getText().toString());
            });
            Thread.sleep(1700);
            main(()->{assertNotNull(CompanionService.instance.input);CompanionService.instance.input.append("!");});
            Thread.sleep(1700);main(()->assertNotNull(CompanionService.instance.composer));
            Thread.sleep(1700);main(()->{assertNull(CompanionService.instance.composer);assertEquals("rascunho de teste!",CompanionService.instance.draft);CompanionService.instance.openComposer();});
            Thread.sleep(500);
            main(()->{CompanionService s=CompanionService.instance;assertNotNull(s.input);assertEquals("rascunho de teste!",s.input.getText().toString());s.closeComposer();assertNull(s.composer);assertEquals(View.VISIBLE,s.bubble.getVisibility());});
        }finally{c.stopService(new Intent(c,CompanionService.class));}
    }
}
