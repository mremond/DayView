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

    /**
     * Arms the term of the session *and* the single overtime reminder that follows it.
     * The term no longer ends anything: it flips the ongoing notification to a count-up
     * (see [FocusAlarmReceiver]), so the break can only be anchored by [close].
     */
    fun schedule(endMillis: Long, intention: String, sessionMinutes: Int): Boolean {
        if (endMillis <= nowMillis()) return false
        cancelBreakReminder()
        FocusNotificationManager(appContext).showFocus(endMillis, intention)
        DayViewFocusTileService.requestRefresh(appContext)
        val alarm = focusAlarmPendingIntent(appContext, intention, endMillis)
        val exact = setAlarm(endMillis, alarm)
        scheduleOvertimeReminder(endMillis, sessionMinutes)
        return exact
    }

    fun cancel() {
        alarmManager.cancel(focusAlarmPendingIntent(appContext, "", 0L))
        cancelBreakReminder()
        cancelOvertimeReminder()
        FocusNotificationManager(appContext).cancel()
        DayViewFocusTileService.requestRefresh(appContext)
    }

    /**
     * The closure ritual landed: the session stops counting here, and the break is anchored
     * on this instant rather than on the term the session may have run far past.
     */
    fun close(breakStartMillis: Long, intention: String = "") {
        alarmManager.cancel(focusAlarmPendingIntent(appContext, "", 0L))
        cancelOvertimeReminder()
        FocusNotificationManager(appContext).showBreak(breakStartMillis, intention)
        scheduleBreakReminder(breakStartMillis, BREAK_INTERVAL_MINUTES)
        DayViewFocusTileService.requestRefresh(appContext)
        // The widget is redrawn by the closure's own persist (WidgetRefreshingPreferences);
        // re-reading it here would block the caller's thread for nothing.
    }

    /**
     * Re-establishes the count-up notification for a session whose term has passed but which
     * was never closed — after a reboot, or when the app is reopened during overtime. The
     * reminder is re-armed only if its moment is still ahead, so it stays a once-per-session
     * event.
     */
    fun restoreOvertime(endMillis: Long, intention: String = "", sessionMinutes: Int) {
        cancelBreakReminder()
        FocusNotificationManager(appContext).showOvertime(endMillis, intention)
        DayViewFocusTileService.requestRefresh(appContext)
        scheduleOvertimeReminder(endMillis, sessionMinutes)
    }

    /**
     * [hasOpenDetour] is required, not defaulted: an open detour means Resume would be a dead
     * control (mirrors DayViewController.startPomodoro's refusal), so the notification must not
     * carry a Resume action while one runs. Only the notification is torn down in that case —
     * not the reminder chain — so the very next boundary self-heals it into a fresh "On break"
     * card as soon as the detour ends, rather than requiring a background/foreground transition
     * that an in-app `stopOpenDetour` never triggers.
     *
     * The BREAK_VISIBLE_MAX cutoff lives here too (not in callers) so both callers — this
     * scheduler's own reopen path and [FocusAlarmReceiver]'s break-reminder branch, reached
     * directly when an already-armed alarm fires — are covered by the same check, and it mirrors
     * calculatePomodoroProgress/focusTileState's inclusive boundary: a break exactly at
     * BREAK_VISIBLE_MAX is still BREAK there, so it must still be BREAK here too, and only
     * strictly past it is fully cancelled instead of reposted. The final boundary reminder
     * (fires at exactly [MAX_BREAK_MINUTES], the same value as BREAK_VISIBLE_MAX) still reposts
     * the card once more at that tick — with nothing left armed to fire again — before the next
     * thing to re-evaluate it (an app reopen, or a reminder firing a hair late) finds it
     * strictly past the cap and cancels it.
     */
    fun restoreBreakReminders(breakStartMillis: Long, intention: String = "", hasOpenDetour: Boolean) {
        val elapsedMillis = (nowMillis() - breakStartMillis).coerceAtLeast(0L)
        if (elapsedMillis > BREAK_VISIBLE_MAX.inWholeMilliseconds) {
            cancel()
            return
        }
        val elapsedMinutes = (elapsedMillis / 60_000L).toInt()
        if (hasOpenDetour) {
            FocusNotificationManager(appContext).cancel()
        } else {
            FocusNotificationManager(appContext).showBreak(breakStartMillis, intention)
        }
        DayViewFocusTileService.requestRefresh(appContext)
        val nextMinutes = ((elapsedMinutes / BREAK_INTERVAL_MINUTES) + 1) * BREAK_INTERVAL_MINUTES
        scheduleBreakReminder(breakStartMillis, nextMinutes)
    }

    internal fun scheduleBreakReminder(breakStartMillis: Long, elapsedMinutes: Int) {
        if (elapsedMinutes !in BREAK_INTERVAL_MINUTES..MAX_BREAK_MINUTES) return
        val triggerMillis = breakStartMillis + elapsedMinutes * 60_000L
        if (triggerMillis <= nowMillis()) return
        setAlarm(triggerMillis, breakReminderPendingIntent(appContext, breakStartMillis, elapsedMinutes))
    }

    /** One discreet nudge when the session has run twice its planned length. */
    private fun scheduleOvertimeReminder(endMillis: Long, sessionMinutes: Int) {
        val triggerMillis = endMillis + sessionMinutes.coerceIn(5, 180) * 60_000L
        if (triggerMillis <= nowMillis()) return
        setAlarm(triggerMillis, overtimeReminderPendingIntent(appContext, endMillis))
    }

    private fun setAlarm(triggerMillis: Long, alarm: PendingIntent): Boolean {
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, alarm)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, alarm)
        }
        return exact
    }

    private fun cancelBreakReminder() {
        alarmManager.cancel(breakReminderPendingIntent(appContext, 0L, 0))
    }

    private fun cancelOvertimeReminder() {
        alarmManager.cancel(overtimeReminderPendingIntent(appContext, 0L))
    }

    private fun focusAlarmPendingIntent(
        context: Context,
        intention: String,
        endMillis: Long,
    ): PendingIntent {
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

    private fun overtimeReminderPendingIntent(context: Context, endMillis: Long): PendingIntent {
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_OVERTIME_REMINDER)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, endMillis)
        return PendingIntent.getBroadcast(
            context,
            OVERTIME_ALARM_REQUEST_CODE,
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
        const val OVERTIME_ALARM_REQUEST_CODE = 4104
        const val BREAK_INTERVAL_MINUTES = 10
        const val MAX_BREAK_MINUTES = 60
    }
}

class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DayViewFocusTileService.requestRefresh(context)
        DayViewWidget.updateAll(context)
        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_FOCUS_END
        val anchorMillis = intent.getLongExtra(EXTRA_BREAK_START, System.currentTimeMillis())
        val elapsedMinutes = if (kind == KIND_BREAK_REMINDER) intent.getIntExtra(EXTRA_BREAK_MINUTES, 10) else 0
        val extraIntention = intent.getStringExtra(EXTRA_INTENTION)
        // KIND_BREAK_REMINDER never carries EXTRA_INTENTION, so this is the fallback snapshot
        // read that recovers the intention; it doubles as the source for openDetourStart,
        // needed to keep that kind from reposting a break notification whose Resume action
        // would be a dead control. KIND_OVERTIME_REMINDER also never carries EXTRA_INTENTION,
        // but its body is a fixed string that never reads `intention` — reading the snapshot
        // for it would only block this receiver's main thread for a value nothing uses.
        val snapshot = if (extraIntention == null && kind == KIND_BREAK_REMINDER) {
            runBlocking { DayViewPreferences.get(context).snapshots.first() }
        } else {
            null
        }
        val intention = extraIntention ?: snapshot?.focusIntention.orEmpty()
        when (kind) {
            // The term is an invitation, not an ending: the ongoing notification flips to
            // counting up and the session keeps running until the user closes it in the app.
            KIND_FOCUS_END -> FocusNotificationManager(context).showOvertime(anchorMillis, intention)
            // The one-shot nudge changes no state and re-arms nothing.
            KIND_OVERTIME_REMINDER -> Unit
            KIND_BREAK_REMINDER -> FocusAlarmScheduler(context).restoreBreakReminders(
                breakStartMillis = anchorMillis,
                intention = intention,
                hasOpenDetour = snapshot?.openDetourStart != null,
            )
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        createChannel(context, notificationManager)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // The overtime nudge is a suggestion, not a summons. It lives on its own quieter
        // channel so it never arrives with the term's alarm-grade insistence — and so the
        // user can silence the nudge without losing the end-of-session chime.
        val discreet = kind == KIND_OVERTIME_REMINDER
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, if (discreet) OVERTIME_CHANNEL_ID else CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
                .setPriority(if (discreet) Notification.PRIORITY_DEFAULT else Notification.PRIORITY_HIGH)
                .setDefaults(if (discreet) 0 else Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_dayview_notification)
            .setColor(context.getColor(R.color.dayview_notification_accent))
            .setContentTitle(
                when (kind) {
                    KIND_BREAK_REMINDER -> context.getString(R.string.break_reminder_title, elapsedMinutes)
                    KIND_OVERTIME_REMINDER -> context.getString(R.string.overtime_reminder_title)
                    else -> context.getString(R.string.focus_end_title)
                },
            )
            .setContentText(
                when (kind) {
                    KIND_BREAK_REMINDER -> context.getString(R.string.break_reminder_body)
                    KIND_OVERTIME_REMINDER -> context.getString(R.string.overtime_reminder_body)
                    else -> intention.takeIf(String::isNotBlank) ?: context.getString(R.string.focus_end_body)
                },
            )
            .setCategory(if (discreet) Notification.CATEGORY_REMINDER else Notification.CATEGORY_ALARM)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        // Reuses the lifecycle slot: the nudge supersedes the term's chime instead of stacking.
        notificationManager.notify(FOCUS_NOTIFICATION_ID, notification)
    }

    private fun createChannel(context: Context, manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.focus_alarm_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.focus_alarm_channel_description)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    OVERTIME_CHANNEL_ID,
                    context.getString(R.string.overtime_reminder_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.overtime_reminder_channel_description)
                    enableVibration(false)
                },
            )
        }
    }

    companion object {
        const val EXTRA_INTENTION = "focus_intention"
        const val EXTRA_KIND = "alarm_kind"
        const val EXTRA_BREAK_START = "break_start"
        const val EXTRA_BREAK_MINUTES = "break_minutes"
        const val KIND_FOCUS_END = "focus_end"
        const val KIND_BREAK_REMINDER = "break_reminder"
        const val KIND_OVERTIME_REMINDER = "overtime_reminder"
        private const val CHANNEL_ID = "focus_finished"
        private const val OVERTIME_CHANNEL_ID = "focus_overtime"
        private const val FOCUS_NOTIFICATION_ID = 4102
    }
}
