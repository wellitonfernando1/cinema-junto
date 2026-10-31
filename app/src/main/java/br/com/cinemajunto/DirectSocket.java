package br.com.cinemajunto;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;
import org.webrtc.*;

/** One-to-one DTLS/SCTP transport. No media relay and no automatic paid fallback. */
final class DirectSocket implements WebSocket {
    interface Codes { void ready(String code); }
    private static boolean initialized;
    private final boolean host;
    private final WebSocketListener listener;
    private final Codes codes;
    private final HandlerThread thread = new HandlerThread("CinemaDireto");
    private final Handler worker;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private volatile DataChannel channel;
    private volatile boolean closed, opened;
    private String session;
    private long expires;
    private boolean codeSent, answerApplied;
    private String talker;
    private final Runnable endTalk = () -> changeTalk(false, talker);
    private final Runnable lost = () -> fail("A conexão direta caiu. Crie um novo convite.");
    private final Runnable connectingTimeout = () -> fail("Não foi possível conectar diretamente nesta rede. Esta versão não usa servidor de retransmissão.");
    private final java.util.concurrent.atomic.AtomicLong pendingBytes = new java.util.concurrent.atomic.AtomicLong();
    private byte[] assembly;
    private int received, incomingId, outgoingId;
    private boolean incomingBinary;
    private static final int CHUNK = 12000, HEADER = 13, MAX = 512 * 1024;
    private static final long MAX_QUEUE = 4 * 1024 * 1024;

    DirectSocket(Context context, boolean host, String offer, WebSocketListener listener, Codes codes) {
        this.host = host; this.listener = listener; this.codes = codes;
        thread.start(); worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                synchronized (DirectSocket.class) {
                    if (!initialized) {
                        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                            .builder(context.getApplicationContext()).createInitializationOptions());
                        initialized = true;
                    }
                }
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
                PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.singletonList(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()));
                peer = factory.createPeerConnection(config, new PeerConnection.Observer() {
                    public void onSignalingChange(PeerConnection.SignalingState state) {}
                    public void onIceConnectionChange(PeerConnection.IceConnectionState state) { worker.post(() -> {
                        if (closed) return;
                        if (state == PeerConnection.IceConnectionState.FAILED) connectingTimeout.run();
                        else if (state == PeerConnection.IceConnectionState.DISCONNECTED) worker.postDelayed(lost, 10000);
                        else if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) worker.removeCallbacks(lost);
                    }); }
                    public void onIceConnectionReceivingChange(boolean receiving) {}
                    public void onIceGatheringChange(PeerConnection.IceGatheringState state) {
                        if (state == PeerConnection.IceGatheringState.COMPLETE) worker.post(() -> publishCode());
                    }
                    public void onIceCandidate(IceCandidate candidate) {}
                    public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
                    public void onAddStream(MediaStream stream) {}
                    public void onRemoveStream(MediaStream stream) {}
                    public void onDataChannel(DataChannel dc) { worker.post(() -> bind(dc)); }
                    public void onRenegotiationNeeded() {}
                });
                if (peer == null) throw new IllegalStateException("WebRTC indisponível.");
                if (host) {
                    session = UUID.randomUUID().toString(); expires = System.currentTimeMillis() + DirectInvite.VALID_MS;
                    DataChannel.Init init = new DataChannel.Init(); init.ordered = true;
                    bind(peer.createDataChannel("cinema-direct-v1", init));
                    peer.createOffer(createDescription(), new MediaConstraints());
                } else {
                    JSONObject data = DirectInvite.decode(offer, "offer");
                    session = data.getString("session"); expires = data.getLong("expires");
                    peer.setRemoteDescription(new Sdp() {
                        public void onSetSuccess() { worker.post(() -> { if (!closed) peer.createAnswer(createDescription(), new MediaConstraints()); }); }
                    }, new SessionDescription(SessionDescription.Type.OFFER, data.getString("sdp")));
                }
                worker.postDelayed(() -> { if (!opened) fail("Convite expirado. Encerre e crie outro."); }, DirectInvite.VALID_MS);
            } catch (Exception e) { fail(e.getMessage()); }
        });
    }
    private Sdp createDescription() { return new Sdp() {
        public void onCreateSuccess(SessionDescription description) { worker.post(() -> {
            if (closed) return;
            peer.setLocalDescription(new Sdp() {
                public void onSetSuccess() { worker.postDelayed(() -> publishCode(), 12000); }
            }, description);
        }); }
    }; }
    private void publishCode() {
        if (closed || codeSent || peer == null || peer.getLocalDescription() == null) return;
        try {
            String code = DirectInvite.encode(session, host ? "offer" : "answer", peer.getLocalDescription().description, expires);
            codeSent = true; codes.ready(code);
        } catch (Exception e) { fail("Não foi possível gerar o convite."); }
    }
    void acceptAnswer(String code) {
        worker.post(() -> {
            if (closed || !host || answerApplied) return;
            try {
                JSONObject data = DirectInvite.decode(code, "answer");
                if (!session.equals(data.getString("session"))) throw new IllegalArgumentException("A resposta pertence a outro convite.");
                peer.setRemoteDescription(new Sdp() {
                    public void onSetSuccess() { worker.post(() -> {
                        answerApplied = true; worker.postDelayed(connectingTimeout, 45000);
                    }); }
                }, new SessionDescription(SessionDescription.Type.ANSWER, data.getString("sdp")));
            } catch (Exception e) { fail(e.getMessage()); }
        });
    }
    private void bind(DataChannel dc) {
        if (closed) { if (dc != null) dc.dispose(); return; }
        if (channel != null || dc == null || !"cinema-direct-v1".equals(dc.label())) {
            if (dc != null) dc.dispose(); fail("Canal direto inválido."); return;
        }
        channel = dc;
        dc.registerObserver(new DataChannel.Observer() {
            public void onBufferedAmountChange(long previous) {}
            public void onStateChange() { worker.post(() -> {
                if (closed) return;
                if (dc.state() == DataChannel.State.OPEN && !opened) {
                    opened = true; worker.removeCallbacks(connectingTimeout); worker.removeCallbacks(lost);
                    Response response = new Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(101).message("WebRTC direto").build();
                    listener.onOpen(DirectSocket.this, response);
                } else if (dc.state() == DataChannel.State.CLOSED) fail("Conexão direta encerrada.");
            }); }
            public void onMessage(DataChannel.Buffer buffer) {
                ByteBuffer data = buffer.data; int size = data.remaining();
                if (size <= HEADER || size > HEADER + CHUNK || pendingBytes.addAndGet(size) > 8 * 1024 * 1024) {
                    worker.post(() -> fail("Excesso de dados na conexão direta.")); return;
                }
                byte[] copy = new byte[size]; data.get(copy);
                worker.post(() -> { try { receive(copy); } finally { pendingBytes.addAndGet(-size); } });
            }
        });
    }
    private void receive(byte[] chunk) {
        if (closed) return;
        try {
            if (chunk.length <= HEADER || chunk.length > HEADER + CHUNK) throw new IllegalArgumentException();
            ByteBuffer header = ByteBuffer.wrap(chunk);
            int length = header.getInt(), id = header.getInt(), offset = header.getInt(); byte kind = header.get();
            if (length < 1 || length > MAX || offset < 0 || kind < 0 || kind > 1) throw new IllegalArgumentException();
            if (offset == 0) {
                if (assembly != null) throw new IllegalArgumentException();
                assembly = new byte[length]; received = 0; incomingId = id; incomingBinary = kind == 1;
            }
            if (assembly == null || assembly.length != length || incomingId != id || received != offset ||
                    incomingBinary != (kind == 1) || chunk.length - HEADER > length - received) throw new IllegalArgumentException();
            System.arraycopy(chunk, HEADER, assembly, received, chunk.length - HEADER); received += chunk.length - HEADER;
            if (received == length) {
                byte[] message = assembly; assembly = null;
                if (incomingBinary) listener.onMessage(this, ByteString.of(message));
                else receiveText(new String(message, StandardCharsets.UTF_8));
            }
        } catch (Exception e) { fail("Dados inválidos na conexão direta."); }
    }
    private void receiveText(String text) throws Exception {
        JSONObject data = new JSONObject(text);
        String type = data.optString("type");
        if ("talk-request".equals(type)) { if (host) changeTalk(data.optBoolean("active"), "viewer"); return; }
        if ("talk".equals(type) && host) return; // Only the host arbitrates speech.
        if ("chat".equals(type)) data.put("from", host ? "viewer" : "host");
        if ("chat".equals(type) || "talk".equals(type) || "request-keyframe".equals(type)) listener.onMessage(this, data.toString());
    }
    private void changeTalk(boolean active, String who) {
        if (closed || who == null) return;
        if (active && talker != null && !talker.equals(who)) return;
        if (!active && !who.equals(talker)) return;
        talker = active ? who : null; worker.removeCallbacks(endTalk);
        try {
            String text = new JSONObject().put("type", "talk").put("from", who).put("active", active).toString();
            if (sendRaw(text.getBytes(StandardCharsets.UTF_8), false)) listener.onMessage(this, text);
            if (active) worker.postDelayed(endTalk, 30000);
        } catch (Exception ignored) {}
    }
    @Override public boolean send(String text) {
        if (closed || !opened || text == null || text.length() > 8192) return false;
        try {
            JSONObject data = new JSONObject(text);
            if (data.has("role")) {
                worker.post(() -> {
                    if (closed) return;
                    listener.onMessage(this, "{\"joined\":true,\"status\":\"Conexão direta ativa · sem tráfego de vídeo pelo Render\"}");
                    if (!host) sendRaw("{\"type\":\"request-keyframe\"}".getBytes(StandardCharsets.UTF_8), false);
                }); return true;
            }
            if ("talk".equals(data.optString("type"))) {
                if (host) { worker.post(() -> changeTalk(data.optBoolean("active"), "host")); return true; }
                data.put("type", "talk-request");
            } else if ("chat".equals(data.optString("type"))) {
                data.put("from", host ? "host" : "viewer");
                String echo = data.toString();
                if (!sendRaw(echo.getBytes(StandardCharsets.UTF_8), false)) return false;
                worker.post(() -> { if (!closed) listener.onMessage(this, echo); }); return true;
            }
            return sendRaw(data.toString().getBytes(StandardCharsets.UTF_8), false);
        } catch (Exception e) { return false; }
    }
    @Override public boolean send(ByteString data) {
        if (data == null || data.size() == 0) return false;
        int type = data.getByte(0);
        if (!host && type != 7) return false;
        return sendRaw(data.toByteArray(), true);
    }
    private synchronized boolean sendRaw(byte[] message, boolean binary) {
        DataChannel dc = channel;
        if (closed || !opened || dc == null || message.length == 0 || message.length > MAX || dc.bufferedAmount() + message.length > MAX_QUEUE) return false;
        int id = ++outgoingId;
        for (int offset = 0; offset < message.length; offset += CHUNK) {
            int size = Math.min(CHUNK, message.length - offset);
            ByteBuffer frame = ByteBuffer.allocate(HEADER + size).putInt(message.length).putInt(id).putInt(offset).put((byte)(binary ? 1 : 0));
            frame.put(message, offset, size).flip();
            if (!dc.send(new DataChannel.Buffer(frame, true))) { worker.post(() -> fail("O envio direto foi interrompido. Crie outro convite.")); return false; }
        }
        return true;
    }
    @Override public Request request() { return new Request.Builder().url("https://direct.invalid/").build(); }
    @Override public synchronized long queueSize() { DataChannel dc = channel; return dc == null || closed ? MAX_QUEUE : dc.bufferedAmount(); }
    private void fail(String message) {
        if (closed) return;
        finish(); listener.onFailure(this, new java.io.IOException(message == null ? "Falha na conexão direta." : message), null);
    }
    private void finish() {
        synchronized (this) { if (closed) return; closed = true; opened = false; }
        worker.removeCallbacksAndMessages(null);
        worker.post(() -> {
            synchronized (DirectSocket.this) {
                if (channel != null) { channel.unregisterObserver(); channel.close(); channel.dispose(); channel = null; }
            }
            if (peer != null) { peer.close(); peer.dispose(); peer = null; }
            if (factory != null) { factory.dispose(); factory = null; }
            assembly = null; thread.quitSafely();
        });
    }
    @Override public void cancel() { finish(); }
    @Override public boolean close(int code, String reason) { finish(); return true; }
    private class Sdp implements SdpObserver {
        public void onCreateSuccess(SessionDescription description) {}
        public void onSetSuccess() {}
        public void onCreateFailure(String error) { worker.post(() -> fail("Falha ao preparar conexão direta.")); }
        public void onSetFailure(String error) { worker.post(() -> fail("Código de conexão recusado. Crie outro convite.")); }
    }
}
