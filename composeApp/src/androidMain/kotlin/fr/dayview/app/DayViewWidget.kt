package fr.dayview.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class DayViewWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { update(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        update(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action in TIME_CHANGE_ACTIONS) updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, DayViewWidget::class.java)
            manager.getAppWidgetIds(component).forEach { update(context, manager, it) }
        }

        private fun update(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val preferences = AndroidDayPreferences(context, notifyWidgets = false)
            val now = System.currentTimeMillis()
            val progress = calculateDayProgress(
                nowMillis = now,
                startMinutesOfDay = preferences.loadStartMinutes(),
                endMinutesOfDay = preferences.loadEndMinutes(),
            )
            val views = RemoteViews(context.packageName, R.layout.dayview_widget)
            val openApp = PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            views.setImageViewBitmap(R.id.widget_ring, renderRing(context, progress))
            views.setContentDescription(
                R.id.widget_ring,
                context.getString(R.string.widget_ring_description, progress.percentageRemaining),
            )
            views.setTextViewText(R.id.widget_remaining, formatRemaining(progress))
            views.setTextViewText(R.id.widget_day_status, formatDayStatus(context, progress))
            views.setTextViewText(
                R.id.widget_day_range,
                context.getString(
                    R.string.widget_day_range,
                    formatTime(progress.startHour, progress.startMinute),
                    formatTime(progress.endHour, progress.endMinute),
                ),
            )

            val goal = preferences.loadGoalTitle().trim()
            views.setViewVisibility(R.id.widget_goal, if (goal.isBlank()) View.GONE else View.VISIBLE)
            views.setTextViewText(R.id.widget_goal, context.getString(R.string.widget_goal, goal))

            val focusEnd = preferences.loadPomodoroEndMillis()
            val focusIsActive = focusEnd != null && focusEnd > now
            views.setViewVisibility(R.id.widget_focus, if (focusIsActive) View.VISIBLE else View.GONE)
            if (focusIsActive) {
                val intention = preferences.loadFocusIntention().trim()
                    .ifBlank { context.getString(R.string.widget_focus_default) }
                views.setTextViewText(R.id.widget_focus_intention, intention)
                views.setChronometer(
                    R.id.widget_focus_countdown,
                    SystemClock.elapsedRealtime() + (focusEnd - now),
                    null,
                    true,
                )
                views.setChronometerCountDown(R.id.widget_focus_countdown, true)
            } else {
                views.setChronometer(R.id.widget_focus_countdown, SystemClock.elapsedRealtime(), null, false)
            }

            val options = manager.getAppWidgetOptions(widgetId)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            if (minHeight in 1..119) {
                views.setViewVisibility(R.id.widget_goal, View.GONE)
                views.setViewVisibility(R.id.widget_day_range, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_day_range, View.VISIBLE)
            }

            manager.updateAppWidget(widgetId, views)
        }

        private fun formatRemaining(progress: DayProgress): String = when {
            !progress.hasStarted -> formatTime(progress.startHour, progress.startMinute)
            progress.isFinished -> "0 min"
            progress.remainingHours > 0 -> "${progress.remainingHours} h ${progress.remainingMinutes.toString().padStart(2, '0')}"
            else -> "${progress.remainingMinutes} min"
        }

        private fun formatDayStatus(context: Context, progress: DayProgress): String = when {
            !progress.hasStarted -> context.getString(R.string.widget_day_not_started)
            progress.isFinished -> context.getString(R.string.widget_day_finished)
            else -> context.getString(R.string.widget_day_remaining)
        }

        private fun formatTime(hour: Int, minute: Int): String =
            String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

        private fun renderRing(context: Context, progress: DayProgress): Bitmap {
            val size = (112 * context.resources.displayMetrics.density).toInt().coerceAtLeast(112)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val stroke = size * .065f
            val inset = stroke * 1.2f
            val bounds = RectF(inset, inset, size - inset, size - inset)
            val colors = context.resources
            val track = colors.getColor(R.color.dayview_widget_ring_track, context.theme)
            val accent = colors.getColor(
                when {
                    progress.isFinished -> R.color.dayview_widget_red
                    progress.remainingRatio < .2f -> R.color.dayview_widget_amber
                    else -> R.color.dayview_widget_mint
                },
                context.theme,
            )
            val tick = colors.getColor(R.color.dayview_widget_tick, context.theme)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = stroke
                color = track
            }
            canvas.drawArc(bounds, -90f, 360f, false, paint)

            val center = size / 2f
            val outer = center - 1f
            paint.strokeCap = Paint.Cap.BUTT
            paint.strokeWidth = (size * .009f).coerceAtLeast(1f)
            paint.color = tick
            repeat(24) { index ->
                val angle = Math.toRadians(index * 15.0 - 90.0)
                val inner = outer - if (index % 6 == 0) size * .075f else size * .04f
                canvas.drawLine(
                    center + (cos(angle) * inner).toFloat(),
                    center + (sin(angle) * inner).toFloat(),
                    center + (cos(angle) * outer).toFloat(),
                    center + (sin(angle) * outer).toFloat(),
                    paint,
                )
            }

            if (progress.remainingRatio > 0f) {
                val start = currentMomentAngleDegrees(progress.remainingRatio)
                paint.color = accent
                paint.strokeWidth = stroke
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(bounds, start, progress.remainingRatio * 360f, false, paint)
            }
            return bitmap
        }

        private val TIME_CHANGE_ACTIONS = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
        )
    }
}
