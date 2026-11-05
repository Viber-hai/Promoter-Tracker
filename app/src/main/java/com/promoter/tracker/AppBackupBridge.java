package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class AppBackupBridge {
    private final Context context;

    public AppBackupBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    @JavascriptInterface
    public String saveSnapshot(String json) {
        if (json == null || json.trim().isEmpty()) return "ERROR|No tracker data.";
        if (json.length() > 5 * 1024 * 1024) return "ERROR|Backup is larger than 5 MB.";
        File target = new File(context.getFilesDir(), "promoter_tracker_backup_snapshot.json");
        File temp = new File(context.getFilesDir(), "promoter_tracker_backup_snapshot.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
            if (target.exists() && !target.delete()) return "ERROR|Could not update the app backup snapshot.";
            if (!temp.renameTo(target)) return "ERROR|Could not finish saving the app backup snapshot.";
            return "OK";
        } catch (IOException e) {
            return "ERROR|" + (e.getMessage() == null ? "Could not save the app backup snapshot." : e.getMessage());
        }
    }

    @JavascriptInterface
    public String createManualBackup(String json) {
        try {
            String path = BackupStorage.writeBackup(context, json, "manual");
            String message = "Manual backup saved · " + path;
            context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                    .edit().putString(BackupWorker.KEY_LAST_BACKUP, message).apply();
            return "OK|" + path;
        } catch (Exception e) {
            return "ERROR|" + (e.getMessage() == null ? "Device storage could not save the backup." : e.getMessage());
        }
    }

    @JavascriptInterface
    public String setAutoBackup(boolean enabled, String frequency) {
        String safeFrequency = ("daily".equals(frequency) || "weekly".equals(frequency)
                || "monthly".equals(frequency) || "yearly".equals(frequency)) ? frequency : "weekly";
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(BackupWorker.KEY_ENABLED, enabled)
                .putString(BackupWorker.KEY_FREQUENCY, safeFrequency).apply();
        BackupScheduler.configure(context, enabled, safeFrequency, enabled);
        return enabled ? "OK|Automatic device backup enabled; first backup queued."
                : "OK|Automatic device backup disabled.";
    }

    @JavascriptInterface
    public boolean isAutoBackupEnabled() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getBoolean(BackupWorker.KEY_ENABLED, false);
    }

    @JavascriptInterface
    public String getAutoBackupFrequency() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getString(BackupWorker.KEY_FREQUENCY, "weekly");
    }

    @JavascriptInterface
    public String getLastBackupStatus() {
        return context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE)
                .getString(BackupWorker.KEY_LAST_BACKUP, "Not yet created");
    }
}
