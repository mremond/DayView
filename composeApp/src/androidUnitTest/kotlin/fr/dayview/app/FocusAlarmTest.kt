package fr.dayview.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow.extract
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowNotificationManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FocusAlarmTest {
    private lateinit var context: Context
    private lateinit var alarmManager: AlarmManager
    private lateinit var shadowAlarms: ShadowAlarmManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("dayview_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        alarmManager = context.getSystemService(AlarmManager::class.java)
        shadowAlarms = extract(alarmManager)
    }

    @Test
    fun futureFocusIsScheduledExactlyAtItsDeadline() {
        val endMillis = NOW + 25 * 60_000L

        assertTrue(scheduler().schedule(endMillis, "Écrire les tests"))

        val alarm = nextAlarm()
        assertEquals(endMillis, alarm.getTriggerAtMs())
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.getType())
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val notification = notifications.allNotifications.single()
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("Écrire les tests", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Arrêter", notification.actions.single().title)
    }

    @Test
    fun pastFocusIsRejectedWithoutSchedulingAnAlarm() {
        assertFalse(scheduler().schedule(NOW, "Trop tard"))
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    @Config(sdk = [31])
    fun unavailableExactAlarmAccessFallsBackToAnInexactAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val endMillis = NOW + 25 * 60_000L

        assertFalse(scheduler().schedule(endMillis, "Écrire les tests"))

        assertEquals(endMillis, nextAlarm().getTriggerAtMs())
    }

    @Test
    fun restoringBreakSchedulesTheNextTenMinuteBoundary() {
        val breakStart = NOW - 17 * 60_000L

        scheduler().restoreBreakReminders(breakStart)

        assertEquals(breakStart + 20 * 60_000L, nextAlarm().getTriggerAtMs())
    }

    @Test
    fun restoringBreakAfterOneHourDoesNotScheduleAnything() {
        scheduler().restoreBreakReminders(NOW - 60 * 60_000L)

        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun cancelRemovesFocusAndBreakAlarms() {
        val scheduler = scheduler()
        scheduler.schedule(NOW + 25 * 60_000L, "Écrire les tests")
        scheduler.scheduleBreakReminder(NOW, 10)

        scheduler.cancel()

        assertEquals(0, shadowAlarms.scheduledAlarms.size)
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun focusReceiverPublishesTheIntentionAndSchedulesFirstBreakReminder() {
        val breakStart = System.currentTimeMillis()
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_FOCUS_END)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, breakStart)
            .putExtra(FocusAlarmReceiver.EXTRA_INTENTION, "Préparer la démo")

        FocusAlarmReceiver().onReceive(context, intent)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val notification = notifications.allNotifications.first {
            it.extras.getString(Notification.EXTRA_TITLE) == "Focus terminé"
        }
        assertEquals("Focus terminé", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Préparer la démo", notification.extras.getString(Notification.EXTRA_TEXT))
        val breakNotification = notifications.allNotifications.first {
            it.extras.getString(Notification.EXTRA_TITLE) == "Pause en cours"
        }
        assertTrue(breakNotification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(breakNotification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals("Reprendre", breakNotification.actions.single().title)
        assertEquals(breakStart + 10 * 60_000L, nextAlarm().getTriggerAtMs())
    }

    @Test
    fun breakReceiverPublishesElapsedPauseDuration() {
        val breakStart = System.currentTimeMillis() - 20 * 60_000L
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_BREAK_REMINDER)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, breakStart)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_MINUTES, 20)

        FocusAlarmReceiver().onReceive(context, intent)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val notification = notifications.allNotifications.first {
            it.extras.getString(Notification.EXTRA_TITLE) == "Pause · 20 minutes"
        }
        assertEquals("Pause · 20 minutes", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Souhaitez-vous reprendre un focus ou terminer la série ?",
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(breakStart + 30 * 60_000L, nextAlarm().getTriggerAtMs())
    }

    @Test
    @Config(sdk = [33])
    fun deniedNotificationPermissionSuppressesNotificationButKeepsNextReminder() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val breakStart = System.currentTimeMillis()
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_FOCUS_END)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, breakStart)

        FocusAlarmReceiver().onReceive(context, intent)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        assertEquals(0, notifications.allNotifications.size)
        assertEquals(breakStart + 10 * 60_000L, nextAlarm().getTriggerAtMs())
    }

    @Test
    fun resumeActionStartsAnotherFocusInTheSameChain() {
        val preferences = AndroidDayPreferences(context, notifyWidgets = false)
        preferences.savePomodoro(25, System.currentTimeMillis() - 1_000L)
        preferences.saveFocusIntention("Continuer la proposition")
        val beforeResume = System.currentTimeMillis()

        FocusNotificationActionReceiver().onReceive(
            context,
            Intent().setAction(FocusNotificationManager.ACTION_RESUME_FOCUS),
        )

        val endMillis = requireNotNull(preferences.loadPomodoroEndMillis())
        assertTrue(endMillis >= beforeResume + 25 * 60_000L)
        assertEquals("Continuer la proposition", preferences.loadFocusIntention())
        assertEquals(endMillis, nextAlarm().getTriggerAtMs())
    }

    @Test
    fun stopActionClosesTheFocusChainAndClearsItsNotification() {
        val preferences = AndroidDayPreferences(context, notifyWidgets = false)
        val scheduler = FocusAlarmScheduler(context)
        val endMillis = System.currentTimeMillis() + 25 * 60_000L
        preferences.savePomodoro(25, endMillis)
        scheduler.schedule(endMillis, "Arrêter proprement")

        FocusNotificationActionReceiver().onReceive(
            context,
            Intent().setAction(FocusNotificationManager.ACTION_STOP_FOCUS),
        )

        assertEquals(null, preferences.loadPomodoroEndMillis())
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        assertEquals(0, notifications.allNotifications.size)
    }

    private fun scheduler(): FocusAlarmScheduler = FocusAlarmScheduler(context) { NOW }

    private fun nextAlarm(): ShadowAlarmManager.ScheduledAlarm = requireNotNull(shadowAlarms.scheduledAlarms.minByOrNull { it.getTriggerAtMs() })

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
