package br.com.cinemajunto;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** Conversation UI that never owns a movie player or sends playback controls. */
public class CompanionActivity extends Activity {
    LinearLayout layout;
    EditText password, input;
    TextView history, status;
    Button mic, setup;
    boolean configuring;
    int pendingStep;
    final Handler ui = new Handler(Looper.getMainLooper());
    String lastHistory = "";
    final Runnable refresh = new Runnable() {
        public void run() {
            CompanionService s = CompanionService.instance;
            status.setText(s == null ? "Entre em uma sala para conversar." : s.status);
            mic.setEnabled(s != null && s.joined);
            if(setup!=null)setup.setText(setupStep()==0 ? "Configuração concluída ✓" : "Configurar chat sobre o filme");
            mic.setText(s != null && s.voiceActive() ? "Desligar microfone" : "Ligar microfone");
            String text = s == null ? "" : s.historyText();
            if (!text.equals(lastHistory)) { history.setText(text); lastHistory = text; }
            ui.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(24,24,24,16);
        layout.setBackgroundColor(0xff151922);
        setContentView(layout);
        layout.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                layout.setPadding(i.left+24,i.top+16,i.right+24,i.bottom+16);
            }
            return insets;
        });
        label("Chat Filme", 23);
        status = label("Entre em uma sala para conversar.", 14);
        password = field("Código da sala: 4 a 24 letras ou números");
        password.setText(getPreferences(0).getString("room", ""));
        if (state != null) password.setText(state.getString("room", ""));
        LinearLayout row = new LinearLayout(this); layout.addView(row);
        button(row, "Criar sala", () -> join("host"));
        button(row, "Entrar", () -> join("viewer"));
        button(row, "Convite", () -> {
            try {
                String code = RoomPassword.normalize(password.getText().toString());
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Entre no Cinema Conversa com o código: " + code);
                startActivity(Intent.createChooser(send, "Convidar amigo"));
            } catch (Exception e) { toast(e.getMessage()); }
        });
        LinearLayout actions = new LinearLayout(this); layout.addView(actions);
        mic = button(actions, "Ligar microfone", this::requestVoice);
        button(actions, "Bolinha", () -> {
            if (!Settings.canDrawOverlays(this)) startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            else if (CompanionService.instance != null) CompanionService.instance.showBubble();
            else toast("Entre em uma sala primeiro.");
        });
        button(actions, "Sair", () -> stopService(new Intent(this, CompanionService.class)));
        setup=button(layout, "Configurar chat sobre o filme", () -> { configuring=true; pendingStep=0; nextSetup(); });
        label("Para o chat, permita aparecer sobre outros aplicativos. O microfone é opcional. Toque em Chat na bolinha para escrever sem sair do filme.",12);
        button(layout, "Abrir aplicativo do filme", this::chooseApp);
        ScrollView scroll = new ScrollView(this);
        history = new TextView(this); history.setTextColor(0xffffffff); history.setTextSize(16); history.setTextIsSelectable(true);
        scroll.addView(history); layout.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        input = field("Escreva uma mensagem");
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(280)});
        if (state != null) input.setText(state.getString("draft", ""));
        button(layout, "Enviar", () -> {
            CompanionService s = CompanionService.instance;
            if (s != null && s.sendChat(input.getText().toString())) input.setText("");
            else toast("A mensagem não foi enviada. Confira a conexão.");
        });
        label("Histórico local: últimas 200 mensagens recebidas por sala. Voz: um por vez, até 30 s por toque. A reprodução depende do app do filme.",12);
    }
    TextView label(String text, int size) { TextView v = new TextView(this); v.setText(text); v.setTextColor(0xffffffff); v.setTextSize(size); layout.addView(v); return v; }
    EditText field(String hint) {
        EditText e = new EditText(this); e.setSingleLine(true); e.setTextColor(0xffffffff); e.setHintTextColor(0xffbbbbbb);
        e.setHint(hint); layout.addView(e); return e;
    }
    Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setOnClickListener(v -> action.run()); parent.addView(b, new LinearLayout.LayoutParams(parent == layout ? -1 : 0,-2,parent == layout ? 0 : 1)); return b;
    }
    void join(String role) {
        try {
            String code = RoomPassword.normalize(password.getText().toString());
            getPreferences(0).edit().putString("room",code).apply();
            startForegroundService(new Intent(this, CompanionService.class).putExtra("room",code).putExtra("role",role));
        } catch (Exception e) { toast(e.getMessage()); }
    }
    int setupStep(){return Settings.canDrawOverlays(this)?0:1;}
    void nextSetup(){
        configuring=false;
        if(Settings.canDrawOverlays(this)){toast("Configuração concluída. O botão Chat abre a conversa sobre o filme.");return;}
        try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
        catch(Exception e){toast("Abra Aparecer sobre outros aplicativos nas configurações.");}
    }
    void requestVoice(){
        if(CompanionService.instance==null || !CompanionService.instance.joined){toast("Entre em uma sala conectada primeiro.");return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);return;
        }
        CompanionService.instance.toggleVoice();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==1 && grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)requestVoice();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if("voice".equals(intent.getAction())){intent.setAction(null);requestVoice();}}
    void chooseApp() {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        java.util.List<android.content.pm.ResolveInfo> apps = getPackageManager().queryIntentActivities(query, 0);
        apps.removeIf(a -> a.activityInfo.packageName.equals(getPackageName()));
        apps.sort((a,b) -> a.loadLabel(getPackageManager()).toString().compareToIgnoreCase(b.loadLabel(getPackageManager()).toString()));
        String[] names = new String[apps.size()];
        for(int i=0;i<names.length;i++) names[i]=apps.get(i).loadLabel(getPackageManager()).toString();
        new AlertDialog.Builder(this).setTitle("Escolha o aplicativo do filme").setItems(names,(d,index)->{
            android.content.pm.ActivityInfo a=apps.get(index).activityInfo;
            Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(a.packageName,a.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getSharedPreferences("conversation-layout",0).edit().putString("movie-label",names[index]).putString("movie-package",a.packageName).apply();
            try { if(CompanionService.instance==null){toast("Entre em uma sala primeiro.");return;} startActivity(launch); }
            catch(Exception e){toast("Não foi possível abrir esse aplicativo.");}
        }).show();
    }
    void toast(String s) { Toast.makeText(this,s == null ? "Não foi possível concluir." : s,Toast.LENGTH_LONG).show(); }
    @Override protected void onResume(){super.onResume();ui.post(refresh);if(configuring)ui.postDelayed(this::nextSetup,500);if("voice".equals(getIntent().getAction())){getIntent().setAction(null);ui.post(this::requestVoice);}}
    @Override protected void onPause(){ui.removeCallbacks(refresh);super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("room",password.getText().toString());state.putString("draft",input.getText().toString());super.onSaveInstanceState(state);}
}
