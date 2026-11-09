package br.com.cinemajunto;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** The host forwards room content over direct channels. Discovery exchanges signaling only. */
final class DirectRoomSocket implements WebSocket {
    private final Context context;
    private final boolean host, chatOnly, cloud;
    private final String endpoint, room, device, name;
    private PowerManager.WakeLock wakeLock;
    private int signalGeneration;
    private final WebSocketListener listener;
    private final HandlerThread thread = new HandlerThread("CinemaSala");
    private final Handler worker;
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final Map<String, Peer> peers = new HashMap<>();
    private WebSocket signal;
    private volatile boolean closed, ready;
    private boolean joinedBefore;
    private int reconnectAttempt, nextGuestNumber;
    static final int CHAT_HISTORY_LIMIT = 100;
    private final ArrayDeque<ChatMessage> chatHistory = new ArrayDeque<>();
    private final Map<String, Integer> guestNumbers = new HashMap<>();
    private long nextChatId;
    private final String chatSession = java.util.UUID.randomUUID().toString();

    private record ChatMessage(long id, String author, String name, String text) {
        String forDevice(String recipient, boolean history) throws Exception {
            return new JSONObject().put("type", "chat").put("id", id).put("name", name)
                .put("own", author.equals(recipient)).put("history", history).put("text", text).toString();
        }
    }
    private String talker;
    private ByteString config;
    private final Runnable stopTalk = () -> talk(false, talker);
    private final Runnable timeout = () -> fail("A conexão demorou. Confira a internet e tente novamente.");
    private static class Peer {
        DirectSocket socket;
        boolean open, waiting = true, reset, chatOnly;
        long lastSeen;
        String device, name;
    }
    private final Runnable rejoin = this::startSignal;
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (closed) return;
            long now = SystemClock.elapsedRealtime();
            for (Map.Entry<String, Peer> entry : new java.util.ArrayList<>(peers.entrySet())) {
                Peer peer = entry.getValue();
                if (!peer.open) continue;
                if (now - peer.lastSeen > 45000) { peerFailed(entry.getKey(), peer, "A conexão não respondeu."); continue; }
                peer.socket.send("{\"type\":\"room-ping\"}");
            }
            worker.postDelayed(this, 5000);
        }
    };

    DirectRoomSocket(Context context, boolean host, String endpoint, String room, WebSocketListener listener) {
        this(context, host, endpoint, room, java.util.UUID.randomUUID().toString(), listener);
    }
    DirectRoomSocket(Context context, boolean host, String endpoint, String room, String device, WebSocketListener listener) {
        this(context, host, endpoint, room, device, false, "", listener);
    }
    DirectRoomSocket(Context context, boolean host, String endpoint, String room, String device,
                     boolean chatOnly, String name, WebSocketListener listener) {
        this.context = context.getApplicationContext(); this.host = host; this.listener = listener;
        this.endpoint = endpoint; this.room = room; this.device = device; this.name = name; this.chatOnly = chatOnly;
        this.cloud = endpoint.contains("peerjs.com") || endpoint.contains("10001");
        thread.start(); worker = new Handler(thread.getLooper());
        worker.post(() -> {
            if (closed) return;
            PowerManager power = (PowerManager)this.context.getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CinemaJunto:Conexao");
            wakeLock.setReferenceCounted(false); wakeLock.acquire();
            worker.postDelayed(timeout, 90000); worker.post(heartbeat); startSignal();
        });
    }
    private void startSignal() {
        if (closed) return;
        final int generation = ++signalGeneration;
        if (signal != null) signal.cancel();
        WebSocketListener signalListener = new WebSocketListener() {
            public void onOpen(WebSocket ws, Response response) {
                try { ws.send(new JSONObject().put("role", host ? "host" : "viewer").put("room", room).put("device", device).put("chatOnly", chatOnly).put("name", name).toString()); }
                catch (Exception e) { worker.post(() -> { if (generation == signalGeneration) fail("Não foi possível abrir a sala."); }); }
            }
            public void onMessage(WebSocket ws, String text) { worker.post(() -> { if (generation == signalGeneration) signaling(text); }); }
            public void onFailure(WebSocket ws, Throwable t, Response response) { worker.post(() -> {
                if (generation != signalGeneration) return;
                if (!host) {
                    if (!ready) recoverViewer("Serviço da sala indisponível");
                } else fail(t.getMessage() == null ? "Não foi possível localizar a sala." : t.getMessage());
            }); }
            public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
            public void onClosed(WebSocket ws, int code, String reason) { worker.post(() -> {
                if (generation != signalGeneration) return;
                if (!host && !reason.contains("encerrou a sala") && !reason.contains("expirada")) {
                    if (!ready) recoverViewer(reason);
                } else fail(reason.isEmpty() ? "Sala encerrada." : reason);
            }); }
        };
        if (cloud) signal = new CloudSignal(client, host, room, endpoint, device, chatOnly, name, signalListener);
        else signal = client.newWebSocket(new Request.Builder().url(endpoint.replaceFirst("^http", "ws") + "signal").build(), signalListener);
    }
    private void recoverViewer(String reason) {
        if (closed || host) return;
        ready = false; removePeer("host");
        ++signalGeneration; if (signal != null) { signal.cancel(); signal = null; }
        try { emit(new JSONObject().put("status", "Reconectando ao transmissor…").put("reconnecting", true).toString()); } catch (Exception ignored) {}
        android.util.Log.i("CinemaSala", "Recuperando conexão: " + reason);
        // The initial invitation has a deadline. A room that has already opened keeps retrying.
        worker.removeCallbacks(rejoin);
        worker.postDelayed(rejoin, Math.min(10000L, 1000L << Math.min(reconnectAttempt++, 4)));
    }
    private void peerFailed(String id, Peer peer, String reason) {
        if (closed || peers.get(id) != peer) return;
        removePeer(id);
        if (host) { signalMessage("drop-peer", id, null); status("Reconectando um convidado. A sala continua aberta.", false); }
        else recoverViewer(reason);
    }
    private void signaling(String text) {
        if (closed) return;
        try {
            JSONObject msg = new JSONObject(text); String type = msg.optString("type");
            if ("signal-offline".equals(type)) { status(msg.optString("message"), false); return; }
            if ("signal-online".equals(type)) { if (ready) status("Serviço da sala reconectado. Transmissão ativa.", false); return; }
            if ("host-unavailable".equals(type)) { if (!ready) status(msg.optString("message"), false); return; }
            if ("peer-timeout".equals(type)) { if (!host) recoverViewer("Conexão anterior expirou"); return; }
            if ("error".equals(type)) {
                String message = msg.optString("message");
                if (!host && joinedBefore && message.startsWith("Palavra não encontrada")) recoverViewer(message);
                else fail(message);
                return;
            }
            if ("joined".equals(type) && host) {
                ready = true; worker.removeCallbacks(timeout);
                listener.onOpen(this, new Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(101).message("Sala direta").build());
                status("Sala criada. Toque em Convidar pessoa. Participantes: 1.", true); return;
            }
            if ("peer-joined".equals(type) && host) {
                createPeer(msg.getString("id"), null, msg.optString("device"), msg.optBoolean("chatOnly"), msg.optString("name"));
                return;
            }
            if ("peer-left".equals(type) && host) { removePeer(msg.getString("id")); return; }
            if ("participants".equals(type)) {
                if (host || ready) status("Participantes: " + msg.optInt("count") + (chatOnly ? " · Só chat" : " · conexão direta"), false);
                return;
            }
            if ("candidate".equals(type)) {
                Peer peer = peers.get(host ? msg.getString("id") : "host");
                if (peer != null) peer.socket.acceptCandidate(msg.getString("code"));
                return;
            }
            if ("joined".equals(type) && !host) status("Aguardando resposta de quem transmite…", false);
            if ("signal".equals(type)) {
                String id = host ? msg.getString("id") : "host";
                if (host) { Peer peer = peers.get(id); if (peer != null) peer.socket.acceptAnswer(msg.getString("code")); }
                else if (!peers.containsKey(id)) createPeer(id, msg.getString("code"), "", chatOnly, "");
            }
        } catch (Exception e) { fail("Não foi possível preparar a sala. Tente novamente."); }
    }
    private void createPeer(String id, String offer, String peerDevice, boolean peerChatOnly, String peerName) {
        if (closed || peers.containsKey(id)) return;
        Peer peer = new Peer();
        peer.device = peerDevice.isEmpty() ? id : peerDevice;
        peer.chatOnly = peerChatOnly;
        if (host) {
            int guestNumber = guestNumbers.computeIfAbsent(peer.device, ignored -> ++nextGuestNumber);
            String trimmed = peerName.trim().replaceAll("[\\p{Cntrl}]", "");
            peer.name = trimmed.trim().isEmpty() ? "Convidado " + guestNumber : trimmed.substring(0, Math.min(24, trimmed.length()));
        }
        peers.put(id, peer);
        peer.socket = new DirectSocket(context, host, offer, new WebSocketListener() {
            public void onOpen(WebSocket ws, Response response) { worker.post(() -> {
                if (closed || peers.get(id) != peer) return;
                peer.open = true; peer.lastSeen = SystemClock.elapsedRealtime();
                if (!host) {
                    ready = true; joinedBefore = true; reconnectAttempt = 0;
                    worker.removeCallbacks(timeout); signalMessage("connected", id, null);
                    listener.onOpen(DirectRoomSocket.this, response);
                    status(chatOnly ? "Conectado · Só chat" : "Conectado à sala · vídeo por conexão direta", true);
                    if (!chatOnly) send("{\"type\":\"request-keyframe\"}");
                } else {
                    signalMessage("peer-connected", id, null);
                    replayChat(peer);
                    if (!peer.chatOnly) {
                        emit("{\"type\":\"request-keyframe\"}");
                        if (talker != null) deliverTalk(peer, id, true, talker);
                    }
                }
            }); }
            public void onMessage(WebSocket ws, String text) { worker.post(() -> { if (peers.get(id) == peer) receive(id, text); }); }
            public void onMessage(WebSocket ws, ByteString data) { worker.post(() -> { if (peers.get(id) == peer) receive(id, data); }); }
            public void onFailure(WebSocket ws, Throwable t, Response response) { worker.post(() -> {
                peerFailed(id, peer, t.getMessage());
            }); }
        }, new DirectSocket.Codes() {
            public void ready(String code) { worker.post(() -> { if (peers.get(id) == peer) signalMessage("signal", id, code); }); }
            public void candidate(String code) { worker.post(() -> { if (peers.get(id) == peer) signalMessage("candidate", id, code); }); }
        }, true);
    }
    private void signalMessage(String type, String id, String code) {
        if (closed || signal == null) return;
        try {
            JSONObject msg = new JSONObject().put("type", type).put("id", id);
            if (code != null) msg.put("code", code);
            signal.send(msg.toString());
        } catch (Exception ignored) {}
    }
    private void removePeer(String id) {
        if (id.equals(talker)) talk(false, id);
        Peer peer = peers.remove(id); if (peer != null) peer.socket.cancel();
    }
    private void status(String text, boolean joined) {
        try { emit(new JSONObject().put("status", text).put("joined", joined).toString()); } catch (Exception ignored) {}
    }
    private void emit(String text) { if (!closed) listener.onMessage(this, text); }
    private void receive(String id, String text) {
        if (closed || !peers.containsKey(id)) return;
        try {
            JSONObject msg = new JSONObject(text); String type = msg.optString("type");
            peers.get(id).lastSeen = SystemClock.elapsedRealtime();
            if ("room-ping".equals(type)) { peers.get(id).socket.send("{\"type\":\"room-pong\"}"); return; }
            if ("room-pong".equals(type)) return;
            if (!host) {
                if ("chat-session".equals(type) || "chat".equals(type) || (!chatOnly && ("talk".equals(type) || "media-reset".equals(type)))) emit(text);
                return;
            }
            if ("chat".equals(type)) chat(id, msg.optString("text"));
            else if (!peers.get(id).chatOnly) {
                if ("talk".equals(type) && msg.opt("active") instanceof Boolean) talk(msg.getBoolean("active"), id);
                else if ("request-keyframe".equals(type)) emit("{\"type\":\"request-keyframe\"}");
            }
        } catch (Exception ignored) {}
    }
    private void receive(String id, ByteString data) {
        if (closed || data.size() < 2 || !peers.containsKey(id)) return;
        peers.get(id).lastSeen = SystemClock.elapsedRealtime();
        if (!host) { if (!chatOnly) listener.onMessage(this, data); return; }
        if (data.getByte(0) != 7 || !id.equals(talker)) return;
        listener.onMessage(this, data);
        for (Map.Entry<String, Peer> entry : peers.entrySet())
            if (!entry.getKey().equals(id) && entry.getValue().open && !entry.getValue().chatOnly) entry.getValue().socket.send(data);
    }
    private String from(String source, String recipient) {
        boolean own = source.equals(recipient);
        return "host".equals(recipient) ? (own ? "host" : "viewer") : (own ? "viewer" : "host");
    }
    private void chat(String source, String text) {
        text = text.trim();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > 280) return;
        Peer author = peers.get(source);
        ChatMessage message = new ChatMessage(++nextChatId, author == null ? "host" : author.device,
            author == null ? "Anfitrião" : author.name, text);
        chatHistory.addLast(message);
        if (chatHistory.size() > CHAT_HISTORY_LIMIT) chatHistory.removeFirst();
        try {
            emit(message.forDevice("host", false));
            for (Peer peer : peers.values()) {
                if (peer.open) peer.socket.send(message.forDevice(peer.device, false));
            }
        } catch (Exception ignored) {}
    }
    private void replayChat(Peer peer) {
        try {
            peer.socket.send(new JSONObject().put("type", "chat-session").put("session", chatSession).toString());
            for (ChatMessage message : chatHistory) {
                if (!peer.socket.send(message.forDevice(peer.device, true))) break;
            }
        } catch (Exception ignored) {}
    }
    private void deliverTalk(Peer peer, String recipient, boolean active, String source) {
        try { peer.socket.send(new JSONObject().put("type", "talk").put("active", active).put("from", from(source, recipient)).toString()); } catch (Exception ignored) {}
    }
    private void talk(boolean active, String source) {
        if (source == null || (active && talker != null && !source.equals(talker)) || (!active && !source.equals(talker))) {
            if (active && source != null && talker != null && peers.containsKey(source)) deliverTalk(peers.get(source), source, true, talker);
            return;
        }
        talker = active ? source : null; worker.removeCallbacks(stopTalk);
        try {
            emit(new JSONObject().put("type", "talk").put("active", active).put("from", from(source, "host")).toString());
            for (Map.Entry<String, Peer> entry : peers.entrySet()) if (entry.getValue().open && !entry.getValue().chatOnly) deliverTalk(entry.getValue(), entry.getKey(), active, source);
        } catch (Exception ignored) {}
        if (active) worker.postDelayed(stopTalk, 30000);
    }
    @Override public boolean send(String text) {
        if (closed || !ready || text == null || text.length() > 8192) return false;
        worker.post(() -> {
            if (closed) return;
            try {
                JSONObject msg = new JSONObject(text);
                if (msg.has("role")) return;
                if (!host) {
                    Peer peer = peers.get("host");
                    if (peer != null && peer.open && (!chatOnly || "chat".equals(msg.optString("type")))) peer.socket.send(text);
                    return;
                }
                if ("chat".equals(msg.optString("type"))) chat("host", msg.optString("text"));
                else if ("talk".equals(msg.optString("type"))) talk(msg.optBoolean("active"), "host");
            } catch (Exception ignored) {}
        }); return true;
    }
    private final java.util.concurrent.atomic.AtomicLong pending = new java.util.concurrent.atomic.AtomicLong();
    @Override public boolean send(ByteString data) {
        if (closed || !ready || chatOnly || data == null || data.size() < 2) return false;
        if (pending.addAndGet(data.size()) > 4 * 1024 * 1024) { pending.addAndGet(-data.size()); return false; }
        worker.post(() -> {
            try {
                if (closed) return;
                int type = data.getByte(0);
                if (!host) { Peer peer = peers.get("host"); if (peer != null && peer.open && type == 7) peer.socket.send(data); return; }
                if (type == 7) {
                    if ("host".equals(talker)) for (Peer peer : peers.values()) if (peer.open && !peer.chatOnly) peer.socket.send(data);
                    return;
                }
                if (type == 5) config = data;
                for (Peer peer : peers.values()) {
                    if (!peer.open || peer.chatOnly) continue;
                    if (peer.socket.queueSize() > 1024 * 1024) { peer.waiting = true; peer.reset = true; continue; }
                    if (peer.reset) { peer.socket.send("{\"type\":\"media-reset\"}"); peer.reset = false; emit("{\"type\":\"request-keyframe\"}"); }
                    if (type == 5) { peer.socket.send(data); continue; }
                    if (peer.waiting) {
                        if (type != 6 || data.size() <= 13 || (data.asByteBuffer().getInt(9) & 1) == 0 || config == null) continue;
                        if (!peer.socket.send(config) || !peer.socket.send(data)) continue;
                        peer.waiting = false;
                    } else if (!peer.socket.send(data)) { peer.waiting = true; peer.reset = true; }
                }
            } finally { pending.addAndGet(-data.size()); }
        }); return true;
    }
    @Override public long queueSize() { return pending.get(); }
    @Override public Request request() { return new Request.Builder().url("https://direct.invalid/").build(); }
    private void fail(String message) {
        if (closed) return;
        finish(); listener.onFailure(this, new java.io.IOException(message == null ? "Sala encerrada." : message), null);
    }
    private void finish() {
        if (closed) return; closed = true; ready = false;
        worker.post(() -> {
            worker.removeCallbacksAndMessages(null);
            for (Peer peer : peers.values()) peer.socket.cancel(); peers.clear();
            if (signal != null) signal.cancel();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            client.dispatcher().executorService().shutdown(); thread.quitSafely();
        });
    }
    @Override public void cancel() { finish(); }
    @Override public boolean close(int code, String reason) { finish(); return true; }
}
