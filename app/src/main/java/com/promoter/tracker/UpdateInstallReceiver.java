package com.promoter.tracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationManagerCompat;

public class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE).edit()
                .remove(UpdateManager.KEY_DOWNLOAD_ID).remove(UpdateManager.KEY_EXPECTED_VERSION).apply();
        NotificationManagerCompat.from(context).cancel(UpdateManager.READY_NOTIFICATION_ID);
    }
}