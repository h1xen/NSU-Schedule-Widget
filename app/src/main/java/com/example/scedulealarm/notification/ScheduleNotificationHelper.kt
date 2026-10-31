package com.example.scedulealarm.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.example.scedulealarm.MainActivity
import com.example.scedulealarm.R
import com.example.scedulealarm.data.model.ScheduleState

object ScheduleNotificationHelper {

    // Use v3 so Android applies IMPORTANCE_HIGH immediately without keeping old channel cache
    const val CHANNEL_ID = "nsu_schedule_lockscreen_v3"
    const val NOTIFICATION_ID = 26946

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Delete legacy lower-importance channels if present
            try {
                notificationManager.deleteNotificationChannel("nsu_schedule_lockscreen_channel")
                notificationManager.deleteNotificationChannel("nsu_schedule_channel")
            } catch (e: Exception) {
                // Ignore
            }

            val name = context.getString(R.string.notification_channel_name)
            val descriptionText = context.getString(R.string.notification_channel_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(
        context: Context,
        state: ScheduleState,
        groupName: String = "Группа 26946"
    ): Notification {
        createNotificationChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val compactViews = RemoteViews(context.packageName, R.layout.notification_schedule_compact)
        val expandedViews = RemoteViews(context.packageName, R.layout.notification_schedule_expanded)

        // Populate both compact and expanded layouts with live data & chronometer
        populateRemoteViews(compactViews, state, groupName, isExpanded = false)
        populateRemoteViews(expandedViews, state, groupName, isExpanded = true)

        val targetWhenMillis: Long? = when (state) {
            is ScheduleState.BeforeLesson -> state.startsAtMillis
            is ScheduleState.DuringLesson -> state.endsAtMillis
            else -> null
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setCustomContentView(compactViews)
            .setCustomBigContentView(expandedViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(contentPendingIntent)

        if (targetWhenMillis != null) {
            builder.setWhen(targetWhenMillis)
            builder.setShowWhen(true)
            builder.setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                builder.setChronometerCountDown(true)
            }
        } else {
            builder.setShowWhen(false)
        }

        return builder.build()
    }

    private fun populateRemoteViews(
        remoteViews: RemoteViews,
        state: ScheduleState,
        groupName: String,
        isExpanded: Boolean
    ) {
        if (isExpanded) {
            remoteViews.setTextViewText(R.id.tv_header_tag, "• $groupName")
        }

        when (state) {
            is ScheduleState.BeforeLesson -> {
                remoteViews.setViewVisibility(R.id.layout_room_badge, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.tv_lesson_type, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.tv_status_label, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.chronometer_timer, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.tv_static_info, View.GONE)

                remoteViews.setTextViewText(R.id.tv_room_badge, state.roomText)
                remoteViews.setTextViewText(R.id.tv_subject, state.subjectText)
                remoteViews.setTextViewText(R.id.tv_lesson_type, state.typeText)
                remoteViews.setTextViewText(R.id.tv_status_label, state.statusTitle)

                val timeDeltaMillis = state.startsAtMillis - System.currentTimeMillis()
                val baseElapsedRealtime = SystemClock.elapsedRealtime() + timeDeltaMillis
                remoteViews.setChronometer(R.id.chronometer_timer, baseElapsedRealtime, null, true)
                remoteViews.setChronometerCountDown(R.id.chronometer_timer, true)

                if (isExpanded) {
                    remoteViews.setViewVisibility(R.id.tv_lesson_time, View.VISIBLE)
                    remoteViews.setTextViewText(
                        R.id.tv_lesson_time,
                        "${state.lesson.slotNumber} пара • ${state.lesson.startTime} - ${state.lesson.endTime}"
                    )
                    if (state.lesson.tutor.isNotBlank()) {
                        remoteViews.setViewVisibility(R.id.tv_tutor, View.VISIBLE)
                        remoteViews.setTextViewText(R.id.tv_tutor, "Преп: ${state.lesson.tutor}")
                    } else {
                        remoteViews.setViewVisibility(R.id.tv_tutor, View.GONE)
                    }
                }
            }

            is ScheduleState.DuringLesson -> {
                remoteViews.setViewVisibility(R.id.layout_room_badge, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.tv_lesson_type, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.tv_status_label, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.chronometer_timer, View.VISIBLE)
                remoteViews.setViewVisibility(R.id.tv_static_info, View.GONE)

                remoteViews.setTextViewText(R.id.tv_room_badge, state.roomText)
                remoteViews.setTextViewText(R.id.tv_subject, state.subjectText)
                remoteViews.setTextViewText(R.id.tv_lesson_type, state.typeText)
                remoteViews.setTextViewText(R.id.tv_status_label, state.statusTitle)

                val timeDeltaMillis = state.endsAtMillis - System.currentTimeMillis()
                val baseElapsedRealtime = SystemClock.elapsedRealtime() + timeDeltaMillis
                remoteViews.setChronometer(R.id.chronometer_timer, baseElapsedRealtime, null, true)
                remoteViews.setChronometerCountDown(R.id.chronometer_timer, true)

                if (isExpanded) {
                    remoteViews.setViewVisibility(R.id.tv_lesson_time, View.VISIBLE)
                    remoteViews.setTextViewText(
                        R.id.tv_lesson_time,
                        "${state.lesson.slotNumber} пара • ${state.lesson.startTime} - ${state.lesson.endTime}"
                    )
                    if (state.lesson.tutor.isNotBlank()) {
                        remoteViews.setViewVisibility(R.id.tv_tutor, View.VISIBLE)
                        remoteViews.setTextViewText(R.id.tv_tutor, "Преп: ${state.lesson.tutor}")
                    } else {
                        remoteViews.setViewVisibility(R.id.tv_tutor, View.GONE)
                    }
                }
            }

            is ScheduleState.DayFinished,
            is ScheduleState.NoLessonsToday -> {
                // Night mode or after last lesson: collapse completely to a clean single-line status
                remoteViews.setViewVisibility(R.id.layout_room_badge, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_lesson_type, View.GONE)
                remoteViews.setViewVisibility(R.id.chronometer_timer, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_status_label, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_static_info, View.GONE)

                remoteViews.setTextViewText(R.id.tv_subject, "Пар на сегодня больше нет")

                if (isExpanded) {
                    remoteViews.setViewVisibility(R.id.tv_lesson_time, View.GONE)
                    remoteViews.setViewVisibility(R.id.tv_tutor, View.GONE)
                }
            }

            is ScheduleState.NoScheduleData -> {
                remoteViews.setViewVisibility(R.id.layout_room_badge, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_lesson_type, View.GONE)
                remoteViews.setViewVisibility(R.id.chronometer_timer, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_status_label, View.GONE)
                remoteViews.setViewVisibility(R.id.tv_static_info, View.GONE)
                remoteViews.setTextViewText(R.id.tv_subject, "Нажмите 'Обновить'")
            }
        }
    }
}
