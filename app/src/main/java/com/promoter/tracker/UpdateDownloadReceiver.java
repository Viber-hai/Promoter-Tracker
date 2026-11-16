package com.promoter.tracker;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;

public class UpdateDownloadReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        long expected = context.getSharedPreferences(UpdateManager.PREFS, Context.MODE_PRIVATE).getLong(UpdateManager.KEY_DOWNLOAD_ID, -2);
        if (id < 0 || id != expected) return;
        DownloadManager dm = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) { UpdateManager.notifyDownloadFailed(context); return; }
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) UpdateManager.notifyDownloadReady(context);
            else UpdateManager.notifyDownloadFailed(context);
        } catch (Exception e) { UpdateManager.notifyDownloadFailed(context); }
    }
}