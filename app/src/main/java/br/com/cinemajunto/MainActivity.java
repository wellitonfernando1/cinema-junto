package br.com.cinemajunto;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
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
    EditText endpoint, invitation;
    TextView status, chatHistory, messageBanner;
    ImageView screen;
    Bitmap currentFrame;
    LinearLayout controls, viewerBar, chatPanel, mainLayout, fullscreenBar;
    FrameLayout root;
    ScrollView chatScroll;
    Button chatButton, fullscreenChatButton;
    EditText chatInput;
    final ArrayDeque<String> chatLines = new ArrayDeque<>();
    final Handler ui = new Handler(Looper.getMainLooper());
    boolean fullscreen, unreadChat;
    int bannerSequence;
    String pendingEndpoint, pendingRoom;
    final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    WebSocket socket;
    volatile boolean watching;
    final ArrayBlockingQueue<byte[]> sound = new ArrayBlockingQueue<>(12);
    final AtomicReference<byte[]> latest = new AtomicReference<>();
    Thread audioThread, imageThread;
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
        root = new FrameLayout(this);
        LinearLayout box = new LinearLayout(this); mainLayout = box;
        box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(12),dp(8),dp(12),dp(8));
        root.addView(box, new FrameLayout.LayoutParams(-1, -1));
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); box.addView(controls);
        TextView title = new TextView(this); title.setText("Cinema Junto Chat e Tela Cheia"); title.setTextSize(24); controls.addView(title);
        TextView subtitle = new TextView(this); subtitle.setText("Versão de teste • apenas você e seu amigo"); controls.addView(subtitle);
        endpoint = new EditText(this); endpoint.setSingleLine(true); endpoint.setHint("Endereço do servidor: https://…");
        endpoint.setText(getPreferences(0).getString("endpoint", "https://cinema-junto-welliton.onrender.com/")); controls.addView(endpoint);
        invitation = new EditText(this); invitation.setSingleLine(true); invitation.setHint("Cole aqui o convite para assistir");
        if (CaptureService.active) invitation.setText(getPreferences(0).getString("hostInvite", "")); controls.addView(invitation);
        button(controls, "Transmitir minha tela e o som", () -> startHost());
        button(controls, "Compartilhar convite", () -> share());
        button(controls, "Assistir ao meu amigo", () -> startViewer());
        button(controls, "Chat", () -> openChat());
        button(controls, "Encerrar", () -> endSession());
        viewerBar = new LinearLayout(this); viewerBar.setOrientation(LinearLayout.HORIZONTAL); box.addView(viewerBar);
        barButton(viewerBar, "Tela cheia", () -> enterFullscreen());
        chatButton = barButton(viewerBar, "Chat", () -> openChat());
        barButton(viewerBar, "Encerrar", () -> endSession());
        status = new TextView(this); status.setText("Servidor preenchido. Inicie ou entre em uma sala."); box.addView(status);
        screen = new ImageView(this); screen.setScaleType(ImageView.ScaleType.FIT_CENTER); screen.setBackgroundColor(0xff000000);
        box.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout floating = new LinearLayout(this); fullscreenBar = floating; floating.setOrientation(LinearLayout.HORIZONTAL);
        floating.setBackgroundColor(0x99000000);
        Button exit = new Button(this); exit.setText("Sair da tela cheia"); exit.setOnClickListener(v -> exitFullscreen()); floating.addView(exit);
        fullscreenChatButton = new Button(this); fullscreenChatButton.setText("Chat"); fullscreenChatButton.setOnClickListener(v -> openChat()); floating.addView(fullscreenChatButton);
        FrameLayout.LayoutParams floatingPosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        root.addView(floating, floatingPosition);
        messageBanner = new TextView(this); messageBanner.setTextColor(0xffffffff); messageBanner.setTextSize(18);
        messageBanner.setPadding(dp(14), dp(10), dp(14), dp(10)); messageBanner.setBackgroundColor(0xdd202020);
        FrameLayout.LayoutParams bannerPosition = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        bannerPosition.topMargin = dp(56); root.addView(messageBanner, bannerPosition); messageBanner.setVisibility(View.GONE);
        buildChatPanel();
        setContentView(root); updateLayout();
        IntentFilter filter = new IntentFilter(getPackageName() + ".STATUS");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(messages, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(messages, filter);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
    }
    void button(LinearLayout box, String label, Runnable action) { Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); box.addView(b); }
    Button barButton(LinearLayout box, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run());
        box.addView(b, new LinearLayout.LayoutParams(0, -2, 1)); return b;
    }
    int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    void buildChatPanel() {
        chatPanel = new LinearLayout(this); chatPanel.setOrientation(LinearLayout.VERTICAL);
        chatPanel.setPadding(dp(10), dp(8), dp(10), dp(10)); chatPanel.setBackgroundColor(0xee171717);
        TextView heading = new TextView(this); heading.setText("Mensagens"); heading.setTextColor(0xffffffff); heading.setTextSize(18);
        chatPanel.addView(heading);
        ScrollView historyScroll = new ScrollView(this); chatScroll = historyScroll;
        chatHistory = new TextView(this); chatHistory.setTextColor(0xffffffff); chatHistory.setTextSize(16);
        chatHistory.setText("Nenhuma mensagem ainda."); historyScroll.addView(chatHistory);
        chatPanel.addView(historyScroll, new LinearLayout.LayoutParams(-1, dp(110)));
        LinearLayout inputRow = new LinearLayout(this); inputRow.setOrientation(LinearLayout.HORIZONTAL);
        chatInput = new EditText(this); chatInput.setSingleLine(true); chatInput.setHint("Escreva uma mensagem");
        chatInput.setTextColor(0xffffffff); chatInput.setHintTextColor(0xffaaaaaa);
        chatInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(280)});
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendChat(); return true; }
            return false;
        });
        inputRow.addView(chatInput, new LinearLayout.LayoutParams(0, -2, 1));
        Button send = new Button(this); send.setText("Enviar"); send.setOnClickListener(v -> sendChat()); inputRow.addView(send);
        chatPanel.addView(inputRow);
        LinearLayout emojiRow = new LinearLayout(this); emojiRow.setOrientation(LinearLayout.HORIZONTAL);
        for (String emoji : new String[]{"😀", "😂", "😍", "❤️", "👍"}) {
            Button choice = new Button(this); choice.setText(emoji); choice.setTextSize(18);
            choice.setOnClickListener(v -> chatInput.getText().insert(Math.max(0, chatInput.getSelectionStart()), emoji));
            emojiRow.addView(choice, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        chatPanel.addView(emojiRow);
        Button close = new Button(this); close.setText("Fechar chat"); close.setOnClickListener(v -> closeChat()); chatPanel.addView(close);
        root.addView(chatPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        chatPanel.setVisibility(View.GONE);
    }
    void updateLayout() {
        controls.setVisibility(watching || fullscreen ? View.GONE : View.VISIBLE);
        viewerBar.setVisibility(watching && !fullscreen ? View.VISIBLE : View.GONE);
        fullscreenBar.setVisibility(fullscreen ? View.VISIBLE : View.GONE);
        status.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        mainLayout.setPadding(fullscreen ? 0 : dp(12), fullscreen ? 0 : dp(8), fullscreen ? 0 : dp(12), fullscreen ? 0 : dp(8));
    }
    void enterFullscreen() {
        if (!watching) return;
        fullscreen = true; updateLayout();
        applyImmersive();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }
    void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    void exitFullscreen() {
        fullscreen = false; updateLayout();
        getWindow().getDecorView().setSystemUiVisibility(0);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }
    void openChat() {
        chatPanel.setVisibility(View.VISIBLE); unreadChat = false; updateChatButtons();
        if (fullscreen) getWindow().getDecorView().setSystemUiVisibility(0);
        chatInput.requestFocus();
        chatInput.post(() -> ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT));
    }
    void closeChat() {
        chatPanel.setVisibility(View.GONE);
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
        chatInput.clearFocus();
        if (fullscreen) applyImmersive();
    }
    void updateChatButtons() {
        String label = unreadChat ? "Chat • nova" : "Chat";
        chatButton.setText(label); fullscreenChatButton.setText(label);
    }
    void receiveChat(String from, String text) {
        if (text == null || text.isEmpty()) return;
        boolean mine = ("host".equals(from) && CaptureService.active) || ("viewer".equals(from) && watching);
        String line = (mine ? "Você: " : "Amigo: ") + text;
        ui.post(() -> {
            chatLines.addLast(line);
            while (chatLines.size() > 20) chatLines.removeFirst();
            chatHistory.setText(String.join("\n", chatLines));
            chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
            if (chatPanel.getVisibility() != View.VISIBLE) { unreadChat = true; updateChatButtons(); }
            messageBanner.setText(line); messageBanner.setVisibility(View.VISIBLE);
            int sequence = ++bannerSequence;
            ui.postDelayed(() -> { if (sequence == bannerSequence) messageBanner.setVisibility(View.GONE); }, 5000);
        });
    }
    void sendChat() {
        String text = chatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        try {
            if (CaptureService.active) {
                startService(new Intent(this, CaptureService.class).setAction("CHAT").putExtra("message", text));
            } else if (watching && socket != null) {
                if (!socket.send(new JSONObject().put("type", "chat").put("text", text).toString())) {
                    show("Chat desconectado. Tente novamente."); return;
                }
            } else { show("Entre em uma sala antes de enviar mensagens."); return; }
            chatInput.setText("");
        } catch (Exception e) { show("Não foi possível enviar a mensagem."); }
    }
    void endSession() {
        if (fullscreen) exitFullscreen();
        closeChat(); stopViewer(); stopService(new Intent(this, CaptureService.class));
        updateLayout(); show("Encerrado.");
    }
    @Override public void onBackPressed() {
        if (chatPanel.getVisibility() == View.VISIBLE) closeChat();
        else if (fullscreen) exitFullscreen();
        else super.onBackPressed();
    }
    @Override public void onConfigurationChanged(Configuration newConfig) { super.onConfigurationChanged(newConfig); }
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
            requestScreen();
        } catch (Exception e) { show(e.getMessage()); }
    }
    void requestScreen() {
        MediaProjectionManager pm = (MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(pm.createScreenCaptureIntent(), 2);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) { if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestScreen(); else show("Sem a permissão de áudio não é possível transmitir o som."); }
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
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
                        if (watching) {
                            Bitmap old = currentFrame; currentFrame = bitmap; screen.setImageBitmap(bitmap);
                            if (old != null) old.recycle();
                        } else bitmap.recycle();
                    });
                }
                Thread.sleep(80);
            }} catch (InterruptedException ignored) {}
        }, "CinemaImagem"); imageThread.start();
    }
    void stopViewer() {
        watching = false; if (socket != null) { WebSocket old = socket; socket = null; old.cancel(); }
        if (audioThread != null) { audioThread.interrupt(); try { audioThread.join(500); } catch (InterruptedException ignored) {} audioThread = null; }
        if (imageThread != null) { imageThread.interrupt(); try { imageThread.join(500); } catch (InterruptedException ignored) {} imageThread = null; }
        sound.clear(); latest.set(null);
        if (screen != null) screen.setImageDrawable(null);
        if (currentFrame != null) { currentFrame.recycle(); currentFrame = null; }
        if (fullscreen) exitFullscreen(); else if (controls != null) updateLayout();
    }
    @Override protected void onDestroy() { stopViewer(); unregisterReceiver(messages); client.dispatcher().executorService().shutdown(); super.onDestroy(); }
}
