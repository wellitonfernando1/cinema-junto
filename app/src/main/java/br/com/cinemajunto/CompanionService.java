package br.com.cinemajunto;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.*;

/** Only chat and microphone packets. No media projection, playback control or audio focus. */
public class CompanionService extends Service {
    static CompanionService instance;
    final Handler ui = new Handler(Looper.getMainLooper());
    final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).build();
    final ArrayDeque<String> history = new ArrayDeque<>();
    WebSocket socket;
    CompanionVoice voice;
    String room="", role="", status="Conectando…", draft="";
    boolean joined, destroyed;
    int generation, retries;
    long lastChat;
    LinearLayout bubble, composer;
    ImageButton mic;
    TextView recent;
    EditText input;
    WindowManager windows;
    WindowManager.LayoutParams bubbleParams;
    final Runnable redraw = new Runnable(){public void run(){
        if (destroyed) return;
        if(mic!=null){mic.setImageResource(voiceActive() ? android.R.drawable.ic_media_pause : R.drawable.ic_microphone); mic.setContentDescription(voiceActive() ? "Desligar microfone" : "Ligar microfone");mic.setEnabled(joined);}
        ui.postDelayed(this,250);
    }};
    @Override public void onCreate(){
        super.onCreate(); instance=this; windows=getSystemService(WindowManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("conversation","Conversa durante o filme",NotificationManager.IMPORTANCE_LOW));
        ui.post(redraw);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        try{
            startForeground(73,notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            String next=RoomPassword.normalize(intent.getStringExtra("room"));
            String nextRole=intent.getStringExtra("role");
            if(!"host".equals(nextRole)&&!"viewer".equals(nextRole))throw new IllegalArgumentException("Entrada inválida.");
            if(next.equals(room)&&nextRole.equals(role)&&socket!=null){showBubble();return START_NOT_STICKY;}
            disconnect(); room=next;role=nextRole;retries=0;
            loadHistory();connect();showBubble();
        }catch(Exception e){status="Não foi possível iniciar a conversa.";Toast.makeText(this,status,Toast.LENGTH_LONG).show();stopSelf();}
        return START_NOT_STICKY;
    }
    Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,CompanionActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CompanionService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"conversation").setSmallIcon(R.drawable.ic_microphone)
            .setContentTitle("Cinema Conversa").setContentText(status).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,"Encerrar",stop).build()).setOngoing(true).build();
    }
    void setStatus(String text){status=text;getSystemService(NotificationManager.class).notify(73,notification());}
    void connect(){
        if(destroyed)return;
        final int attempt=++generation; joined=false;setStatus("Conectando à sala…");
        voice=new CompanionVoice(this,role,new CompanionVoice.Listener(){
            public boolean sendText(String json){WebSocket s=socket;return s!=null&&joined&&s.send(json);}
            public boolean sendBinary(byte[] packet){WebSocket s=socket;return s!=null&&joined&&s.queueSize()<128*1024&&s.send(ByteString.of(packet));}
            public void muteMovie(boolean ignored){}
            public void error(String message){ui.post(()->{if(attempt==generation&&!destroyed)setStatus(message);});}
        });
        socket=client.newWebSocket(new Request.Builder().url("wss://cinema-junto-welliton.onrender.com/relay").build(),new WebSocketListener(){
            @Override public void onOpen(WebSocket ws,Response response){ui.post(()->{
                if(attempt!=generation||destroyed){ws.cancel();return;}
                try{ws.send(new JSONObject().put("room",RoomPassword.room(room)).put("role",role).put("device",RoomPassword.device(CompanionService.this)).toString());}
                catch(Exception e){ws.cancel();}
            });}
            @Override public void onMessage(WebSocket ws,String text){ui.post(()->{
                if(attempt!=generation||destroyed)return;
                try{
                    JSONObject m=new JSONObject(text);
                    if(m.optBoolean("joined")){joined=true;retries=0;setStatus("Sala conectada · microfone desligado");}
                    if("chat".equals(m.optString("type")))append((role.equals(m.optString("from"))?"Você: ":"Amigo: ")+m.optString("text"));
                    if("talk".equals(m.optString("type"))) {
                        voice.onControl(m);
                        setStatus(voiceActive()?"Microfone ligado · toque para desligar":voice.peerTalking?"Seu amigo está falando":"Sala conectada · microfone desligado");
                    }
                    if("error".equals(m.optString("type")))setStatus(m.optString("message","Erro ao entrar na sala."));
                }catch(Exception ignored){}
            });}
            @Override public void onMessage(WebSocket ws,ByteString bytes){ui.post(()->{
                if(attempt==generation&&!destroyed&&voice!=null)voice.onAudio(bytes.toByteArray());
            });}
            @Override public void onClosing(WebSocket ws,int code,String reason){ws.close(code,reason);}
            @Override public void onClosed(WebSocket ws,int code,String reason){ui.post(()->lost(attempt,code,reason));}
            @Override public void onFailure(WebSocket ws,Throwable error,Response response){ui.post(()->lost(attempt,-1,"Conexão perdida"));}
        });
    }
    void lost(int attempt,int code,String reason){
        if(attempt!=generation||destroyed)return;
        disconnect();
        setStatus(reason.isEmpty()?"Sala desconectada":reason);
        // Protocol rejections require a user decision; transient network failures reconnect with mic off.
        if(code==-1&&retries<8){
            long delay=Math.min(30000,1000L<<Math.min(retries++,5));
            int token=generation;
            ui.postDelayed(()->{if(!destroyed&&token==generation)connect();},delay);
        }
    }
    void disconnect(){
        joined=false;++generation;
        if(voice!=null){voice.close();voice=null;}
        if(socket!=null){WebSocket old=socket;socket=null;old.cancel();}
    }
    boolean voiceActive(){return voice!=null&&(voice.held||voice.pending||voice.isTalking());}
    void toggleVoice(){
        if(!joined||voice==null)return;
        if(voiceActive())voice.release();else voice.press();
        setStatus(voiceActive()?"Microfone ligado · toque para desligar":"Sala conectada · microfone desligado");
    }
    boolean sendChat(String raw){
        String text=raw.trim();
        if(!joined||socket==null||text.isEmpty()||text.codePointCount(0,text.length())>280||SystemClock.elapsedRealtime()-lastChat<300)return false;
        try{boolean sent=socket.send(new JSONObject().put("type","chat").put("text",text).toString());if(sent)lastChat=SystemClock.elapsedRealtime();return sent;}
        catch(Exception e){return false;}
    }
    String historyKey(){return RoomPassword.room(room);}
    void loadHistory(){
        history.clear();
        try{JSONArray saved=new JSONArray(getSharedPreferences("conversation-history",0).getString(historyKey(),"[]"));
            for(int i=Math.max(0,saved.length()-200);i<saved.length();i++)history.add(saved.getString(i));
        }catch(Exception ignored){}
    }
    void append(String line){
        history.addLast(line);while(history.size()>200)history.removeFirst();
        JSONArray saved=new JSONArray();for(String item:history)saved.put(item);
        getSharedPreferences("conversation-history",0).edit().putString(historyKey(),saved.toString()).apply();
        if(recent!=null)recent.setText(historyText());
    }
    String historyText(){return String.join("\n\n",history);}
    int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    WindowManager.LayoutParams params(int width,int height,int gravity,boolean focus){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|(focus?0:WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),PixelFormat.TRANSLUCENT);
        p.gravity=gravity;p.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if(Build.VERSION.SDK_INT>=30)p.setFitInsetsTypes(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|(focus?WindowInsets.Type.ime():0));
        return p;
    }
    void showBubble(){
        if(destroyed||bubble!=null||!Settings.canDrawOverlays(this))return;
        bubble=new LinearLayout(this);bubble.setBackgroundColor(0xee202531);
        mic=new ImageButton(this);mic.setImageTintList(android.content.res.ColorStateList.valueOf(0xffffffff));mic.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xffc62828));
        mic.setImageResource(R.drawable.ic_microphone);mic.setPadding(dp(14),dp(14),dp(14),dp(14));mic.setOnClickListener(v->toggleVoice());bubble.addView(mic,new LinearLayout.LayoutParams(dp(56),dp(56)));
        Button chat=new Button(this);chat.setText("Chat");chat.setOnClickListener(v->openComposer());bubble.addView(chat,new LinearLayout.LayoutParams(dp(64),dp(56)));
        TextView handle=new TextView(this);handle.setText("↕");handle.setTextColor(0xffffffff);handle.setGravity(Gravity.CENTER);handle.setContentDescription("Arrastar conversa");
        bubble.addView(handle,new LinearLayout.LayoutParams(dp(28),dp(56)));
        bubbleParams=params(dp(148),dp(56),Gravity.TOP|Gravity.LEFT,false);bubbleParams.x=dp(8);bubbleParams.y=dp(100);
        handle.setOnTouchListener(new View.OnTouchListener(){
            float x,y;int ox,oy;
            public boolean onTouch(View v,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x=e.getRawX();y=e.getRawY();ox=bubbleParams.x;oy=bubbleParams.y;return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                    bubbleParams.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-dp(148),ox+(int)(e.getRawX()-x)));
                    bubbleParams.y=Math.max(0,Math.min(getResources().getDisplayMetrics().heightPixels-dp(100),oy+(int)(e.getRawY()-y)));
                    try{windows.updateViewLayout(bubble,bubbleParams);}catch(Exception ignored){}
                }return true;
            }
        });
        try{windows.addView(bubble,bubbleParams);}catch(Exception e){bubble=null;mic=null;setStatus("Ative a permissão de aparecer sobre outros aplicativos.");}
    }
    void openComposer(){
        if(composer!=null||!Settings.canDrawOverlays(this))return;
        composer=new LinearLayout(this);composer.setOrientation(LinearLayout.VERTICAL);composer.setBackgroundColor(0xff151922);composer.setPadding(dp(8),dp(8),dp(8),dp(8));
        recent=new TextView(this);recent.setTextColor(0xffffffff);recent.setTextSize(15);recent.setText(historyText());
        ScrollView scroll=new ScrollView(this);scroll.addView(recent);composer.addView(scroll,new LinearLayout.LayoutParams(-1,dp(130)));
        input=new EditText(this);input.setTextColor(0xffffffff);input.setHintTextColor(0xffbbbbbb);input.setHint("Mensagem");input.setSingleLine(true);input.setText(draft);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(280)});
        composer.addView(input);
        LinearLayout row=new LinearLayout(this);composer.addView(row);
        Button send=new Button(this);send.setText("Enviar");send.setOnClickListener(v->{if(sendChat(input.getText().toString()))input.setText("");else Toast.makeText(this,"Mensagem não enviada. Confira a conexão.",Toast.LENGTH_SHORT).show();});
        row.addView(send,new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(this);close.setText("Fechar");close.setOnClickListener(v->closeComposer());row.addView(close,new LinearLayout.LayoutParams(0,-2,1));
        try{windows.addView(composer,params(-1,-2,Gravity.BOTTOM,true));input.requestFocus();}
        catch(Exception e){closeComposer();}
    }
    void closeComposer(){
        if(input!=null){draft=input.getText().toString();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);}
        if(composer!=null){try{windows.removeViewImmediate(composer);}catch(Exception ignored){}composer=null;}
        input=null;recent=null;
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration c){
        super.onConfigurationChanged(c);closeComposer();
        if(bubble!=null){bubbleParams.x=dp(8);bubbleParams.y=dp(100);try{windows.updateViewLayout(bubble,bubbleParams);}catch(Exception ignored){}}
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){
        destroyed=true;disconnect();ui.removeCallbacksAndMessages(null);closeComposer();
        if(bubble!=null){try{windows.removeViewImmediate(bubble);}catch(Exception ignored){}bubble=null;}
        client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();
        if(instance==this)instance=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
