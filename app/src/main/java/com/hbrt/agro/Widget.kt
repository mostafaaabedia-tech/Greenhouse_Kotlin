package com.hbrt.agro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Home screen widget: temperature, humidity, soil, tank and a "Water 10 s" button. Works from anywhere through the cloud. */
class WidgetProvider : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        Prefs.init(c)
        Widget.render(c)
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { Widget.refreshFromCloud(c) } finally { done.finish() }
        }
    }

    override fun onReceive(c: Context, intent: Intent) {
        super.onReceive(c, intent)
        if (intent.action != Widget.ACTION_WATER) return
        Prefs.init(c)
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = Widget.water(c, 10)
                withContext(Dispatchers.Main) {
                    Toast.makeText(c, if (ok) c.tr(R.string.watering_for, 10) else c.tr(R.string.a_offline), Toast.LENGTH_SHORT).show()
                }
            } finally { done.finish() }
        }
    }
}

object Widget {
    const val ACTION_WATER = "com.hbrt.agro.WATER"

    /** Called by the live connection (at most once a minute). */
    fun update(c: Context, r: Reading) {
        Prefs.widgetJson = JSONObject().put("name", r.name).put("t", r.temperature.toDouble()).put("h", r.humidity.toDouble())
            .put("s", r.soil).put("w", r.water).put("ts", r.ts).put("al", r.alerts(GreenhouseClient.cfg.value).size).toString()
        render(c)
    }

    private fun ref(): CloudRef? {
        val addr = Prefs.lastAddress
        return CloudRef.parse(addr) ?: CloudRef.parse(Prefs.greenhouses.firstOrNull { it.addr.equals(addr, true) }?.cloud)
    }

    suspend fun refreshFromCloud(c: Context) {
        val ref = ref() ?: return
        try {
            val text = GreenhouseClient.cloudHttp.newCall(Request.Builder().url("${ref.base}/live/s.json").build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return
            if (text == "null") return
            val r = Reading.parse(JSONObject(text), JSONObject(text).optLong("t", System.currentTimeMillis())) ?: return
            update(c, r)
        } catch (e: Exception) { }
    }

    suspend fun water(c: Context, sec: Int): Boolean {
        if (GreenhouseClient.link.value == Link.ONLINE && !GreenhouseClient.readOnly.value) return GreenhouseClient.waterNow(sec)
        val ref = ref() ?: return false
        if (ref.ro) return false
        val body = JSONObject().put("device", "water").put("value", sec).put("t", JSONObject().put(".sv", "timestamp")).toString()
        return try {
            GreenhouseClient.cloudHttp.newCall(Request.Builder().url("${ref.base}/in/cmd.json").post(body.toRequestBody(GreenhouseClient.JSON_TYPE)).build())
                .execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    fun render(c: Context) {
        val mgr = AppWidgetManager.getInstance(c)
        val ids = mgr.getAppWidgetIds(ComponentName(c, WidgetProvider::class.java))
        if (ids.isEmpty()) return
        val v = RemoteViews(c.packageName, R.layout.widget)
        val j = try { JSONObject(Prefs.widgetJson) } catch (e: Exception) { null }
        if (j != null) {
            v.setTextViewText(R.id.w_name, j.optString("name").ifBlank { c.tr(R.string.a_widget_name) })
            v.setTextViewText(R.id.w_t, j.optDouble("t").toFloat().f1() + "°")
            v.setTextViewText(R.id.w_h, j.optDouble("h").toFloat().f0() + "%")
            v.setTextViewText(R.id.w_s, "${j.optInt("s")}%")
            v.setTextViewText(R.id.w_w, "${j.optInt("w")}%")
            v.setTextViewText(R.id.w_time, DateFormat.getTimeInstance(DateFormat.SHORT, Lang.locale()).format(Date(j.optLong("ts"))))
            val n = j.optInt("al")
            v.setTextViewText(R.id.w_status, "${c.tr(R.string.m_air)} · ${c.tr(R.string.m_hum)} · ${c.tr(R.string.m_soil)} · ${c.tr(R.string.m_tank)}" +
                if (n > 0) "\n⚠ ${c.tr(R.string.n_alerts_n, n)}" else "")
        }
        v.setTextViewText(R.id.w_water, c.tr(R.string.a_widget_water))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.w_root, open)
        val water = PendingIntent.getBroadcast(c, 1, Intent(c, WidgetProvider::class.java).setAction(ACTION_WATER), PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.w_water, water)
        mgr.updateAppWidget(ids, v)
    }
}
