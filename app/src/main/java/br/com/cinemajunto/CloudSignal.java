package br.com.cinemajunto;

import android.os.Handler;
import android.os.HandlerThread;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** PeerServer wire protocol, used exclusively for joining and SDP. Never transports media. */
final class CloudSignal implements WebSocket {
    private final boolean host;
    private final String hostId;
    private final WebSocketListener listener;
    private final HandlerThread thread = new HandlerThread("CinemaEncontro");
    private final Handler worker;
    private final Map<String, Runnable> guests = new HashMap<>();
    private WebSocket socket;
    private volatile boolean closed;
    private boolean opened;
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (closed) return;
            if (socket != null) socket.send("{\"type\":\"HEARTBEAT\"}");
            worker.postDelayed(this, 5000);
        }
    };
    CloudSignal(OkHttpClient client, boolean host, String room, String base, WebSocketListener listener) {
        this.host = host; this.hostId = "cinema21-" + room; this.listener = listener;
        String id = host ? hostId : "cinema21-guest-" + UUID.randomUUID();
        thread.start(); worker = new Handler(thread.getLooper());
        worker.post(() -> {
            if (closed) return;
            String url = base.replaceFirst("^https", "wss").replaceFirst("^http:", "ws:")
                + "peerjs?key=peerjs&id=" + id + "&token=" + UUID.randomUUID() + "&version=1.5.5";
            socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                public void onMessage(WebSocket ws, String text) { worker.post(() -> receive(text)); }
                public void onFailure(WebSocket ws, Throwable t, Response r) { worker.post(() -> fail("Não foi possível abrir a sala. Confira a internet e tente novamente.")); }
                public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
                public void onClosed(WebSocket ws, int code, String reason) { worker.post(() -> fail("A conexão da sala foi encerrada. Entre novamente.")); }
            });
        });
    }
    private void event(JSONObject value) { if (!closed) listener.onMessage(this, value.toString()); }
    private void relay(String destination, String action, String code) {
        if (closed || socket == null) return;
        try {
            JSONObject metadata = new JSONObject().put("cinema", 21).put("action", action);
            if (code != null) metadata.put("code", code);
            JSONObject sdp = new JSONObject().put("type", "offer").put("sdp", "v=0\r\n");
            if ("signal".equals(action)) {
                JSONObject invite = DirectInvite.decode(code, host ? "offer" : "answer");
                sdp.put("type", host ? "offer" : "answer").put("sdp", invite.getString("sdp"));
            }
            JSONObject payload = new JSONObject().put("type", "data").put("connectionId", "dc_cinema21")
                .put("label", "cinema-direct-v1").put("serialization", "raw").put("reliable", true)
                .put("sdp", sdp).put("metadata", metadata);
            socket.send(new JSONObject().put("type", host || !"signal".equals(action) ? "OFFER" : "ANSWER")
                .put("dst", destination).put("payload", payload).toString());
        } catch (Exception ignored) {}
    }
    private void count() {
        try {
            JSONObject count = new JSONObject().put("type", "participants").put("count", guests.size() + 1).put("limit", 3);
            event(count);
            for (String id : guests.keySet()) relay(id, "count", Integer.toString(guests.size() + 1));
        } catch (Exception ignored) {}
    }
    private void remove(String id) {
        Runnable timer = guests.remove(id); if (timer == null) return;
        worker.removeCallbacks(timer);
        try { event(new JSONObject().put("type", "peer-left").put("id", id)); } catch (Exception ignored) {}
        count();
    }
    private void receive(String text) {
        if (closed || text.length() > 96 * 1024) return;
        try {
            JSONObject msg = new JSONObject(text); String type = msg.optString("type");
            if ("OPEN".equals(type)) {
                if (opened) return; opened = true;
                listener.onOpen(this, new Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(101).message("Sinalização").build());
                event(new JSONObject().put("type", "joined").put("role", host ? "host" : "viewer"));
                if (host) count(); else relay(hostId, "join", null);
                worker.post(heartbeat); return;
            }
            if ("ID-TAKEN".equals(type)) { fail("Palavra em uso. Escolha outra palavra de 4 letras."); return; }
            if ("ERROR".equals(type)) { fail("Serviço de entrada indisponível. Tente novamente."); return; }
            String source = msg.optString("src");
            if ("EXPIRE".equals(type) || "LEAVE".equals(type)) {
                if (host) remove(source); else if (hostId.equals(source)) fail("Palavra não encontrada ou sala encerrada. Confira com quem transmite.");
                return;
            }
            if (!"OFFER".equals(type) && !"ANSWER".equals(type)) return;
            JSONObject envelope = msg.optJSONObject("payload");
            JSONObject payload = envelope == null ? null : envelope.optJSONObject("metadata");
            if (payload == null || payload.optInt("cinema") != 21) return;
            String action = payload.optString("action"), code = payload.optString("code");
            if (host) {
                if (!source.matches("cinema21-guest-[a-f0-9-]{36}")) return;
                if ("join".equals(action)) {
                    if (guests.containsKey(source)) return;
                    if (guests.size() >= 2) { relay(source, "full", null); return; }
                    Runnable timeout = () -> { relay(source, "timeout", null); remove(source); };
                    guests.put(source, timeout); worker.postDelayed(timeout, 90000);
                    event(new JSONObject().put("type", "peer-joined").put("id", source)); count(); return;
                }
                if (!guests.containsKey(source)) return;
                if ("leave".equals(action)) { remove(source); return; }
                if ("connected".equals(action)) { worker.removeCallbacks(guests.get(source)); return; }
            } else {
                if (!hostId.equals(source)) return;
                if ("full".equals(action)) { fail("Sala cheia: até 3 pessoas, incluindo quem transmite."); return; }
                if ("closed".equals(action) || "timeout".equals(action)) { fail("Sala encerrada ou conexão interrompida. Entre novamente."); return; }
                if ("count".equals(action)) { event(new JSONObject().put("type", "participants").put("count", Integer.parseInt(code)).put("limit", 3)); return; }
            }
            if ("signal".equals(action) && code.startsWith("CJ19:") && code.length() <= 65536)
                event(new JSONObject().put("type", "signal").put("id", source).put("code", code));
        } catch (Exception ignored) {}
    }
    @Override public boolean send(String text) {
        if (closed || text == null || text.length() > 96 * 1024) return false;
        worker.post(() -> {
            if (closed) return;
            try {
                JSONObject msg = new JSONObject(text); String type = msg.optString("type"), id = msg.optString("id");
                if ("signal".equals(type)) { if (!host || guests.containsKey(id)) relay(host ? id : hostId, "signal", msg.getString("code")); }
                else if ("connected".equals(type) && !host) relay(hostId, "connected", null);
                else if ("drop-peer".equals(type) && host && guests.containsKey(id)) { relay(id, "timeout", null); remove(id); }
            } catch (Exception ignored) {}
        }); return true;
    }
    @Override public boolean send(ByteString bytes) { return false; }
    @Override public Request request() { return new Request.Builder().url("https://0.peerjs.com/").build(); }
    @Override public long queueSize() { return socket == null ? 0 : socket.queueSize(); }
    private void fail(String message) {
        if (closed) return;
        finish(); listener.onFailure(this, new java.io.IOException(message), null);
    }
    private void finish() {
        if (closed) return;
        // Send final signaling before setting closed; close() drains OkHttp's outgoing queue.
        if (host) for (String id : guests.keySet()) relay(id, "closed", null);
        else relay(hostId, "leave", null);
        closed = true; worker.removeCallbacksAndMessages(null);
        if (socket != null) socket.close(1000, "Sala encerrada");
        guests.clear(); thread.quitSafely();
    }
    @Override public void cancel() { worker.post(this::finish); }
    @Override public boolean close(int code, String reason) { cancel(); return true; }
}
