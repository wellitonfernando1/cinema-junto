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
            while((SplitChatActivity.instance==null || !SplitChatActivity.instance.keyboardReady) && SystemClock.elapsedRealtime()<end)Thread.sleep(100);
            main(()->{
                SplitChatActivity a=SplitChatActivity.instance;
                assertNotNull("Chat must open even while disconnected",a);
                assertTrue("Native keyboard must be visible",a.keyboardReady);
                assertTrue(a.getWindow().getDecorView().getRootWindowInsets().isVisible(WindowInsets.Type.ime()));
                assertTrue("History needs visible space",a.scroll.getHeight()>36*a.getResources().getDisplayMetrics().density);
                assertFalse("Chat must not depend on split screen",a.isInMultiWindowMode());
                assertFalse(CompanionService.instance.openingChat);
                a.input.setText("rascunho de teste");a.send();
                assertEquals("rascunho de teste",a.input.getText().toString());
            });
            Thread.sleep(1700);
            main(()->{assertTrue(SplitChatActivity.instance.active);SplitChatActivity.instance.input.append("!");});
            Thread.sleep(1700);main(()->assertTrue(SplitChatActivity.instance.active));
            Thread.sleep(1700);main(()->{assertFalse(SplitChatActivity.instance.active);assertEquals("rascunho de teste!",CompanionService.instance.draft);CompanionService.instance.openComposer();});
            end=SystemClock.elapsedRealtime()+5000;
            while(!SplitChatActivity.instance.active && SystemClock.elapsedRealtime()<end)Thread.sleep(100);
            main(()->{SplitChatActivity a=SplitChatActivity.instance;assertTrue(a.active);assertEquals("rascunho de teste!",a.input.getText().toString());a.returnToMovie();assertFalse(a.active);assertEquals(View.VISIBLE,CompanionService.instance.bubble.getVisibility());});
        }finally{main(()->{if(SplitChatActivity.instance!=null)SplitChatActivity.instance.finishAndRemoveTask();});c.stopService(new Intent(c,CompanionService.class));}
    }
}
