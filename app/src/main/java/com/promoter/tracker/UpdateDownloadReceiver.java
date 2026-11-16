package com.promoter.tracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.DownloadManager;

public class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        long expected = context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE)
                .getLong(UpdateManager.KEY_DOWNLOAD_ID, -2);
        if (id < 0 || id != expected) return;
        UpdateManager.installPending(context);
    }
}
