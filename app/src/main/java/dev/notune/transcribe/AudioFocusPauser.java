package dev.notune.transcribe;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;

/** Requests transient focus only while the keyboard is recording. */
public final class AudioFocusPauser {
    private final AudioManager.OnAudioFocusChangeListener listener = change -> { };
    private AudioFocusRequest request;

    public void request(Context context) {
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) return;
            abandon(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setOnAudioFocusChangeListener(listener).build();
                manager.requestAudioFocus(request);
            } else {
                manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (RuntimeException error) {
            Log.w("VoiceAudioFocus", "Could not request audio focus", error);
        }
    }

    public void abandon(Context context) {
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (request != null) manager.abandonAudioFocusRequest(request);
                request = null;
            } else {
                manager.abandonAudioFocus(listener);
            }
        } catch (RuntimeException error) {
            Log.w("VoiceAudioFocus", "Could not release audio focus", error);
        }
    }
}
