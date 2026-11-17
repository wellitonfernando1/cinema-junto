package br.com.cinemajunto;

import android.content.*;
import android.provider.Settings;

/** A temporary rotation lock. Original settings survive service/process recreation. */
final class ChatRotation {
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("chat-rotation-session",0);}
    static boolean begin(Context c){
        if(!Settings.System.canWrite(c))return false;
        SharedPreferences p=prefs(c);
        if(p.getBoolean("active",false) && !restore(c))return false;
        ContentResolver r=c.getContentResolver();
        int auto=Settings.System.getInt(r,Settings.System.ACCELEROMETER_ROTATION,1);
        int rotation=Settings.System.getInt(r,Settings.System.USER_ROTATION,0);
        if(!p.edit().putBoolean("active",true).putInt("auto",auto).putInt("rotation",rotation).commit())return false;
        try{
            boolean locked=Settings.System.putInt(r,Settings.System.ACCELEROMETER_ROTATION,0);
            boolean portrait=Settings.System.putInt(r,Settings.System.USER_ROTATION,0);
            if(locked && portrait)return true;
        }catch(Exception ignored){}
        restore(c);return false;
    }
    static boolean restore(Context c){
        SharedPreferences p=prefs(c);
        if(!p.getBoolean("active",false))return true;
        if(!Settings.System.canWrite(c))return false;
        try{
            ContentResolver r=c.getContentResolver();
            boolean rotation=Settings.System.putInt(r,Settings.System.USER_ROTATION,p.getInt("rotation",0));
            boolean auto=Settings.System.putInt(r,Settings.System.ACCELEROMETER_ROTATION,p.getInt("auto",1));
            if(rotation && auto){p.edit().remove("active").apply();return true;}
        }catch(Exception ignored){}
        return false;
    }
}
