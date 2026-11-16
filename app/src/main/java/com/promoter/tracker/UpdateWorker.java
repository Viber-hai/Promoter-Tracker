package com.promoter.tracker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONObject;

public class UpdateWorker extends Worker {
    public UpdateWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }
    @NonNull @Override public Result doWork() {
        try {
            JSONObject latest = UpdateManager.fetchLatestRelease();
            int latestCode = UpdateManager.releaseVersionCode(latest);
            int currentCode = UpdateManager.currentVersionCode(getApplicationContext());
            if (latestCode > currentCode) UpdateManager.notifyUpdate(getApplicationContext(), latest);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
