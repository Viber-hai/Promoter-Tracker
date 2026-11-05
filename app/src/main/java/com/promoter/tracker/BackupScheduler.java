package com.promoter.tracker;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

final class BackupScheduler {
    private static final String PERIODIC_NAME = "promoter-tracker-local-auto-backup";
    private static final String INITIAL_NAME = "promoter-tracker-local-backup-now";

    private BackupScheduler() {}

    static void ensureScheduled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(BackupWorker.PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean(BackupWorker.KEY_ENABLED, false)) {
            configure(context, true, prefs.getString(BackupWorker.KEY_FREQUENCY, "weekly"), false);
        }
    }

    static void configure(Context context, boolean enabled, String frequency, boolean runNow) {
        WorkManager manager = WorkManager.getInstance(context);
        if (!enabled) {
            manager.cancelUniqueWork(INITIAL_NAME);
            manager.cancelUniqueWork(PERIODIC_NAME);
            return;
        }

        long days;
        switch (frequency) {
            case "daily": days = 1; break;
            case "monthly": days = 30; break;
            case "yearly": days = 365; break;
            case "weekly":
            default: days = 7; break;
        }

        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                BackupWorker.class, days, TimeUnit.DAYS).build();
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.REPLACE, periodic);

        if (runNow) {
            OneTimeWorkRequest first = new OneTimeWorkRequest.Builder(BackupWorker.class).build();
            manager.enqueueUniqueWork(INITIAL_NAME, ExistingWorkPolicy.REPLACE, first);
        }
    }
}
