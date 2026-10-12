package br.com.cinemajunto;

import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.os.Build;

/** Separates call speech from movie volume and selects speakerphone unless a headset is connected. */
final class VoiceAudioRoute implements AutoCloseable {
    final AudioManager manager;
    boolean active;
    int previousMode;
    boolean previousSpeaker;
    AudioDeviceInfo previousDevice;
    VoiceAudioRoute(AudioManager manager) { this.manager = manager; }
    synchronized boolean setActive(boolean value) {
        if (!value) { close(); return true; }
        if (active) return true;
        previousMode = manager.getMode();
        previousSpeaker = manager.isSpeakerphoneOn();
        if (Build.VERSION.SDK_INT >= 31) previousDevice = manager.getCommunicationDevice();
        active = true;
        try {
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo speaker = null, headset = null;
                for (AudioDeviceInfo device : manager.getAvailableCommunicationDevices()) {
                    int type = device.getType();
                    if (type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = device;
                    if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                            || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || type == AudioDeviceInfo.TYPE_BLE_HEADSET) headset = device;
                }
                AudioDeviceInfo target = headset != null ? headset : speaker;
                if (target == null || !manager.setCommunicationDevice(target)) { close(); return false; }
            } else {
                boolean headset = manager.isWiredHeadsetOn() || manager.isBluetoothScoOn();
                manager.setSpeakerphoneOn(!headset);
            }
            return true;
        } catch (RuntimeException error) { close(); return false; }
    }
    @Override public synchronized void close() {
        if (!active) return;
        active = false;
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                manager.clearCommunicationDevice();
                if (previousDevice != null && previousMode == AudioManager.MODE_IN_COMMUNICATION)
                    manager.setCommunicationDevice(previousDevice);
            } else manager.setSpeakerphoneOn(previousSpeaker);
        } catch (RuntimeException ignored) {}
        try { manager.setMode(previousMode); } catch (RuntimeException ignored) {}
    }
}
