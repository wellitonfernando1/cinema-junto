package br.com.cinemajunto;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** A separate task below the movie, removed when the conversation composer closes. */
public class SplitChatActivity extends Activity {
    static SplitChatActivity instance;
    final Handler ui = new Handler(Looper.getMainLooper());
    final ChatIdleTimer idle = new ChatIdleTimer(ui, this::returnToMovie);
    EditText input;
    TextView history, connection;
    ScrollView scroll;
    boolean closing, confirmedSplit;
    String shownHistory = "";
    final Runnable refresh = new Runnable() { public void run() {
        if (closing) return;
        CompanionService s = CompanionService.instance;
        if (s == null) { returnToMovie(); return; }
        connection.setText(s.status);
        String text = s.historyText();
        if (!shownHistory.equals(text)) {
            shownHistory = text; history.setText(text);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
        ui.postDelayed(this, 350);
    }};
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); instance = this;
        CompanionService s = CompanionService.instance;
        if (s == null) { finishAndRemoveTask(); return; }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff151922); root.setPadding(12,8,12,8); setContentView(root);
        if(Build.VERSION.SDK_INT>=30)root.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime()|WindowInsets.Type.displayCutout());
            root.setPadding(i.left+12,i.top+8,i.right+12,i.bottom+8);return insets;
        });
        connection = text("Chat · volta ao filme após 3 s sem digitar", 12); root.addView(connection);
        history = text(s.historyText(), 15); history.setTextIsSelectable(true);
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(history);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout row = new LinearLayout(this); root.addView(row);
        input = new EditText(this); input.setTextColor(0xffffffff); input.setHintTextColor(0xffbbbbbb);
        input.setHint("Mensagem"); input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(280)});
        input.setText(state == null ? s.draft : state.getString("draft", s.draft));
        input.setSelection(input.length()); input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        row.addView(input, new LinearLayout.LayoutParams(0,-2,1));
        Button send = new Button(this); send.setText("Enviar"); send.setOnClickListener(v -> send()); row.addView(send);
        Button close = new Button(this); close.setText("Voltar ao filme"); close.setOnClickListener(v -> returnToMovie()); root.addView(close);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence v,int start,int count,int after) { }
            public void onTextChanged(CharSequence v,int start,int before,int count) { s.draft = v.toString(); idle.touch(); }
            public void afterTextChanged(Editable v) { }
        });
        input.setOnEditorActionListener((v,action,event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(); return true; } return false;
        });
        // Fail visibly instead of presenting a full-screen chat as a successful division.
        ui.postDelayed(() -> {
            if (closing) return;
            if (!isInMultiWindowMode()) {
                Toast.makeText(this,"O Android ou este streaming não permitiu dividir a tela. Use Recentes > Tela dividida.",Toast.LENGTH_LONG).show();
                returnToMovie(); return;
            }
            confirmedSplit = true; input.requestFocus();
            getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            idle.touch();
        }, 900);
        ui.post(refresh);
    }
    TextView text(String value,int size) { TextView t=new TextView(this); t.setText(value); t.setTextColor(0xffffffff); t.setTextSize(size); return t; }
    void send() {
        CompanionService s=CompanionService.instance;
        if (input.getText().toString().trim().isEmpty()) { idle.touch(); return; }
        if (s!=null && s.sendChat(input.getText().toString())) input.setText("");
        else Toast.makeText(this,s==null||!s.joined?"Sala desconectada. Sua mensagem foi mantida.":"Não foi possível enviar. Sua mensagem foi mantida.",Toast.LENGTH_LONG).show();
        idle.touch();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (confirmedSplit && (e.getActionMasked()==MotionEvent.ACTION_DOWN || e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_MOVE)) idle.touch();
        return super.dispatchTouchEvent(e);
    }
    void returnToMovie() {
        if (closing) return; closing=true; idle.cancel();
        CompanionService s=CompanionService.instance;
        if(input!=null) {
            if(s!=null) s.draft=input.getText().toString();
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(),0);
        }
        // Removing this secondary task lets Android expand the surviving movie task.
        finishAndRemoveTask();
        if(s!=null) s.restoreMovieOrientation();
    }
    @Override public void onBackPressed() { returnToMovie(); }
    @Override public void onMultiWindowModeChanged(boolean active, Configuration configuration) {
        super.onMultiWindowModeChanged(active,configuration);
        if(confirmedSplit && !active) returnToMovie();
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); }
    @Override protected void onSaveInstanceState(Bundle b) { if(input!=null)b.putString("draft",input.getText().toString()); super.onSaveInstanceState(b); }
    @Override protected void onDestroy() {
        idle.cancel(); ui.removeCallbacksAndMessages(null);
        if(instance==this)instance=null;
        if(!isChangingConfigurations() && !closing && CompanionService.instance!=null)CompanionService.instance.restoreMovieOrientation();
        super.onDestroy();
    }
}
