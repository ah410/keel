package com.arceusvault.keel.data

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.graphics.toColorInt
import android.widget.RemoteViews
import com.arceusvault.keel.MainActivity
import com.arceusvault.keel.R
import com.arceusvault.keel.KeelApp
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen widget fed by the background alert check. It shows counts and severity only, never
 * alert text, since a widget is visible without unlocking the phone.
 */
class AlertWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, AlertWidget::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val watch = (context.applicationContext as KeelApp).container.alertWatch
            val views = RemoteViews(context.packageName, R.layout.widget_alerts)
            val summary = watch.summary()
            val (status, color, detail) = when {
                !watch.enabled.value -> Triple("Not set up", MUTED, "Turn on alert notifications in the app")
                summary == null -> Triple("Waiting", MUTED, "No check has run yet")
                else -> describe(summary, watch.lastError)
            }
            views.setTextViewText(R.id.widget_title, summary?.host ?: "TrueNAS")
            views.setTextViewText(R.id.widget_status, status)
            views.setTextColor(R.id.widget_status, color)
            views.setTextViewText(R.id.widget_detail, detail)
            val open = PendingIntent.getActivity(
                context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }

        internal fun describe(s: AlertWatch.Summary, error: String?): Triple<String, Int, String> {
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.checkedAt))
            val total = s.counts.values.sum()
            val worst = AlertWatch.LEVELS.firstOrNull { (s.counts[it] ?: 0) > 0 }
            val status = if (total == 0) "All clear" else "$total alert${if (total > 1) "s" else ""}"
            val color = when (worst) {
                null -> GREEN
                "EMERGENCY", "ALERT", "CRITICAL", "ERROR" -> RED
                "WARNING" -> ORANGE
                else -> BLUE
            }
            val parts = AlertWatch.LEVELS.mapNotNull { l -> s.counts[l]?.takeIf { it > 0 }?.let { "$it ${l.lowercase(Locale.ROOT)}" } }
            val detail = (parts + (if (error != null) "last check failed" else "checked $time")).joinToString(" · ")
            return Triple(status, color, detail)
        }

        // TrueNAS ix-dark status colors.
        private val GREEN = "#5CC98A".toColorInt()
        private val ORANGE = "#F4A259".toColorInt()
        private val RED = "#EF6461".toColorInt()
        private val BLUE = "#5AA9E6".toColorInt()
        private val MUTED = "#A6FFFFFF".toColorInt()
    }
}
