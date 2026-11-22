package com.printwidget.bambu

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import java.util.Date

class PrintWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        RefreshWorker.schedulePeriodic(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
        RefreshWorker.schedulePeriodic(context)
        RefreshWorker.refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        RefreshWorker.cancelAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            // Show "Refreshing…" right away so the tap feels responsive.
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PrintWidgetProvider::class.java))
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_print)
                views.setTextViewText(R.id.updated, context.getString(R.string.refreshing))
                manager.partiallyUpdateAppWidget(id, views)
            }
            RefreshWorker.refreshNow(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_REFRESH = "com.printwidget.bambu.REFRESH"
        private const val HANDY_PACKAGE = "bbl.intl.bambulab.com"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PrintWidgetProvider::class.java))
            if (ids.isNotEmpty()) render(context, manager, ids)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = buildViews(context)
            for (id in ids) manager.updateAppWidget(id, views)
        }

        private fun buildViews(context: Context): RemoteViews {
            val store = Store(context)
            val v = RemoteViews(context.packageName, R.layout.widget_print)

            val refreshIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, PrintWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.refresh, refreshIntent)

            // Tapping the widget opens Bambu Handy if it's installed, otherwise this app.
            val openApp = if (store.isSignedIn && store.lastError.isEmpty()) {
                context.packageManager.getLaunchIntentForPackage(HANDY_PACKAGE)
            } else null
            val target = openApp ?: Intent(context, MainActivity::class.java)
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(context, 1, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )

            if (!store.isSignedIn) {
                showMessage(v, context.getString(R.string.app_name), context.getString(R.string.widget_sign_in))
                v.setTextViewText(R.id.updated, "")
                return v
            }

            val status = store.statusToShow()
            if (status == null) {
                showMessage(v, context.getString(R.string.app_name), store.lastError.ifEmpty { context.getString(R.string.widget_loading) })
                v.setTextViewText(R.id.updated, "")
                return v
            }

            v.setTextViewText(R.id.printer_name, status.printerName)
            v.setTextViewText(R.id.state, stateLabel(context, status.state))

            if (status.isActive) {
                v.setViewVisibility(R.id.job_name, View.VISIBLE)
                v.setViewVisibility(R.id.countdown, View.VISIBLE)
                v.setViewVisibility(R.id.message, View.GONE)
                v.setViewVisibility(R.id.progress, View.VISIBLE)
                v.setViewVisibility(R.id.detail, View.VISIBLE)
                v.setTextViewText(R.id.job_name, status.jobName.ifEmpty { context.getString(R.string.unnamed_job) })

                // A Chronometer counts down on the home screen by itself, between cloud refreshes.
                val msLeft = status.finishAt - System.currentTimeMillis()
                val paused = status.state == "PAUSE"
                v.setChronometer(R.id.countdown, SystemClock.elapsedRealtime() + msLeft, null, !paused)
                v.setChronometerCountDown(R.id.countdown, true)

                v.setProgressBar(R.id.progress, 100, status.percent.coerceIn(0, 100), false)
                val finish = DateFormat.getTimeFormat(context).format(Date(status.finishAt))
                val layers = if (status.totalLayers > 0) " · ${status.layer}/${status.totalLayers}" else ""
                v.setTextViewText(
                    R.id.detail,
                    context.getString(R.string.detail_format, status.percent, finish) + layers,
                )
            } else {
                val msg = when (status.state) {
                    "FINISH" -> context.getString(R.string.msg_finished, status.jobName.ifEmpty { context.getString(R.string.unnamed_job) })
                    "FAILED" -> context.getString(R.string.msg_failed)
                    "OFFLINE" -> context.getString(R.string.msg_offline)
                    "NO_DATA" -> context.getString(R.string.msg_no_data)
                    else -> context.getString(R.string.msg_idle)
                }
                v.setViewVisibility(R.id.job_name, View.GONE)
                v.setViewVisibility(R.id.countdown, View.GONE)
                v.setViewVisibility(R.id.progress, View.GONE)
                v.setViewVisibility(R.id.detail, View.GONE)
                v.setViewVisibility(R.id.message, View.VISIBLE)
                v.setTextViewText(R.id.message, msg)
            }

            val updated = DateFormat.getTimeFormat(context).format(Date(status.fetchedAt))
            v.setTextViewText(
                R.id.updated,
                store.lastError.ifEmpty { context.getString(R.string.updated_format, updated) },
            )
            return v
        }

        private fun showMessage(v: RemoteViews, title: String, message: String) {
            v.setTextViewText(R.id.printer_name, title)
            v.setTextViewText(R.id.state, "")
            v.setViewVisibility(R.id.job_name, View.GONE)
            v.setViewVisibility(R.id.countdown, View.GONE)
            v.setViewVisibility(R.id.progress, View.GONE)
            v.setViewVisibility(R.id.detail, View.GONE)
            v.setViewVisibility(R.id.message, View.VISIBLE)
            v.setTextViewText(R.id.message, message)
        }

        fun stateLabel(context: Context, state: String): String = context.getString(
            when (state) {
                "RUNNING" -> R.string.state_printing
                "PREPARE", "SLICING" -> R.string.state_preparing
                "PAUSE" -> R.string.state_paused
                "FINISH" -> R.string.state_finished
                "FAILED" -> R.string.state_failed
                "OFFLINE" -> R.string.state_offline
                "NO_DATA" -> R.string.state_no_data
                else -> R.string.state_idle
            },
        )
    }
}
