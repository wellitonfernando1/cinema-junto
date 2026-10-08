package br.com.cinemajunto;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;
import android.view.WindowInsets;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

public class CaptureService extends Service {
    public static volatile boolean active, starting;
    public static boolean supportsSingleAppSharing() { return Build.VERSION.SDK_INT >= 34; }
    volatile boolean running, connected;
    volatile WebSocket socket;
    OkHttpClient client;
    MediaProjection projection;
    VirtualDisplay display;
    MediaCodec encoder;
    Surface encoderSurface;
    HandlerThread images;
    Handler imageHandler;
    AudioRecord recorder;
    Thread audio;
    final Object sendLock = new Object();
    byte[] cachedVideoConfig;
    boolean waitingForKeyFrame = true, configurationPending = true;
    long lastSyncRequestUs, encoderTimestampOffsetUs = Long.MIN_VALUE, lastVideoTimestampUs;
    static final int AUDIO_RATE = 44100;
    static final long MAX_SEND_QUEUE = 256 * 1024;
    int width, height, density;
    final Handler main = new Handler(Looper.getMainLooper());
    HostChatOverlay overlay;
    volatile VoiceTalk voice;
    VoiceButton voiceButton;
    WindowManager voiceWindows;
    AudioManager audioManager;
    int previousMovieVolume = -1;
    boolean movieMuted;
    DisplayManager displayManager;
    final DisplayManager.DisplayListener rotationListener = new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) {}
        public void onDisplayRemoved(int id) {}
        public void onDisplayChanged(int id) { if (id == Display.DEFAULT_DISPLAY) resizeForDisplay(); }
    };
    void status(String text) { sendBroadcast(new Intent(getPackageName()+".STATUS").setPackage(getPackageName()).putExtra("status", text)); }
    void chat(String from, String text) {
        sendBroadcast(new Intent(getPackageName()+".STATUS").setPackage(getPackageName())
            .putExtra("from", from).putExtra("chat", text));
        main.post(() -> { if (running && overlay != null) overlay.receive(from, text); });
    }
    boolean sendChat(String text) {
        if (!connected || socket == null || text == null || text.trim().isEmpty()) return false;
        try { synchronized (sendLock) { return connected && socket != null && socket.send(new JSONObject().put("type", "chat").put("text", text.trim()).toString()); } }
        catch (Exception e) { return false; }
    }
    boolean sendVoiceText(String text) {
        synchronized (sendLock) { return running && connected && socket != null && socket.send(text); }
    }
    boolean sendVoiceAudio(byte[] packet) {
        synchronized (sendLock) {
            return running && connected && socket != null && socket.queueSize() < MAX_SEND_QUEUE
                && socket.send(ByteString.of(packet));
        }
    }
    void createVoice() {
        audioManager = getSystemService(AudioManager.class);
        voice = new VoiceTalk(this, "host", new VoiceTalk.Listener() {
            public boolean sendText(String text) { return sendVoiceText(text); }
            public boolean sendBinary(byte[] packet) { return sendVoiceAudio(packet); }
            public void muteMovie(boolean muted) { setMovieMuted(muted); }
            public void error(String text) { status(text); }
        });
        if (!Settings.canDrawOverlays(this)) {
            status("Permita aparecer sobre outros apps para usar o botão de voz ao transmitir."); return;
        }
        voiceWindows = getSystemService(WindowManager.class);
        voiceButton = new VoiceButton(this, () -> { VoiceTalk talk = voice; if (talk != null) talk.press(); },
            () -> { VoiceTalk talk = voice; if (talk != null) talk.release(); });
        WindowManager.LayoutParams position = new WindowManager.LayoutParams(dp(56), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        position.gravity = Gravity.TOP | Gravity.RIGHT; position.x = dp(8); position.y = dp(8);
        if (Build.VERSION.SDK_INT >= 30) position.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        try { voiceWindows.addView(voiceButton, position); }
        catch (Exception error) { voiceButton = null; status("Não foi possível mostrar o botão de voz. Confira a permissão para aparecer sobre outros apps."); }
    }
    int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    void setMovieMuted(boolean muted) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> setMovieMuted(muted)); return; }
        if (muted && !running) return;
        try {
            if (audioManager != null && muted && !movieMuted) {
                previousMovieVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0); movieMuted = true;
            } else if (audioManager != null && !muted && movieMuted) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousMovieVolume, 0);
                movieMuted = false; previousMovieVolume = -1;
            }
        } catch (Exception error) { status("Não foi possível ajustar o som do filme para a conversa."); }
        if (voiceButton != null) { VoiceTalk talk = voice; voiceButton.setTalking(talk != null && talk.isTalking()); }
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if ("VOICE_PRESS".equals(intent.getAction()) || "VOICE_RELEASE".equals(intent.getAction())) {
            VoiceTalk talk = voice;
            if (running && talk != null) { if ("VOICE_PRESS".equals(intent.getAction())) talk.press(); else talk.release(); }
            return START_NOT_STICKY;
        }
        if ("OPEN_CHAT".equals(intent.getAction())) {
            if (running) { if (overlay == null) overlay = new HostChatOverlay(this, this::sendChat); overlay.open(); }
            return START_NOT_STICKY;
        }
        if ("CHAT".equals(intent.getAction())) {
            String text = intent.getStringExtra("message");
            if (!sendChat(text)) status("Chat desconectado. Tente novamente.");
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        starting = true;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("transmissao", "Compartilhamento de tela", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder notification = new Notification.Builder(this, "transmissao")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Cinema Junto: tela e som compartilhados")
                .setContentText("Segure o microfone para falar. Toque em Chat para responder.")
                .setContentIntent(open).setOngoing(true);
            PendingIntent openChat = PendingIntent.getService(this, 3, new Intent(this, CaptureService.class).setAction("OPEN_CHAT"), PendingIntent.FLAG_IMMUTABLE);
            notification.addAction(android.R.drawable.ic_menu_send, "Chat", openChat);
            Notification n = notification.addAction(android.R.drawable.ic_media_pause, "Encerrar", stop).build();
            int serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (Build.VERSION.SDK_INT >= 30) serviceTypes |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(1, n, serviceTypes);
            running = true;
            overlay = new HostChatOverlay(this, this::sendChat); overlay.show();
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent consent = intent.getParcelableExtra("data");
            projection = manager.getMediaProjection(intent.getIntExtra("result", -1), consent);
            images = new HandlerThread("CinemaCaptura"); images.start(); imageHandler = new Handler(images.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { if (running) { status("Compartilhamento encerrado pelo celular."); stopSelf(); } }
                @Override public void onCapturedContentResize(int w, int h) { if (display != null && running) imageHandler.post(() -> resize(w, h)); }
            }, new Handler(getMainLooper()));
            DisplayMetrics metrics = new DisplayMetrics();
            displayManager = getSystemService(DisplayManager.class);
            displayManager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics); density = metrics.densityDpi;
            dimensions(metrics.widthPixels, metrics.heightPixels);
            createEncoder();
            display = projection.createVirtualDisplay("CinemaJunto", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoderSurface, null, imageHandler);
            displayManager.registerDisplayListener(rotationListener, main);
            createVoice();
            String url = intent.getStringExtra("endpoint").replaceFirst("^https", "wss") + "relay";
            String room = intent.getStringExtra("room");
            client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
            socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    try { ws.send(new JSONObject().put("room", room).put("role", "host").put("device", RoomPassword.device(CaptureService.this)).toString()); } catch (Exception ignored) {}
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    if (!running || socket != ws) return;
                    try {
                        JSONObject msg = new JSONObject(text);
                        if ("error".equals(msg.optString("type"))) {
                            status(msg.optString("message", "Não foi possível criar a sala.")); stopSelf();
                        }
                        else if ("chat".equals(msg.optString("type"))) chat(msg.optString("from"), msg.optString("text"));
                        else if ("talk".equals(msg.optString("type"))) { VoiceTalk talk = voice; if (talk != null) talk.onControl(msg); }
                        else if ("request-keyframe".equals(msg.optString("type"))) {
                            imageHandler.post(() -> { if (running) { waitingForKeyFrame = true; configurationPending = true; requestSyncFrame(); } });
                        }
                        else if (msg.has("status")) {
                            if (msg.optBoolean("joined")) { connected = true; active = true; starting = false; }
                            status(msg.optString("status"));
                        }
                    } catch (Exception ignored) {}
                }
                @Override public void onMessage(WebSocket ws, ByteString packet) {
                    VoiceTalk talk = voice; if (running && socket == ws && talk != null) talk.onAudio(packet.toByteArray());
                }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) { if (running) { status("Falha na conexão. Abra o Cinema Junto e tente novamente."); stopSelf(); } }
                @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
                @Override public void onClosed(WebSocket ws, int code, String reason) { if (running) { status(reason); stopSelf(); } }
            });
            captureAudio();
        } catch (Exception e) { status("Não foi possível capturar a tela e o som: " + e.getClass().getSimpleName()); stopSelf(); }
        return START_NOT_STICKY;
    }
    void dimensions(int w, int h) {
        float scale = Math.min(1f, 960f / Math.max(w, h));
        width = Math.max(2, Math.round(w * scale) / 2 * 2);
        height = Math.max(2, Math.round(h * scale) / 2 * 2);
    }
    void resizeForDisplay() {
        // Android 14+ supplies the captured region size, which may differ from the entire display.
        if (Build.VERSION.SDK_INT >= 34 || !running || displayManager == null || imageHandler == null) return;
        Display screen = displayManager.getDisplay(Display.DEFAULT_DISPLAY); if (screen == null) return;
        DisplayMetrics metrics = new DisplayMetrics(); screen.getRealMetrics(metrics);
        imageHandler.post(() -> resize(metrics.widthPixels, metrics.heightPixels));
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); resizeForDisplay();
        if (overlay != null) overlay.onConfigurationChanged();
    }
    void resize(int w, int h) {
        if (!running || display == null || w <= 0 || h <= 0) return;
        int ow = width, oh = height; dimensions(w,h); if (ow == width && oh == height) return;
        try {
            // The consent token is used only once. Reattach the new encoder to the existing display.
            display.setSurface(null); releaseEncoder(); createEncoder();
            display.resize(width, height, density); display.setSurface(encoderSurface);
        } catch (Exception e) { status("Não foi possível ajustar a transmissão. Encerre e tente novamente."); stopSelf(); }
    }
    void createEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1400000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 24);
        format.setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, 24f);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        // Legacy OMX encoders require a level whenever an explicit profile is supplied.
        // Level 3.1 covers even the maximum 960x960 capture at 24 frames per second.
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000L / 24);
        waitingForKeyFrame = true; configurationPending = true; cachedVideoConfig = null;
        encoderTimestampOffsetUs = Long.MIN_VALUE; lastSyncRequestUs = 0;
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.setCallback(new MediaCodec.Callback() {
            @Override public void onInputBufferAvailable(MediaCodec codec, int index) {}
            @Override public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
                try {
                    if (!running || codec != encoder || info.size <= 0) return;
                    ByteBuffer source = codec.getOutputBuffer(index);
                    if (source == null) return;
                    source.position(info.offset); source.limit(info.offset + info.size);
                    byte[] payload = new byte[info.size]; source.get(payload);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // Some encoders put SPS and PPS together in csd-0.
                        if (cachedVideoConfig == null) cachedVideoConfig = StreamPacket.videoConfig(width, height, payload, new byte[0]);
                        return;
                    }
                    sendVideo(payload, info.presentationTimeUs, info.flags);
                } catch (Exception e) {
                    if (running && codec == encoder) { status("Falha na captura do vídeo. Encerre e tente novamente."); stopSelf(); }
                } finally { try { codec.releaseOutputBuffer(index, false); } catch (Exception ignored) {} }
            }
            @Override public void onOutputFormatChanged(MediaCodec codec, MediaFormat next) {
                if (!running || codec != encoder) return;
                try {
                    byte[] csd0 = copyBuffer(next.getByteBuffer("csd-0"));
                    byte[] csd1 = copyBuffer(next.getByteBuffer("csd-1"));
                    if (csd0.length > 0) {
                        cachedVideoConfig = StreamPacket.videoConfig(width, height, csd0, csd1);
                        configurationPending = true; waitingForKeyFrame = true; requestSyncFrame();
                    }
                } catch (Exception e) { status("Não foi possível preparar o vídeo. Encerre e tente novamente."); stopSelf(); }
            }
            @Override public void onError(MediaCodec codec, MediaCodec.CodecException error) {
                if (running && codec == encoder) { status("Este aparelho não conseguiu transmitir vídeo fluido. Encerre e tente novamente."); stopSelf(); }
            }
        }, imageHandler);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface(); encoder.start();
    }
    static byte[] copyBuffer(ByteBuffer source) {
        if (source == null) return new byte[0];
        ByteBuffer copy = source.duplicate(); byte[] bytes = new byte[copy.remaining()]; copy.get(bytes); return bytes;
    }
    long videoTimestamp(long codecTimestampUs) {
        long nowUs = System.nanoTime() / 1000;
        if (encoderTimestampOffsetUs == Long.MIN_VALUE)
            encoderTimestampOffsetUs = Math.abs(codecTimestampUs - nowUs) < 10000000L ? 0 : nowUs - codecTimestampUs;
        long timestampUs = Math.max(lastVideoTimestampUs + 1, codecTimestampUs + encoderTimestampOffsetUs);
        lastVideoTimestampUs = timestampUs; return timestampUs;
    }
    void requestSyncFrame() {
        long nowUs = System.nanoTime() / 1000;
        if (!running || encoder == null || nowUs - lastSyncRequestUs < 300000L) return;
        lastSyncRequestUs = nowUs;
        try { Bundle parameters = new Bundle(); parameters.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0); encoder.setParameters(parameters); }
        catch (IllegalStateException ignored) {}
    }
    void sendVideo(byte[] payload, long codecTimestampUs, int flags) {
        long timestampUs = videoTimestamp(codecTimestampUs);
        boolean keyFrame = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        synchronized (sendLock) {
            if (!running || !connected || socket == null || socket.queueSize() > MAX_SEND_QUEUE
                    || payload.length > StreamPacket.MAX_PACKET_SIZE - StreamPacket.HEADER_SIZE) {
                waitingForKeyFrame = true; configurationPending = true; requestSyncFrame(); return;
            }
            if (waitingForKeyFrame && !keyFrame) { requestSyncFrame(); return; }
            if (keyFrame && (configurationPending || waitingForKeyFrame)) {
                if (cachedVideoConfig == null) { waitingForKeyFrame = true; requestSyncFrame(); return; }
                // Audio uses the same lock so configuration is immediately followed by its sync frame.
                if (!socket.send(ByteString.of(StreamPacket.pack(StreamPacket.AVC_CONFIG, timestampUs, 0, cachedVideoConfig)))) return;
            }
            boolean sent = socket.send(ByteString.of(StreamPacket.pack(StreamPacket.AVC_FRAME, timestampUs, flags, payload)));
            waitingForKeyFrame = !sent; configurationPending = !sent;
            if (!sent) requestSyncFrame();
        }
    }
    void releaseEncoder() {
        MediaCodec previous = encoder; encoder = null;
        if (previous != null) { try { previous.stop(); } catch (Exception ignored) {} try { previous.release(); } catch (Exception ignored) {} }
        if (encoderSurface != null) { encoderSurface.release(); encoderSurface = null; }
        cachedVideoConfig = null;
    }
    void captureAudio() {
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(AUDIO_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        int min = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(min, 8820)).setAudioPlaybackCaptureConfig(config).build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Captura indisponível");
        recorder.startRecording();
        audio = new Thread(() -> {
            byte[] pcm = new byte[1764];
            AudioTimestamp stamp = new AudioTimestamp();
            long totalFrames = 0, originUs = Long.MIN_VALUE, lastEndUs = Long.MIN_VALUE;
            while (running && !Thread.currentThread().isInterrupted()) {
                int count = recorder.read(pcm, 0, pcm.length);
                if (count < 0) { status("A captura de áudio foi interrompida."); stopSelf(); break; }
                if (count > 0) {
                    int frames = count / 2;
                    long nowUs = System.nanoTime() / 1000;
                    if (originUs == Long.MIN_VALUE) originUs = nowUs - (totalFrames + frames) * 1000000L / AUDIO_RATE;
                    if (recorder.getTimestamp(stamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        long capturedOriginUs = stamp.nanoTime / 1000 - stamp.framePosition * 1000000L / AUDIO_RATE;
                        long candidate = capturedOriginUs + totalFrames * 1000000L / AUDIO_RATE;
                        if (candidate >= nowUs - 2000000L && candidate <= nowUs + 100000L) originUs = capturedOriginUs;
                    }
                    long timestampUs = originUs + totalFrames * 1000000L / AUDIO_RATE;
                    // Small timestamp estimation changes must not create gaps in the sample timeline.
                    if (lastEndUs != Long.MIN_VALUE && Math.abs(timestampUs - lastEndUs) < 100000L) timestampUs = lastEndUs;
                    lastEndUs = timestampUs + frames * 1000000L / AUDIO_RATE; totalFrames += frames;
                    synchronized (sendLock) {
                        if (connected && socket != null && socket.queueSize() < MAX_SEND_QUEUE)
                            socket.send(ByteString.of(StreamPacket.pack(StreamPacket.PCM, timestampUs, 0, Arrays.copyOf(pcm, frames * 2))));
                    }
                }
            }
        }, "CinemaSom"); audio.start();
    }
    @Override public void onDestroy() {
        running = false; connected = false; active = false; starting = false;
        if (displayManager != null) displayManager.unregisterDisplayListener(rotationListener);
        main.removeCallbacksAndMessages(null);
        VoiceTalk talk = voice; voice = null; if (talk != null) talk.close();
        if (voiceButton != null && voiceWindows != null) { try { voiceWindows.removeView(voiceButton); } catch (Exception ignored) {} voiceButton = null; }
        setMovieMuted(false);
        if (overlay != null) { overlay.close(); overlay = null; }
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} }
        if (audio != null) { audio.interrupt(); try { audio.join(500); } catch (InterruptedException ignored) {} }
        if (recorder != null) recorder.release();
        synchronized (sendLock) { if (socket != null) socket.cancel(); }
        if (imageHandler != null && images != null && images.isAlive()) {
            CountDownLatch released = new CountDownLatch(1);
            imageHandler.post(() -> { try { if (display != null) { display.release(); display = null; } releaseEncoder(); } finally { released.countDown(); } });
            try { released.await(750, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            images.quitSafely(); try { images.join(500); } catch (InterruptedException ignored) {}
        } else { if (display != null) display.release(); releaseEncoder(); }
        if (projection != null) projection.stop();
        if (client != null) client.dispatcher().executorService().shutdown();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
