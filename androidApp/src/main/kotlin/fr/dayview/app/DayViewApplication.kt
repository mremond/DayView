package fr.dayview.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Keeps placed widgets current when the user comes back to the device.
 *
 * The widget's ring and remaining-time text are static snapshots redrawn only on the
 * sparse system triggers ([DayViewWidget] handles time/date changes and the 30-minute
 * [android.appwidget.action.APPWIDGET_UPDATE]); only the focus countdown ticks on its
 * own. Between those, the reading goes stale. [Intent.ACTION_USER_PRESENT] fires when the
 * device is unlocked but cannot be delivered to a manifest-declared receiver, so we
 * register for it here, from the live process, and redraw as soon as the user is back.
 */
class DayViewApplication : Application() {
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DayViewWidget.updateAll(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The widget is a manifest component in this module, so :shared can't redraw it after a
        // preferences write directly; register the refresh side effect here instead.
        DayViewPreferences.snapshotListener = { context, snapshot ->
            DayViewWidget.render(context, snapshot)
        }
        // ACTION_USER_PRESENT is a protected system broadcast, so no exported flag is needed.
        registerReceiver(userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
    }
}
