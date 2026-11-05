package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.WorkManager;
import androidx.work.PeriodicWorkRequest;

import java.util.concurrent.TimeUnit;

final class BackupScheduler {
    private static final String PERIODIC_NAME = "promoter-tracker-local-auto-backup";

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
        return new PeriodicWorkRequest.Builder(
                BackupWorker.class, intervalDays(frequency), TimeUnit.DAYS).build();
    }

    // Called whenever the app opens/resumes. KEEP prevents restarting the countdown.
    static void ensureScheduled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(BackupWorker.KEY_ENABLED, false)) return;

        String frequency = prefs.getString(BackupWorker.KEY_FREQUENCY, "weekly");
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, buildRequest(frequency));
    }

    // Called only when the user explicitly saves the backup settings.
    static void configure(Context context, boolean enabled, String frequency) {
        WorkManager manager = WorkManager.getInstance(context);
        if (!enabled) {
            manager.cancelUniqueWork(PERIODIC_NAME);
            return;
        }

        // Replace only on a deliberate settings save, e.g. when frequency changes.
        // WorkManager runs the first periodic execution as soon as its scheduling window allows.
        manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.REPLACE, buildRequest(frequency));
    }
}
