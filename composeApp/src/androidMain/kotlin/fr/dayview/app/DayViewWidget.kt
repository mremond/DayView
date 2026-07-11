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
import android.os.Build
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

internal enum class DayViewWidgetLayout(val layoutResource: Int) {
    COMPACT(R.layout.dayview_widget_compact),
    MEDIUM(R.layout.dayview_widget),
    LARGE(R.layout.dayview_widget_large),
}

internal fun selectDayViewWidgetLayout(widthDp: Int, heightDp: Int): DayViewWidgetLayout = when {
    widthDp <= 0 || heightDp <= 0 -> DayViewWidgetLayout.MEDIUM
    widthDp < 230 || heightDp < 115 -> DayViewWidgetLayout.COMPACT
    widthDp >= 300 && heightDp >= 170 -> DayViewWidgetLayout.LARGE
    else -> DayViewWidgetLayout.MEDIUM
}

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
            val snap = runBlocking { DayViewPreferences.get(context).snapshots.first() }
            val now = System.currentTimeMillis()
            val progress = calculateDayProgress(
                nowMillis = now,
                startMinutesOfDay = snap.startMinutes,
                endMinutesOfDay = snap.endMinutes,
            )
            val content = WidgetContent(
                progress = progress,
                goal = snap.goalTitle.trim(),
                focusEndMillis = snap.pomodoroEndMillis?.takeIf { it > now },
                focusIntention = snap.focusIntention.trim(),
                nowMillis = now,
                ring = renderRing(context, progress),
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.updateAppWidget(widgetId, responsiveViews(context, widgetId, content))
            } else {
                val options = manager.getAppWidgetOptions(widgetId)
                val layout = selectDayViewWidgetLayout(
                    widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
                    heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT),
                )
                manager.updateAppWidget(widgetId, createViews(context, widgetId, layout, content))
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun responsiveViews(
            context: Context,
            widgetId: Int,
            content: WidgetContent,
        ): RemoteViews = RemoteViews(
            mapOf(
                SizeF(180f, 90f) to createViews(context, widgetId, DayViewWidgetLayout.COMPACT, content),
                SizeF(230f, 115f) to createViews(context, widgetId, DayViewWidgetLayout.MEDIUM, content),
                SizeF(300f, 170f) to createViews(context, widgetId, DayViewWidgetLayout.LARGE, content),
            ),
        )

        private fun createViews(
            context: Context,
            widgetId: Int,
            layout: DayViewWidgetLayout,
            content: WidgetContent,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, layout.layoutResource)
            val openApp = PendingIntent.getActivity(
                context,
                widgetId,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            views.setOnClickPendingIntent(R.id.widget_root, openApp)
            views.setImageViewBitmap(R.id.widget_ring, content.ring)
            views.setContentDescription(
                R.id.widget_ring,
                context.getString(R.string.widget_ring_description, content.progress.percentageRemaining),
            )
            views.setTextViewText(R.id.widget_remaining, formatRemaining(content.progress))
            views.setTextViewText(R.id.widget_day_status, formatDayStatus(context, content.progress))
            views.setTextViewText(
                R.id.widget_day_range,
                context.getString(
                    R.string.widget_day_range,
                    formatTime(content.progress.startHour, content.progress.startMinute),
                    formatTime(content.progress.endHour, content.progress.endMinute),
                ),
            )
            views.setViewVisibility(
                R.id.widget_day_range,
                if (layout == DayViewWidgetLayout.COMPACT) View.GONE else View.VISIBLE,
            )

            val showGoal = content.goal.isNotBlank() &&
                when (layout) {
                    DayViewWidgetLayout.COMPACT -> false
                    DayViewWidgetLayout.MEDIUM -> content.focusEndMillis == null
                    DayViewWidgetLayout.LARGE -> true
                }
            views.setViewVisibility(R.id.widget_goal, if (showGoal) View.VISIBLE else View.GONE)
            views.setTextViewText(R.id.widget_goal, context.getString(R.string.widget_goal, content.goal))

            val focusEnd = content.focusEndMillis
            val focusIsActive = focusEnd != null
            views.setViewVisibility(R.id.widget_focus, if (focusIsActive) View.VISIBLE else View.GONE)
            if (focusIsActive) {
                val intention = content.focusIntention.ifBlank { context.getString(R.string.widget_focus_default) }
                views.setTextViewText(R.id.widget_focus_intention, intention)
                views.setViewVisibility(
                    R.id.widget_focus_intention,
                    if (layout == DayViewWidgetLayout.COMPACT) View.GONE else View.VISIBLE,
                )
                views.setChronometer(
                    R.id.widget_focus_countdown,
                    SystemClock.elapsedRealtime() + (focusEnd - content.nowMillis),
                    null,
                    true,
                )
                views.setChronometerCountDown(R.id.widget_focus_countdown, true)
            } else {
                views.setViewVisibility(R.id.widget_focus_intention, View.GONE)
                views.setChronometer(R.id.widget_focus_countdown, SystemClock.elapsedRealtime(), null, false)
            }
            return views
        }

        private data class WidgetContent(
            val progress: DayProgress,
            val goal: String,
            val focusEndMillis: Long?,
            val focusIntention: String,
            val nowMillis: Long,
            val ring: Bitmap,
        )

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

        private fun formatTime(hour: Int, minute: Int): String = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)

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
