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
        super.onCreate(); instance=this; windows=getSystemService(WindowManager.class);ChatRotation.restore(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("conversation","Conversa durante o filme",NotificationManager.IMPORTANCE_LOW));
        ui.post(redraw);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        try{
            startForeground(73,notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
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
    void setStatus(String text){status=text;if(composerStatus!=null)composerStatus.setText(text);getSystemService(NotificationManager.class).notify(73,notification());}
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
        if((code==-1 || code==1001 || code==1011 || code==1012 || code==1013) && retries<8){
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
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            startActivity(new Intent(this,CompanionActivity.class).setAction("voice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));return;
        }
        if(voiceActive())voice.release();else {
            try{startForeground(73,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE|ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);voice.press();}
            catch(Exception e){startActivity(new Intent(this,CompanionActivity.class).setAction("voice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));return;}
        }
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
        if(recent!=null){recent.setText(historyText());if(composerScroll!=null)composerScroll.post(()->{if(composerScroll!=null)composerScroll.fullScroll(View.FOCUS_DOWN);});}
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
    final ChatIdleTimer composerIdle=new ChatIdleTimer(ui,this::closeComposer);
    ScrollView composerScroll;
    TextView composerStatus;
    boolean composerKeyboardReady;
    int composerKeyboardAttempts;
    final Runnable composerKeyboard=new Runnable(){public void run(){
        if(composer==null || input==null)return;
        WindowInsets insets=composer.getRootWindowInsets();
        if(insets!=null && insets.isVisible(WindowInsets.Type.ime())){
            composerKeyboardReady=true;composerIdle.touch();return;
        }
        input.requestFocus();
        getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        if(++composerKeyboardAttempts<20)ui.postDelayed(this,200);
    }};
    boolean openingChat;
    int movieOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    void openComposer(){
        if(destroyed)return;
        if(composer!=null){input.requestFocus();ui.removeCallbacks(composerKeyboard);ui.post(composerKeyboard);return;}
        if(!Settings.canDrawOverlays(this)){
            Toast.makeText(this,"Permita aparecer sobre outros aplicativos para abrir o chat.",Toast.LENGTH_LONG).show();return;
        }
        // Open a focusable overlay directly. Never navigate Recents, Home or a movie task.
        openingChat=false;
        if(SplitScreenService.instance!=null)SplitScreenService.instance.cancel();
        composerKeyboardReady=false;composerKeyboardAttempts=0;
        composer=new LinearLayout(this){
            @Override public boolean dispatchTouchEvent(MotionEvent event){
                if(composerKeyboardReady)composerIdle.touch();
                return super.dispatchTouchEvent(event);
            }
        };
        composer.setOrientation(LinearLayout.VERTICAL);composer.setPadding(dp(12),dp(8),dp(12),dp(8));
        composer.setBackgroundColor(0xff151922);
        LinearLayout heading=new LinearLayout(this);composer.addView(heading);
        TextView title=new TextView(this);title.setText("Chat Filme");title.setTextSize(18);title.setTextColor(0xffffffff);
        heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=new Button(this);close.setText("×");close.setContentDescription("Fechar chat");close.setMinWidth(0);close.setMinimumWidth(0);
        close.setOnClickListener(v->closeComposer());heading.addView(close,new LinearLayout.LayoutParams(dp(48),dp(40)));
        composerStatus=new TextView(this);composerStatus.setTextColor(0xffc5d3ea);composerStatus.setTextSize(12);
        composerStatus.setText(status);composer.addView(composerStatus);
        recent=new TextView(this);recent.setTextColor(0xffffffff);recent.setTextSize(15);recent.setText(historyText().isEmpty()?"As mensagens da sala aparecem aqui.":historyText());
        composerScroll=new ScrollView(this);composerScroll.setFillViewport(true);composerScroll.addView(recent);
        composer.addView(composerScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout row=new LinearLayout(this);composer.addView(row);
        input=new EditText(this);input.setTextColor(0xffffffff);input.setHintTextColor(0xffbbbbbb);input.setHint("Mensagem");input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(280)});
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);input.setText(draft);input.setSelection(input.length());
        row.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        Button send=new Button(this);send.setText("Enviar");send.setOnClickListener(v->sendComposer());row.addView(send);
        input.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEND){sendComposer();return true;}return false;});
        input.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence t,int start,int count,int after){}
            public void onTextChanged(CharSequence t,int start,int before,int count){draft=t.toString();if(composerKeyboardReady)composerIdle.touch();}
            public void afterTextChanged(android.text.Editable t){}
        });
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();windows.getDefaultDisplay().getRealMetrics(metrics);
        WindowManager.LayoutParams p=params(-1,Math.min(dp(280),Math.round(metrics.heightPixels*0.48f)),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,true);
        p.flags|=WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        composer.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_OUTSIDE){closeComposer();return true;}return false;});
        try{windows.addView(composer,p);hideBubble();input.requestFocus();ui.postDelayed(composerKeyboard,250);}
        catch(Exception e){closeComposer();Toast.makeText(this,"Não foi possível abrir a janela do chat. Confira a permissão de sobreposição.",Toast.LENGTH_LONG).show();}
    }
    void sendComposer(){
        if(input==null)return;
        if(input.getText().toString().trim().isEmpty())return;
        if(sendChat(input.getText().toString()))input.setText("");
        else Toast.makeText(this,joined?"Não foi possível enviar. A mensagem foi mantida.":"Sala desconectada. Aguarde a conexão; sua mensagem foi mantida.",Toast.LENGTH_LONG).show();
        if(composerKeyboardReady)composerIdle.touch();
    }
    void setOverlayOrientation(int orientation){
        if(bubble==null)return;
        bubbleParams.screenOrientation=orientation;
        try{windows.updateViewLayout(bubble,bubbleParams);}catch(Exception ignored){}
    }
    void restoreMovieOrientation(){
        openingChat=false;
        revealBubble();
        setOverlayOrientation(movieOrientation);
        ChatRotation.restore(this);
        ui.postDelayed(()->{if(!openingChat && (SplitChatActivity.instance==null || !SplitChatActivity.instance.active))setOverlayOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);},1200);
    }
    void hideBubble(){if(bubble!=null)bubble.setVisibility(View.GONE);}
    void revealBubble(){if(bubble!=null)bubble.setVisibility(View.VISIBLE);else showBubble();}
    void closeComposer(){
        composerIdle.cancel();ui.removeCallbacks(composerKeyboard);composerKeyboardReady=false;
        if(input!=null){draft=input.getText().toString();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);}
        if(composer!=null){try{windows.removeViewImmediate(composer);}catch(Exception ignored){}composer=null;}
        input=null;recent=null;composerScroll=null;composerStatus=null;if(!destroyed)revealBubble();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration c){
        super.onConfigurationChanged(c);
        if(bubble!=null){bubbleParams.x=dp(8);bubbleParams.y=dp(100);try{windows.updateViewLayout(bubble,bubbleParams);}catch(Exception ignored){}}
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){
        ChatRotation.restore(this);
        destroyed=true;if(SplitScreenService.instance!=null)SplitScreenService.instance.cancel();if(SplitChatActivity.instance!=null)SplitChatActivity.instance.finishAndRemoveTask();disconnect();ui.removeCallbacksAndMessages(null);closeComposer();
        if(bubble!=null){try{windows.removeViewImmediate(bubble);}catch(Exception ignored){}bubble=null;}
        client.dispatcher().executorService().shutdown();client.connectionPool().evictAll();
        if(instance==this)instance=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
