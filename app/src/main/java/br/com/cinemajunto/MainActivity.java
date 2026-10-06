package br.com.cinemajunto;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.*;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

public class MainActivity extends Activity {
    EditText endpoint, invitation;
    TextView status;
    ImageView screen;
    String pendingEndpoint, pendingRoom;
    final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    WebSocket socket;
    volatile boolean watching;
    final ArrayBlockingQueue<byte[]> sound = new ArrayBlockingQueue<>(12);
    final AtomicReference<byte[]> latest = new AtomicReference<>();
    Thread audioThread, imageThread;
    final BroadcastReceiver messages = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { show(i.getStringExtra("status")); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24,24,24,16);
        TextView title = new TextView(this); title.setText("Cinema Junto"); title.setTextSize(28); box.addView(title);
        TextView subtitle = new TextView(this); subtitle.setText("Versão de teste • apenas você e seu amigo\nUse Wi-Fi e fones de ouvido."); box.addView(subtitle);
        endpoint = new EditText(this); endpoint.setSingleLine(true); endpoint.setHint("Endereço do servidor: https://…");
        endpoint.setText(getPreferences(0).getString("endpoint", "https://cinema-junto-welliton.onrender.com/")); box.addView(endpoint);
        invitation = new EditText(this); invitation.setSingleLine(true); invitation.setHint("Cole aqui o convite para assistir");
        if (CaptureService.active) invitation.setText(getPreferences(0).getString("hostInvite", "")); box.addView(invitation);
        button(box, "Transmitir minha tela e o som", () -> startHost());
        button(box, "Compartilhar convite", () -> share());
        button(box, "Assistir ao meu amigo", () -> startViewer());
        button(box, "Encerrar", () -> { stopViewer(); stopService(new Intent(this, CaptureService.class)); show("Encerrado."); });
        status = new TextView(this); status.setText("Configure o servidor para começar."); box.addView(status);
        screen = new ImageView(this); screen.setScaleType(ImageView.ScaleType.FIT_CENTER); screen.setBackgroundColor(0xff000000);
        box.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(box);
        IntentFilter filter = new IntentFilter(getPackageName() + ".STATUS");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(messages, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(messages, filter);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
    }
    void button(LinearLayout box, String label, Runnable action) { Button b = new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); box.addView(b); }
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
            stopViewer(); endpoint.setText(base); watching = true;
            startPlayback(); show("Conectando ao seu amigo…");
            String url = base.replaceFirst("^https", "wss") + "relay";
            socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    try { ws.send(new JSONObject().put("role", "viewer").put("room", room).toString()); } catch (Exception ignored) {}
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    if (!watching || socket != ws) return;
                    try { show(new JSONObject(text).optString("status")); } catch (Exception ignored) {}
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
                if (jpeg != null) { android.graphics.Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length); if (bitmap != null) runOnUiThread(() -> { if (watching) screen.setImageBitmap(bitmap); else bitmap.recycle(); }); }
                Thread.sleep(80);
            }} catch (InterruptedException ignored) {}
        }, "CinemaImagem"); imageThread.start();
    }
    void stopViewer() {
        watching = false; if (socket != null) { WebSocket old = socket; socket = null; old.cancel(); }
        if (audioThread != null) { audioThread.interrupt(); try { audioThread.join(500); } catch (InterruptedException ignored) {} audioThread = null; }
        if (imageThread != null) { imageThread.interrupt(); try { imageThread.join(500); } catch (InterruptedException ignored) {} imageThread = null; }
        sound.clear(); latest.set(null); if (screen != null) screen.setImageDrawable(null);
    }
    @Override protected void onDestroy() { stopViewer(); unregisterReceiver(messages); client.dispatcher().executorService().shutdown(); super.onDestroy(); }
}

