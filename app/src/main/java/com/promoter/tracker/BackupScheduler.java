package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.WorkManager;
import androidx.work.PeriodicWorkRequest;

import java.util.concurrent.TimeUnit;

final class BackupScheduler {
    private static final String PERIODIC_NAME = "promoter-tracker-auto-backup-v3";
    private static final String LEGACY_NAME = "promoter-tracker-local-auto-backup";
    private static final String PREVIOUS_NAME = "promoter-tracker-local-auto-backup-v2";

    private BackupScheduler() {}

    private static long intervalDays(String frequency) {
        switch (frequency) {
            case "daily": return 1;
            case "monthly": return 30;
            case "yearly": return 365;
            case "weekly":
            default: return 7;
        }
    }

    private static PeriodicWorkRequest buildRequest(String frequency) {
        long days = intervalDays(frequency);
        return new PeriodicWorkRequest.Builder(BackupWorker.class, days, TimeUnit.DAYS)
                .setInitialDelay(days, TimeUnit.DAYS)
                .build();
    }

    static void ensureScheduled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        // Migration safety: if an older installation had both destinations enabled,
        // preserve local backups and turn off Drive rather than running both.
        if (prefs.getBoolean(BackupWorker.KEY_ENABLED, false)
                && prefs.getBoolean("drive_auto_enabled", false)) {
            prefs.edit().putBoolean("drive_auto_enabled", false).commit();
        }
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(LEGACY_NAME);
        manager.cancelUniqueWork(PREVIOUS_NAME);
        boolean localEnabled = prefs.getBoolean(BackupWorker.KEY_ENABLED, false);
        boolean driveEnabled = prefs.getBoolean("drive_auto_enabled", false) && !localEnabled;
        if (!localEnabled && !driveEnabled) return;
        String frequency = localEnabled
                ? prefs.getString(BackupWorker.KEY_FREQUENCY, "weekly")
                : prefs.getString("drive_frequency", "weekly");
        // KEEP preserves the existing countdown when the app is reopened.
        manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, buildRequest(frequency));
    }

    static void configure(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(LEGACY_NAME);
        manager.cancelUniqueWork(PREVIOUS_NAME);
        manager.cancelUniqueWork(PERIODIC_NAME);

        boolean localEnabled = prefs.getBoolean(BackupWorker.KEY_ENABLED, false);
        boolean driveEnabled = prefs.getBoolean("drive_auto_enabled", false) && !localEnabled;
        if (!localEnabled && !driveEnabled) return;

        String frequency = localEnabled
                ? prefs.getString(BackupWorker.KEY_FREQUENCY, "weekly")
                : prefs.getString("drive_frequency", "weekly");
        manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, buildRequest(frequency));
    }
}
