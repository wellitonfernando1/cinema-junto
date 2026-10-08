package br.com.cinemajunto;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.util.ArrayDeque;

/** Small windows owned by the capture service; removed as soon as transmission stops. */
final class HostChatOverlay {
    interface Sender { boolean send(String text); }
    final Context context;
    final WindowManager windows;
    final Sender sender;
    final Handler main = new Handler(Looper.getMainLooper());
    final ArrayDeque<String> history = new ArrayDeque<>();
    Button bubble;
    TextView banner, recent;
    Composer composer;
    EditText input;
    WindowManager.LayoutParams bubbleParams;
    boolean closed, keyboardSeen;
    int bannerSequence;
    String draft = "";
    int draftCursor;
    final Runnable idleClose = this::closeComposer;
    final Runnable keyboardOpen = this::openKeyboardNow;

    HostChatOverlay(Context context, Sender sender) {
        this.context = context; this.sender = sender; windows = context.getSystemService(WindowManager.class);
    }
    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    WindowManager.LayoutParams params(int width, int height, int gravity, boolean focusable) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | (focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
            PixelFormat.TRANSLUCENT);
        p.gravity = gravity;
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        if (Build.VERSION.SDK_INT >= 30) p.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | (focusable ? WindowInsets.Type.ime() : 0));
        return p;
    }
    void show() {
        if (closed || bubble != null || !Settings.canDrawOverlays(context)) return;
        bubble = new Button(context); bubble.setText("Chat"); bubble.setTextSize(13); bubble.setTextColor(0xffffffff);
        bubble.setBackgroundColor(0xcc292929); bubble.setContentDescription("Abrir chat flutuante");
        bubble.setMinWidth(0); bubble.setMinimumWidth(0); bubble.setPadding(dp(8), 0, dp(8), 0);
        bubbleParams = params(dp(64), dp(44), Gravity.TOP | Gravity.END, false);
        bubbleParams.x = dp(10); bubbleParams.y = dp(80);
        bubble.setOnClickListener(v -> open());
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float x, y; int originalX, originalY; boolean moved;
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    x = event.getRawX(); y = event.getRawY(); originalX = bubbleParams.x; originalY = bubbleParams.y; moved = false; return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    float dx = event.getRawX() - x, dy = event.getRawY() - y;
                    moved |= Math.abs(dx) + Math.abs(dy) > dp(8);
                    if (moved) {
                        bubbleParams.x = Math.max(0, Math.min(context.getResources().getDisplayMetrics().widthPixels - dp(64), originalX - Math.round(dx)));
                        bubbleParams.y = Math.max(0, Math.min(context.getResources().getDisplayMetrics().heightPixels - dp(90), originalY + Math.round(dy)));
                        try { windows.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {}
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) { if (!moved) v.performClick(); return true; }
                return true;
            }
        });
        try { windows.addView(bubble, bubbleParams); } catch (Exception e) { bubble = null; }
    }
    void receive(String from, String text) {
        if (closed) return;
        String line = ("host".equals(from) ? "Você: " : "Amigo: ") + text;
        history.addLast(line); while (history.size() > 20) history.removeFirst();
        if (recent != null) recent.setText(line);
        show(); if (bubble != null && composer == null) bubble.setText("Chat •");
        removeBanner();
        banner = new TextView(context); banner.setText(line); banner.setTextColor(0xffffffff); banner.setTextSize(16);
        banner.setMaxLines(2); banner.setPadding(dp(12), dp(8), dp(12), dp(8)); banner.setBackgroundColor(0xff202020);
        banner.setContentDescription("Mensagem no topo: " + line);
        WindowManager.LayoutParams p = params(Math.max(dp(80), context.getResources().getDisplayMetrics().widthPixels - dp(80)), -2, Gravity.TOP | Gravity.LEFT, false);
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        // Allow touches to the film through the transient banner on Android 12 and newer.
        p.alpha = 0.7f; p.x = dp(8); p.y = dp(8);
        try { windows.addView(banner, p); } catch (Exception e) { banner = null; }
        int sequence = ++bannerSequence;
        main.postDelayed(() -> { if (sequence == bannerSequence) removeBanner(); }, 5000);
    }
    void open() {
        if (closed || !Settings.canDrawOverlays(context)) return;
        show(); if (composer != null) { input.requestFocus(); showKeyboard(); resetIdleTimeout(); return; }
        keyboardSeen = false;
        composer = new Composer(context); composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(6), 0, dp(6), 0); composer.setBackgroundColor(0xff171717);
        recent = new TextView(context); recent.setTextColor(0xffffffff); recent.setTextSize(13); recent.setMaxLines(1);
        recent.setText(history.isEmpty() ? "Chat com seu amigo" : history.getLast()); recent.setPadding(dp(8), dp(4), dp(8), 0);
        composer.addView(recent);
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        input = new EditText(context); input.setSingleLine(true); input.setTextSize(16); input.setHint("Mensagem");
        input.setTextColor(0xffffffff); input.setHintTextColor(0xffbbbbbb);
        input.setContentDescription("Mensagem de quem transmite"); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(280)});
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        input.setText(draft); input.setSelection(Math.min(draftCursor, input.length()));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {}
            public void afterTextChanged(Editable text) { resetIdleTimeout(); }
        });
        input.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_SEND) { send(); return true; } return false; });
        row.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button send = compactButton("Enviar"); send.setOnClickListener(v -> send()); row.addView(send, new LinearLayout.LayoutParams(dp(70), dp(44)));
        Button close = compactButton("×"); close.setContentDescription("Fechar chat flutuante"); close.setOnClickListener(v -> closeComposer());
        row.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44))); composer.addView(row);
        LinearLayout emojis = new LinearLayout(context);
        for (String emoji : new String[]{"😀", "😂", "😍", "❤️", "👍"}) {
            Button b = compactButton(emoji); b.setOnClickListener(v -> {
                resetIdleTimeout();
                if (input != null) input.getText().insert(Math.max(0, input.getSelectionStart()), emoji);
            });
            emojis.addView(b, new LinearLayout.LayoutParams(0, dp(32), 1));
        }
        composer.addView(emojis);
        composer.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                boolean visible = insets.isVisible(WindowInsets.Type.ime());
                if (visible) keyboardSeen = true;
                if (keyboardSeen && !visible) main.post(() -> { if (composer == view) closeComposer(); });
            }
            return insets;
        });
        try {
            windows.addView(composer, params(-1, -2, Gravity.BOTTOM, true));
            if (bubble != null) { bubble.setText("Chat"); bubble.setVisibility(View.GONE); }
            input.requestFocus(); showKeyboard(); resetIdleTimeout();
        } catch (Exception e) { closeComposer(); }
    }
    Button compactButton(String text) {
        Button b = new Button(context); b.setText(text); b.setTextSize(13); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(3), 0, dp(3), 0); return b;
    }
    void showKeyboard() {
        main.removeCallbacks(keyboardOpen);
        main.postDelayed(keyboardOpen, 200);
    }
    void openKeyboardNow() {
        if (closed || input == null || composer == null || !input.hasWindowFocus()) return;
        input.requestFocus();
        if (Build.VERSION.SDK_INT >= 30 && input.getWindowInsetsController() != null)
            input.getWindowInsetsController().show(WindowInsets.Type.ime());
        else context.getSystemService(InputMethodManager.class).showSoftInput(input, 0);
    }
    void resetIdleTimeout() {
        main.removeCallbacks(idleClose);
        if (!closed && composer != null) main.postDelayed(idleClose, 5000);
    }
    void send() {
        resetIdleTimeout();
        if (input == null) return;
        String text = input.getText().toString().trim(); if (text.isEmpty()) return;
        if (sender.send(text)) input.setText("");
        else if (recent != null) recent.setText("Chat desconectado. Encerre e tente novamente.");
    }
    void closeComposer() {
        main.removeCallbacks(idleClose); main.removeCallbacks(keyboardOpen);
        if (composer == null) return;
        if (input != null) {
            draft = input.getText().toString();
            draftCursor = Math.max(0, input.getSelectionStart());
        }
        Composer old = composer; composer = null; keyboardSeen = false;
        if (input != null) context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        remove(old); input = null; recent = null;
        if (bubble != null) bubble.setVisibility(View.VISIBLE);
    }
    void onConfigurationChanged() {
        closeComposer(); removeBanner();
        if (bubble != null) {
            bubbleParams.x = dp(10); bubbleParams.y = dp(80);
            try { windows.updateViewLayout(bubble, bubbleParams); } catch (Exception ignored) {}
        }
    }
    void removeBanner() { if (banner != null) { remove(banner); banner = null; } }
    void remove(View view) { try { windows.removeViewImmediate(view); } catch (Exception ignored) {} }
    void close() {
        closed = true; main.removeCallbacksAndMessages(null); closeComposer(); removeBanner();
        if (bubble != null) { remove(bubble); bubble = null; }
    }
    final class Composer extends LinearLayout {
        OnBackInvokedDispatcher backDispatcher;
        OnBackInvokedCallback backCallback;
        Composer(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE)
                resetIdleTimeout();
            return super.dispatchTouchEvent(event);
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (focused && composer == this) {
                showKeyboard();
                if (Build.VERSION.SDK_INT >= 33 && backDispatcher == null) {
                    backDispatcher = findOnBackInvokedDispatcher();
                    if (backDispatcher != null) {
                        backCallback = HostChatOverlay.this::closeComposer;
                        backDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback);
                    }
                }
            }
        }
        @Override protected void onDetachedFromWindow() {
            if (Build.VERSION.SDK_INT >= 33 && backDispatcher != null && backCallback != null)
                backDispatcher.unregisterOnBackInvokedCallback(backCallback);
            super.onDetachedFromWindow();
        }
        @Override public boolean dispatchKeyEventPreIme(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) { closeComposer(); return true; }
            return super.dispatchKeyEventPreIme(event);
        }
    }
}
