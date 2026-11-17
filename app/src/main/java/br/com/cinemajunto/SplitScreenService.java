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
    boolean active, openedIcon, selectingChat;
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
    int foregroundWindowOrientation(){
        for(AccessibilityWindowInfo window:getWindows()){
            if(window.getType()==AccessibilityWindowInfo.TYPE_APPLICATION && (window.isFocused() || window.isActive())){
                android.graphics.Rect bounds=new android.graphics.Rect();window.getBoundsInScreen(bounds);
                if(bounds.width()>0 && bounds.height()>0)return bounds.width()>bounds.height()
                    ?android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            }
        }
        return android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }
    void splitForegroundMovie(String label, Runnable ready, Runnable failed) {
        cancel(); success=ready; failure=failed; movieLabel=label; active=true; attempts=0; openedIcon=false;selectingChat=false;
        if(Build.VERSION.SDK_INT<=30 && performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)) {
            complete(true); return;
        }
        if(label.isEmpty() || !performGlobalAction(GLOBAL_ACTION_RECENTS)) {complete(false);return;}
        ui.postDelayed(scan,500);
    }
    final Runnable scan=new Runnable(){public void run(){
        if(!active)return;
        if(++attempts>24){complete(false);return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root!=null){
            String pkg=String.valueOf(root.getPackageName());
            if(Arrays.asList(getServiceInfo().packageNames).contains(pkg)){
                diagnostic=describe(root,0);
                if(selectingChat){
                    AccessibilityNodeInfo chat=findChatTask(root);
                    if(chat!=null && chat.performAction(AccessibilityNodeInfo.ACTION_CLICK)){complete(true);return;}
                    ui.postDelayed(this,300);return;
                }
                if(performSplitShortcut(root)){selectingChat=true;ui.postDelayed(this,800);return;}
                AccessibilityNodeInfo split=findSplit(root);
                if(split!=null && click(split)){selectingChat=true;ui.postDelayed(this,800);return;}
                if(!openedIcon){
                    AccessibilityNodeInfo icon=findMovieIcon(root);
                    if(icon!=null && click(icon))openedIcon=true;
                }
            }
        }
        ui.postDelayed(this,300);
    }};
    AccessibilityNodeInfo findChatTask(AccessibilityNodeInfo n){
        String id=n.getViewIdResourceName();String desc=String.valueOf(n.getContentDescription());
        if(desc.contains("Chat Filme · chat")){
            for(AccessibilityNodeInfo candidate=n;candidate!=null;candidate=candidate.getParent()){
                android.graphics.Rect bounds=new android.graphics.Rect();candidate.getBoundsInScreen(bounds);
                int minimum=Math.round(80*getResources().getDisplayMetrics().density);
                if(candidate.isClickable() && bounds.width()>minimum && bounds.height()>minimum)return candidate;
            }
        }
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){AccessibilityNodeInfo found=findChatTask(c);if(found!=null)return found;}}
        return null;
    }
    String describe(AccessibilityNodeInfo n,int depth){
        if(depth>8)return "";
        StringBuilder b=new StringBuilder("\n"+n.getViewIdResourceName()+" text="+n.getText()+" desc="+n.getContentDescription()+" click="+n.isClickable()+" actions="+n.getActionList());
        for(int i=0;i<n.getChildCount() && b.length()<7000;i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)b.append(describe(c,depth+1));}
        return b.toString();
    }
    boolean isSplitLabel(String value){
        String s=value.toLowerCase(Locale.ROOT);
        return s.contains("split screen") || s.contains("split top") || s.contains("split-screen") || s.contains("tela dividida") || s.equals("dividir");
    }
    boolean performSplitShortcut(AccessibilityNodeInfo n){
        String desc=String.valueOf(n.getContentDescription()).toLowerCase(Locale.ROOT);
        String id=n.getViewIdResourceName();
        if(desc.contains(movieLabel.toLowerCase(Locale.ROOT))){
            for(AccessibilityNodeInfo.AccessibilityAction action:n.getActionList()){
                if(action.getLabel()!=null && isSplitLabel(action.getLabel().toString()))return n.performAction(action.getId());
            }
        }
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null && performSplitShortcut(c))return true;}
        return false;
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
        boolean icon=id!=null && (id.endsWith("/icon") || id.endsWith("/task_icon") || id.endsWith("/icon_view") || id.endsWith("/app_icon"));
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
