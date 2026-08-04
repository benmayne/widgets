package dev.ben.aqiwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.util.Log
import android.util.TypedValue
import android.widget.RemoteViews
import java.util.concurrent.Executors

class AqiWidgetProvider : AppWidgetProvider() {

    /**
     * onUpdate runs on the main thread, where a network call throws
     * NetworkOnMainThreadException. goAsync() plus a background executor keeps the process
     * alive for the ~10s the fetch needs, which is ample for a 250-byte response.
     */
    override fun onReceive(context: Context, intent: Intent) {
        val handled = intent.action == ACTION_REFRESH ||
            intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE
        if (!handled) {
            super.onReceive(context, intent)
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        EXECUTOR.execute {
            try {
                render(app, AppGraph.repository(app).refresh())
            } catch (t: Throwable) {
                Log.e(TAG, "widget update failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "dev.ben.aqiwidget.ACTION_REFRESH"

        private const val TAG = "AqiWidget"
        private val EXECUTOR = Executors.newSingleThreadExecutor()

        /** Broadcasts a refresh request. Safe to call from the main thread. */
        fun requestRefresh(context: Context) {
            context.sendBroadcast(
                Intent(context, AqiWidgetProvider::class.java).setAction(ACTION_REFRESH)
            )
        }

        fun render(context: Context, state: RenderState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, AqiWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, buildViews(context, state))
        }

        private fun buildViews(context: Context, state: RenderState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_aqi)
            if (state is RenderState.Ok) {
                val category = AqiScale.categoryFor(state.aqi)
                val background =
                    if (state.stale) AqiScale.dim(category.background) else category.background
                val text = state.aqi.toString()
                views.setTextViewText(R.id.aqi_value, text)
                views.setTextColor(R.id.aqi_value, category.foreground)
                // autoSizeText is unavailable through RemoteViews, so size by digit count.
                views.setTextViewTextSize(
                    R.id.aqi_value,
                    TypedValue.COMPLEX_UNIT_SP,
                    if (text.length >= 3) 19f else 24f,
                )
                views.setColorStateList(
                    R.id.widget_root,
                    "setBackgroundTintList",
                    ColorStateList.valueOf(background),
                )
            } else {
                views.setTextViewText(R.id.aqi_value, context.getString(R.string.dash))
                views.setTextColor(R.id.aqi_value, AqiScale.NEUTRAL_FOREGROUND)
                views.setTextViewTextSize(R.id.aqi_value, TypedValue.COMPLEX_UNIT_SP, 24f)
                views.setColorStateList(
                    R.id.widget_root,
                    "setBackgroundTintList",
                    ColorStateList.valueOf(AqiScale.NEUTRAL_BACKGROUND),
                )
            }
            views.setOnClickPendingIntent(R.id.widget_root, tapIntent(context, state))
            return views
        }

        /** Tapping opens setup when the user must act; otherwise it refreshes in place. */
        private fun tapIntent(context: Context, state: RenderState): PendingIntent =
            when (state) {
                RenderState.NeedsPermission, RenderState.NoLocation -> PendingIntent.getActivity(
                    context,
                    REQUEST_SETUP,
                    Intent(context, SetupActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                else -> PendingIntent.getBroadcast(
                    context,
                    REQUEST_REFRESH,
                    Intent(context, AqiWidgetProvider::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }

        private const val REQUEST_SETUP = 1
        private const val REQUEST_REFRESH = 2
    }
}
