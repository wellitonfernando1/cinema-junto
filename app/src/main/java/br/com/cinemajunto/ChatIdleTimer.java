package br.com.cinemajunto;

import android.os.Handler;

/** One cancellable deadline shared by typing, sending and scrolling. */
final class ChatIdleTimer {
    static final long DELAY_MS = 3000;
    private final Handler handler;
    private final Runnable close;
    ChatIdleTimer(Handler handler, Runnable close) { this.handler = handler; this.close = close; }
    void touch() { cancel(); handler.postDelayed(close, DELAY_MS); }
    void cancel() { handler.removeCallbacks(close); }
}
