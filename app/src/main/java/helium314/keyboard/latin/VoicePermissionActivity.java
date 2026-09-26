// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** Requests the microphone permission on behalf of the input method service and reports the answer. */
public final class VoicePermissionActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1;

    public static final int GRANTED = 0;
    /** Denied, but Android will show the dialog again on the next request. */
    public static final int DENIED = 1;
    /** Denied, and Android shows no dialog any more (two denials); only App info can grant it. */
    public static final int DENIED_PERMANENTLY = 2;

    public interface ResultListener {
        void onVoicePermissionResult(int result);
    }

    @Nullable private static ResultListener sListener;

    /** Asks for the permission; {@code listener} gets at most one answer, on the main thread. */
    public static void request(final Context context, final ResultListener listener) {
        sListener = listener;
        final Intent intent = new Intent(context, VoicePermissionActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** An empty result means the dialog was dismissed; that is an ordinary denial. */
    static int classify(final int[] grantResults, final boolean mayAskAgain) {
        if (grantResults.length == 0) return DENIED;
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) return GRANTED;
        return mayAskAgain ? DENIED : DENIED_PERMANENTLY;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            report(GRANTED);
            finish();
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_RECORD_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
            final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        report(classify(grantResults, ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.RECORD_AUDIO)));
        finish();
    }

    private static void report(final int result) {
        final ResultListener listener = sListener;
        sListener = null;
        if (listener != null) listener.onVoicePermissionResult(result);
    }
}
