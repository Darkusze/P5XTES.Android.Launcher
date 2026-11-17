package dev.allofus.fusioncore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

/**
 * Receives the result of the system installer. When the system needs the user to confirm the update it gives us
 * an intent for the confirmation screen; we only have to start it.
 */
public class UpdateInstallReceiver extends BroadcastReceiver {
    private static final String TAG = "P5XTES";

    @Override
    @SuppressWarnings("deprecation")
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                        : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                break;
            }
            case PackageInstaller.STATUS_SUCCESS:
                Log.i(TAG, "Update installed");
                break;
            default:
                Log.w(TAG, "Update failed: status=" + status + " message=" + message);
                Toast.makeText(context,
                        context.getString(R.string.update_install_failed, message != null ? message : String.valueOf(status)),
                        Toast.LENGTH_LONG).show();
        }
    }
}
