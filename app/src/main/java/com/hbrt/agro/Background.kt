package com.hbrt.agro

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        History.init(this)
        Notifier.init(this)
        GreenhouseClient.init(this)
        if (Prefs.dailyReport) ReportWorker.schedule(this)
    }
}

/** Strings in the language picked in the app (also for notifications made in the background). */
fun Context.tr(id: Int, vararg args: Any): String = Lang.wrap(this).getString(id, *args)

object Notifier {
    private const val CH_STATUS = "status"
    private const val CH_ALERTS = "alerts"
    const val ID_SERVICE = 1
    private const val ID_REPORT = 2
    private var nextId = 100

    fun init(c: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_STATUS, c.tr(R.string.n_ch_status), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERTS, c.tr(R.string.n_ch_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 150, 300)
                }
            )
        }
    }

    /** The sentence for an alert, in the app's language. */
    fun alertText(c: Context, key: String, r: Reading, wx: Wx?): String = when (key) {
        "hot" -> c.tr(R.string.al_hot, r.temperature.f1())
        "cold" -> c.tr(R.string.al_cold, r.temperature.f1())
        "humid" -> c.tr(R.string.al_humid, r.humidity.f0())
        "dry" -> c.tr(R.string.al_dry, r.soil)
        "tank" -> c.tr(R.string.al_tank)
        "lock" -> c.tr(R.string.al_lock)
        "dht" -> c.tr(R.string.al_dht)
        "st" -> c.tr(R.string.al_st)
        "frost" -> c.tr(R.string.al_frost, wx?.days?.getOrNull(1)?.min?.f0() ?: "--")
        else -> key
    }

    private fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun allowed(c: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun status(c: Context, text: String): Notification = NotificationCompat.Builder(c, CH_STATUS)
        .setSmallIcon(R.drawable.ic_leaf)
        .setContentTitle(c.tr(R.string.n_status_title))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp(c))
        .build()

    @SuppressLint("MissingPermission")
    fun updateStatus(c: Context, text: String) {
        if (allowed(c)) NotificationManagerCompat.from(c).notify(ID_SERVICE, status(c, text))
    }

    @SuppressLint("MissingPermission")
    fun alert(c: Context, title: String, msg: String) {
        if (!allowed(c)) return
        val n = NotificationCompat.Builder(c, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_leaf)
            .setContentTitle(title.ifBlank { c.tr(R.string.n_alert_title) })
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .build()
        NotificationManagerCompat.from(c).notify(nextId++, n)
    }

    @SuppressLint("MissingPermission")
    fun report(c: Context, msg: String) {
        if (!allowed(c)) return
        val n = NotificationCompat.Builder(c, CH_STATUS)
            .setSmallIcon(R.drawable.ic_leaf)
            .setContentTitle(c.tr(R.string.n_report_title))
            .setContentText(msg)
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .build()
        NotificationManagerCompat.from(c).notify(ID_REPORT, n)
    }
}

/**
 * Foreground service: keeps the connection (Wi-Fi or cloud) alive with the screen off or the app
 * closed, so readings keep being logged and alerts still arrive.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        val n = Notifier.status(this, tr(R.string.n_connecting))
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(Notifier.ID_SERVICE, n)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hbrt:ws").apply {
            setReferenceCounted(false)
            acquire()
        }
        scope.launch {
            while (isActive) {
                Notifier.updateStatus(this@MonitorService, statusText())
                delay(5000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (GreenhouseClient.link.value == Link.IDLE && Prefs.autoConnect && Prefs.lastAddress.isNotBlank()) {
            GreenhouseClient.connect(Prefs.lastAddress, persistent = true)
        }
        return START_STICKY
    }

    private fun statusText(): String {
        val r = GreenhouseClient.latest.value
        val n = GreenhouseClient.activeAlerts.value.size
        return when {
            GreenhouseClient.link.value != Link.ONLINE -> tr(R.string.n_lost)
            r == null -> tr(R.string.n_waiting)
            else -> "${r.temperature.f1()}°C   ${r.humidity.f0()}%   ${tr(R.string.m_soil)} ${r.soil}%   ${tr(R.string.m_tank)} ${r.water}%" +
                if (n > 0) "   ⚠ ${tr(R.string.n_alerts_n, n)}" else ""
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, MonitorService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, MonitorService::class.java)) }
    }
}

/** Saves a PDF of the last 24 hours to Downloads/HBRT every evening at 20:00. */
class ReportWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        val data = History.since(System.currentTimeMillis() - 86_400_000L)
        if (data.isEmpty()) return Result.success()
        val f = Exporter.pdf(applicationContext, data, "24 h")
        val saved = Exporter.saveToDownloads(applicationContext, f, "application/pdf")
        Notifier.report(applicationContext, if (saved) applicationContext.tr(R.string.n_report_saved, f.name) else applicationContext.tr(R.string.n_report_made))
        return Result.success()
    }

    companion object {
        private const val NAME = "daily_report"
        fun schedule(c: Context) {
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }
            val req = PeriodicWorkRequestBuilder<ReportWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(next.timeInMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
        fun cancel(c: Context) { WorkManager.getInstance(c).cancelUniqueWork(NAME) }
    }
}
