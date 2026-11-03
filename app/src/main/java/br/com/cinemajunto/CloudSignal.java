package br.com.cinemajunto;

import android.os.Handler;
import android.os.HandlerThread;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** Discovery can reconnect independently of established media connections. */
final class CloudSignal implements WebSocket {
    private final boolean host;
    private final String hostId, ownId, device, base, token = UUID.randomUUID().toString();
    private final OkHttpClient client;
    private final WebSocketListener listener;
    private final HandlerThread thread = new HandlerThread("CinemaEncontro");
    private final Handler worker;
    private final Map<String, Guest> guests = new HashMap<>();
    private final ArrayDeque<String> outgoing = new ArrayDeque<>();
    private WebSocket socket;
    private volatile boolean closed;
    private boolean online, everOpened, connected;
    private int attempt;
    private static final class Guest { String device; boolean active; Runnable expiry; }
    private final Runnable retry = this::connect;
    private final Runnable heartbeat = new Runnable() {
        public void run() {
            if (closed || !online) return;
            if (!socket.send("{\"type\":\"HEARTBEAT\"}")) { disconnected(socket); return; }
            worker.postDelayed(this, 5000);
        }
    };
    CloudSignal(OkHttpClient client, boolean host, String room, String base, WebSocketListener listener) {
        this(client, host, room, base, UUID.randomUUID().toString(), listener);
    }
    CloudSignal(OkHttpClient client, boolean host, String room, String base, String device, WebSocketListener listener) {
        this.client = client; this.host = host; this.device = device; this.base = base;
        this.hostId = "cinema23-" + room;
        this.ownId = host ? hostId : "cinema23-guest-" + UUID.randomUUID();
        this.listener = listener;
        thread.start(); worker = new Handler(thread.getLooper()); worker.post(this::connect);
    }
    private void connect() {
        if (closed) return;
        worker.removeCallbacks(retry);
        String url = base.replaceFirst("^https", "wss").replaceFirst("^http:", "ws:")
            + "peerjs?key=peerjs&id=" + ownId + "&token=" + token + "&version=1.5.5";
        socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            public void onMessage(WebSocket ws, String text) { worker.post(() -> { if (ws == socket) receive(text); }); }
            public void onFailure(WebSocket ws, Throwable t, Response r) { worker.post(() -> disconnected(ws)); }
            public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
            public void onClosed(WebSocket ws, int code, String reason) { worker.post(() -> disconnected(ws)); }
        });
    }
    private void disconnected(WebSocket ws) {
        if (closed || ws != socket) return;
        socket = null; online = false; ws.cancel(); worker.removeCallbacks(heartbeat);
        event("signal-offline", "Reconectando o serviço da sala. A transmissão continua.");
        worker.removeCallbacks(retry);
        worker.postDelayed(retry, Math.min(10000L, 1000L << Math.min(attempt++, 4)));
    }
    private void event(String type, String message) {
        try { event(new JSONObject().put("type", type).put("message", message)); } catch (Exception ignored) {}
    }
    private void event(JSONObject value) { if (!closed) listener.onMessage(this, value.toString()); }
    private void relay(String destination, String action, String code) {
        if (closed) return;
        try {
            JSONObject metadata = new JSONObject().put("cinema", 23).put("action", action).put("device", device);
            if (code != null) metadata.put("code", code);
            JSONObject sdp = new JSONObject().put("type", "offer").put("sdp", "v=0\r\n");
            if ("signal".equals(action)) {
                JSONObject invite = DirectInvite.decode(code, host ? "offer" : "answer");
                sdp.put("type", host ? "offer" : "answer").put("sdp", invite.getString("sdp"));
            }
            JSONObject payload = new JSONObject().put("type", "data").put("connectionId", "dc_cinema23")
                .put("label", "cinema-direct-v1").put("serialization", "raw").put("reliable", true)
                .put("sdp", sdp).put("metadata", metadata);
            if ("candidate".equals(action)) {
                JSONObject value = new JSONObject(DirectInvite.decode(code, "ice").getString("sdp").substring(5));
                payload.put("candidate", new JSONObject().put("sdpMid", value.optString("mid", null))
                    .put("sdpMLineIndex", value.getInt("line")).put("candidate", value.getString("candidate")));
            }
            String packet = new JSONObject().put("type", "candidate".equals(action) ? "CANDIDATE" : (host || !"signal".equals(action) ? "OFFER" : "ANSWER"))
                .put("dst", destination).put("payload", payload).toString();
            if (online && socket != null && socket.send(packet)) return;
            // Only negotiation is queued. Presence, counts and media never accumulate here.
            if ("signal".equals(action) || "candidate".equals(action)) {
                if (outgoing.size() < 128) outgoing.add(packet);
            }
        } catch (Exception e) { android.util.Log.w("CinemaSala", "Falha na sinalização: " + action); }
    }
    private void count() {
        try {
            event(new JSONObject().put("type", "participants").put("count", guests.size() + 1).put("limit", 3));
            for (String id : guests.keySet()) relay(id, "count", Integer.toString(guests.size() + 1));
        } catch (Exception ignored) {}
    }
    private void remove(String id) {
        Guest guest = guests.remove(id); if (guest == null) return;
        worker.removeCallbacks(guest.expiry);
        try { event(new JSONObject().put("type", "peer-left").put("id", id)); } catch (Exception ignored) {}
        count();
    }
    private void activate(String id) {
        Guest guest = guests.get(id);
        if (guest != null) { guest.active = true; worker.removeCallbacks(guest.expiry); }
    }
    private void receive(String text) {
        if (closed || text.length() > 96 * 1024) return;
        try {
            JSONObject msg = new JSONObject(text); String type = msg.optString("type");
            if ("OPEN".equals(type)) {
                if (online) return;
                online = true; attempt = 0;
                if (!everOpened) {
                    everOpened = true;
                    listener.onOpen(this, new Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(101).message("Sinalização").build());
                    event(new JSONObject().put("type", "joined").put("role", host ? "host" : "viewer"));
                }
                if (host) count(); else relay(hostId, connected ? "resume" : "join", null);
                while (online && !outgoing.isEmpty()) {
                    if (!socket.send(outgoing.peek())) { disconnected(socket); break; }
                    outgoing.remove();
                }
                event("signal-online", "Serviço da sala conectado.");
                worker.removeCallbacks(heartbeat); worker.post(heartbeat); return;
            }
            if ("ID-TAKEN".equals(type)) {
                if (everOpened) disconnected(socket);
                else fail("Palavra em uso. Escolha outra palavra de 4 letras.");
                return;
            }
            if ("ERROR".equals(type)) { disconnected(socket); return; }
            String source = msg.optString("src");
            if ("EXPIRE".equals(type) || "LEAVE".equals(type)) {
                // Broker presence is not media presence. An active direct peer owns its slot.
                if (host) { Guest g = guests.get(source); if (g != null && !g.active) remove(source); }
                else if (hostId.equals(source) && !connected) event("host-unavailable", "Aguardando o aparelho que transmite…");
                return;
            }
            if (!"OFFER".equals(type) && !"ANSWER".equals(type) && !"CANDIDATE".equals(type)) return;
            JSONObject envelope = msg.optJSONObject("payload");
            JSONObject payload = envelope == null ? null : envelope.optJSONObject("metadata");
            if (payload == null || payload.optInt("cinema") != 23) return;
            String action = payload.optString("action"), code = payload.optString("code");
            if (host) {
                if (!source.matches("cinema23-guest-[a-f0-9-]{36}")) return;
                if ("join".equals(action) || "resume".equals(action)) {
                    String identity = payload.optString("device");
                    if (!identity.matches("[a-f0-9-]{36}")) return;
                    if (guests.containsKey(source)) { count(); return; }
                    // A returning installation replaces only its previous connection, even in a full room.
                    String previous = null;
                    for (Map.Entry<String, Guest> entry : guests.entrySet())
                        if (identity.equals(entry.getValue().device)) previous = entry.getKey();
                    if (previous != null) { relay(previous, "replaced", null); remove(previous); }
                    if (guests.size() >= 2) { relay(source, "full", null); return; }
                    Guest guest = new Guest(); guest.device = identity;
                    guest.expiry = () -> { relay(source, "timeout", null); remove(source); };
                    guests.put(source, guest); worker.postDelayed(guest.expiry, 90000);
                    event(new JSONObject().put("type", "peer-joined").put("id", source)); count(); return;
                }
                if (!guests.containsKey(source)) return;
                if ("leave".equals(action)) { remove(source); return; }
                if ("connected".equals(action)) { activate(source); return; }
            } else {
                if (!hostId.equals(source)) return;
                if ("full".equals(action)) { fail("Sala cheia: já existem dois aparelhos assistindo."); return; }
                if ("replaced".equals(action)) { fail("Este aparelho entrou novamente. A conexão anterior foi encerrada."); return; }
                if ("closed".equals(action)) { fail("Quem transmitia encerrou a sala."); return; }
                if ("timeout".equals(action)) { event("peer-timeout", "Reconectando ao aparelho que transmite…"); return; }
                if ("count".equals(action)) { event(new JSONObject().put("type", "participants").put("count", Integer.parseInt(code)).put("limit", 3)); return; }
            }
            if (("signal".equals(action) || "candidate".equals(action)) && code.startsWith("CJ19:") && code.length() <= 65536)
                event(new JSONObject().put("type", action).put("id", source).put("code", code));
        } catch (Exception e) { android.util.Log.w("CinemaSala", "Mensagem de sinalização inválida"); }
    }
    @Override public boolean send(String text) {
        if (closed || text == null || text.length() > 96 * 1024) return false;
        worker.post(() -> {
            if (closed) return;
            try {
                JSONObject msg = new JSONObject(text); String type = msg.optString("type"), id = msg.optString("id");
                if ("signal".equals(type) || "candidate".equals(type)) { if (!host || guests.containsKey(id)) relay(host ? id : hostId, type, msg.getString("code")); }
                else if ("connected".equals(type) && !host) { connected = true; relay(hostId, "connected", null); }
                else if ("peer-connected".equals(type) && host) activate(id);
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
        if (host) for (String id : guests.keySet()) relay(id, "closed", null);
        else relay(hostId, "leave", null);
        closed = true; online = false; worker.removeCallbacksAndMessages(null);
        if (socket != null) socket.close(1000, "Sala encerrada");
        for (Guest guest : guests.values()) worker.removeCallbacks(guest.expiry);
        outgoing.clear(); guests.clear(); thread.quitSafely();
    }
    @Override public void cancel() { worker.post(this::finish); }
    @Override public boolean close(int code, String reason) { cancel(); return true; }
}
