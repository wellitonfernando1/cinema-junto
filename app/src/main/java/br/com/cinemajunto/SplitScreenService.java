package br.com.cinemajunto;

import android.accessibilityservice.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.*;
import java.util.*;

/** Reads only system Recents controls, never the streaming window or its player. */
public class SplitScreenService extends AccessibilityService {
    static SplitScreenService instance;
    final Handler ui = new Handler(Looper.getMainLooper());
    Runnable success, failure;
    String movieLabel="";
    boolean active, openedIcon;
    int attempts;
    String diagnostic="No Recents window observed";
    @Override protected void onServiceConnected() {
        instance = this;
        AccessibilityServiceInfo info=getServiceInfo();
        String home="com.android.launcher3";
        android.content.pm.ResolveInfo resolved=getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);
        if(resolved!=null)home=resolved.activityInfo.packageName;
        info.packageNames=new String[]{home,"com.android.systemui","com.android.launcher3","com.google.android.apps.nexuslauncher","com.sec.android.app.launcher"};
        setServiceInfo(info);
    }
    void splitForegroundMovie(String label, Runnable ready, Runnable failed) {
        cancel(); success=ready; failure=failed; movieLabel=label; active=true; attempts=0; openedIcon=false;
        if(Build.VERSION.SDK_INT<=30 && performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)) {
            complete(true); return;
        }
        if(label.isEmpty() || !performGlobalAction(GLOBAL_ACTION_RECENTS)) {complete(false);return;}
        ui.postDelayed(scan,500);
    }
    final Runnable scan=new Runnable(){public void run(){
        if(!active)return;
        if(++attempts>16){complete(false);return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null){
            String pkg=String.valueOf(root.getPackageName());
            if(Arrays.asList(getServiceInfo().packageNames).contains(pkg)){
                diagnostic=describe(root,0);
                AccessibilityNodeInfo split=findSplit(root);
                if(split!=null && click(split)){complete(true);return;}
                if(!openedIcon){
                    AccessibilityNodeInfo icon=findMovieIcon(root);
                    if(icon!=null && click(icon))openedIcon=true;
                }
            }
        }
        ui.postDelayed(this,300);
    }};
    String describe(AccessibilityNodeInfo n,int depth){
        if(depth>8)return "";
        StringBuilder b=new StringBuilder("\n"+n.getViewIdResourceName()+" text="+n.getText()+" desc="+n.getContentDescription()+" click="+n.isClickable());
        for(int i=0;i<n.getChildCount() && b.length()<7000;i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)b.append(describe(c,depth+1));}
        return b.toString();
    }
    AccessibilityNodeInfo findSplit(AccessibilityNodeInfo n){
        String value=(String.valueOf(n.getText())+" "+String.valueOf(n.getContentDescription())).toLowerCase(Locale.ROOT);
        if(value.contains("split screen") || value.contains("split top") || value.contains("split-screen") || value.contains("tela dividida") || value.trim().equals("dividir null"))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){AccessibilityNodeInfo found=findSplit(c);if(found!=null)return found;}}
        return null;
    }
    AccessibilityNodeInfo findMovieIcon(AccessibilityNodeInfo n){
        String id=n.getViewIdResourceName();
        String desc=String.valueOf(n.getContentDescription()).toLowerCase(Locale.ROOT);
        String label=movieLabel.toLowerCase(Locale.ROOT);
        // Require the chosen movie app's label. Never click an arbitrary Recents card.
        boolean icon=id!=null && (id.endsWith("/icon") || id.endsWith("/task_icon") || id.endsWith("/icon_view"));
        if(desc.contains(label) && (icon || desc.contains("app icon") || desc.contains("ícone")))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){AccessibilityNodeInfo found=findMovieIcon(c);if(found!=null)return found;}}
        return null;
    }
    boolean click(AccessibilityNodeInfo n){
        for(int depth=0;n!=null && depth<4;depth++,n=n.getParent())if(n.isClickable())return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return false;
    }
    void complete(boolean ok){Runnable callback=ok?success:failure;cancel();if(callback!=null)callback.run();}
    void cancel(){active=false;ui.removeCallbacks(scan);success=null;failure=null;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() {if(active)complete(false);}
    @Override public void onDestroy() {cancel();if(instance==this)instance=null;super.onDestroy();}
}
