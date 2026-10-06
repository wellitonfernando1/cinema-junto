package br.com.cinemajunto;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.*;
import android.hardware.display.*;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import okio.ByteString;
import org.json.JSONObject;

public class CaptureService extends Service {
    public static volatile boolean active;
    volatile boolean running, connected;
    WebSocket socket;
    OkHttpClient client;
    MediaProjection projection;
    VirtualDisplay display;
    ImageReader reader;
    HandlerThread images;
    Handler imageHandler;
    AudioRecord recorder;
    Thread audio;
    long lastFrame;
    int width, height, density;
    void status(String text) { sendBroadcast(new Intent(getPackageName()+".STATUS").setPackage(getPackageName()).putExtra("status", text)); }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("transmissao", "Compartilhamento de tela", NotificationManager.IMPORTANCE_LOW));
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, CaptureService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
            PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(this, "transmissao").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("Cinema Junto: tela e som compartilhados")
                .setContentText("Toque em Encerrar para parar.").setContentIntent(open).setOngoing(true).addAction(android.R.drawable.ic_media_pause, "Encerrar", stop).build();
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            running = true;
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent consent = intent.getParcelableExtra("data");
            projection = manager.getMediaProjection(intent.getIntExtra("result", -1), consent);
            images = new HandlerThread("CinemaCaptura"); images.start(); imageHandler = new Handler(images.getLooper());
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { status("Compartilhamento encerrado pelo celular."); stopSelf(); }
                @Override public void onCapturedContentResize(int w, int h) { if (display != null && running) imageHandler.post(() -> resize(w, h)); }
            }, new Handler(getMainLooper()));
            DisplayMetrics metrics = getResources().getDisplayMetrics(); density = metrics.densityDpi;
            dimensions(metrics.widthPixels, metrics.heightPixels);
            reader = createReader();
            display = projection.createVirtualDisplay("CinemaJunto", width, height, density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, imageHandler);
            String url = intent.getStringExtra("endpoint").replaceFirst("^https", "wss") + "relay";
            String room = intent.getStringExtra("room");
            client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
            socket = client.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    try { ws.send(new JSONObject().put("room", room).put("role", "host").toString()); } catch (Exception ignored) {}
                }
                @Override public void onMessage(WebSocket ws, String text) { try { connected = true; active = true; status(new JSONObject(text).optString("status")); } catch (Exception ignored) {} }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) { status("Falha na conexão. Abra o Cinema Junto e tente novamente."); stopSelf(); }
                @Override public void onClosing(WebSocket ws, int code, String reason) { ws.close(code, reason); }
                @Override public void onClosed(WebSocket ws, int code, String reason) { status(reason); stopSelf(); }
            });
            captureAudio();
        } catch (Exception e) { status("Não foi possível capturar a tela e o som: " + e.getClass().getSimpleName()); stopSelf(); }
        return START_NOT_STICKY;
    }
    void dimensions(int w, int h) { float scale = Math.min(1f, 640f / Math.max(w,h)); width = Math.max(2, Math.round(w*scale)); height = Math.max(2, Math.round(h*scale)); }
    void resize(int w, int h) {
        if (!running || display == null || w <= 0 || h <= 0) return;
        int ow = width, oh = height; dimensions(w,h); if (ow == width && oh == height) return;
        ImageReader old = reader; reader = createReader(); display.resize(width, height, density); display.setSurface(reader.getSurface()); old.close();
    }
    ImageReader createReader() {
        ImageReader next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        next.setOnImageAvailableListener(source -> {
            Image image = null; Bitmap padded = null, cropped = null;
            try {
                image = source.acquireLatestImage();
                long now = SystemClock.elapsedRealtime();
                if (image == null || !running || !connected || socket == null || socket.queueSize() > 256*1024 || now - lastFrame < 80) return;
                lastFrame = now;
                Image.Plane plane = image.getPlanes()[0]; ByteBuffer buffer = plane.getBuffer();
                int iw = image.getWidth(), ih = image.getHeight();
                int padding = plane.getRowStride() - plane.getPixelStride()*iw;
                padded = Bitmap.createBitmap(iw + padding/plane.getPixelStride(), ih, Bitmap.Config.ARGB_8888);
                if (buffer.remaining() < padded.getByteCount()) {
                    ByteBuffer complete = ByteBuffer.allocate(padded.getByteCount()); complete.put(buffer); complete.rewind(); buffer = complete;
                }
                padded.copyPixelsFromBuffer(buffer);
                cropped = Bitmap.createBitmap(padded, 0, 0, iw, ih);
                ByteArrayOutputStream packet = new ByteArrayOutputStream(); packet.write(1); cropped.compress(Bitmap.CompressFormat.JPEG, 60, packet);
                if (packet.size() < 512*1024) socket.send(ByteString.of(packet.toByteArray()));
            } catch (Exception e) { if (running) status("Falha na captura da imagem. Encerre e tente novamente."); }
            finally { if (cropped != null && cropped != padded) cropped.recycle(); if (padded != null) padded.recycle(); if (image != null) image.close(); }
        }, imageHandler); return next;
    }
    void captureAudio() {
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
        int min = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        recorder = new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(min, 8820)).setAudioPlaybackCaptureConfig(config).build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Captura indisponível");
        recorder.startRecording();
        audio = new Thread(() -> {
            byte[] pcm = new byte[1764];
            while (running && !Thread.currentThread().isInterrupted()) {
                int count = recorder.read(pcm, 0, pcm.length);
                if (count < 0) { status("A captura de áudio foi interrompida."); stopSelf(); break; }
                if (count > 0 && connected && socket != null && socket.queueSize() < 256*1024) {
                    byte[] packet = new byte[count+1]; packet[0] = 2; System.arraycopy(pcm,0,packet,1,count); socket.send(ByteString.of(packet));
                }
            }
        }, "CinemaSom"); audio.start();
    }
    @Override public void onDestroy() {
        running = false; connected = false; active = false;
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) {} }
        if (audio != null) { audio.interrupt(); try { audio.join(500); } catch (InterruptedException ignored) {} }
        if (recorder != null) recorder.release();
        if (socket != null) socket.cancel();
        if (images != null) { images.quitSafely(); try { images.join(500); } catch (InterruptedException ignored) {} }
        if (display != null) display.release();
        if (reader != null) reader.close();
        if (projection != null) projection.stop();
        if (client != null) client.dispatcher().executorService().shutdown();
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
