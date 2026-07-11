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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    private lateinit var preferences: AndroidDayPreferences
    private lateinit var focusAlarmScheduler: FocusAlarmScheduler
    private var displayedFocusEndMillis: Long? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AndroidDayPreferences(applicationContext)
        focusAlarmScheduler = FocusAlarmScheduler(applicationContext)
        displayedFocusEndMillis = preferences.loadPomodoroEndMillis()
        restoreActiveFocusAlarm()
        setContent {
            DayViewApp(
                preferences = preferences,
                onFocusAlarmChange = { endMillis, intention ->
                    if (endMillis == null) {
                        focusAlarmScheduler.cancel()
                    } else {
                        val scheduledExactly = focusAlarmScheduler.schedule(endMillis, intention)
                        requestRequiredAccess(requestExactAlarm = !scheduledExactly)
                    }
                    displayedFocusEndMillis = endMillis
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        DayViewWidget.updateAll(applicationContext)
        if (::focusAlarmScheduler.isInitialized) {
            if (recreateIfFocusChanged()) return
            restoreActiveFocusAlarm()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::preferences.isInitialized) recreateIfFocusChanged()
    }

    private fun recreateIfFocusChanged(): Boolean {
        if (preferences.loadPomodoroEndMillis() == displayedFocusEndMillis) return false
        recreate()
        return true
    }

    private fun restoreActiveFocusAlarm() {
        val endMillis = preferences.loadPomodoroEndMillis() ?: return
        if (endMillis > System.currentTimeMillis()) {
            focusAlarmScheduler.schedule(endMillis, preferences.loadFocusIntention())
        } else {
            focusAlarmScheduler.restoreBreakReminders(endMillis, preferences.loadFocusIntention())
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
