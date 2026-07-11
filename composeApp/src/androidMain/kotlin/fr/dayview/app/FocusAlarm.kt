package fr.dayview.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class FocusAlarmScheduler(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(endMillis: Long, intention: String): Boolean {
        if (endMillis <= nowMillis()) return false
        cancelBreakReminder()
        FocusNotificationManager(appContext).showFocus(endMillis, intention)
        DayViewFocusTileService.requestRefresh(appContext)
        val alarm = focusAlarmPendingIntent(appContext, intention, endMillis)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, alarm)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, alarm)
        }
        return exact
    }

    fun cancel() {
        alarmManager.cancel(focusAlarmPendingIntent(appContext, "", 0L))
        cancelBreakReminder()
        FocusNotificationManager(appContext).cancel()
        DayViewFocusTileService.requestRefresh(appContext)
    }

    fun restoreBreakReminders(breakStartMillis: Long, intention: String = "") {
        FocusNotificationManager(appContext).showBreak(breakStartMillis, intention)
        DayViewFocusTileService.requestRefresh(appContext)
        val elapsedMinutes = ((nowMillis() - breakStartMillis).coerceAtLeast(0L) / 60_000L).toInt()
        val nextMinutes = ((elapsedMinutes / BREAK_INTERVAL_MINUTES) + 1) * BREAK_INTERVAL_MINUTES
        if (nextMinutes <= MAX_BREAK_MINUTES) scheduleBreakReminder(breakStartMillis, nextMinutes)
    }

    internal fun scheduleBreakReminder(breakStartMillis: Long, elapsedMinutes: Int) {
        if (elapsedMinutes !in BREAK_INTERVAL_MINUTES..MAX_BREAK_MINUTES) return
        val triggerMillis = breakStartMillis + elapsedMinutes * 60_000L
        if (triggerMillis <= nowMillis()) return
        val alarm = breakReminderPendingIntent(appContext, breakStartMillis, elapsedMinutes)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, alarm)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, alarm)
        }
    }

    private fun cancelBreakReminder() {
        alarmManager.cancel(breakReminderPendingIntent(appContext, 0L, 0))
    }

    private fun focusAlarmPendingIntent(context: Context, intention: String, endMillis: Long): PendingIntent {
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_INTENTION, intention)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_FOCUS_END)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, endMillis)
        return PendingIntent.getBroadcast(
            context,
            FOCUS_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun breakReminderPendingIntent(
        context: Context,
        breakStartMillis: Long,
        elapsedMinutes: Int,
    ): PendingIntent {
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_BREAK_REMINDER)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, breakStartMillis)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_MINUTES, elapsedMinutes)
        return PendingIntent.getBroadcast(
            context,
            BREAK_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val FOCUS_ALARM_REQUEST_CODE = 4101
        const val BREAK_ALARM_REQUEST_CODE = 4103
        const val BREAK_INTERVAL_MINUTES = 10
        const val MAX_BREAK_MINUTES = 60
    }
}

class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DayViewFocusTileService.requestRefresh(context)
        DayViewWidget.updateAll(context)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_FOCUS_END
        val breakStartMillis: Long
        val elapsedMinutes: Int
        if (kind == KIND_BREAK_REMINDER) {
            breakStartMillis = intent.getLongExtra(EXTRA_BREAK_START, System.currentTimeMillis())
            elapsedMinutes = intent.getIntExtra(EXTRA_BREAK_MINUTES, 10)
        } else {
            breakStartMillis = intent.getLongExtra(EXTRA_BREAK_START, System.currentTimeMillis())
            elapsedMinutes = 0
        }
        val intention = intent.getStringExtra(EXTRA_INTENTION)
            ?: runBlocking { DayViewPreferences.get(context).snapshots.first() }.focusIntention
        FocusAlarmScheduler(context).restoreBreakReminders(breakStartMillis, intention)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        createChannel(notificationManager)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_dayview_notification)
            .setColor(context.getColor(R.color.dayview_notification_accent))
            .setContentTitle(if (kind == KIND_BREAK_REMINDER) "Pause · $elapsedMinutes minutes" else "Focus terminé")
            .setContentText(
                if (kind == KIND_BREAK_REMINDER) {
                    "Souhaitez-vous reprendre un focus ou terminer la série ?"
                } else {
                    intention.takeIf(String::isNotBlank) ?: "Votre session est terminée. La pause commence."
                },
            )
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(FOCUS_NOTIFICATION_ID, notification)
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fin des sessions Focus",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Prévient lorsqu’une session Focus se termine"
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_INTENTION = "focus_intention"
        const val EXTRA_KIND = "alarm_kind"
        const val EXTRA_BREAK_START = "break_start"
        const val EXTRA_BREAK_MINUTES = "break_minutes"
        const val KIND_FOCUS_END = "focus_end"
        const val KIND_BREAK_REMINDER = "break_reminder"
        private const val CHANNEL_ID = "focus_finished"
        private const val FOCUS_NOTIFICATION_ID = 4102
    }
}
