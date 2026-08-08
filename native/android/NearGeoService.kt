package com.fit_up.health.capacitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Фоновая служба геолокации.
 *
 * Задача — не прерываться: работает при выключенном экране, при свёрнутом приложении
 * и переживает засыпание телефона. Для этого:
 *   1) это foreground-служба с постоянным уведомлением — Android не убивает такие;
 *   2) частичный wake lock не даёт процессору «уснуть» между координатами;
 *   3) координаты складываются в буфер на диске, поэтому не теряются, даже если
 *      приложение выгружено из памяти;
 *   4) служба сама отправляет точку в облако (NearCloudPush) — ей не нужен ни
 *      открытый экран, ни живой WebView.
 */
class NearGeoService : Service() {

    companion object {
        const val ACTION_START = "near.GEO_START"
        const val ACTION_STOP = "near.GEO_STOP"

        private const val CHANNEL_ID = "near_geo"
        private const val NOTIF_ID = 4711

        const val PREFS = "near_geo"
        private const val KEY_BUFFER = "buffer"
        private const val KEY_WANTED = "wanted"      // пользователь включил слежение
        private const val KEY_INTERVAL = "interval"
        private const val KEY_LAST_TS = "lastTs"
        private const val KEY_LAST_LAT = "lastLat"
        private const val KEY_LAST_LNG = "lastLng"

        private const val MAX_BUFFER = 600

        /** Мост в JS. Ставится плагином, пока приложение открыто. */
        @Volatile
        var listener: ((JSONObject) -> Unit)? = null

        @Volatile
        var running: Boolean = false

        fun isWanted(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

        fun setWanted(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_WANTED, on).apply()
        }

        fun lastFix(ctx: Context): JSONObject? {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ts = p.getLong(KEY_LAST_TS, 0L)
            if (ts <= 0L) return null
            val o = JSONObject()
            o.put("ts", ts)
            o.put("lat", java.lang.Double.longBitsToDouble(p.getLong(KEY_LAST_LAT, 0L)))
            o.put("lng", java.lang.Double.longBitsToDouble(p.getLong(KEY_LAST_LNG, 0L)))
            return o
        }

        /** Забрать накопленные точки и очистить буфер. */
        @Synchronized
        fun drain(ctx: Context): JSONArray {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = p.getString(KEY_BUFFER, "[]") ?: "[]"
            p.edit().putString(KEY_BUFFER, "[]").apply()
            return try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        }

        @Synchronized
        private fun buffer(ctx: Context, point: JSONObject) {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = try { JSONArray(p.getString(KEY_BUFFER, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
            arr.put(point)
            val trimmed = if (arr.length() > MAX_BUFFER) {
                val out = JSONArray()
                for (i in (arr.length() - MAX_BUFFER) until arr.length()) out.put(arr.get(i))
                out
            } else arr
            p.edit().putString(KEY_BUFFER, trimmed.toString()).apply()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var lm: LocationManager? = null
    private var intervalMs: Long = 60_000L
    private var minDistanceM: Float = 0f
    private var lastSentTs = 0L
    private var lastLat = 0.0
    private var lastLng = 0.0
    private var haveLast = false

    private val locListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleFix(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("нужен для Android 8–9")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            stopSelf()
            return START_NOT_STICKY
        }

        val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        intervalMs = (intent?.getIntExtra("interval", 0) ?: 0).toLong().let {
            if (it > 0) it else p.getLong(KEY_INTERVAL, 60_000L)
        }
        if (intervalMs < 5_000L) intervalMs = 5_000L
        minDistanceM = intent?.getFloatExtra("distance", 0f) ?: 0f
        p.edit().putLong(KEY_INTERVAL, intervalMs).putBoolean(KEY_WANTED, true).apply()

        startInForeground()
        startTracking()
        running = true
        // START_STICKY: если Android всё же выгрузит службу под нехватку памяти,
        // система поднимет её обратно сама.
        return START_STICKY
    }

    override fun onDestroy() {
        stopTracking()
        running = false
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Пользователь смахнул приложение из списка задач — слежение должно продолжаться.
        super.onTaskRemoved(rootIntent)
    }

    // ---------- уведомление ----------

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Местоположение",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "Пока включено, близкие видят, где вы находитесь."
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        }
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = if (launch != null)
            PendingIntent.getActivity(this, 0, launch, flags) else null

        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Near")
            .setContentText("Близкие видят, где вы. Можно выключить в приложении.")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (pi != null) b.setContentIntent(pi)
        return b.build()
    }

    // ---------- слежение ----------

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")   // проверяем разрешение строкой ниже
    private fun startTracking() {
        if (!hasLocationPermission()) return

        if (wakeLock == null) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Near:geo")
                wakeLock?.setReferenceCounted(false)
                wakeLock?.acquire()
            } catch (e: Exception) { }
        }

        val manager = lm ?: (getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { lm = it }
        // Просим и спутники, и сеть: в помещении GPS молчит, а сеть даёт хоть какую-то точку.
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        for (prov in providers) {
            try {
                if (!manager.allProviders.contains(prov)) continue
                manager.requestLocationUpdates(
                    prov, intervalMs, minDistanceM, locListener, Looper.getMainLooper()
                )
            } catch (e: Exception) { }
        }
        // Последняя известная точка — чтобы карта не была пустой в первые секунды.
        try {
            for (prov in providers) {
                val l = manager.getLastKnownLocation(prov) ?: continue
                if (System.currentTimeMillis() - l.time < 10 * 60_000L) { handleFix(l); break }
            }
        } catch (e: Exception) { }
    }

    private fun stopTracking() {
        try { lm?.removeUpdates(locListener) } catch (e: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        wakeLock = null
    }

    private fun handleFix(l: Location) {
        val now = System.currentTimeMillis()
        val lat = l.latitude
        val lng = l.longitude
        if (lat == 0.0 && lng == 0.0) return

        // Не засоряем след: если человек стоит на месте, пишем точку раз в 3 минуты.
        if (haveLast) {
            val moved = distanceMeters(lastLat, lastLng, lat, lng)
            if (moved < 15.0 && now - lastSentTs < 180_000L) return
        }
        lastLat = lat; lastLng = lng; haveLast = true; lastSentTs = now

        val point = JSONObject()
        point.put("lat", lat)
        point.put("lng", lng)
        point.put("acc", l.accuracy.toDouble())
        point.put("ts", now)
        point.put("provider", l.provider ?: "")

        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_TS, now)
            .putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LAST_LNG, java.lang.Double.doubleToRawLongBits(lng))
            .apply()

        buffer(applicationContext, point)

        // приложение открыто — отдаём точку сразу на экран
        try { listener?.invoke(point) } catch (e: Exception) { }

        // и независимо от экрана отправляем в облако
        NearCloudPush.pushAsync(applicationContext, lat, lng, l.accuracy.toDouble(), now)
    }

    private fun distanceMeters(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6371000.0
        val d1 = Math.toRadians(la2 - la1)
        val d2 = Math.toRadians(lo2 - lo1)
        val a = Math.sin(d1 / 2) * Math.sin(d1 / 2) +
                Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) *
                Math.sin(d2 / 2) * Math.sin(d2 / 2)
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}
