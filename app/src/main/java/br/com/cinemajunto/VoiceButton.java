package br.com.cinemajunto;

import android.content.Context;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

/** Hold-to-talk control. Every end or cancellation path releases the microphone. */
final class VoiceButton extends Button {
    final Runnable pressAction, releaseAction;
    boolean held;
    int pointer = -1;

    VoiceButton(Context context, Runnable press, Runnable release) {
        super(context); pressAction = press; releaseAction = release;
        setTextSize(13); setTextColor(0xffffffff); setMinWidth(0); setMinimumWidth(0);
        int padding = Math.round(8 * getResources().getDisplayMetrics().density);
        setPadding(padding, 0, padding, 0); setTalking(false);
        setContentDescription("Segure para falar com seu amigo. Solte para encerrar a voz.");
    }

    void setTalking(boolean active) {
        if (Looper.myLooper() != Looper.getMainLooper()) { post(() -> setTalking(active)); return; }
        setText(active ? "Falando… solte" : "🎙 Segure para falar");
        setBackgroundColor(active ? 0xdd9b2424 : 0xdd303030);
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
