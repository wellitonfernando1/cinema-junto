package br.com.cinemajunto;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.FrameLayout;

/** Draws the current frame at the current viewport size, including after rotation and IME resize. */
public class FilmView extends FrameLayout {
    private final TextureView texture;
    private final Poster poster;
    private volatile Surface surface;
    private volatile int surfaceGeneration;
    private volatile boolean videoFrame;
    private int videoWidth, videoHeight;
    boolean zoom;

    public FilmView(Context context) {
        super(context); setFocusable(true); setBackgroundColor(Color.BLACK);
        texture = new TextureView(context); texture.setOpaque(false);
        addView(texture, new FrameLayout.LayoutParams(-1, -1));
        poster = new Poster(context); addView(poster, new FrameLayout.LayoutParams(-1, -1));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture t, int w, int h) {
                surface = new Surface(t); surfaceGeneration++; transform();
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture t, int w, int h) { transform(); }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture t) {
                Surface old = surface; surface = null; surfaceGeneration++;
                if (old != null) old.release(); return true;
            }
            public void onSurfaceTextureUpdated(SurfaceTexture t) { videoFrame = true; }
        });
    }
    Surface decoderSurface() { return surface; }
    int surfaceGeneration() { return surfaceGeneration; }
    void setVideoSize(int w, int h) {
        videoWidth = w; videoHeight = h; poster.frame = null; poster.setVisibility(GONE); transform();
    }
    void setFrame(Bitmap bitmap) { poster.frame = bitmap; poster.setVisibility(VISIBLE); poster.invalidate(); }
    void clearFrame() { poster.frame = null; poster.setBackgroundColor(Color.BLACK); poster.setVisibility(VISIBLE); poster.invalidate(); videoFrame = false; }
    void setZoom(boolean enabled) { zoom = enabled; transform(); poster.invalidate(); }
    boolean hasFrame() { return videoFrame || (poster.frame != null && !poster.frame.isRecycled()); }
    private void transform() {
        float vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0 || videoWidth == 0 || videoHeight == 0) return;
        float scale = zoom ? Math.max(vw / videoWidth, vh / videoHeight) : Math.min(vw / videoWidth, vh / videoHeight);
        Matrix matrix = new Matrix();
        matrix.setScale(videoWidth * scale / vw, videoHeight * scale / vh, vw / 2, vh / 2);
        texture.setTransform(matrix);
    }
    private final class Poster extends View {
        Bitmap frame;
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        final RectF destination = new RectF();
        Poster(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            if (frame == null || frame.isRecycled() || getWidth() == 0 || getHeight() == 0) return;
            float scale = zoom ? Math.max((float)getWidth()/frame.getWidth(), (float)getHeight()/frame.getHeight())
                : Math.min((float)getWidth()/frame.getWidth(), (float)getHeight()/frame.getHeight());
            float w = frame.getWidth()*scale, h = frame.getHeight()*scale;
            destination.set((getWidth()-w)/2, (getHeight()-h)/2, (getWidth()+w)/2, (getHeight()+h)/2);
            canvas.drawBitmap(frame, null, destination, paint);
        }
    }
}
