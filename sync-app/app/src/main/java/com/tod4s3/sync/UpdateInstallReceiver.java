package com.tod4s3.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION_UPDATE_INSTALL_STATUS = "com.tod4s3.sync.UPDATE_INSTALL_STATUS";
    private static final String TAG = "Tod4S3Sync";

    @Override public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation;
            if (Build.VERSION.SDK_INT >= 33) {
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                //noinspection deprecation
                confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            }
            if (confirmation != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirmation);
            } else {
                Toast.makeText(context, "Abre el instalador de Android para confirmar la actualización.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Tod4S3 Sync se actualizó correctamente.", Toast.LENGTH_LONG).show();
        } else {
            int code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Log.w(TAG, "El instalador de Android rechazó la actualización; estado=" + code);
            Toast.makeText(context, "No se instaló la actualización. La versión actual se mantiene.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
