package fr.dayview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = AndroidDayPreferences(applicationContext)
        setContent { DayViewApp(preferences) }
    }
}
