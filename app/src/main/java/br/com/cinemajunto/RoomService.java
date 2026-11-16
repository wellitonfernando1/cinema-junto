package br.com.cinemajunto;

import android.app.*;
import android.content.*;
import android.media.session.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.json.*;

public class RoomService extends Service {
    public static RoomService active;
    public static Runnable observer;
    public String status = "Conectando", room = "", password = "", session = "", selectedPackage = "";
    public boolean host, chatOnly, connected;
    public int count;
    public final ArrayList<String> messages = new ArrayList<>();
    public JSONObject state;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private WebSocket socket;
    private JSONObject request;
    private boolean stopping, pending;
    private long offset, retry = 1000, lastCommand, lastSample;
    private final Runnable tick = new Runnable() {
        public void run() {
            synchronizePlayer();
            notifyUi();
            handler.postDelayed(this, 1000);
        }
    };
    public static final String ENDPOINT = "wss://cinema-junto-welliton-teste.onrender.com/room";
    @Override public void onCreate() {
        super.onCreate();
        active = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("room", "Sala de cinema", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, StreamingActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "room")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Cinema Junto")
            .setContentText("Sala ativa. Toque para abrir o chat.")
            .setContentIntent(pendingIntent).setOngoing(true).build();
        startForeground(42, notification);
        handler.post(tick);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("request")) {
            try {
                request = new JSONObject(intent.getStringExtra("request"));
                chatOnly = intent.getBooleanExtra("chat", false);
                selectedPackage = intent.getStringExtra("player");
                if (selectedPackage == null) selectedPackage = "";
                connect();
            } catch (Exception e) { status = "Dados da sala inválidos"; notifyUi(); }
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private void notifyUi() { if (observer != null) observer.run(); }
    private void connect() {
        if (stopping || request == null) return;
        connected = false;
        status = "Conectando…";
        final WebSocket previous = socket;
        socket = null;
        if (previous != null) previous.cancel();
        socket = client.newWebSocket(new Request.Builder().url(ENDPOINT).build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                handler.post(() -> {
                    if (ws != socket) return;
                    send(json("type", "clock", "sent", System.currentTimeMillis()));
                    send(request);
                });
            }
            @Override public void onMessage(WebSocket ws, String text) {
                handler.post(() -> { if (ws == socket) receive(text); });
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                handler.post(() -> lost(ws, code));
            }
            @Override public void onFailure(WebSocket ws, Throwable error, Response response) {
                handler.post(() -> lost(ws, 0));
            }
            @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
        });
    }
    private void lost(WebSocket ws, int code) {
        if (ws != socket || stopping) return;
        connected = false;
        pending = false;
        socket = null;
        if (code == 4001) { status = "Sessão aberta em outro aparelho"; notifyUi(); return; }
        status = "Reconectando…";
        notifyUi();
        handler.postDelayed(this::connect, retry);
        retry = Math.min(10000, retry * 2);
    }
    private void receive(String text) {
        try {
            JSONObject msg = new JSONObject(text);
            switch (msg.optString("type")) {
                case "clock":
                    offset = msg.getLong("now") - (System.currentTimeMillis() + msg.getLong("sent")) / 2;
                    break;
                case "joined":
                    connected = true; retry = 1000;
                    room = msg.getString("room"); session = msg.getString("session");
                    password = msg.getString("password"); host = msg.getBoolean("host");
                    chatOnly = msg.optString("mode").equals("chat");
                    request = json("type", "join", "room", room, "session", session, "name", msg.getString("name"));
                    getSharedPreferences("room", 0).edit().putString("resume", request.toString())
                        .putBoolean("chat", chatOnly).putString("player", selectedPackage).apply();
                    messages.clear();
                    JSONArray history = msg.getJSONArray("history");
                    for (int i = 0; i < history.length(); i++) add(history.getJSONObject(i));
                    state = msg.getJSONObject("state");
                    status = chatOnly ? "Conectado ao chat" : "Conectado. Selecione o mesmo filme em todos os aparelhos.";
                    pending = false;
                    break;
                case "state": state = msg.getJSONObject("state"); pending = false; break;
                case "presence": count = msg.getInt("count"); break;
                case "message": add(msg.getJSONObject("item")); break;
                case "error":
                    status = msg.optString("message");
                    connected = false;
                    if (socket != null) { WebSocket old = socket; socket = null; old.close(1000, "Entrada recusada"); }
                    getSharedPreferences("room", 0).edit().remove("resume").apply();
                    break;
            }
        } catch (Exception e) { status = "Resposta inválida do servidor"; }
        notifyUi();
    }
    private void add(JSONObject item) {
        messages.add(item.optString("name") + ": " + item.optString("text"));
        if (messages.size() > 100) messages.remove(0);
    }
    public static JSONObject json(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); }
        catch (JSONException error) { throw new IllegalArgumentException(error); }
        return result;
    }
    private boolean send(JSONObject value) { return socket != null && socket.send(value.toString()); }
    public boolean chat(String text) {
        return connected && send(json("type", "chat", "text", text));
    }
    public List<MediaController> players() {
        try {
            return getSystemService(MediaSessionManager.class).getActiveSessions(new ComponentName(this, PlaybackAccess.class));
        } catch (SecurityException error) { return Collections.emptyList(); }
    }
    public MediaController player() {
        for (MediaController controller : players()) if (controller.getPackageName().equals(selectedPackage)) return controller;
        return null;
    }
    private long position(PlaybackState playback) {
        long value = playback.getPosition();
        if (playback.getState() == PlaybackState.STATE_PLAYING) {
            value += (long) ((SystemClock.elapsedRealtime() - playback.getLastPositionUpdateTime()) * playback.getPlaybackSpeed());
        }
        return Math.max(0, value);
    }
    public void control(boolean play) {
        MediaController controller = player();
        if (controller == null) return;
        PlaybackState playback = controller.getPlaybackState();
        if (playback == null) return;
        long action = play ? PlaybackState.ACTION_PLAY : PlaybackState.ACTION_PAUSE;
        if ((playback.getActions() & action) == 0) { status = "O player não disponibiliza este comando."; notifyUi(); return; }
        if (play) controller.getTransportControls().play(); else controller.getTransportControls().pause();
    }
    private void synchronizePlayer() {
        if (!connected || chatOnly || state == null) return;
        MediaController controller = player();
        if (controller == null) { status = "Abra o filme no streaming escolhido e mantenha a reprodução ativa."; return; }
        PlaybackState playback = controller.getPlaybackState();
        if (playback == null || playback.getPosition() < 0) { status = "O player não informa a posição."; return; }
        int mode = playback.getState();
        if (mode != PlaybackState.STATE_PLAYING && mode != PlaybackState.STATE_PAUSED) return;
        long now = System.currentTimeMillis();
        if (host) {
            if (now - lastSample < 2000 || (pending && now - lastSample < 6000)) return;
            lastSample = now;
            pending = send(json("type", "playback", "version", state.optInt("version"),
                "position", position(playback) / 1000.0, "playing", mode == PlaybackState.STATE_PLAYING));
            status = "Anfitrião • transmitindo os comandos do player";
            return;
        }
        long target = (long) (state.optDouble("position") * 1000);
        boolean playing = state.optBoolean("playing");
        if (playing) target += Math.max(0, now + offset - state.optLong("at"));
        long actions = playback.getActions();
        if (now - lastCommand < 2500) return;
        try {
            if (Math.abs(position(playback) - target) > 1800) {
                if ((actions & PlaybackState.ACTION_SEEK_TO) == 0) { status = "Este player não permite sincronizar a posição."; return; }
                controller.getTransportControls().seekTo(target);
                lastCommand = now;
            }
            if (playing && mode != PlaybackState.STATE_PLAYING) {
                if ((actions & PlaybackState.ACTION_PLAY) == 0) { status = "Este player não permite iniciar por comando."; return; }
                controller.getTransportControls().play(); lastCommand = now;
            }
            if (!playing && mode == PlaybackState.STATE_PLAYING) {
                if ((actions & PlaybackState.ACTION_PAUSE) == 0) { status = "Este player não permite pausar por comando."; return; }
                controller.getTransportControls().pause(); lastCommand = now;
            }
            status = "Acompanhando o anfitrião";
        } catch (RuntimeException error) { status = "O player recusou o comando de sincronização."; }
    }
    public void leave() {
        getSharedPreferences("room", 0).edit().clear().apply();
        stopSelf();
    }
    @Override public void onDestroy() {
        stopping = true;
        handler.removeCallbacksAndMessages(null);
        if (socket != null) socket.close(1000, "Saiu");
        client.dispatcher().executorService().shutdown();
        active = null;
        notifyUi();
        super.onDestroy();
    }
}
