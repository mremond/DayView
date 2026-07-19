package fr.dayview.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant

class FocusNotificationManager(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun showFocus(endMillis: Long, intention: String) {
        val remainingMillis = endMillis - System.currentTimeMillis()
        if (remainingMillis <= 0L || !canPostNotifications()) {
            cancel()
            return
        }

        createChannel()
        val openApp = openAppPendingIntent()
        val notification = baseBuilder(openApp, intention)
            .setSmallIcon(R.drawable.ic_dayview_notification)
            .setColor(appContext.getColor(R.color.dayview_notification_accent))
            .setContentTitle(appContext.getString(R.string.focus_notification_title))
            .setWhen(System.currentTimeMillis() + remainingMillis)
            .setChronometerCountDown(true)
            .addAction(closeAction(openApp))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Past the term the session has not ended — it counts up. Same ongoing notification,
     * same slot, chronometer reversed, and the only way out leads into the app.
     */
    fun showOvertime(endMillis: Long, intention: String) {
        if (endMillis > System.currentTimeMillis() || !canPostNotifications()) {
            cancel()
            return
        }

        createChannel()
        val openApp = openAppPendingIntent()
        val notification = baseBuilder(openApp, intention)
            .setContentTitle(appContext.getString(R.string.focus_notification_overtime_title))
            .setWhen(endMillis)
            .setChronometerCountDown(false)
            .addAction(closeAction(openApp))
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun showBreak(breakStartMillis: Long, intention: String) {
        if (breakStartMillis > System.currentTimeMillis() || !canPostNotifications()) {
            cancel()
            return
        }

        createChannel()
        val openApp = openAppPendingIntent()
        val notification = baseBuilder(openApp, intention)
            .setContentTitle(appContext.getString(R.string.focus_notification_break_title))
            .setWhen(breakStartMillis)
            .setChronometerCountDown(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(appContext, R.drawable.ic_dayview_notification),
                    appContext.getString(R.string.focus_notification_resume),
                    actionPendingIntent(ACTION_RESUME_FOCUS, RESUME_REQUEST_CODE),
                ).build(),
            )
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    private fun baseBuilder(openApp: PendingIntent, intention: String): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext).setPriority(Notification.PRIORITY_LOW)
        }
        return builder
            .setSmallIcon(R.drawable.ic_dayview_notification)
            .setColor(appContext.getColor(R.color.dayview_notification_accent))
            .setContentText(
                intention.trim().ifBlank { appContext.getString(R.string.widget_focus_default) },
            )
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openApp)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        OPEN_REQUEST_CODE,
        Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Closing is never a broadcast. Ending a session costs a deliberate act — naming the
     * outcome, and naming the pull when leaving early — so the action opens the app where
     * that ritual lives rather than ending the session behind the user's back.
     */
    private fun closeAction(openApp: PendingIntent): Notification.Action = Notification.Action.Builder(
        Icon.createWithResource(appContext, R.drawable.ic_dayview_notification),
        appContext.getString(R.string.focus_notification_close),
        openApp,
    ).build()

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        requestCode,
        Intent(appContext, FocusNotificationActionReceiver::class.java).apply {
            this.action = action
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    appContext.getString(R.string.focus_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = appContext.getString(R.string.focus_notification_channel_description)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    companion object {
        const val NOTIFICATION_ID = 4110
        private const val CHANNEL_ID = "focus_active"
        private const val OPEN_REQUEST_CODE = 4111
        private const val RESUME_REQUEST_CODE = 4113
        const val ACTION_RESUME_FOCUS = "fr.dayview.app.action.RESUME_FOCUS"
    }
}

class FocusNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val preferences = DayViewPreferences.get(context)
        when (intent.action) {
            FocusNotificationManager.ACTION_RESUME_FOCUS -> {
                val s = runBlocking { preferences.snapshots.first() }
                // Mirrors DayViewController.startPomodoro: refuses while a session or an open
                // detour runs, and writes the whole session snapshot rather than a bare end.
                // A refusal still falls through to the tile refresh below, so a stale surface
                // that offered this action corrects itself on the tap.
                if (s.openDetourStart == null && s.pomodoroEnd == null) {
                    val durationMinutes = s.pomodoroMinutes.coerceIn(5, 180)
                    val endMillis = System.currentTimeMillis() + durationMinutes * 60_000L
                    runBlocking {
                        preferences.persist(
                            s.copy(
                                pomodoroMinutes = durationMinutes,
                                pomodoroEnd = Instant.fromEpochMilliseconds(endMillis),
                                pomodoroSessionMinutes = durationMinutes,
                                breakStart = null,
                            ),
                        )
                    }
                    FocusAlarmScheduler(context).schedule(endMillis, s.focusIntention, durationMinutes)
                }
            }
            else -> return
        }
        DayViewFocusTileService.requestRefresh(context)
    }
}
