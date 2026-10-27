package br.com.cinemajunto;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.FrameLayout;

/** Draws the current frame at the current viewport size, including after rotation and IME resize. */
public class FilmView extends FrameLayout {
    private final SurfaceView texture;
    private final Poster poster;
    private volatile Surface surface;
    private volatile int surfaceGeneration;
    private volatile boolean videoFrame;
    private int videoWidth, videoHeight;
    private float frameRate;
    boolean zoom, fitForChat;

    public FilmView(Context context) {
        super(context); setFocusable(true); setBackgroundColor(Color.BLACK);
        texture = new SurfaceView(context);
        addView(texture, new FrameLayout.LayoutParams(-1, -1));
        poster = new Poster(context); addView(poster, new FrameLayout.LayoutParams(-1, -1));
        texture.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface(); surfaceGeneration++; applyFrameRate(); transform();
            }
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { transform(); }
            public void surfaceDestroyed(SurfaceHolder holder) { surface = null; surfaceGeneration++; }
        });
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh); transform();
    }
    void setMovieFrameRate(float fps) { frameRate = fps; applyFrameRate(); }
    private void applyFrameRate() {
        Surface target = surface;
        if (target == null || !target.isValid() || android.os.Build.VERSION.SDK_INT < 30) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31)
                target.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            else target.setFrameRate(frameRate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
        } catch (IllegalArgumentException | IllegalStateException ignored) { }
    }
    void markVideoFrame() { videoFrame = true; }
    Surface decoderSurface() { return surface; }
    int surfaceGeneration() { return surfaceGeneration; }
    void setVideoSize(int w, int h) {
        videoWidth = w; videoHeight = h;
        // The codec always draws into source-sized buffers. Window/IME resizing is
        // a compositor transform, not a reallocation of the decoder's buffers.
        texture.getHolder().setFixedSize(w, h);
        poster.frame = null; poster.setVisibility(GONE); transform();
    }
    void setFrame(Bitmap bitmap) { poster.frame = bitmap; poster.setVisibility(VISIBLE); poster.invalidate(); }
    void clearFrame() { poster.frame = null; poster.setBackgroundColor(Color.BLACK); poster.setVisibility(VISIBLE); poster.invalidate(); videoFrame = false; }
    void setZoom(boolean enabled) { zoom = enabled; transform(); poster.invalidate(); }
    void setFitForChat(boolean fit) { fitForChat = fit; transform(); poster.invalidate(); }
    boolean useZoom() { return zoom && !fitForChat; }
    boolean hasFrame() { return videoFrame || (poster.frame != null && !poster.frame.isRecycled()); }
    private void transform() {
        float vw = getWidth(), vh = getHeight();
        if (vw == 0 || vh == 0 || videoWidth == 0 || videoHeight == 0) return;
        float scale = useZoom() ? Math.max(vw / videoWidth, vh / videoHeight) : Math.min(vw / videoWidth, vh / videoHeight);
        int w = Math.max(1, Math.round(videoWidth * scale)), h = Math.max(1, Math.round(videoHeight * scale));
        FrameLayout.LayoutParams old = (FrameLayout.LayoutParams)texture.getLayoutParams();
        if (old.width != w || old.height != h || old.gravity != Gravity.CENTER)
            texture.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }
    private final class Poster extends View {
        Bitmap frame;
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final RectF destination = new RectF();
        Poster(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            if (frame == null || frame.isRecycled() || getWidth() == 0 || getHeight() == 0) return;
            float scale = useZoom() ? Math.max((float)getWidth()/frame.getWidth(), (float)getHeight()/frame.getHeight())
                : Math.min((float)getWidth()/frame.getWidth(), (float)getHeight()/frame.getHeight());
            float w = frame.getWidth()*scale, h = frame.getHeight()*scale;
            destination.set((getWidth()-w)/2, (getHeight()-h)/2, (getWidth()+w)/2, (getHeight()+h)/2);
            canvas.drawBitmap(frame, null, destination, paint);
        }
    }
}
