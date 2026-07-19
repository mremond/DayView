package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusTileTest {
    @Test
    fun opensAppWhenFocusIsAlreadyActive() {
        assertEquals(
            FocusTileAction.OPEN_APP,
            focusTileAction(
                state = FocusTileState.ACTIVE,
                canPostNotifications = true,
                hasOpenDetour = false,
            ),
        )
    }

    @Test
    fun startsFromIdleWithNothingNamedYetBecauseEnteringFocusIsFree() {
        // The intention is invited at the close, never charged at the entrance — including
        // on the tile, which is the one surface with no way to ask for it.
        assertEquals(
            FocusTileAction.START_FOCUS,
            focusTileAction(state = FocusTileState.IDLE, canPostNotifications = true, hasOpenDetour = false),
        )
    }

    @Test
    fun opensAppWhenTheNotificationPermissionIsMissing() {
        assertEquals(
            FocusTileAction.OPEN_APP,
            focusTileAction(FocusTileState.IDLE, canPostNotifications = false, hasOpenDetour = false),
        )
    }

    @Test
    fun resumesFocusFromBreak() {
        assertEquals(
            FocusTileAction.START_FOCUS,
            focusTileAction(
                state = FocusTileState.BREAK,
                canPostNotifications = true,
                hasOpenDetour = false,
            ),
        )
        assertEquals(
            FocusTileState.BREAK,
            focusTileState(endMillis = null, breakStartMillis = 1_000L, nowMillis = 1_001L),
        )
    }

    @Test
    fun aTermPassedWithoutClosureIsOvertimeNotBreak() {
        assertEquals(
            FocusTileState.OVERTIME,
            focusTileState(endMillis = 1_000L, breakStartMillis = null, nowMillis = 1_001L),
        )
    }

    @Test
    fun aLongForgottenBreakFallsBackToIdle() {
        assertEquals(
            FocusTileState.IDLE,
            focusTileState(endMillis = null, breakStartMillis = 0L, nowMillis = 61 * 60_000L),
        )
    }

    @Test
    fun opensAppInOvertimeBecauseStartingWouldClobberTheRunningSession() {
        assertEquals(
            FocusTileAction.OPEN_APP,
            focusTileAction(
                state = FocusTileState.OVERTIME,
                canPostNotifications = true,
                hasOpenDetour = false,
            ),
        )
    }

    @Test
    fun opensAppWhileAnOpenDetourRunsBecauseAFocusCannotStart() {
        assertEquals(
            FocusTileAction.OPEN_APP,
            focusTileAction(
                state = FocusTileState.BREAK,
                canPostNotifications = true,
                hasOpenDetour = true,
            ),
        )
    }
}
