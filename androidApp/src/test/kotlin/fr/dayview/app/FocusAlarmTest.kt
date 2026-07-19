package fr.dayview.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import kotlin.time.Instant

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
        // Real-clock-anchored (not the fixed, deliberately-future NOW used elsewhere in this
        // file): FocusNotificationManager.showFocus gates on the real System.currentTimeMillis()
        // directly, not the scheduler's injected clock. NOW sits months ahead of the real clock
        // only until 2027-01-15; after that date it reads as real-past and showFocus would
        // silently cancel instead of posting, and this test's notification assertions below
        // would go unexercised without ever failing loudly.
        val realNow = System.currentTimeMillis()
        val endMillis = realNow + 25 * 60_000L

        assertTrue(FocusAlarmScheduler(context) { realNow }.schedule(endMillis, "Écrire les tests", 25))

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
        // Closing now runs through the in-app ritual: the only action opens DayView.
        assertEquals("Close", notification.actions.single().title)
    }

    @Test
    fun scheduleArmsEndAndOvertimeAlarms() {
        val endMillis = NOW + 25 * 60_000L

        scheduler().schedule(endMillis, "", 25)

        val triggers = shadowAlarms.scheduledAlarms.map { it.getTriggerAtMs() }.sorted()
        assertEquals(listOf(endMillis, endMillis + 25 * 60_000L), triggers)
    }

    @Test
    fun closeCancelsOvertimeAndSchedulesBreakReminderFromClosure() {
        // Real-clock-anchored: FocusNotificationManager.showBreak (called inside close()) gates
        // on the real System.currentTimeMillis(), not the scheduler's injected clock, so a
        // NOW-relative closure instant would silently no-op the notification assertions below —
        // the whole point of this test's name ("...FromClosure").
        val realNow = System.currentTimeMillis()
        val scheduler = FocusAlarmScheduler(context) { realNow }
        scheduler.schedule(realNow + 25 * 60_000L, "", 25)
        val closureMillis = realNow - 1_000L

        scheduler.close(closureMillis, "")

        val triggers = shadowAlarms.scheduledAlarms.map { it.getTriggerAtMs() }
        // Only the break reminder survives, anchored on the closure instant — not on the term.
        assertEquals(listOf(closureMillis + 10 * 60_000L), triggers)
        // close() must actually post the ongoing "On break" card with a live Resume action —
        // the literal closure-anchored break this task is named for, not merely an alarm.
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val breakNotification = notifications.allNotifications.single()
        assertEquals("On break", breakNotification.extras.getString(Notification.EXTRA_TITLE))
        val resumeAction = breakNotification.actions.single()
        assertEquals("Resume", resumeAction.title)
        val resumeIntent = shadowOf(resumeAction.actionIntent).savedIntent
        assertEquals(FocusNotificationManager.ACTION_RESUME_FOCUS, resumeIntent.action)
        assertEquals(FocusNotificationActionReceiver::class.java.name, resumeIntent.component?.className)
    }

    @Test
    fun pastFocusIsRejectedWithoutSchedulingAnAlarm() {
        assertFalse(scheduler().schedule(NOW, "Trop tard", 25))
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun restoreOvertimeRearmsTheReminderWhenItsTriggerIsStillAhead() {
        // Term passed 5 minutes ago; the once-per-session nudge (term + sessionMinutes) still
        // sits 20 minutes ahead, so restoring overtime after a reboot must re-arm it.
        // Anchored on the real clock rather than the fixed, deliberately-future NOW used
        // elsewhere in this file: FocusNotificationManager.showOvertime gates on the real
        // System.currentTimeMillis() directly, not the scheduler's injected clock, so with NOW
        // (which sits months ahead of the real clock) endMillis would still read as "in the
        // future" against the real check and showOvertime would silently cancel instead of
        // posting — the restored card would go unasserted no matter what was written below.
        val realNow = System.currentTimeMillis()
        val endMillis = realNow - 5 * 60_000L

        FocusAlarmScheduler(context) { realNow }.restoreOvertime(endMillis, "Écrire", 25)

        assertEquals(endMillis + 25 * 60_000L, nextAlarm().getTriggerAtMs())
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val restored = notifications.allNotifications.single()
        assertEquals("Focus — running over, still counted", restored.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(restored.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test
    fun restoreOvertimeSkipsRearmingWhenItsTriggerHasAlreadyPassed() {
        // Term passed 40 minutes ago; the nudge's moment (term + 25 min) is itself 15 minutes
        // in the past, so restoring overtime must not re-arm a reminder that already elapsed —
        // the once-per-session guarantee's actual mechanism. Restoring the count-up card still
        // happens regardless — the session is still running, only its nudge already fired.
        // Real-clock-anchored for the same reason as the test above.
        val realNow = System.currentTimeMillis()
        val endMillis = realNow - 40 * 60_000L

        FocusAlarmScheduler(context) { realNow }.restoreOvertime(endMillis, "Écrire", 25)

        assertEquals(0, shadowAlarms.scheduledAlarms.size)
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val restored = notifications.allNotifications.single()
        assertEquals("Focus — running over, still counted", restored.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test
    @Config(sdk = [31])
    fun unavailableExactAlarmAccessFallsBackToAnInexactAlarm() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val endMillis = NOW + 25 * 60_000L

        assertFalse(scheduler().schedule(endMillis, "Écrire les tests", 25))

        assertEquals(endMillis, nextAlarm().getTriggerAtMs())
    }

    @Test
    fun restoringBreakSchedulesTheNextTenMinuteBoundary() {
        // Real-clock-anchored for the same reason as the tests above: showBreak's gate reads
        // the real clock directly, so a NOW-relative breakStart would silently no-op the
        // notification assertions below.
        val realNow = System.currentTimeMillis()
        val breakStart = realNow - 17 * 60_000L
        val scheduler = FocusAlarmScheduler(context) { realNow }

        scheduler.restoreBreakReminders(breakStart, hasOpenDetour = false)

        assertEquals(breakStart + 20 * 60_000L, nextAlarm().getTriggerAtMs())
        // restoreBreakReminders(hasOpenDetour = false) must actually post the ongoing "On break"
        // card with its Resume action — not merely arm the next boundary alarm.
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val breakNotification = notifications.allNotifications.single()
        assertEquals("On break", breakNotification.extras.getString(Notification.EXTRA_TITLE))
        val resumeAction = breakNotification.actions.single()
        assertEquals("Resume", resumeAction.title)
        val resumeIntent = shadowOf(resumeAction.actionIntent).savedIntent
        assertEquals(FocusNotificationManager.ACTION_RESUME_FOCUS, resumeIntent.action)
        assertEquals(FocusNotificationActionReceiver::class.java.name, resumeIntent.component?.className)
    }

    @Test
    fun restoringBreakAfterOneHourDoesNotScheduleAnythingAndClearsAnyStaleNotification() {
        // Real-clock-anchored (not the fixed, deliberately-future NOW used elsewhere in this
        // file): FocusNotificationManager.showBreak gates on the real System.currentTimeMillis()
        // directly, so pre-posting a notification with a NOW-relative "past" instant would
        // silently no-op — it would still read as being in the future against the real clock,
        // and the "already cleared" assertion below would pass regardless of this fix.
        // Strictly past BREAK_VISIBLE_MAX, not exactly at it — the exact tick is its own test,
        // restoringBreakAtExactlyTheVisibilityCapStillPostsTheCard, mirroring
        // calculatePomodoroProgress/focusTileState's inclusive cutoff.
        val realNow = System.currentTimeMillis()
        val breakStart = realNow - BREAK_VISIBLE_MAX.inWholeMilliseconds - 1_000L
        val scheduler = FocusAlarmScheduler(context) { realNow }
        // A break aged past BREAK_VISIBLE_MAX must not repost/refresh an ongoing card that
        // would then have nothing left armed to ever clear it — it is fully cancelled instead.
        FocusNotificationManager(context).showBreak(breakStart, "Écrire")

        scheduler.restoreBreakReminders(breakStart, hasOpenDetour = false)

        assertEquals(0, shadowAlarms.scheduledAlarms.size)
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun restoringBreakAtExactlyTheVisibilityCapStillPostsTheCard() {
        // BREAK_VISIBLE_MAX is an inclusive boundary in calculatePomodoroProgress
        // (`elapsed <= BREAK_VISIBLE_MAX`) and focusTileState
        // (`nowMillis - breakStartMillis <= BREAK_VISIBLE_MAX...`) alike — both still read BREAK
        // at the exact tick. The scheduler must agree, or the tile reads BREAK for an instant
        // whose ongoing notification has already been torn down.
        val realNow = System.currentTimeMillis()
        val breakStart = realNow - BREAK_VISIBLE_MAX.inWholeMilliseconds
        val scheduler = FocusAlarmScheduler(context) { realNow }

        scheduler.restoreBreakReminders(breakStart, hasOpenDetour = false)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val breakNotification = notifications.allNotifications.single()
        assertEquals("On break", breakNotification.extras.getString(Notification.EXTRA_TITLE))
        // Nothing left to arm past the final boundary (nextMinutes would be 70, outside
        // BREAK_INTERVAL_MINUTES..MAX_BREAK_MINUTES) — this only proves the card itself still
        // renders at the exact tick, matching focusTileState's BREAK reading for the same instant.
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun restoringBreakWithAnOpenDetourClearsTheNotificationButKeepsTheChainArmed() {
        // Real-clock-anchored for the same reason as the test above.
        val realNow = System.currentTimeMillis()
        val breakStart = realNow - 5 * 60_000L
        val scheduler = FocusAlarmScheduler(context) { realNow }
        // Simulate a break notification already on screen from before the detour opened.
        FocusNotificationManager(context).showBreak(breakStart, "Écrire")

        scheduler.restoreBreakReminders(breakStart, hasOpenDetour = true)

        // The dead Resume control is torn down...
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        assertEquals(0, notifications.allNotifications.size)
        // ...but only the notification, not the reminder chain: stopOpenDetour fires no
        // callback, so this alarm is the only thing that will ever re-evaluate the break once
        // the detour ends.
        assertEquals(breakStart + 10 * 60_000L, nextAlarm().getTriggerAtMs())
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun cancelRemovesFocusBreakAndOvertimeAlarms() {
        // Real-clock-anchored so schedule()'s internal showFocus call does not silently depend
        // on NOW staying real-future (audited: this test's own assertions below don't currently
        // depend on whether that post succeeds, since cancel() unconditionally clears the
        // notification slot either way — converted anyway for consistency with the rest of the
        // file and to remove any doubt as this file evolves).
        val realNow = System.currentTimeMillis()
        val scheduler = FocusAlarmScheduler(context) { realNow }
        scheduler.schedule(realNow + 25 * 60_000L, "Écrire les tests", 25)
        scheduler.scheduleBreakReminder(realNow, 10)

        scheduler.cancel()

        assertEquals(0, shadowAlarms.scheduledAlarms.size)
        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        assertEquals(0, notifications.allNotifications.size)
    }

    @Test
    fun focusEndInvitesClosureAndFlipsTheOngoingNotificationToOvertime() {
        val endMillis = System.currentTimeMillis()
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_FOCUS_END)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, endMillis)
            .putExtra(FocusAlarmReceiver.EXTRA_INTENTION, "Préparer la démo")

        FocusAlarmReceiver().onReceive(context, intent)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val invitation = notifications.allNotifications.first {
            it.extras.getString(Notification.EXTRA_TITLE) == "Focus ended"
        }
        assertEquals("Préparer la démo", invitation.extras.getString(Notification.EXTRA_TEXT))

        // The term does not open a break: the session keeps counting, upwards.
        val ongoing = notifications.allNotifications.first {
            it.extras.getString(Notification.EXTRA_TITLE) == "Focus — running over, still counted"
        }
        assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(ongoing.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertFalse(ongoing.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals("Close", ongoing.actions.single().title)
        assertTrue(notifications.allNotifications.none { it.extras.getString(Notification.EXTRA_TITLE) == "On break" })
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun overtimeReminderInvitesClosureWithoutReschedulingAnything() {
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_OVERTIME_REMINDER)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, System.currentTimeMillis())

        FocusAlarmReceiver().onReceive(context, intent)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val reminder = notifications.allNotifications.first {
            it.extras.getString(Notification.EXTRA_TITLE) == "Still going — closing stays a choice."
        }
        assertEquals(
            "The session passed twice its length.",
            reminder.extras.getString(Notification.EXTRA_TEXT),
        )
        // Fires once per session: nothing is re-armed.
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
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
            it.extras.getString(Notification.EXTRA_TITLE) == "Break · 20 minutes"
        }
        assertEquals("Break · 20 minutes", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(
            "Would you like to resume a focus or end the series?",
            notification.extras.getString(Notification.EXTRA_TEXT),
        )
        assertEquals(breakStart + 30 * 60_000L, nextAlarm().getTriggerAtMs())
    }

    @Test
    fun breakReminderWithAnOpenDetourDoesNotRepostTheOngoingResumeControl() {
        val preferences = DayViewPreferences.get(context)
        val breakStart = System.currentTimeMillis() - 20 * 60_000L
        runBlocking {
            val seed = preferences.snapshots.first()
            preferences.persist(
                seed.copy(
                    pomodoroEnd = null,
                    breakStart = Instant.fromEpochMilliseconds(breakStart),
                    openDetourStart = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    openDetourCategory = "Slack",
                    focusIntention = "Écrire",
                ),
            )
        }
        // A break notification already on screen from before the detour opened.
        FocusNotificationManager(context).showBreak(breakStart, "Écrire")
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_BREAK_REMINDER)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, breakStart)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_MINUTES, 20)

        FocusAlarmReceiver().onReceive(context, intent)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        // The ongoing "On break" card (with the now-dead Resume action) must be torn down;
        // the term-chime-style "Break · N minutes" popup below has no action button of its
        // own, so it staying tap-to-open is not the defect this guards against.
        assertTrue(notifications.allNotifications.none { it.extras.getString(Notification.EXTRA_TITLE) == "On break" })
        assertTrue(notifications.allNotifications.none { n -> n.actions?.any { it.title == "Resume" } == true })
        // Only the notification is torn down — the reminder chain is left standing so it
        // self-heals into a fresh "On break" card once the detour ends (stopOpenDetour fires
        // no callback, so this alarm is the only thing that will ever re-evaluate the break).
        assertEquals(breakStart + 30 * 60_000L, nextAlarm().getTriggerAtMs())
        assertEquals(1, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun finalBreakReminderCancelsRatherThanLeavingAnUnclearableCard() {
        // The last-armed break reminder fires at (or a hair past) MAX_BREAK_MINUTES past
        // closure. Before the BREAK_VISIBLE_MAX gate lived inside restoreBreakReminders itself,
        // this receiver path — reached directly when an already-armed alarm fires, never
        // through MainActivity's own now-removed gate — reposted the ongoing "On break" card
        // unconditionally, with nothing left armed (nextMinutes = 70 > MAX_BREAK_MINUTES) to
        // ever clear it again.
        val breakStart = System.currentTimeMillis() - 60 * 60_000L - 1_000L
        // DayViewPreferences is a process-wide singleton that outlives the SharedPreferences
        // clear in setUp(): seed openDetourStart = null explicitly so this test's outcome
        // cannot depend on whatever an earlier test left behind (this receiver path reads it
        // via the same fallback snapshot as the intention, since EXTRA_INTENTION is never set
        // on break-reminder pending intents).
        runBlocking {
            val preferences = DayViewPreferences.get(context)
            preferences.persist(preferences.snapshots.first().copy(openDetourStart = null))
        }
        FocusNotificationManager(context).showBreak(breakStart, "Écrire")
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_BREAK_REMINDER)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, breakStart)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_MINUTES, 60)

        FocusAlarmReceiver().onReceive(context, intent)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        assertTrue(notifications.allNotifications.none { it.extras.getString(Notification.EXTRA_TITLE) == "On break" })
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    @Config(sdk = [33])
    fun deniedNotificationPermissionSuppressesNotificationButKeepsNextReminder() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val breakStart = System.currentTimeMillis()
        val intent = Intent(context, FocusAlarmReceiver::class.java)
            .putExtra(FocusAlarmReceiver.EXTRA_KIND, FocusAlarmReceiver.KIND_BREAK_REMINDER)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_START, breakStart)
            .putExtra(FocusAlarmReceiver.EXTRA_BREAK_MINUTES, 10)

        FocusAlarmReceiver().onReceive(context, intent)

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        assertEquals(0, notifications.allNotifications.size)
        assertEquals(breakStart + 10 * 60_000L, nextAlarm().getTriggerAtMs())
    }

    @Test
    fun resumeActionStartsAnotherFocusInTheSameChain() {
        val preferences = DayViewPreferences.get(context)
        runBlocking {
            val seed = preferences.snapshots.first()
            preferences.persist(
                // A break: the previous session was closed, so no session is running.
                seed.copy(
                    pomodoroMinutes = 25,
                    pomodoroEnd = null,
                    breakStart = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 1_000L),
                    openDetourStart = null,
                    focusIntention = "Continuer la proposition",
                ),
            )
        }
        val beforeResume = System.currentTimeMillis()

        FocusNotificationActionReceiver().onReceive(
            context,
            Intent().setAction(FocusNotificationManager.ACTION_RESUME_FOCUS),
        )

        val reloaded = runBlocking { preferences.snapshots.first() }
        val endMillis = requireNotNull(reloaded.pomodoroEnd).toEpochMilliseconds()
        assertTrue(endMillis >= beforeResume + 25 * 60_000L)
        assertEquals("Continuer la proposition", reloaded.focusIntention)
        // The resumed session must be a coherent snapshot, not a bare end instant.
        assertEquals(25, reloaded.pomodoroSessionMinutes)
        assertEquals(null, reloaded.breakStart)
        assertEquals(endMillis, nextAlarm().getTriggerAtMs())
        // Resuming arms the overtime reminder too.
        assertEquals(
            listOf(endMillis, endMillis + 25 * 60_000L),
            shadowAlarms.scheduledAlarms.map { it.getTriggerAtMs() }.sorted(),
        )
    }

    @Test
    fun resumeActionIsRefusedWhileAnOpenDetourRuns() {
        val preferences = DayViewPreferences.get(context)
        runBlocking {
            val seed = preferences.snapshots.first()
            preferences.persist(
                seed.copy(
                    pomodoroMinutes = 25,
                    pomodoroEnd = null,
                    breakStart = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    openDetourStart = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                    openDetourCategory = "Slack",
                ),
            )
        }

        FocusNotificationActionReceiver().onReceive(
            context,
            Intent().setAction(FocusNotificationManager.ACTION_RESUME_FOCUS),
        )

        val reloaded = runBlocking { preferences.snapshots.first() }
        assertEquals(null, reloaded.pomodoroEnd)
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun resumeActionIsRefusedWhileASessionRunsOver() {
        val preferences = DayViewPreferences.get(context)
        val endMillis = System.currentTimeMillis() - 60_000L
        runBlocking {
            val seed = preferences.snapshots.first()
            preferences.persist(
                seed.copy(
                    pomodoroMinutes = 25,
                    pomodoroEnd = Instant.fromEpochMilliseconds(endMillis),
                    pomodoroSessionMinutes = 25,
                    openDetourStart = null,
                ),
            )
        }

        // A stale break notification must not restart a session that is still counting up.
        FocusNotificationActionReceiver().onReceive(
            context,
            Intent().setAction(FocusNotificationManager.ACTION_RESUME_FOCUS),
        )

        val reloaded = runBlocking { preferences.snapshots.first() }
        assertEquals(endMillis, requireNotNull(reloaded.pomodoroEnd).toEpochMilliseconds())
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
    }

    @Test
    fun theOngoingNotificationRoutesClosureThroughTheApp() {
        // Closing costs a deliberate act: the action opens DayView instead of
        // broadcasting a stop that would end the session behind the user's back.
        FocusNotificationManager(context).showFocus(System.currentTimeMillis() + 25 * 60_000L, "Écrire")

        val notifications: ShadowNotificationManager = extract(
            context.getSystemService(NotificationManager::class.java),
        )
        val action = notifications.allNotifications.single().actions.single()
        assertEquals("Close", action.title)
        assertEquals(
            MainActivity::class.java.name,
            shadowOf(action.actionIntent).savedIntent.component?.className,
        )
    }

    private fun scheduler(): FocusAlarmScheduler = FocusAlarmScheduler(context) { NOW }

    private fun nextAlarm(): ShadowAlarmManager.ScheduledAlarm = requireNotNull(shadowAlarms.scheduledAlarms.minByOrNull { it.getTriggerAtMs() })

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
