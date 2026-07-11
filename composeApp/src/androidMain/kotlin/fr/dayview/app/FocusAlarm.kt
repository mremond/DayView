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

class FocusAlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(AlarmManager::class.java)

    fun schedule(endMillis: Long, intention: String): Boolean {
        if (endMillis <= System.currentTimeMillis()) return false
        val alarm = focusAlarmPendingIntent(appContext, intention)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, alarm)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMillis, alarm)
        }
        return exact
    }

    fun cancel() {
        alarmManager.cancel(focusAlarmPendingIntent(appContext, ""))
    }

    private fun focusAlarmPendingIntent(context: Context, intention: String): PendingIntent {
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_INTENTION, intention)
        return PendingIntent.getBroadcast(
            context,
            FOCUS_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val FOCUS_ALARM_REQUEST_CODE = 4101
    }
}

class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        createChannel(notificationManager)
        val intention = intent.getStringExtra(EXTRA_INTENTION).orEmpty()
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
            .setContentTitle("Focus terminé")
            .setContentText(intention.takeIf(String::isNotBlank) ?: "Votre session est terminée.")
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
        private const val CHANNEL_ID = "focus_finished"
        private const val FOCUS_NOTIFICATION_ID = 4102
    }
}
