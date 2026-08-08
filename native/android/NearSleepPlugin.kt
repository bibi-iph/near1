package com.fit_up.health.capacitor

import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/**
 * Чтение сна, пульса и кислорода из Health Connect + запрос разрешений на них.
 *
 * Зачем отдельный плагин: основной плагин браслета (capacitor-health) умеет просить
 * доступ только к шагам, тренировкам, калориям и дистанции. Разрешения на «Сон»
 * и «Кислород в крови» он не запрашивает вообще — поэтому Health Connect отвечал
 * отказом, и в приложении эти показатели всегда были пустыми.
 *
 * Здесь мы просим ВЕСЬ набор разрешений одним окном (включая шаги и пульс),
 * так что второе окно от основного плагина уже не нужно.
 */
@CapacitorPlugin(name = "NearSleep")
class NearSleepPlugin : Plugin() {

    private fun hcClient(): HealthConnectClient? = try {
        if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
            HealthConnectClient.getOrCreate(context)
        else null
    } catch (e: Exception) {
        null
    }

    /** Показатели, которые приложение читает. Названия — как их видит пользователь. */
    private val wanted: Map<String, String> by lazy {
        linkedMapOf(
            "steps" to HealthPermission.getReadPermission(StepsRecord::class),
            "distance" to HealthPermission.getReadPermission(DistanceRecord::class),
            "calories" to HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            "activeCalories" to HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            "exercise" to HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            "heartRate" to HealthPermission.getReadPermission(HeartRateRecord::class),
            "sleep" to HealthPermission.getReadPermission(SleepSessionRecord::class),
            "spo2" to HealthPermission.getReadPermission(OxygenSaturationRecord::class)
        )
    }

    private suspend fun grantedSet(): Set<String> {
        val c = hcClient() ?: return emptySet()
        return try { c.permissionController.getGrantedPermissions() } catch (e: Exception) { emptySet() }
    }

    private fun report(granted: Set<String>, available: Boolean): JSObject {
        val out = JSObject()
        out.put("available", available)
        var all = true
        for ((name, perm) in wanted) {
            val ok = granted.contains(perm)
            out.put(name, ok)
            if (!ok) all = false
        }
        out.put("all", all)
        out.put("grantedCount", granted.size)
        return out
    }

    /**
     * Какие показатели уже разрешены. Имена методов не совпадают с
     * checkPermissions/requestPermissions — эти заняты базовым классом Capacitor.
     */
    @PluginMethod
    fun checkAccess(call: PluginCall) {
        CoroutineScope(Dispatchers.IO).launch {
            val c = hcClient()
            if (c == null) { call.resolve(report(emptySet(), false)); return@launch }
            call.resolve(report(grantedSet(), true))
        }
    }

    @PluginMethod
    fun requestAccess(call: PluginCall) {
        CoroutineScope(Dispatchers.Main).launch {
            val c = hcClient()
            if (c == null) { call.resolve(report(emptySet(), false)); return@launch }
            val granted = withContext(Dispatchers.IO) { grantedSet() }
            val missing = wanted.values.toSet() - granted
            if (missing.isEmpty()) {
                val out = report(granted, true)
                out.put("dialogShown", false)
                out.put("note", "всё уже разрешено")
                call.resolve(out); return@launch
            }
            try {
                val contract = PermissionController.createRequestPermissionResultContract()
                val intent = contract.createIntent(activity, wanted.values.toSet())
                startActivityForResult(call, intent, "accessResult")
            } catch (e: Exception) {
                val out = report(granted, true)
                out.put("dialogShown", false)
                out.put("error", e.message ?: "cannot open Health Connect")
                call.resolve(out)
            }
        }
    }

    @ActivityCallback
    fun accessResult(call: PluginCall?, result: ActivityResult?) {
        if (call == null) return
        CoroutineScope(Dispatchers.IO).launch {
            val out = report(grantedSet(), true)
            out.put("dialogShown", true)
            out.put("resultCode", result?.resultCode ?: -999)
            call.resolve(out)
        }
    }

    /**
     * Открыть страницу разрешений именно нашего приложения в Health Connect.
     *
     * Это надёжный ручной путь. Он нужен, потому что окно запроса разрешений
     * Health Connect иногда не показывается вовсе — например, если человек уже
     * отклонял запрос: система тогда молча отвечает отказом, и приложению
     * остаётся только отвести пользователя в настройки.
     */
    @PluginMethod
    fun openHealthSettings(call: PluginCall) {
        val out = JSObject()
        val attempts = listOf(
            Intent("androidx.health.ACTION_MANAGE_HEALTH_PERMISSIONS")
                .putExtra("android.intent.extra.PACKAGE_NAME", context.packageName),
            Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"),
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
        )
        for (i in attempts) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                out.put("opened", i.action)
                call.resolve(out)
                return
            } catch (e: Exception) { }
        }
        // последняя попытка — просто запустить приложение Health Connect
        try {
            val launch = context.packageManager
                .getLaunchIntentForPackage("com.google.android.apps.healthdata")
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                out.put("opened", "app")
                call.resolve(out)
                return
            }
        } catch (e: Exception) { }
        out.put("opened", false)
        call.resolve(out)
    }

    /**
     * Калории за период. Готовый плагин их не умеет вовсе — он отвечает
     * «Unsupported dataType: calories», поэтому читаем записи сами.
     */
    @PluginMethod
    fun queryCalories(call: PluginCall) {
        val startStr = call.getString("startDate")
        val endStr = call.getString("endDate")
        CoroutineScope(Dispatchers.IO).launch {
            val out = JSObject()
            try {
                val c = hcClient()
                if (c == null) { out.put("available", false); call.resolve(out); return@launch }
                val range = TimeRangeFilter.between(Instant.parse(startStr), Instant.parse(endStr))
                out.put("available", true)
                var total = 0.0
                var n = 0
                try {
                    val res = c.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, range))
                    for (r in res.records) { total += r.energy.inKilocalories; n++ }
                } catch (e: Exception) { out.put("totalError", e.message ?: "") }
                if (n == 0) {
                    try {
                        val res = c.readRecords(ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, range))
                        for (r in res.records) { total += r.energy.inKilocalories; n++ }
                    } catch (e: Exception) { out.put("activeError", e.message ?: "") }
                }
                out.put("count", n)
                if (n > 0) out.put("kcal", Math.round(total))
                call.resolve(out)
            } catch (e: Exception) {
                out.put("available", true)
                out.put("error", e.message ?: "calories read error")
                call.resolve(out)
            }
        }
    }

    /**
     * Шаги за период — читаем сами записи, а не сводку.
     * Это надёжнее: сводка у части браслетов приходит пустой, хотя записи есть.
     */
    @PluginMethod
    fun querySteps(call: PluginCall) {
        val startStr = call.getString("startDate")
        val endStr = call.getString("endDate")
        CoroutineScope(Dispatchers.IO).launch {
            val out = JSObject()
            try {
                val c = hcClient()
                if (c == null) { out.put("available", false); call.resolve(out); return@launch }
                val res = c.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(Instant.parse(startStr), Instant.parse(endStr))
                    )
                )
                var total = 0L
                var newest: Instant? = null
                var source = ""
                for (r in res.records) {
                    total += r.count
                    val n = newest
                    if (n == null || r.endTime.isAfter(n)) {
                        newest = r.endTime
                        source = try { r.metadata.dataOrigin.packageName } catch (e: Exception) { "" }
                    }
                }
                out.put("available", true)
                out.put("total", total)
                out.put("count", res.records.size)
                if (newest != null) out.put("lastTime", newest.toString())
                if (source.isNotEmpty()) out.put("source", source)
                call.resolve(out)
            } catch (e: Exception) {
                out.put("available", true)
                out.put("error", e.message ?: "steps read error")
                call.resolve(out)
            }
        }
    }

    @PluginMethod
    fun querySleep(call: PluginCall) {
        val startStr = call.getString("startDate")
        val endStr = call.getString("endDate")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val c = hcClient()
                if (c == null) {
                    val out = JSObject()
                    out.put("available", false)
                    out.put("totalMinutes", 0)
                    out.put("sessions", JSArray())
                    call.resolve(out)
                    return@launch
                }
                val start = Instant.parse(startStr)
                val end = Instant.parse(endStr)
                val res = c.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(start, end)
                    )
                )
                val arr = JSArray()
                var total = 0L
                for (r in res.records) {
                    val mins = Duration.between(r.startTime, r.endTime).toMinutes()
                    total += mins
                    val o = JSObject()
                    o.put("startDate", r.startTime.toString())
                    o.put("endDate", r.endTime.toString())
                    o.put("minutes", mins)
                    try { o.put("source", r.metadata.dataOrigin.packageName) } catch (e: Exception) {}
                    arr.put(o)
                }
                val out = JSObject()
                out.put("available", true)
                out.put("totalMinutes", total)
                out.put("count", res.records.size)
                out.put("sessions", arr)
                call.resolve(out)
            } catch (e: Exception) {
                call.reject(e.message ?: "sleep read error")
            }
        }
    }

    // Пульс и насыщение крови кислородом (SpO2).
    // Возвращаем только то, что реально лежит в Health Connect, и всегда со временем
    // измерения — иначе в приложении нельзя отличить свежий пульс от прошлогоднего.
    @PluginMethod
    fun queryVitals(call: PluginCall) {
        val startStr = call.getString("startDate")
        val endStr = call.getString("endDate")
        CoroutineScope(Dispatchers.IO).launch {
            val out = JSObject()
            try {
                val c = hcClient()
                if (c == null) {
                    out.put("available", false)
                    call.resolve(out)
                    return@launch
                }
                out.put("available", true)
                val start = Instant.parse(startStr)
                val end = Instant.parse(endStr)

                // --- SpO2 ---
                try {
                    val res = c.readRecords(
                        ReadRecordsRequest(
                            recordType = OxygenSaturationRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end)
                        )
                    )
                    out.put("spo2Count", res.records.size)
                    var best: OxygenSaturationRecord? = null
                    for (r in res.records) {
                        val b = best
                        if (b == null || r.time.isAfter(b.time)) best = r
                    }
                    val bb = best
                    if (bb != null) {
                        out.put("spo2", bb.percentage.value)
                        out.put("spo2Time", bb.time.toEpochMilli())
                        try { out.put("spo2Source", bb.metadata.dataOrigin.packageName) } catch (e: Exception) {}
                    }
                } catch (e: Exception) {
                    out.put("spo2Error", e.message ?: "spo2 error")
                }

                // --- Пульс ---
                try {
                    val res = c.readRecords(
                        ReadRecordsRequest(
                            recordType = HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(start, end)
                        )
                    )
                    out.put("hrCount", res.records.size)
                    var bpm = 0L
                    var at: Instant? = null
                    var src = ""
                    var sum = 0L
                    var n = 0L
                    var minBpm = Long.MAX_VALUE
                    for (r in res.records) {
                        for (sm in r.samples) {
                            sum += sm.beatsPerMinute; n++
                            if (sm.beatsPerMinute < minBpm) minBpm = sm.beatsPerMinute
                            val a = at
                            if (a == null || sm.time.isAfter(a)) {
                                at = sm.time
                                bpm = sm.beatsPerMinute
                                src = try { r.metadata.dataOrigin.packageName } catch (e: Exception) { "" }
                            }
                        }
                    }
                    val atv = at
                    if (atv != null && bpm > 0L) {
                        out.put("hr", bpm)
                        out.put("hrTime", atv.toEpochMilli())
                        if (n > 0) out.put("hrAvg", sum / n)
                        if (minBpm != Long.MAX_VALUE) out.put("hrMin", minBpm)
                        out.put("hrSamples", n)
                        if (src.isNotEmpty()) out.put("hrSource", src)
                    }
                } catch (e: Exception) {
                    out.put("hrError", e.message ?: "hr error")
                }

                call.resolve(out)
            } catch (e: Exception) {
                call.reject(e.message ?: "vitals read error")
            }
        }
    }
}
