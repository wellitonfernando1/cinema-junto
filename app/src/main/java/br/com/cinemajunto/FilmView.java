package br.com.cinemajunto;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Draws the current frame at the current viewport size, including after rotation and IME resize. */
public class FilmView extends View {
    private Bitmap frame;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect source = new Rect();
    private final RectF destination = new RectF();
    boolean zoom;

    public FilmView(Context context) { super(context); setFocusable(true); setBackgroundColor(Color.BLACK); }
    void setFrame(Bitmap bitmap) {
        // A bitmap submitted to a hardware canvas must not be recycled while the GPU may still use it.
        frame = bitmap; source.set(0, 0, bitmap.getWidth(), bitmap.getHeight()); invalidate();
    }
    void clearFrame() { frame = null; invalidate(); }
    void setZoom(boolean enabled) { zoom = enabled; invalidate(); }
    boolean hasFrame() { return frame != null && !frame.isRecycled(); }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { super.onSizeChanged(w, h, ow, oh); invalidate(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasFrame() || getWidth() == 0 || getHeight() == 0) return;
        float sx = (float)getWidth() / source.width(), sy = (float)getHeight() / source.height();
        float scale = zoom ? Math.max(sx, sy) : Math.min(sx, sy);
        float w = source.width() * scale, h = source.height() * scale;
        destination.set((getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
        canvas.drawBitmap(frame, source, destination, paint);
    }
}
