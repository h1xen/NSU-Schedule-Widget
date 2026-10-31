package com.example.scedulealarm.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.scedulealarm.notification.ScheduleNotificationHelper
import com.example.scedulealarm.repository.ScheduleRepository

class ScheduleForegroundService : Service {

    constructor() : super()

    private lateinit var repository: ScheduleRepository
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        repository = ScheduleRepository(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Log.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_UPDATE
        Log.d(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_STOP -> {
                repository.setServiceEnabled(false)
                ScheduleAlarmScheduler.cancelAlarm(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(ScheduleNotificationHelper.NOTIFICATION_ID)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                repository.setServiceEnabled(true)
                updateAndSchedule(isForegroundStart = true)
            }

            ACTION_UPDATE -> {
                updateAndSchedule(isForegroundStart = false)
            }
        }

        return START_STICKY
    }

    private fun updateAndSchedule(isForegroundStart: Boolean) {
        val schedule = repository.getCachedSchedule()
        val selectedGroup = repository.getSelectedGroup()
        val groupName = schedule?.groupName ?: "Группа ${selectedGroup.name}"
        val state = repository.getCurrentScheduleState()

        val notification = ScheduleNotificationHelper.buildNotification(this, state, groupName)

        if (isForegroundStart) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        ScheduleNotificationHelper.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(
                        ScheduleNotificationHelper.NOTIFICATION_ID,
                        notification,
                        0
                    )
                }
            } else {
                startForeground(ScheduleNotificationHelper.NOTIFICATION_ID, notification)
            }
        }
        
        // Always notify manager to force immediate RemoteViews update in SystemUI/Lockscreen
        notificationManager.notify(ScheduleNotificationHelper.NOTIFICATION_ID, notification)

        // Schedule exact alarm for next state transition (start or end of lesson, or next day)
        val nextTransitionMillis = repository.getNextTransitionTime()
        ScheduleAlarmScheduler.scheduleNextAlarm(this, nextTransitionMillis)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
    }

    companion object {
        const val ACTION_START = "com.example.scedulealarm.ACTION_START"
        const val ACTION_STOP = "com.example.scedulealarm.ACTION_STOP"
        const val ACTION_UPDATE = "com.example.scedulealarm.ACTION_UPDATE"
        private const val TAG = "ScheduleForegroundSvc"

        fun startOrUpdate(context: Context) {
            val intent = Intent(context, ScheduleForegroundService::class.java).apply {
                action = ACTION_START
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${e.message}")
            }
        }

        fun updateNotification(context: Context) {
            val intent = Intent(context, ScheduleForegroundService::class.java).apply {
                action = ACTION_UPDATE
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update foreground service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ScheduleForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: ${e.message}")
            }
        }
    }
}
