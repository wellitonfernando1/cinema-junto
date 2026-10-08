package br.com.cinemajunto;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;

/** Hold-to-talk control. Every end or cancellation path releases the microphone. */
final class VoiceButton extends ImageButton {
    final Runnable pressAction, releaseAction;
    boolean held;
    int pointer = -1;

    VoiceButton(Context context, Runnable press, Runnable release) {
        super(context); pressAction = press; releaseAction = release;
        setMinimumWidth(0); setMinimumHeight(0);
        int padding = Math.round(14 * getResources().getDisplayMetrics().density);
        setPadding(padding, padding, padding, padding);
        setImageResource(R.drawable.ic_microphone); setScaleType(ScaleType.CENTER_INSIDE);
        setImageTintList(ColorStateList.valueOf(0xffffffff));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_selected}, circle(0xffb71c1c));
        background.addState(new int[]{android.R.attr.state_pressed}, circle(0xffb71c1c));
        background.addState(new int[]{}, circle(0xffe53935));
        setBackground(background); setBackgroundTintList(null);
        setElevation(4 * getResources().getDisplayMetrics().density); setTalking(false);
    }

    GradientDrawable circle(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL); shape.setColor(color); return shape;
    }

    void setTalking(boolean active) {
        if (Looper.myLooper() != Looper.getMainLooper()) { post(() -> setTalking(active)); return; }
        setSelected(active);
        setContentDescription(active ? "Falando. Solte para encerrar a voz."
            : "Segure para falar com seu amigo. Solte para encerrar a voz.");
    }

    void begin() {
        if (held || !isEnabled()) return;
        held = true; setPressed(true); setTalking(true);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        pressAction.run();
    }

    void end() {
        if (!held) return;
        held = false; pointer = -1; setPressed(false); setTalking(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        releaseAction.run();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0); begin(); return true;
            case MotionEvent.ACTION_MOVE:
                if (held && event.findPointerIndex(pointer) < 0) end();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) end();
                return true;
            case MotionEvent.ACTION_UP:
                end(); performClick(); return true;
            case MotionEvent.ACTION_CANCEL:
                end(); return true;
            default: return held;
        }
    }

    @Override public boolean performClick() { return super.performClick(); }

    boolean voiceKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            || keyCode == KeyEvent.KEYCODE_SPACE;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (voiceKey(keyCode)) { if (event.getRepeatCount() == 0) begin(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (voiceKey(keyCode)) { end(); return true; }
        return super.onKeyUp(keyCode, event);
    }

    @Override public void setEnabled(boolean enabled) {
        if (!enabled) end();
        super.setEnabled(enabled);
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        if (visibility != View.VISIBLE) end();
        super.onVisibilityChanged(changedView, visibility);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        if (!focused) end();
        super.onWindowFocusChanged(focused);
    }

    @Override protected void onDetachedFromWindow() { end(); super.onDetachedFromWindow(); }
}
