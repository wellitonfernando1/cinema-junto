package br.com.cinemajunto;

import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;

/** Ducks other apps' movie audio without changing the media volume used by received speech. */
final class VoiceMovieFocus implements AutoCloseable {
    final AudioManager manager;
    final AudioFocusRequest request;
    boolean held;
    VoiceMovieFocus(AudioManager manager, Handler handler) {
        this.manager = manager;
        request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(change -> {}, handler)
            .setAcceptsDelayedFocusGain(false).build();
    }
    boolean setListening(boolean listening) {
        if (!listening) { close(); return true; }
        if (held) return true;
        held = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return held;
    }
    @Override public void close() {
        if (held) { manager.abandonAudioFocusRequest(request); held = false; }
    }
}
