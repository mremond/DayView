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
import kotlin.time.Instant

internal enum class FocusTileAction {
    OPEN_APP,
    START_FOCUS,
}

internal enum class FocusTileState {
    IDLE,
    ACTIVE,
    BREAK,
}

internal fun focusTileAction(
    state: FocusTileState,
    intention: String,
    canPostNotifications: Boolean,
): FocusTileAction = if (
    state != FocusTileState.ACTIVE && intention.isNotBlank() && canPostNotifications
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
            val tileState = focusTileState(snap.pomodoroEnd?.toEpochMilliseconds(), now)
            when (
                focusTileAction(
                    state = tileState,
                    intention = snap.focusIntention,
                    canPostNotifications = canPostNotifications(),
                )
            ) {
                FocusTileAction.OPEN_APP -> openDayView()
                FocusTileAction.START_FOCUS -> {
                    if (snap.openDetourStart == null) {
                        val endMillis = now + snap.pomodoroMinutes.coerceIn(5, 180) * 60_000L
                        runBlocking {
                            preferences.persist(snap.copy(pomodoroEnd = Instant.fromEpochMilliseconds(endMillis)))
                        }
                        FocusAlarmScheduler(applicationContext).schedule(
                            endMillis,
                            snap.focusIntention,
                        )
                    }
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
            System.currentTimeMillis(),
        )
        tile.state = if (tileState == FocusTileState.IDLE) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = getString(
            when (tileState) {
                FocusTileState.ACTIVE -> R.string.focus_tile_active
                FocusTileState.BREAK -> R.string.focus_tile_break
                FocusTileState.IDLE -> R.string.focus_tile_start
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (tileState) {
                FocusTileState.ACTIVE -> snap.focusIntention.trim().take(30)
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

internal fun focusTileState(endMillis: Long?, nowMillis: Long): FocusTileState = when {
    endMillis == null -> FocusTileState.IDLE
    endMillis > nowMillis -> FocusTileState.ACTIVE
    else -> FocusTileState.BREAK
}
