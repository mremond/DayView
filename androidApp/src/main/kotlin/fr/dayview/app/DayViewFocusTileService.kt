package fr.dayview.app

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Instant

internal enum class FocusTileAction {
    OPEN_APP,
    START_FOCUS,
}

internal enum class FocusTileState {
    IDLE,
    ACTIVE,
    OVERTIME,
    BREAK,
}

/**
 * The tile only offers its one-tap start where a start can actually succeed. A session that
 * is running — whether inside its term (ACTIVE) or past it (OVERTIME) — and an open detour
 * both make [DayViewController.startPomodoro] a no-op, so in those states the tap opens the
 * app instead of rendering a control that would do nothing (or worse, overwrite a running
 * session's end).
 *
 * Entering focus is free, so the intention plays no part here: it is invited at the close.
 * A tile labelled "Start Focus" that opened the app instead, because nothing was named yet,
 * would be the abolished toll surviving on the one surface that cannot ask for a name.
 */
internal fun focusTileAction(
    state: FocusTileState,
    canPostNotifications: Boolean,
    hasOpenDetour: Boolean,
): FocusTileAction = if (
    state != FocusTileState.ACTIVE &&
    state != FocusTileState.OVERTIME &&
    !hasOpenDetour &&
    canPostNotifications
) {
    FocusTileAction.START_FOCUS
} else {
    FocusTileAction.OPEN_APP
}

class DayViewFocusTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        unlockAndRun {
            val preferences = DayViewPreferences.get(applicationContext)
            val now = System.currentTimeMillis()
            val snap = runBlocking { preferences.snapshots.first() }
            val tileState = focusTileState(
                snap.pomodoroEnd?.toEpochMilliseconds(),
                snap.breakStart?.toEpochMilliseconds(),
                now,
            )
            when (
                focusTileAction(
                    state = tileState,
                    canPostNotifications = canPostNotifications(),
                    hasOpenDetour = snap.openDetourStart != null,
                )
            ) {
                FocusTileAction.OPEN_APP -> openDayView()
                FocusTileAction.START_FOCUS -> {
                    val durationMinutes = snap.pomodoroMinutes.coerceIn(5, 180)
                    val endMillis = now + durationMinutes * 60_000L
                    runBlocking {
                        // The whole session snapshot, as DayViewController.startPomodoro writes it.
                        preferences.persist(
                            snap.copy(
                                pomodoroEnd = Instant.fromEpochMilliseconds(endMillis),
                                pomodoroSessionMinutes = durationMinutes,
                                breakStart = null,
                            ),
                        )
                    }
                    FocusAlarmScheduler(applicationContext).schedule(
                        endMillis,
                        snap.focusIntention,
                        durationMinutes,
                    )
                    updateTile()
                }
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val snap = runBlocking { DayViewPreferences.get(applicationContext).snapshots.first() }
        val tileState = focusTileState(
            snap.pomodoroEnd?.toEpochMilliseconds(),
            snap.breakStart?.toEpochMilliseconds(),
            System.currentTimeMillis(),
        )
        tile.state = if (tileState == FocusTileState.IDLE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(
            when (tileState) {
                FocusTileState.ACTIVE -> R.string.focus_tile_active
                FocusTileState.OVERTIME -> R.string.focus_tile_overtime
                FocusTileState.BREAK -> R.string.focus_tile_break
                FocusTileState.IDLE -> R.string.focus_tile_start
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (tileState) {
                FocusTileState.ACTIVE -> snap.focusIntention.trim().take(30)
                FocusTileState.OVERTIME -> formatOvertimeLabel(
                    calculatePomodoroProgress(
                        now = Clock.System.now(),
                        durationMinutes = snap.sessionMinutesEffective,
                        end = snap.pomodoroEnd,
                    ),
                )
                FocusTileState.BREAK -> getString(R.string.focus_tile_break_subtitle)
                FocusTileState.IDLE -> getString(R.string.focus_tile_subtitle)
            }
        }
        tile.updateTile()
    }

    private fun openDayView() {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                OPEN_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun canPostNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val OPEN_REQUEST_CODE = 4120

        fun requestRefresh(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context, DayViewFocusTileService::class.java),
                )
            }
        }
    }
}

/**
 * Mirrors [calculatePomodoroProgress]: a term that has passed is *overtime*, not a break.
 * A break exists only once the session was closed — that closure is what sets [breakStartMillis].
 */
internal fun focusTileState(
    endMillis: Long?,
    breakStartMillis: Long?,
    nowMillis: Long,
): FocusTileState = when {
    endMillis != null && endMillis > nowMillis -> FocusTileState.ACTIVE
    endMillis != null -> FocusTileState.OVERTIME
    breakStartMillis != null &&
        nowMillis - breakStartMillis <= BREAK_VISIBLE_MAX.inWholeMilliseconds -> FocusTileState.BREAK
    else -> FocusTileState.IDLE
}
