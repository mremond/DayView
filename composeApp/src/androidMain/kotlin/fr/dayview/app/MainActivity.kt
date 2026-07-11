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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    private lateinit var preferences: DayPreferences
    private lateinit var focusAlarmScheduler: FocusAlarmScheduler
    private var requestExactAfterNotificationPermission = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = DayViewPreferences.get(applicationContext)
        focusAlarmScheduler = FocusAlarmScheduler(applicationContext)
        initCalendarSource(applicationContext)
        restoreActiveFocusAlarm()
        setContent {
            DayViewApp(
                preferences = preferences,
                onFocusAlarmChange = { end, intention ->
                    if (end == null) {
                        focusAlarmScheduler.cancel()
                    } else {
                        val scheduledExactly = focusAlarmScheduler.schedule(end.toEpochMilliseconds(), intention)
                        requestRequiredAccess(requestExactAlarm = !scheduledExactly)
                    }
                },
                onRequestCalendarPermission = {
                    calendarPermissionLauncher.launch(Manifest.permission.READ_CALENDAR)
                },
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

    private fun restoreActiveFocusAlarm() {
        val snap = runBlocking { preferences.snapshots.first() }
        val endMillis = snap.pomodoroEnd?.toEpochMilliseconds() ?: return
        if (endMillis > System.currentTimeMillis()) {
            focusAlarmScheduler.schedule(endMillis, snap.focusIntention)
        } else {
            focusAlarmScheduler.restoreBreakReminders(endMillis, snap.focusIntention)
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
