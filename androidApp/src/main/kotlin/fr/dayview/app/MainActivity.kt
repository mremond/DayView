package fr.dayview.app

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import dev.whyoleg.cryptography.random.CryptographyRandom
import fr.dayview.app.sync.Aes256GcmCodec
import fr.dayview.app.sync.FileSyncStatePersistence
import fr.dayview.app.sync.HttpSyncTransport
import fr.dayview.app.sync.SyncCoordinator
import fr.dayview.app.sync.androidSecureKeyStore
import fr.dayview.app.sync.createSyncHttpClient
import fr.dayview.app.sync.deviceIdOrCreate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.time.Clock

class MainActivity : ComponentActivity() {
    private lateinit var preferences: DayPreferences
    private lateinit var focusAlarmScheduler: FocusAlarmScheduler
    private var requestExactAfterNotificationPermission = false

    // Owns the SyncCoordinator's background work (auto-retry jobs); cancelled in
    // onDestroy so a recreated Activity never leaves an orphaned retry loop running.
    private val syncScope = CoroutineScope(Dispatchers.Default)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (requestExactAfterNotificationPermission && granted) {
            requestExactAfterNotificationPermission = false
            requestExactAlarmAccess()
        } else {
            requestExactAfterNotificationPermission = false
        }
        if (granted) restoreActiveFocusAlarm()
    }
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Le recalcul du temps net relit l'état de l'autorisation. */ }

    @OptIn(ExperimentalStdlibApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = DayViewPreferences.get(applicationContext)
        focusAlarmScheduler = FocusAlarmScheduler(applicationContext)
        initCalendarSource(applicationContext)
        restoreActiveFocusAlarm()

        val keyStore = androidSecureKeyStore(applicationContext)
        val stateFile = File(filesDir, "sync/state.json")
        val statePersistence = FileSyncStatePersistence(
            read = { stateFile.takeIf { it.exists() }?.readText() },
            write = {
                stateFile.parentFile?.mkdirs()
                stateFile.writeText(it)
            },
        )
        val deviceId = keyStore.deviceIdOrCreate { CryptographyRandom.nextBytes(16).toHexString() }
        val syncCoordinator = SyncCoordinator(
            deviceId = deviceId,
            keyStore = keyStore,
            statePersistence = statePersistence,
            preferences = preferences,
            transportFactory = { HttpSyncTransport(createSyncHttpClient(), it.baseUrl, it.userId, it.token) },
            codecFactory = { Aes256GcmCodec(it) },
            scope = syncScope,
            now = { Clock.System.now().toEpochMilliseconds() },
            historyStore = DayViewPreferences.history(),
            focusContributionStore = DayViewPreferences.focusContributions(),
        )

        setContent {
            DayViewApp(
                preferences = preferences,
                history = DayViewPreferences.history(),
                focusContributions = DayViewPreferences.focusContributions(),
                deviceId = deviceId,
                onFocusAlarmChange = { end, intention, sessionMinutes ->
                    if (end == null) {
                        focusAlarmScheduler.cancel()
                    } else {
                        val scheduledExactly = focusAlarmScheduler.schedule(
                            end.toEpochMilliseconds(),
                            intention,
                            sessionMinutes,
                        )
                        requestRequiredAccess(requestExactAlarm = !scheduledExactly)
                    }
                },
                // Fired only once a closure actually landed and it was not a detour exit: the
                // break is anchored on that instant, never on the term the session ran past.
                onFocusBreakStarted = { focusAlarmScheduler.close(it) },
                onRequestCalendarPermission = {
                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                },
                onOpenPowerSettings = { openPowerManagementSettings(this) },
                derivesEngagedFromSessions = true,
                secureKeyStore = keyStore,
                syncCoordinator = syncCoordinator,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        DayViewWidget.updateAll(applicationContext)
        if (::focusAlarmScheduler.isInitialized) {
            restoreActiveFocusAlarm()
        }
    }

    override fun onDestroy() {
        syncScope.cancel()
        super.onDestroy()
    }

    private fun restoreActiveFocusAlarm() {
        val snap = runBlocking { preferences.snapshots.first() }
        val sessionMinutes = snap.sessionMinutesEffective
        val endMillis = snap.pomodoroEnd?.toEpochMilliseconds()
        val breakStartMillis = snap.breakStart?.toEpochMilliseconds()
        // The BREAK_VISIBLE_MAX cutoff and the open-detour guard both live inside
        // FocusAlarmScheduler.restoreBreakReminders now, so this decision only needs to route
        // to the right primitive — it does not need to re-derive "is this break still worth
        // showing" itself.
        when (focusAlarmRestoreAction(endMillis, breakStartMillis, System.currentTimeMillis())) {
            FocusAlarmRestoreAction.SCHEDULE ->
                focusAlarmScheduler.schedule(requireNotNull(endMillis), snap.focusIntention, sessionMinutes)
            FocusAlarmRestoreAction.RESTORE_OVERTIME ->
                // The term passed without a closure: the session is still running, upwards.
                focusAlarmScheduler.restoreOvertime(requireNotNull(endMillis), snap.focusIntention, sessionMinutes)
            FocusAlarmRestoreAction.RESTORE_BREAK ->
                focusAlarmScheduler.restoreBreakReminders(
                    breakStartMillis = requireNotNull(breakStartMillis),
                    intention = snap.focusIntention,
                    hasOpenDetour = snap.openDetourStart != null,
                )
            FocusAlarmRestoreAction.NOTHING -> Unit
        }
    }

    private fun requestRequiredAccess(requestExactAlarm: Boolean) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestExactAfterNotificationPermission = requestExactAlarm
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (requestExactAlarm) {
            requestExactAlarmAccess()
        }
    }

    private fun requestExactAlarmAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager.canScheduleExactAlarms()) return
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:$packageName"),
            ),
        )
    }
}

internal enum class FocusAlarmRestoreAction {
    SCHEDULE,
    RESTORE_OVERTIME,
    RESTORE_BREAK,
    NOTHING,
}

/**
 * Mirrors [focusTileState]'s split: a term in the future is scheduled, a term already past is
 * overtime, and only the absence of a session leaves room for a break. Unlike the tile's
 * decision, this one does not need its own BREAK_VISIBLE_MAX cutoff — that gate (and the
 * open-detour guard) live inside [FocusAlarmScheduler.restoreBreakReminders] itself, so both of
 * its callers (this restore path and [FocusAlarmReceiver]'s break-reminder branch) are covered
 * by the same check rather than by one the other bypasses.
 */
internal fun focusAlarmRestoreAction(
    endMillis: Long?,
    breakStartMillis: Long?,
    nowMillis: Long,
): FocusAlarmRestoreAction = when {
    endMillis != null && endMillis > nowMillis -> FocusAlarmRestoreAction.SCHEDULE
    endMillis != null -> FocusAlarmRestoreAction.RESTORE_OVERTIME
    breakStartMillis != null -> FocusAlarmRestoreAction.RESTORE_BREAK
    else -> FocusAlarmRestoreAction.NOTHING
}
