package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.WorkManager;
import androidx.work.PeriodicWorkRequest;

import java.util.concurrent.TimeUnit;

final class BackupScheduler {
    private static final String PERIODIC_NAME = "promoter-tracker-local-auto-backup-v2";
    private static final String LEGACY_NAME = "promoter-tracker-local-auto-backup";

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
        WorkManager manager = WorkManager.getInstance(context);
        // Remove the earlier schedule once, so an old immediate/near-term run cannot remain active.
        manager.cancelUniqueWork(LEGACY_NAME);
        if (!prefs.getBoolean(BackupWorker.KEY_ENABLED, false)) return;

        String frequency = prefs.getString(BackupWorker.KEY_FREQUENCY, "weekly");
        manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, buildRequest(frequency));
    }

    static void configure(Context context, boolean enabled, String frequency) {
        WorkManager manager = WorkManager.getInstance(context);
        manager.cancelUniqueWork(LEGACY_NAME);
        manager.cancelUniqueWork(PERIODIC_NAME);
        if (!enabled) return;

        // No one-time work is queued. The first periodic backup is delayed by the chosen interval.
        manager.enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, buildRequest(frequency));
    }
}
