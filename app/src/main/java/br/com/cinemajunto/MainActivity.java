package br.com.cinemajunto;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

public class MainActivity extends Activity {
    EditText endpoint, invitation, chatInput;
    TextView status, chatHistory, messageBanner, loading, roomWord;
    FilmView screen;
    ScrollView setupScroll, chatScroll;
    LinearLayout mainLayout, controls, viewerBar, chatPanel, emojiRow;
    FrameLayout root, videoPane;
    Button chatButton, fullscreenButton, zoomButton;
    final ArrayDeque<String> chatLines = new ArrayDeque<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean fullscreen, unreadChat, imeVisible, keyboardSeen, chatOpen, destroyed;
    int bannerSequence;
    volatile int playbackGeneration;
    String pendingEndpoint, pendingRoom, pendingPassword;
    boolean hostFlowPending;
    OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    volatile WebSocket socket;
    volatile boolean watching, viewerJoined;
    PlaybackEngine playback;
    VoiceTalk voice;
    VoiceButton microphone;
    final Runnable idleChat = () -> { if (chatOpen) closeChat(); };
    final Runnable hideControls = () -> {
        if (watching && fullscreen && !chatOpen) viewerBar.setVisibility(View.GONE);
    };
    final BroadcastReceiver messages = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) {
            if (i.hasExtra("chat")) receiveChat(i.getStringExtra("from"), i.getStringExtra("chat"));
            else if (i.hasExtra("status")) show(i.getStringExtra("status"));
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if (saved != null) {
            pendingEndpoint = saved.getString("pendingEndpoint"); pendingRoom = saved.getString("pendingRoom");
            pendingPassword = saved.getString("pendingPassword"); hostFlowPending = saved.getBoolean("hostFlowPending");
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = new FrameLayout(this); root.setBackgroundColor(0xff000000);
        mainLayout = new LinearLayout(this); mainLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainLayout, new FrameLayout.LayoutParams(-1, -1));
        setupScroll = new ScrollView(this);
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(12), dp(16), dp(12)); controls.setBackgroundColor(0xfffafafa);
        setupScroll.addView(controls); mainLayout.addView(setupScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView title = new TextView(this); title.setTextColor(Color.BLACK); title.setText("Cinema Direto · " + BuildConfig.VERSION_NAME); title.setTextSize(24); controls.addView(title);
        TextView subtitle = new TextView(this); subtitle.setTextColor(Color.BLACK); subtitle.setText("Alta qualidade · até " + BuildConfig.CAPTURE_FPS + " fps · reserva de 5 segundos"); controls.addView(subtitle);
        endpoint = new EditText(this); endpoint.setSingleLine(true); endpoint.setHint("Endereço do servidor");
        readableInput(endpoint);
        endpoint.setText("https://cinema-junto-welliton.onrender.com/"); endpoint.setVisibility(View.GONE); controls.addView(endpoint);
        roomWord = new TextView(this); roomWord.setTextSize(26); roomWord.setTextColor(Color.BLACK);
        roomWord.setPadding(0, dp(12), 0, dp(12)); roomWord.setTextIsSelectable(true);
        roomWord.setText(CaptureService.active || CaptureService.starting
            ? "PALAVRA DA SALA: " + getPreferences(0).getString("hostInvite", "").toUpperCase(java.util.Locale.ROOT)
            : "Uma palavra de 4 letras para entrar"); controls.addView(roomWord);
        button(controls, "Criar sala", () -> startHost());
        button(controls, "Convidar pessoa", () -> share());
        invitation = new EditText(this); invitation.setSingleLine(true); invitation.setHint("Palavra de 4 letras, ex.: GATO");
        invitation.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        invitation.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        invitation.setContentDescription("Palavra de 4 letras"); readableInput(invitation); controls.addView(invitation);
        button(controls, "Entrar na sala", () -> startViewer());
        button(controls, "Chat de quem transmite", () -> openHostChat());
        button(controls, "Encerrar", () -> endSession());
        status = new TextView(this); status.setTextColor(Color.BLACK);
        status.setText("Até 3 pessoas: você e mais duas. Quem transmite cria uma palavra de 4 letras e toca em Convidar pessoa. Os convidados digitam a mesma palavra e tocam em Entrar na sala."); controls.addView(status);
        TextView tip = new TextView(this);
        tip.setTextColor(Color.BLACK);
        tip.setText(CaptureService.supportsSingleAppSharing()
            ? "Se houver a opção ‘Um app’, escolha o app do filme para manter chat e teclado só no seu aparelho. Compartilhar a tela inteira também os envia."
            : "As mensagens aparecem no alto do filme nos dois aparelhos. Neste Android, o chat e o teclado de quem transmite também podem aparecer na imagem compartilhada."); controls.addView(tip);
        videoPane = new FrameLayout(this); mainLayout.addView(videoPane, new LinearLayout.LayoutParams(-1, 0, 1));
        screen = new FilmView(this); screen.setContentDescription("Filme compartilhado");
        videoPane.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        loading = new TextView(this); loading.setTextColor(Color.WHITE); loading.setBackgroundColor(0xb3202020);
        loading.setPadding(dp(12), dp(8), dp(12), dp(8)); loading.setText("Carregando imagem e som…");
        videoPane.addView(loading, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)); loading.setVisibility(View.GONE);
        screen.setOnClickListener(v -> revealControls());
        screen.setOnLongClickListener(v -> {
            PlaybackEngine engine = playback;
            if (engine != null) new AlertDialog.Builder(this).setTitle("Diagnóstico · " + BuildConfig.VERSION_NAME)
                .setMessage(engine.diagnostics()).setPositiveButton("Fechar", null)
                .setNeutralButton("Copiar", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = getSystemService(android.content.ClipboardManager.class);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cinema", engine.diagnostics()));
                }).show();
            return true;
        });
        viewerBar = new LinearLayout(this); viewerBar.setGravity(Gravity.CENTER); viewerBar.setBackgroundColor(0xaa171717);
        fullscreenButton = barButton(viewerBar, "Tela cheia", () -> { if (fullscreen) exitFullscreen(); else enterFullscreen(); });
        chatButton = barButton(viewerBar, "Chat", () -> openChat());
        zoomButton = barButton(viewerBar, "Ampliar", () -> {
            screen.setZoom(!screen.zoom); zoomButton.setText(screen.zoom ? "Ajustar" : "Ampliar"); revealControls();
        });
        barButton(viewerBar, "Encerrar", () -> endSession());
        videoPane.addView(viewerBar, new FrameLayout.LayoutParams(-1, dp(44), Gravity.BOTTOM));
        messageBanner = new TextView(this); messageBanner.setTextColor(0xffffffff); messageBanner.setTextSize(16);
        messageBanner.setMaxLines(2); messageBanner.setPadding(dp(12), dp(7), dp(12), dp(7)); messageBanner.setBackgroundColor(0xb3202020);
        FrameLayout.LayoutParams bannerPosition = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        bannerPosition.setMargins(dp(10), dp(72), dp(10), 0); videoPane.addView(messageBanner, bannerPosition);
        messageBanner.setVisibility(View.GONE);
        microphone = new VoiceButton(this, this::pressVoice, () -> { if (voice != null) voice.release(); });
        microphone.setEnabled(false);
        FrameLayout.LayoutParams micPosition = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.RIGHT);
        micPosition.setMargins(dp(8), dp(8), dp(8), 0); videoPane.addView(microphone, micPosition);
        buildChatPanel(); setContentView(root); installInsets(); updateLayout();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
        IntentFilter filter = new IntentFilter(getPackageName() + ".STATUS");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(messages, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(messages, filter);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
    }
    void button(LinearLayout box, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); box.addView(b);
    }
    Button barButton(LinearLayout box, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setTextSize(12); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(3), 0, dp(3), 0); b.setOnClickListener(v -> action.run());
        box.addView(b, new LinearLayout.LayoutParams(0, dp(44), 1)); return b;
    }
    int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    void readableInput(EditText input) { input.setTextColor(Color.BLACK); input.setHintTextColor(0xff666666); input.setBackgroundColor(Color.WHITE); }
    void resetChatIdle() { ui.removeCallbacks(idleChat); if (chatOpen) ui.postDelayed(idleChat, 5000); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (chatOpen && event.getActionMasked() == MotionEvent.ACTION_DOWN) resetChatIdle();
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (chatOpen && event.getAction() == KeyEvent.ACTION_DOWN) resetChatIdle();
        return super.dispatchKeyEvent(event);
    }
    void buildChatPanel() {
        chatPanel = new LinearLayout(this); chatPanel.setOrientation(LinearLayout.VERTICAL);
        chatPanel.setPadding(dp(6), 0, dp(6), 0); chatPanel.setBackgroundColor(0xff171717);
        chatScroll = new ScrollView(this); chatHistory = new TextView(this);
        chatHistory.setTextColor(0xffffffff); chatHistory.setTextSize(14); chatHistory.setPadding(dp(8), dp(4), dp(8), dp(4));
        chatHistory.setText("As mensagens aparecem no alto do filme."); chatScroll.addView(chatHistory);
        chatPanel.addView(chatScroll, new LinearLayout.LayoutParams(-1, dp(56)));
        LinearLayout inputRow = new LinearLayout(this); inputRow.setGravity(Gravity.CENTER_VERTICAL);
        chatInput = new EditText(this); chatInput.setSingleLine(true); chatInput.setHint("Mensagem");
        chatInput.setTextColor(0xffffffff); chatInput.setHintTextColor(0xffbbbbbb); chatInput.setTextSize(16);
        chatInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        chatInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(280)});
        chatInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            public void afterTextChanged(Editable s) { resetChatIdle(); }
        });
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        chatInput.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEND) { sendChat(); return true; } return false; });
        inputRow.addView(chatInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button send = new Button(this); send.setText("Enviar"); send.setTextSize(12); send.setMinWidth(0); send.setMinimumWidth(0);
        send.setOnClickListener(v -> sendChat()); inputRow.addView(send, new LinearLayout.LayoutParams(dp(70), dp(44)));
        Button close = new Button(this); close.setText("×"); close.setContentDescription("Fechar chat"); close.setMinWidth(0); close.setMinimumWidth(0);
        close.setOnClickListener(v -> closeChat()); inputRow.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        chatPanel.addView(inputRow);
        emojiRow = new LinearLayout(this);
        chatPanel.addView(emojiRow); mainLayout.addView(chatPanel, new LinearLayout.LayoutParams(-1, -2));
        chatPanel.setVisibility(View.GONE);
    }
    void installInsets() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                root.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                handleKeyboard(insets.isVisible(WindowInsets.Type.ime()));
                return insets;
            });
            root.requestApplyInsets();
        } else {
            root.setFitsSystemWindows(true);
            root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                Rect visible = new Rect(); root.getWindowVisibleDisplayFrame(visible);
                handleKeyboard(root.getRootView().getHeight() - visible.bottom > dp(120));
            });
        }
    }
    void handleKeyboard(boolean visible) {
        imeVisible = visible;
        if (chatOpen && visible) keyboardSeen = true;
        chatScroll.setVisibility(visible ? View.GONE : View.VISIBLE);
        // Android's landscape keyboard can otherwise turn into a full-screen text editor.
        emojiRow.setVisibility(View.GONE);
        if (chatOpen && keyboardSeen && !visible) root.post(() -> { if (chatOpen && !imeVisible) closeChat(); });
    }
    void updateLayout() {
        setupScroll.setVisibility(watching && viewerJoined ? View.GONE : View.VISIBLE);
        videoPane.setVisibility(watching && viewerJoined ? View.VISIBLE : View.GONE);
        fullscreenButton.setText(fullscreen ? "Voltar" : "Tela cheia");
        if (!watching && chatOpen) closeChat();
        root.requestLayout(); screen.invalidate();
    }
    void revealControls() {
        viewerBar.setVisibility(View.VISIBLE); ui.removeCallbacks(hideControls);
        if (fullscreen && !chatOpen) ui.postDelayed(hideControls, 4500);
    }
    void enterFullscreen() {
        if (!watching) return;
        if (!chatOpen) dismissSetupKeyboard();
        fullscreen = true; updateLayout(); applyImmersive(); revealControls();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }
    void applyImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        } else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
    }
    void showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.systemBars());
        } else getWindow().getDecorView().setSystemUiVisibility(0);
    }
    void exitFullscreen() {
        fullscreen = false; ui.removeCallbacks(hideControls); viewerBar.setVisibility(View.VISIBLE);
        showSystemBars(); setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED); updateLayout();
    }
    void openHostChat() {
        if (!CaptureService.active) { show("Inicie uma transmissão para usar o chat flutuante."); return; }
        if (!Settings.canDrawOverlays(this)) {
            startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), 5);
        } else startService(new Intent(this, CaptureService.class).setAction("OPEN_CHAT"));
    }
    void pressVoice() {
        if (!watching || !viewerJoined || voice == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 6); return;
        }
        voice.press();
    }
    void startVoice() {
        voice = new VoiceTalk(this, "viewer", new VoiceTalk.Listener() {
            public boolean sendText(String text) { return sendViewerText(text); }
            public boolean sendBinary(byte[] packet) { WebSocket ws = socket; return watching && viewerJoined && ws != null && ws.queueSize() < 128*1024 && ws.send(ByteString.of(packet)); }
            public void muteMovie(boolean muted) {
                PlaybackEngine engine = playback; if (engine != null) engine.setFilmMuted(muted);
                ui.post(() -> {
                    setVolumeControlStream(muted ? android.media.AudioManager.STREAM_VOICE_CALL : android.media.AudioManager.STREAM_MUSIC);
                    if (microphone != null) microphone.setTalking(voice != null && voice.isTalking());
                });
            }
            public void error(String message) { ui.post(() -> { if (!destroyed && watching) Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show(); }); }
        });
    }
    void openChat() {
        if (CaptureService.active) { openHostChat(); return; }
        if (!watching) { show("Entre em uma sala primeiro."); return; }
        chatOpen = true; screen.setFitForChat(true); keyboardSeen = false; chatPanel.setVisibility(View.VISIBLE); unreadChat = false;
        updateChatButtons(); ui.removeCallbacks(hideControls); viewerBar.setVisibility(View.GONE); showSystemBars();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        showChatKeyboard();
        resetChatIdle();
    }
    void showChatKeyboard() {
        chatInput.requestFocus();
        chatInput.postDelayed(() -> {
            if (!chatOpen || getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT) return;
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT);
        }, 250);
    }
    void closeChat() {
        ui.removeCallbacks(idleChat);
        chatOpen = false; screen.setFitForChat(false); keyboardSeen = false; chatPanel.setVisibility(View.GONE);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
        chatInput.clearFocus(); screen.requestFocus();
        if (watching) setRequestedOrientation(fullscreen ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (fullscreen) applyImmersive();
        if (watching) revealControls();
    }
    void updateChatButtons() { chatButton.setText(unreadChat ? "Chat •" : "Chat"); }
    void receiveChat(String from, String text) {
        if (text == null || text.isEmpty()) return;
        String line = (("host".equals(from) && CaptureService.active) || ("viewer".equals(from) && watching) ? "Você: " : "Amigo: ") + text;
        ui.post(() -> {
            if (destroyed) return;
            chatLines.addLast(line); while (chatLines.size() > 20) chatLines.removeFirst();
            chatHistory.setText(String.join("\n", chatLines)); chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
            if (!chatOpen) { unreadChat = true; updateChatButtons(); }
            messageBanner.setText(line); messageBanner.setVisibility(View.VISIBLE);
            int sequence = ++bannerSequence;
            ui.postDelayed(() -> { if (sequence == bannerSequence) messageBanner.setVisibility(View.GONE); }, 5000);
        });
    }
    void sendChat() {
        resetChatIdle();
        String text = chatInput.getText().toString().trim(); if (text.isEmpty()) return;
        try {
            if (sendViewerText(new JSONObject().put("type", "chat").put("text", text).toString())) chatInput.setText("");
            else show("Chat desconectado. Entre na sala novamente.");
        } catch (Exception e) { show("Não foi possível enviar a mensagem."); }
    }
    boolean sendViewerText(String text) {
        WebSocket ws = socket;
        // OkHttp queues sends while connecting. The room identification must be first.
        return watching && viewerJoined && ws != null && ws.send(text);
    }
    void endSession() {
        closeChat(); stopViewer(); stopService(new Intent(this, CaptureService.class)); updateLayout(); show("Encerrado.");
    }
    @Override public void onBackPressed() {
        if (chatOpen) closeChat(); else if (fullscreen) exitFullscreen(); else super.onBackPressed();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); updateLayout(); root.requestApplyInsets();
        if (chatOpen) { keyboardSeen = false; showChatKeyboard(); resetChatIdle(); }
        if (watching && !chatOpen) dismissSetupKeyboard();
        if (fullscreen && !chatOpen) applyImmersive();
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (focused && fullscreen && !chatOpen) applyImmersive();
    }

    void show(String s) { runOnUiThread(() -> status.setText(s == null ? "" : s)); }
    String validEndpoint(String input) {
        HttpUrl u = HttpUrl.parse(input.trim());
        if (u == null || !u.scheme().equals("https") || !u.username().isEmpty() || !u.password().isEmpty()) throw new IllegalArgumentException("Use o endereço HTTPS do servidor.");
        return u.newBuilder().encodedPath("/").query(null).fragment(null).build().toString();
    }
    void startHost() {
        if (CaptureService.active || CaptureService.starting || hostFlowPending) {
            show("A transmissão já está iniciando ou ativa. Encerre antes de criar outra sala."); return;
        }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView explanation = new TextView(this); explanation.setTextColor(Color.BLACK);
        explanation.setText("Escolha uma palavra com exatamente 4 letras, como GATO. Até 3 pessoas podem entrar: você e mais duas.");
        box.addView(explanation);
        EditText word = new EditText(this); word.setSingleLine(true); readableInput(word);
        word.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        word.setHint("Palavra de 4 letras, ex.: GATO"); word.setContentDescription("Palavra que você vai criar");
        word.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        box.addView(word);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Criar palavra da sala").setView(box)
            .setPositiveButton("Criar sala", null).setNegativeButton("Cancelar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                String value = RoomPassword.fourLetters(word.getText().toString());
                validEndpoint(endpoint.getText().toString()); dialog.dismiss(); startHostWithPassword(value);
            } catch (IllegalArgumentException error) { word.setError(error.getMessage()); }
        }));
        dialog.show();
    }
    void startHostWithPassword(String password) {
        try {
            if (CaptureService.active || CaptureService.starting || hostFlowPending) { show("A transmissão já está iniciando ou ativa. Encerre antes de criar outra sala."); return; }
            pendingPassword = RoomPassword.fourLetters(password); pendingRoom = RoomPassword.room(pendingPassword);
            stopViewer(); pendingEndpoint = validEndpoint(endpoint.getText().toString());
            hostFlowPending = true;
            getPreferences(0).edit().putString("endpoint", pendingEndpoint).apply();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
            }
            requestHostOverlay();
        } catch (Exception e) { hostFlowPending = false; show(e.getMessage()); }
    }
    void requestHostOverlay() {
        if (Settings.canDrawOverlays(this)) { requestScreen(); return; }
        hostFlowPending = false;
        new AlertDialog.Builder(this).setTitle("Chat e microfone sobre o filme")
            .setMessage("Ative ‘Aparecer sobre outros apps’ para ler as mensagens, responder pelo Chat e segurar o microfone para falar enquanto transmite.")
            .setPositiveButton("Permitir botão flutuante", (dialog, which) -> {
                try { hostFlowPending = true; startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), 4); }
                catch (Exception e) { hostFlowPending = false; show("Abra as configurações do Android e permita aparecer sobre outros apps."); }
            }).setNegativeButton("Cancelar", (dialog, which) -> hostFlowPending = false)
            .setOnCancelListener(dialog -> hostFlowPending = false).show();
    }
    void requestScreen() {
        hostFlowPending = false;
        MediaProjectionManager pm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        String message = CaptureService.supportsSingleAppSharing()
            ? "No próximo aviso, se houver a opção ‘Um app’, selecione o app do filme. Assim o chat flutuante e o teclado ficam só no seu aparelho. A opção ‘Tela inteira’ também os transmite."
            : "As mensagens aparecerão no alto do filme para você também. Este Android compartilha a tela inteira, então o amigo também poderá ver seu chat e teclado. Abra o filme após autorizar a captura.";
        new AlertDialog.Builder(this).setTitle("Compartilhar filme").setMessage(message)
            .setPositiveButton("Continuar", (dialog, which) -> {
                try { hostFlowPending = true; startActivityForResult(pm.createScreenCaptureIntent(), 2); }
                catch (Exception error) { hostFlowPending = false; show("Não foi possível pedir a captura. Tente novamente."); }
            })
            .setNegativeButton("Cancelar", (dialog, which) -> hostFlowPending = false)
            .setOnCancelListener(dialog -> hostFlowPending = false).show();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 6) {
            Toast.makeText(this, results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                ? "Microfone liberado. Segure o botão para falar." : "Permita o microfone para falar com seu amigo.", Toast.LENGTH_LONG).show();
        }
        if (code == 1) { if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestHostOverlay(); else { hostFlowPending = false; show("Sem a permissão de áudio não é possível transmitir o som."); } }
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 4) {
            if (Settings.canDrawOverlays(this)) requestScreen();
            else { hostFlowPending = false; show("Permita aparecer sobre outros apps para usar o microfone ao transmitir."); }
            return;
        }
        if (code == 5) { if (Settings.canDrawOverlays(this) && CaptureService.active) openHostChat(); return; }
        if (code != 2) return;
        hostFlowPending = false;
        if (result != RESULT_OK || data == null) { show("Compartilhamento cancelado."); return; }
        if (pendingEndpoint == null || pendingRoom == null || pendingPassword == null) { show("Crie a senha novamente para iniciar a transmissão."); return; }
        Intent service = new Intent(this, CaptureService.class).putExtra("result", result).putExtra("data", data).putExtra("endpoint", pendingEndpoint).putExtra("room", pendingRoom);
        CaptureService.starting = true;
        try { startForegroundService(service); }
        catch (Exception error) { CaptureService.starting = false; show("Não foi possível iniciar a transmissão. Tente novamente."); return; }
        getPreferences(0).edit().putString("hostInvite", pendingPassword).apply();
        roomWord.setText("PALAVRA DA SALA: " + pendingPassword.toUpperCase(java.util.Locale.ROOT));
        invitation.setText("");
        show("Criando sala… aguarde para convidar as duas pessoas.");
    }
    void share() {
        String word = getPreferences(0).getString("hostInvite", "");
        if (!CaptureService.active || word.isEmpty()) { show("Toque em Criar sala e aguarde a confirmação antes de convidar."); return; }
        String visible = word.toUpperCase(java.util.Locale.ROOT);
        new AlertDialog.Builder(this).setTitle("Convidar pessoa · " + visible)
            .setMessage("Palavra da sala: " + visible + "\n\nEnvie a mesma palavra para as duas pessoas. No Cinema Direto 0.20, elas digitam a palavra e tocam em Entrar na sala.")
            .setPositiveButton("Enviar convite", (d, w) -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "Vamos assistir juntos! No Cinema Direto 0.20, digite a palavra " + visible + " e toque em Entrar na sala. Até 3 pessoas na mesma sala.");
                startActivity(Intent.createChooser(send, "Enviar convite"));
            }).setNeutralButton("Copiar palavra", (d, w) -> {
                getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Palavra da sala", visible));
                Toast.makeText(this, "Palavra copiada: " + visible, Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Fechar", null).show();
    }
    void dismissSetupKeyboard() {
        root.setFocusableInTouchMode(true); root.requestFocus();
        invitation.clearFocus(); endpoint.clearFocus();
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(root.getWindowToken(), 0);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null)
            getWindow().getInsetsController().hide(WindowInsets.Type.ime());
    }
    void startViewer() {
        try {
            if (CaptureService.active || CaptureService.starting || hostFlowPending) { show("Encerre ou cancele sua transmissão antes de assistir."); return; }
            String value = RoomPassword.fourLetters(invitation.getText().toString());
            String base = validEndpoint(endpoint.getText().toString()), room = RoomPassword.room(value);
            dismissSetupKeyboard();
            stopViewer(); watching = true; updateLayout();
            startPlayback(); startVoice(); show("Entrando na sala com a palavra… aguarde a conexão direta.");
            WebSocketListener directListener = new WebSocketListener() {

                @Override public void onOpen(WebSocket ws, Response response) {
                    try { ws.send(new JSONObject().put("role", "viewer").put("room", room).put("device", RoomPassword.device(MainActivity.this)).toString()); } catch (Exception ignored) {}
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    if (!watching || socket != ws) return;
                    try {
                        JSONObject msg = new JSONObject(text);
                        if ("error".equals(msg.optString("type"))) {
                            String reason = msg.optString("message", "Não foi possível entrar na sala.");
                            ui.post(() -> { if (socket == ws) { stopViewer(); show(reason); } });
                        }
                        else if ("chat".equals(msg.optString("type"))) receiveChat(msg.optString("from"), msg.optString("text"));
                        else if ("talk".equals(msg.optString("type")) && voice != null) voice.onControl(msg);
                        else if ("media-reset".equals(msg.optString("type")) && playback != null) playback.resetStream();
                        else if (msg.has("status")) {
                            if (msg.optBoolean("joined")) {
                                ui.post(() -> {
                                    if (watching && socket == ws) { viewerJoined = true; microphone.setEnabled(true); updateLayout(); }
                                });
                            }
                            show(msg.optString("status"));
                        }
                    } catch (Exception ignored) {}
                }
                @Override public void onMessage(WebSocket ws, ByteString message) {
                    if (!watching || socket != ws || message.size() < 2) return;
                    if (message.getByte(0) == 7) { VoiceTalk talk = voice; if (talk != null) talk.onAudio(message.toByteArray()); return; }
                    if (message.getByte(0) == 1 || message.getByte(0) == 2) {
                        show("Os dois aparelhos precisam instalar a nova versão para usar o vídeo mais fluido."); return;
                    }
                    PlaybackEngine engine = playback;
                    if (engine != null) engine.offer(message.toByteArray());
                }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) { runOnUiThread(() -> { if (socket == ws) { stopViewer(); show(t.getMessage()); } }); }
                @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
                @Override public void onClosed(WebSocket ws, int code, String reason) { runOnUiThread(() -> { if (socket == ws) { stopViewer(); show(reason); } }); }
            };
            socket = new DirectRoomSocket(this, false, base, room, directListener);
        } catch (Exception e) { stopViewer(); show(e.getMessage()); }
    }
    void startPlayback() {
        final int generation = ++playbackGeneration;
        loading.setText("Carregando imagem e som…"); loading.setVisibility(View.VISIBLE);
        playback = new PlaybackEngine(screen, new PlaybackEngine.Listener() {
            public void buffering(boolean value) { ui.post(() -> {
                if (!destroyed && watching && generation == playbackGeneration) loading.setVisibility(value ? View.VISIBLE : View.GONE);
            }); }
            public void requestKeyFrame() {
                if (generation == playbackGeneration) sendViewerText("{\"type\":\"request-keyframe\"}");
            }
            public void error(String message) { ui.post(() -> {
                if (!destroyed && watching && generation == playbackGeneration) { loading.setText(message); loading.setVisibility(View.VISIBLE); }
            }); }
        });
    }
    void stopViewer() {
        viewerJoined = false;
        if (microphone != null) microphone.setEnabled(false);
        if (voice != null) { voice.close(); voice = null; }
        if (microphone != null) microphone.setTalking(false);
        watching = false; ++playbackGeneration; if (socket != null) { WebSocket old = socket; socket = null; old.cancel(); }
        if (playback != null) { playback.close(); playback = null; }
        if (loading != null) loading.setVisibility(View.GONE);
        if (screen != null) screen.clearFrame();
        if (chatOpen) closeChat();
        if (fullscreen) exitFullscreen(); else if (controls != null) updateLayout();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("pendingEndpoint", pendingEndpoint); state.putString("pendingRoom", pendingRoom);
        state.putString("pendingPassword", pendingPassword); state.putBoolean("hostFlowPending", hostFlowPending);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { destroyed = true; stopViewer(); ui.removeCallbacksAndMessages(null); unregisterReceiver(messages); client.dispatcher().executorService().shutdown(); super.onDestroy(); }
    @Override protected void onPause() { if (voice != null) voice.release(); super.onPause(); }
}

