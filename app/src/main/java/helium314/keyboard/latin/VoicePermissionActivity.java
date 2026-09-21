// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/** Requests the microphone permission on behalf of the input method service. */
public final class VoicePermissionActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
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
        finish();
    }
}
