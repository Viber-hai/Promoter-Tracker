package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.JavascriptInterface;

public class AppBackupBridge {
    private final MainActivity activity;
    private final Context context;

    public AppBackupBridge(MainActivity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
    }

    @JavascriptInterface
    public String saveSnapshot(String json) {
        if (json == null || json.trim().isEmpty()) return "ERROR|No tracker data.";
        if (json.length() > 5 * 1024 * 1024) return "ERROR|Backup is larger than 5 MB.";
        java.io.File target = new java.io.File(context.getFilesDir(), "promoter_tracker_backup_snapshot.json");
        java.io.File temp = new java.io.File(context.getFilesDir(), "promoter_tracker_backup_snapshot.tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(temp)) {
            out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.getFD().sync();
            if (target.exists() && !target.delete()) return "ERROR|Could not update the app backup snapshot.";
            if (!temp.renameTo(target)) return "ERROR|Could not finish saving the app backup snapshot.";
            return "OK";
        } catch (java.io.IOException e) {
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
    public void chooseBackupFolder(String kind) {
        if (!"automatic".equals(kind) && !"manual".equals(kind)) return;
        activity.runOnUiThread(() -> activity.launchBackupFolderPicker(kind));
    }

    @JavascriptInterface
    public String getBackupFolderName(String kind) {
        if (!"automatic".equals(kind) && !"manual".equals(kind)) kind = "manual";
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        return prefs.getString("folder_name_" + kind, BackupStorage.defaultFolderLabel(kind));
    }

    @JavascriptInterface
    public String setAutoBackup(boolean enabled, String frequency) {
        String safeFrequency = ("daily".equals(frequency) || "weekly".equals(frequency)
                || "monthly".equals(frequency) || "yearly".equals(frequency)) ? frequency : "weekly";
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        boolean oldEnabled = prefs.getBoolean(BackupWorker.KEY_ENABLED, false);
        String oldFrequency = prefs.getString(BackupWorker.KEY_FREQUENCY, "weekly");
        boolean changed = oldEnabled != enabled || !oldFrequency.equals(safeFrequency);
        prefs.edit().putBoolean(BackupWorker.KEY_ENABLED, enabled)
                .putString(BackupWorker.KEY_FREQUENCY, safeFrequency).apply();

        if (changed) BackupScheduler.configure(context, enabled, safeFrequency);
        return enabled ? "OK|Automatic device backup enabled for " + safeFrequency + " schedule. First backup is due after the selected interval."
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
