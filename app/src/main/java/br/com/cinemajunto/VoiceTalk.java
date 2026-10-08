package br.com.cinemajunto;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.*;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Momentary voice messages. Microphone capture starts only after the room grants the turn. */
final class VoiceTalk implements AutoCloseable {
    interface Listener {
        boolean sendText(String json);
        boolean sendBinary(byte[] packet);
        void muteMovie(boolean muted);
        void error(String message);
    }
    static final int VOICE_PACKET = 7, RATE = 16000, CHUNK_BYTES = 640;
    final Context context;
    final String role;
    final Listener listener;
    final Object lock = new Object();
    final Handler main = new Handler(Looper.getMainLooper());
    final ArrayBlockingQueue<byte[]> voice = new ArrayBlockingQueue<>(8);
    final Thread player;
    volatile boolean closed, talking, peerTalking;
    boolean held, pending;
    long generation;
    volatile AudioRecord microphone;
    Thread capture;
    final Runnable pendingTimeout = this::handlePendingTimeout;
    final Runnable maximumHold = this::handleMaximumHold;

    void handlePendingTimeout() {
        boolean timedOut;
        synchronized (lock) { timedOut = pending && held && !closed; }
        if (timedOut) { release(); listener.error("Não foi possível iniciar a voz. Tente novamente."); }
    }
    void handleMaximumHold() {
        boolean active;
        synchronized (lock) { active = held && !closed; }
        if (active) { release(); listener.error("Solte e segure novamente para continuar falando."); }
    }

    VoiceTalk(Context context, String role, Listener listener) {
        if (!"host".equals(role) && !"viewer".equals(role)) throw new IllegalArgumentException("Papel inválido");
        this.context = context.getApplicationContext(); this.role = role; this.listener = listener;
        player = new Thread(this::playVoice, "CinemaVozRecebida"); player.start();
    }

    boolean isTalking() { return talking; }

    void press() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.error("Permita o microfone para enviar sua voz."); return;
        }
        boolean sent;
        synchronized (lock) {
            if (closed || held) return;
            if (peerTalking) { listener.error("Seu amigo está falando. Aguarde ele soltar o botão."); return; }
            held = true; pending = true;
            sent = sendControl(true);
        }
        updateMovieMute();
        if (!sent) {
            release(); listener.error("A voz está desconectada. Entre na sala novamente."); return;
        }
        synchronized (lock) {
            if (!closed && held) {
                main.removeCallbacks(pendingTimeout); main.removeCallbacks(maximumHold);
                main.postDelayed(pendingTimeout, 3000); main.postDelayed(maximumHold, 30000);
            }
        }
    }

    void release() {
        boolean notify;
        Thread old;
        AudioRecord oldMicrophone;
        synchronized (lock) {
            notify = held || pending || talking;
            held = false; pending = false; talking = false; generation++;
            old = capture; capture = null;
            oldMicrophone = microphone; microphone = null;
            if (notify) sendControl(false);
        }
        main.removeCallbacks(pendingTimeout); main.removeCallbacks(maximumHold);
        stopCapture(old, oldMicrophone);
        updateMovieMute();
    }

    void onControl(JSONObject message) {
        if (closed || !"talk".equals(message.optString("type"))) return;
        String from = message.optString("from");
        if (!"host".equals(from) && !"viewer".equals(from)) return;
        boolean active = message.optBoolean("active");
        boolean own = role.equals(from), cancelled = false, staleGrant = false;
        Thread old = null, next = null;
        AudioRecord oldMicrophone = null;
        synchronized (lock) {
            if (closed) return;
            if (active && own) {
                if (!held) staleGrant = true;
                else if (!talking) {
                    pending = false; talking = true; peerTalking = false;
                    long session = ++generation;
                    next = new Thread(() -> recordVoice(session), "CinemaMicrofone"); capture = next;
                }
            } else if (active) {
                peerTalking = true;
                cancelled = held || pending || talking;
                held = false; pending = false; talking = false; generation++;
                old = capture; capture = null;
                oldMicrophone = microphone; microphone = null;
            } else if (own) {
                // The acknowledgement of a previous release can precede a new grant.
                if (!held || !pending) {
                    held = false; pending = false; talking = false; generation++;
                    old = capture; capture = null;
                    oldMicrophone = microphone; microphone = null;
                }
            } else peerTalking = false;
        }
        if (!pending) main.removeCallbacks(pendingTimeout);
        if (!held) main.removeCallbacks(maximumHold);
        stopCapture(old, oldMicrophone);
        if (!peerTalking) clearVoice();
        updateMovieMute();
        if (cancelled) listener.error("Seu amigo está falando. Aguarde ele soltar o botão.");
        if (staleGrant) sendControl(false);
        if (next != null) next.start();
    }

    void onAudio(byte[] data) {
        if (closed || !peerTalking) return;
        try {
            StreamPacket.Packet packet = StreamPacket.unpack(data);
            if (packet.type != VOICE_PACKET || packet.payload.length == 0
                    || packet.payload.length > CHUNK_BYTES || (packet.payload.length & 1) != 0) return;
            if (!voice.offer(packet.payload)) { voice.poll(); voice.offer(packet.payload); }
        } catch (IllegalArgumentException ignored) {}
    }

    boolean sendControl(boolean active) {
        try { return listener.sendText(new JSONObject().put("type", "talk").put("active", active).toString()); }
        catch (Exception ignored) { return false; }
    }

    void updateMovieMute() {
        boolean muted;
        synchronized (lock) { muted = !closed && (peerTalking || talking || pending); }
        listener.muteMovie(muted);
    }

    boolean captureValid(long session) {
        synchronized (lock) { return !closed && held && talking && generation == session; }
    }

    void recordVoice(long session) {
        AudioRecord record = null;
        AcousticEchoCanceler echo = null;
        NoiseSuppressor noise = null;
        boolean failed = false;
        try {
            int minimum = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Microfone indisponível");
            AudioFormat format = new AudioFormat.Builder().setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build();
            record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(format).setBufferSizeInBytes(Math.max(minimum, CHUNK_BYTES * 8)).build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microfone indisponível");
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echo = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (echo != null) echo.setEnabled(true);
                }
                if (NoiseSuppressor.isAvailable()) {
                    noise = NoiseSuppressor.create(record.getAudioSessionId());
                    if (noise != null) noise.setEnabled(true);
                }
            } catch (RuntimeException ignored) {}
            synchronized (lock) {
                if (!captureValid(session)) return;
                microphone = record; record.startRecording();
            }
            byte[] pcm = new byte[CHUNK_BYTES];
            while (captureValid(session)) {
                int count = record.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (!captureValid(session)) break;
                if (count < 0) throw new IllegalStateException("Microfone interrompido");
                if (count == 0) continue;
                byte[] payload = Arrays.copyOf(pcm, count & ~1);
                if (payload.length > 0 && !listener.sendBinary(StreamPacket.pack(VOICE_PACKET,
                        SystemClock.elapsedRealtimeNanos() / 1000L, 0, payload)))
                    throw new IllegalStateException("Conexão interrompida");
            }
        } catch (Exception error) { failed = captureValid(session); }
        finally {
            if (record != null) {
                try { record.stop(); } catch (Exception ignored) {}
                record.release();
            }
            if (echo != null) echo.release();
            if (noise != null) noise.release();
            boolean reportFailure;
            synchronized (lock) {
                if (microphone == record) microphone = null;
                reportFailure = failed && !closed && generation == session;
                if (reportFailure) {
                    held = false; pending = false; talking = false; capture = null; generation++;
                    sendControl(false);
                }
            }
            if (reportFailure) {
                main.removeCallbacks(pendingTimeout); main.removeCallbacks(maximumHold);
                updateMovieMute(); listener.error("Não foi possível usar o microfone. Solte e tente novamente.");
            }
        }
    }

    void stopCapture(Thread old, AudioRecord record) {
        if (record != null) { try { record.stop(); } catch (Exception ignored) {} }
        if (old != null) {
            old.interrupt();
            if (old != Thread.currentThread()) {
                try { old.join(150); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    void clearVoice() { voice.clear(); voice.offer(new byte[0]); }

    void playVoice() {
        AudioTrack track = null;
        try {
            int minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Áudio indisponível");
            track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(minimum, CHUNK_BYTES * 4)).build();
            boolean playing = false;
            while (!closed && !Thread.currentThread().isInterrupted()) {
                byte[] packet = voice.poll(100, TimeUnit.MILLISECONDS);
                if (packet == null) continue;
                if (packet.length == 0 || !peerTalking) {
                    if (playing) { track.pause(); track.flush(); playing = false; }
                    continue;
                }
                if (!playing) { track.play(); playing = true; }
                int offset = 0;
                while (offset < packet.length && !closed && peerTalking) {
                    int count = track.write(packet, offset, packet.length - offset, AudioTrack.WRITE_NON_BLOCKING);
                    if (count < 0) throw new IllegalStateException("Áudio interrompido");
                    if (count == 0) Thread.sleep(4); else offset += count;
                }
            }
        } catch (InterruptedException ignored) {
        } catch (Exception error) {
            if (!closed) listener.error("Não foi possível ouvir a voz. Entre na sala novamente.");
        } finally {
            if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
            voice.clear();
        }
    }

    @Override public void close() {
        synchronized (lock) { if (closed) return; closed = true; peerTalking = false; }
        release();
        main.removeCallbacksAndMessages(null); clearVoice(); player.interrupt();
        try { player.join(150); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        listener.muteMovie(false);
    }
}
