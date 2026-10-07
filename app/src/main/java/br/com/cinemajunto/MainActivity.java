package br.com.cinemajunto;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Rect;
import android.media.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

public class MainActivity extends Activity {
    EditText endpoint, invitation, chatInput;
    TextView status, chatHistory, messageBanner;
    FilmView screen;
    ScrollView setupScroll, chatScroll;
    LinearLayout mainLayout, controls, viewerBar, chatPanel, emojiRow;
    FrameLayout root, videoPane;
    Button chatButton, fullscreenButton, zoomButton;
    final ArrayDeque<String> chatLines = new ArrayDeque<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean fullscreen, unreadChat, imeVisible, keyboardSeen, chatOpen, destroyed;
    int bannerSequence, playbackGeneration;
    String pendingEndpoint, pendingRoom;
    final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    WebSocket socket;
    volatile boolean watching;
    final ArrayBlockingQueue<byte[]> sound = new ArrayBlockingQueue<>(12);
    final AtomicReference<byte[]> latest = new AtomicReference<>();
    Thread audioThread, imageThread;
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = new FrameLayout(this); root.setBackgroundColor(0xff000000);
        mainLayout = new LinearLayout(this); mainLayout.setOrientation(LinearLayout.VERTICAL);
        root.addView(mainLayout, new FrameLayout.LayoutParams(-1, -1));
        setupScroll = new ScrollView(this);
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), dp(12), dp(16), dp(12)); controls.setBackgroundColor(0xfffafafa);
        setupScroll.addView(controls); mainLayout.addView(setupScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView title = new TextView(this); title.setText("Cinema Junto Chat Final"); title.setTextSize(24); controls.addView(title);
        TextView subtitle = new TextView(this); subtitle.setText("Você e seu amigo, com filme e mensagens juntos."); controls.addView(subtitle);
        endpoint = new EditText(this); endpoint.setSingleLine(true); endpoint.setHint("Endereço do servidor");
        endpoint.setText(getPreferences(0).getString("endpoint", "https://cinema-junto-welliton.onrender.com/")); controls.addView(endpoint);
        invitation = new EditText(this); invitation.setSingleLine(true); invitation.setHint("Cole aqui o convite para assistir");
        if (CaptureService.active) invitation.setText(getPreferences(0).getString("hostInvite", "")); controls.addView(invitation);
        button(controls, "Transmitir minha tela e o som", () -> startHost());
        button(controls, "Compartilhar convite", () -> share());
        button(controls, "Assistir ao meu amigo", () -> startViewer());
        button(controls, "Chat de quem transmite", () -> openHostChat());
        button(controls, "Encerrar", () -> endSession());
        status = new TextView(this); status.setText("Inicie uma transmissão ou cole o convite do seu amigo."); controls.addView(status);
        TextView tip = new TextView(this);
        tip.setText("Ao transmitir, permita aparecer sobre outros apps. O botão Chat fica sobre o filme para ler e responder mensagens."); controls.addView(tip);
        videoPane = new FrameLayout(this); mainLayout.addView(videoPane, new LinearLayout.LayoutParams(-1, 0, 1));
        screen = new FilmView(this); screen.setContentDescription("Filme compartilhado");
        videoPane.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        screen.setOnClickListener(v -> revealControls());
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
        bannerPosition.setMargins(dp(10), dp(8), dp(10), 0); videoPane.addView(messageBanner, bannerPosition);
        messageBanner.setVisibility(View.GONE);
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
        chatInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(280)});
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        chatInput.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEND) { sendChat(); return true; } return false; });
        inputRow.addView(chatInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button send = new Button(this); send.setText("Enviar"); send.setTextSize(12); send.setMinWidth(0); send.setMinimumWidth(0);
        send.setOnClickListener(v -> sendChat()); inputRow.addView(send, new LinearLayout.LayoutParams(dp(70), dp(44)));
        Button close = new Button(this); close.setText("×"); close.setContentDescription("Fechar chat"); close.setMinWidth(0); close.setMinimumWidth(0);
        close.setOnClickListener(v -> closeChat()); inputRow.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        chatPanel.addView(inputRow);
        emojiRow = new LinearLayout(this);
        for (String emoji : new String[]{"😀", "😂", "😍", "❤️", "👍"}) {
            Button choice = new Button(this); choice.setText(emoji); choice.setTextSize(17); choice.setMinHeight(0); choice.setMinimumHeight(0);
            choice.setOnClickListener(v -> chatInput.getText().insert(Math.max(0, chatInput.getSelectionStart()), emoji));
            emojiRow.addView(choice, new LinearLayout.LayoutParams(0, dp(34), 1));
        }
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
        emojiRow.setVisibility(View.VISIBLE);
        if (chatOpen && keyboardSeen && !visible) root.post(() -> { if (chatOpen && !imeVisible) closeChat(); });
    }
    void updateLayout() {
        setupScroll.setVisibility(watching ? View.GONE : View.VISIBLE);
        videoPane.setVisibility(watching ? View.VISIBLE : View.GONE);
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
    void openChat() {
        if (CaptureService.active) { openHostChat(); return; }
        if (!watching) { show("Entre em uma sala primeiro."); return; }
        chatOpen = true; keyboardSeen = false; chatPanel.setVisibility(View.VISIBLE); unreadChat = false;
        updateChatButtons(); ui.removeCallbacks(hideControls); viewerBar.setVisibility(View.GONE); showSystemBars();
        chatInput.requestFocus();
        chatInput.postDelayed(() -> { if (chatOpen) ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT); }, 150);
    }
    void closeChat() {
        chatOpen = false; keyboardSeen = false; chatPanel.setVisibility(View.GONE);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
        chatInput.clearFocus(); screen.requestFocus();
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
        String text = chatInput.getText().toString().trim(); if (text.isEmpty()) return;
        try {
            if (watching && socket != null && socket.send(new JSONObject().put("type", "chat").put("text", text).toString())) chatInput.setText("");
            else show("Chat desconectado. Entre na sala novamente.");
        } catch (Exception e) { show("Não foi possível enviar a mensagem."); }
    }
    void endSession() {
        closeChat(); stopViewer(); stopService(new Intent(this, CaptureService.class)); updateLayout(); show("Encerrado.");
    }
    @Override public void onBackPressed() {
        if (chatOpen) closeChat(); else if (fullscreen) exitFullscreen(); else super.onBackPressed();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config); updateLayout(); root.requestApplyInsets();
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
        try {
            if (CaptureService.active) { show("Já está transmitindo. Encerre antes de criar outra sala."); return; }
            stopViewer(); pendingEndpoint = validEndpoint(endpoint.getText().toString());
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            StringBuilder token = new StringBuilder(); for (byte b : random) token.append(String.format("%02x", b & 255)); pendingRoom = token.toString();
            getPreferences(0).edit().putString("endpoint", pendingEndpoint).apply();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
            }
            requestHostOverlay();
        } catch (Exception e) { show(e.getMessage()); }
    }
    void requestHostOverlay() {
        if (Settings.canDrawOverlays(this)) { requestScreen(); return; }
        new AlertDialog.Builder(this).setTitle("Chat sobre o filme")
            .setMessage("Ative ‘Aparecer sobre outros apps’ para ler e responder mensagens enquanto transmite. A janela só aparece durante a transmissão.")
            .setPositiveButton("Permitir chat flutuante", (dialog, which) -> {
                try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())), 4); }
                catch (Exception e) { show("Abra as configurações do Android e permita aparecer sobre outros apps."); }
            }).setNegativeButton("Cancelar", null).show();
    }
    void requestScreen() {
        MediaProjectionManager pm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(pm.createScreenCaptureIntent(), 2);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) { if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestHostOverlay(); else show("Sem a permissão de áudio não é possível transmitir o som."); }
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == 4) {
            if (Settings.canDrawOverlays(this)) requestScreen();
            else show("Permita aparecer sobre outros apps para usar o chat ao transmitir.");
            return;
        }
        if (code == 5) { if (Settings.canDrawOverlays(this) && CaptureService.active) openHostChat(); return; }
        if (code != 2) return;
        if (result != RESULT_OK || data == null) { show("Compartilhamento cancelado."); return; }
        Intent service = new Intent(this, CaptureService.class).putExtra("result", result).putExtra("data", data).putExtra("endpoint", pendingEndpoint).putExtra("room", pendingRoom);
        startForegroundService(service);
        String invite = pendingEndpoint + "#" + pendingRoom;
        invitation.setText(invite); getPreferences(0).edit().putString("hostInvite", invite).apply();
        show("Conectando… aguarde a confirmação antes de enviar o convite.");
    }
    void share() {
        String value = invitation.getText().toString().trim();
        if (!CaptureService.active || value.isEmpty()) { show("Inicie a transmissão e aguarde a conexão primeiro."); return; }
        Intent send = new Intent(Intent.ACTION_SEND); send.setType("text/plain"); send.putExtra(Intent.EXTRA_TEXT, value);
        startActivity(Intent.createChooser(send, "Envie ao seu amigo. Ele deve colar no Cinema Junto."));
    }
    void startViewer() {
        try {
            if (CaptureService.active) { show("Encerre sua transmissão antes de assistir."); return; }
            Uri uri = Uri.parse(invitation.getText().toString().trim());
            String base = validEndpoint(uri.toString()); String room = uri.getFragment();
            if (room == null || !room.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Cole o convite completo enviado pelo seu amigo.");
            stopViewer(); endpoint.setText(base); watching = true; updateLayout();
            startPlayback(); show("Conectando ao seu amigo…");
            String url = base.replaceFirst("^https", "wss") + "relay";
            socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    try { ws.send(new JSONObject().put("role", "viewer").put("room", room).toString()); } catch (Exception ignored) {}
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    if (!watching || socket != ws) return;
                    try {
                        JSONObject msg = new JSONObject(text);
                        if ("chat".equals(msg.optString("type"))) receiveChat(msg.optString("from"), msg.optString("text"));
                        else if (msg.has("status")) show(msg.optString("status"));
                    } catch (Exception ignored) {}
                }
                @Override public void onMessage(WebSocket ws, ByteString message) {
                    if (!watching || socket != ws || message.size() < 2) return;
                    byte[] data = message.substring(1).toByteArray();
                    if (message.getByte(0) == 1) latest.set(data);
                    else if (message.getByte(0) == 2) { if (!sound.offer(data)) { sound.poll(); sound.offer(data); } }
                }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) { runOnUiThread(() -> { if (socket == ws) { stopViewer(); show("Conexão falhou. Confira o convite e tente novamente."); } }); }
                @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
                @Override public void onClosed(WebSocket ws, int code, String reason) { runOnUiThread(() -> { if (socket == ws) { stopViewer(); show(reason); } }); }
            });
        } catch (Exception e) { stopViewer(); show(e.getMessage()); }
    }
    void startPlayback() {
        final int generation = ++playbackGeneration;
        audioThread = new Thread(() -> {
            AudioTrack track = null;
            try {
                int min = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, 8820)).setTransferMode(AudioTrack.MODE_STREAM).build();
                track.play();
                while (!Thread.currentThread().isInterrupted()) {
                    byte[] pcm = sound.poll(100, TimeUnit.MILLISECONDS);
                    if (pcm != null) track.write(pcm, 0, pcm.length);
                }
            } catch (InterruptedException ignored) {} catch (Exception e) { show("Falha ao reproduzir o áudio. Encerre e tente novamente."); }
            finally { if (track != null) { track.stop(); track.release(); } }
        }, "CinemaAudio"); audioThread.start();
        imageThread = new Thread(() -> {
            try { while (!Thread.currentThread().isInterrupted()) {
                byte[] jpeg = latest.getAndSet(null);
                if (jpeg != null) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (bitmap != null) runOnUiThread(() -> {
                        if (watching && !destroyed && generation == playbackGeneration) screen.setFrame(bitmap);
                        else bitmap.recycle();
                    });
                }
                Thread.sleep(80);
            }} catch (InterruptedException ignored) {}
        }, "CinemaImagem"); imageThread.start();
    }
    void stopViewer() {
        watching = false; ++playbackGeneration; if (socket != null) { WebSocket old = socket; socket = null; old.cancel(); }
        if (audioThread != null) { audioThread.interrupt(); try { audioThread.join(500); } catch (InterruptedException ignored) {} audioThread = null; }
        if (imageThread != null) { imageThread.interrupt(); try { imageThread.join(500); } catch (InterruptedException ignored) {} imageThread = null; }
        sound.clear(); latest.set(null);
        if (screen != null) screen.clearFrame();
        if (chatOpen) closeChat();
        if (fullscreen) exitFullscreen(); else if (controls != null) updateLayout();
    }
    @Override protected void onDestroy() { destroyed = true; stopViewer(); ui.removeCallbacksAndMessages(null); unregisterReceiver(messages); client.dispatcher().executorService().shutdown(); super.onDestroy(); }
}
