package com.example.scedulealarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.scedulealarm.repository.ScheduleRepository
import com.example.scedulealarm.service.ScheduleForegroundService

class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Log.d(TAG, "Alarm received: action=${intent?.action}")

        val repo = ScheduleRepository(context)
        if (!repo.isServiceEnabled()) {
            Log.d(TAG, "Service is disabled in settings. Skipping update.")
            return
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "nsu:ScheduleAlarmWakeLock"
        )?.apply {
            acquire(5000L) // 5 seconds maximum
        }

        try {
            ScheduleForegroundService.startOrUpdate(context)
        } finally {
            if (wakeLock?.isHeld == true) {
                wakeLock.release()
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULE_TRANSITION = "com.example.scedulealarm.ACTION_SCHEDULE_TRANSITION"
        private const val TAG = "ScheduleAlarmReceiver"
    }
}
