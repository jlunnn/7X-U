package com.sevenxu.eq;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 保活 Job：每 15 分鐘由系統喚醒一次，確保懸浮窗服務在生（唔受 doze／被殺影響）。
 * 需要 RECEIVE_BOOT_COMPLETED（setPersisted）才可以在重開機後仍然排程。
 */
public class BootJob extends JobService {

    private static final int JOB_ID = 4821;

    public static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID, new ComponentName(c, BootJob.class))
                    .setPeriodic(15 * 60 * 1000L)          // 最短 15 分鐘
                    .setPersisted(true)                     // 重開機都保留
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
            if (Build.VERSION.SDK_INT >= 28) {
                b.setImportantWhileForeground(true);
            }
            js.schedule(b.build());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            if (getSharedPreferences("zeekreq", MODE_PRIVATE).getBoolean("float_on", true)) {
                Intent i = new Intent(this, FloatEqService.class);
                i.putExtra("why", "job15m");
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            }
        } catch (Throwable ignored) {
        }
        schedule(this);            // 週期性 job 唔會自己重排，手動補
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        schedule(this);
        return true;
    }
}
