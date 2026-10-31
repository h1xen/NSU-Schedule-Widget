package com.example.scedulealarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.scedulealarm.repository.ScheduleRepository
import com.example.scedulealarm.service.ScheduleForegroundService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.d(TAG, "BootReceiver triggered with action: $action")

        val repo = ScheduleRepository(context)
        if (repo.isServiceEnabled()) {
            Log.d(TAG, "Schedule widget was enabled. Restoring service after $action")
            ScheduleForegroundService.startOrUpdate(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
