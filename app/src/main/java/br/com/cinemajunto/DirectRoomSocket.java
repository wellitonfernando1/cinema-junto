package br.com.cinemajunto;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

/** A three-person star. The server exchanges SDP only; all content uses direct channels. */
final class DirectRoomSocket implements WebSocket {
    private final Context context;
    private final boolean host;
    private final WebSocketListener listener;
    private final HandlerThread thread = new HandlerThread("CinemaSala");
    private final Handler worker;
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final Map<String, Peer> peers = new HashMap<>();
    private WebSocket signal;
    private volatile boolean closed, ready;
    private String talker;
    private ByteString config;
    private final Runnable stopTalk = () -> talk(false, talker);
    private final Runnable timeout = () -> fail("A conexão demorou. Confira a internet e tente novamente.");
    private static class Peer { DirectSocket socket; boolean open, waiting = true, reset; }

    DirectRoomSocket(Context context, boolean host, String endpoint, String room, WebSocketListener listener) {
        this.context = context.getApplicationContext(); this.host = host; this.listener = listener;
        thread.start(); worker = new Handler(thread.getLooper());
        // Defer to the worker so callers can assign their socket before receiving callbacks.
        worker.post(() -> {
            if (closed) return;
            worker.postDelayed(timeout, 90000);
            WebSocketListener signalListener = new WebSocketListener() {
                public void onOpen(WebSocket ws, Response response) {
                    try { ws.send(new JSONObject().put("role", host ? "host" : "viewer").put("room", room).toString()); }
                    catch (Exception e) { worker.post(() -> fail("Não foi possível abrir a sala.")); }
                }
                public void onMessage(WebSocket ws, String text) { worker.post(() -> signaling(text)); }
                public void onFailure(WebSocket ws, Throwable t, Response response) { worker.post(() -> fail(t.getMessage() == null ? "Não foi possível localizar a sala. Confira a internet e tente novamente." : t.getMessage())); }
                public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
                public void onClosed(WebSocket ws, int code, String reason) { worker.post(() -> fail(reason.isEmpty() ? "Sala encerrada." : reason)); }
            };
            if (endpoint.contains("peerjs.com") || endpoint.contains("10001")) signal = new CloudSignal(client, host, room, endpoint, signalListener);
            else signal = client.newWebSocket(new Request.Builder().url(endpoint.replaceFirst("^https", "wss") + "signal").build(), signalListener);
        });
    }
    private void signaling(String text) {
        if (closed) return;
        try {
            JSONObject msg = new JSONObject(text); String type = msg.optString("type");
            if ("error".equals(type)) { fail(msg.optString("message")); return; }
            if ("joined".equals(type) && host) {
                ready = true; worker.removeCallbacks(timeout);
                listener.onOpen(this, new Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(101).message("Sala direta").build());
                status("Sala criada. Toque em Convidar pessoa. Participantes: 1/3.", true); return;
            }
            if ("peer-joined".equals(type) && host) { createPeer(msg.getString("id"), null); return; }
            if ("peer-left".equals(type) && host) { removePeer(msg.getString("id")); return; }
            if ("participants".equals(type)) {
                if (host || ready) status("Participantes: " + msg.optInt("count") + "/3 · vídeo por conexão direta", false);
                return;
            }
            if ("signal".equals(type)) {
                String id = host ? msg.getString("id") : "host";
                if (host) { Peer peer = peers.get(id); if (peer != null) peer.socket.acceptAnswer(msg.getString("code")); }
                else if (!peers.containsKey(id)) createPeer(id, msg.getString("code"));
            }
        } catch (Exception e) { fail("Não foi possível preparar a sala. Tente novamente."); }
    }
    private void createPeer(String id, String offer) {
        if (closed || peers.containsKey(id) || (host && peers.size() >= 2)) return;
        Peer peer = new Peer(); peers.put(id, peer);
        peer.socket = new DirectSocket(context, host, offer, new WebSocketListener() {
            public void onOpen(WebSocket ws, Response response) { worker.post(() -> {
                if (closed || peers.get(id) != peer) return;
                peer.open = true;
                if (!host) {
                    ready = true; worker.removeCallbacks(timeout); signalMessage("connected", id, null);
                    listener.onOpen(DirectRoomSocket.this, response);
                    status("Conectado à sala · vídeo por conexão direta", true);
                    send("{\"type\":\"request-keyframe\"}");
                } else {
                    emit("{\"type\":\"request-keyframe\"}");
                    // A late arrival must mute its movie too if someone is speaking.
                    if (talker != null) deliverTalk(peer, id, true, talker);
                }
            }); }
            public void onMessage(WebSocket ws, String text) { worker.post(() -> receive(id, text)); }
            public void onMessage(WebSocket ws, ByteString data) { worker.post(() -> receive(id, data)); }
            public void onFailure(WebSocket ws, Throwable t, Response response) { worker.post(() -> {
                if (closed || peers.get(id) != peer) return;
                removePeer(id);
                if (host) { signalMessage("drop-peer", id, null); status("Um convidado não conseguiu conectar. Ele pode tentar entrar de novo.", false); }
                else fail(t.getMessage());
            }); }
        }, code -> worker.post(() -> signalMessage("signal", id, code)), true);
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
            if (!host) {
                if ("chat".equals(type) || "talk".equals(type) || "media-reset".equals(type)) emit(text);
                return;
            }
            if ("chat".equals(type)) chat(id, msg.optString("text"));
            else if ("talk".equals(type) && msg.opt("active") instanceof Boolean) talk(msg.getBoolean("active"), id);
            else if ("request-keyframe".equals(type)) emit("{\"type\":\"request-keyframe\"}");
        } catch (Exception ignored) {}
    }
    private void receive(String id, ByteString data) {
        if (closed || data.size() < 2 || !peers.containsKey(id)) return;
        if (!host) { listener.onMessage(this, data); return; }
        if (data.getByte(0) != 7 || !id.equals(talker)) return;
        listener.onMessage(this, data);
        for (Map.Entry<String, Peer> entry : peers.entrySet())
            if (!entry.getKey().equals(id) && entry.getValue().open) entry.getValue().socket.send(data);
    }
    private String from(String source, String recipient) {
        boolean own = source.equals(recipient);
        return "host".equals(recipient) ? (own ? "host" : "viewer") : (own ? "viewer" : "host");
    }
    private void chat(String source, String text) {
        text = text.trim(); if (text.isEmpty() || text.codePointCount(0, text.length()) > 280) return;
        try {
            emit(new JSONObject().put("type", "chat").put("from", from(source, "host")).put("text", text).toString());
            for (Map.Entry<String, Peer> entry : peers.entrySet()) if (entry.getValue().open)
                entry.getValue().socket.send(new JSONObject().put("type", "chat").put("from", from(source, entry.getKey())).put("text", text).toString());
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
            for (Map.Entry<String, Peer> entry : peers.entrySet()) if (entry.getValue().open) deliverTalk(entry.getValue(), entry.getKey(), active, source);
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
                if (!host) { Peer peer = peers.get("host"); if (peer != null && peer.open) peer.socket.send(text); return; }
                if ("chat".equals(msg.optString("type"))) chat("host", msg.optString("text"));
                else if ("talk".equals(msg.optString("type"))) talk(msg.optBoolean("active"), "host");
            } catch (Exception ignored) {}
        }); return true;
    }
    private final java.util.concurrent.atomic.AtomicLong pending = new java.util.concurrent.atomic.AtomicLong();
    @Override public boolean send(ByteString data) {
        if (closed || !ready || data == null || data.size() < 2) return false;
        if (pending.addAndGet(data.size()) > 4 * 1024 * 1024) { pending.addAndGet(-data.size()); return false; }
        worker.post(() -> {
            try {
                if (closed) return;
                int type = data.getByte(0);
                if (!host) { Peer peer = peers.get("host"); if (peer != null && peer.open && type == 7) peer.socket.send(data); return; }
                if (type == 7) {
                    if ("host".equals(talker)) for (Peer peer : peers.values()) if (peer.open) peer.socket.send(data);
                    return;
                }
                if (type == 5) config = data;
                for (Peer peer : peers.values()) {
                    if (!peer.open) continue;
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
            client.dispatcher().executorService().shutdown(); thread.quitSafely();
        });
    }
    @Override public void cancel() { finish(); }
    @Override public boolean close(int code, String reason) { finish(); return true; }
}
