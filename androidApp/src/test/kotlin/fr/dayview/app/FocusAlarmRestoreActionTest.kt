package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function tests for [focusAlarmRestoreAction], the decision MainActivity's
 * restoreActiveFocusAlarm routes through on every onCreate/onResume — extracted for the same
 * reason [focusTileState]/[focusTileAction] were: no Robolectric Activity harness is needed to
 * exercise the branching, only plain Long/Long?/Long inputs.
 */
class FocusAlarmRestoreActionTest {
    @Test
    fun schedulesAFutureTerm() {
        assertEquals(
            FocusAlarmRestoreAction.SCHEDULE,
            focusAlarmRestoreAction(endMillis = 2_000L, breakStartMillis = null, nowMillis = 1_000L),
        )
    }

    @Test
    fun restoresOvertimeForATermAlreadyPassed() {
        assertEquals(
            FocusAlarmRestoreAction.RESTORE_OVERTIME,
            focusAlarmRestoreAction(endMillis = 1_000L, breakStartMillis = null, nowMillis = 2_000L),
        )
    }

    @Test
    fun aTermExactlyAtNowIsOvertimeNotAFutureSchedule() {
        // Mirrors schedule()'s own `endMillis <= nowMillis()` rejection: "now" is not future.
        assertEquals(
            FocusAlarmRestoreAction.RESTORE_OVERTIME,
            focusAlarmRestoreAction(endMillis = 1_000L, breakStartMillis = null, nowMillis = 1_000L),
        )
    }

    @Test
    fun restoresABreakWheneverOneIsStandingAndNoSessionIsRunning() {
        // No freshness cutoff here: BREAK_VISIBLE_MAX and the open-detour guard both live
        // inside FocusAlarmScheduler.restoreBreakReminders now, so a break aged well past the
        // window still routes to RESTORE_BREAK — restoreBreakReminders itself decides whether
        // that means posting a card or a full cancel.
        assertEquals(
            FocusAlarmRestoreAction.RESTORE_BREAK,
            focusAlarmRestoreAction(endMillis = null, breakStartMillis = 500L, nowMillis = 999_999L),
        )
    }

    @Test
    fun doesNothingWithoutASessionOrABreak() {
        assertEquals(
            FocusAlarmRestoreAction.NOTHING,
            focusAlarmRestoreAction(endMillis = null, breakStartMillis = null, nowMillis = 1_000L),
        )
    }

    @Test
    fun aRunningSessionTakesPriorityOverAStaleBreakField() {
        // pomodoroEnd and breakStart are never both meaningful at once in practice, but the
        // decision itself must not get confused if it ever sees both set.
        assertEquals(
            FocusAlarmRestoreAction.SCHEDULE,
            focusAlarmRestoreAction(endMillis = 2_000L, breakStartMillis = 500L, nowMillis = 1_000L),
        )
    }
}
