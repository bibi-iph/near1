package com.fit_up.health.capacitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import org.json.JSONObject

/**
 * Мост между приложением и фоновой службой геолокации.
 *
 * Имена методов намеренно НЕ совпадают с checkPermissions/requestPermissions —
 * эти два имени заняты базовым классом Capacitor.
 */
@CapacitorPlugin(
    name = "NearGeo",
    permissions = [
        Permission(
            alias = "location",
            strings = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
        ),
        Permission(
            alias = "background",
            strings = [Manifest.permission.ACCESS_BACKGROUND_LOCATION]
        ),
        Permission(
            alias = "notifications",
            strings = [Manifest.permission.POST_NOTIFICATIONS]
        )
    ]
)
class NearGeoPlugin : Plugin() {

    override fun load() {
        super.load()
        NearGeoService.listener = { point ->
            try { notifyListeners("location", JSObject.fromJSONObject(point)) } catch (e: Exception) { }
        }
    }

    override fun handleOnDestroy() {
        NearGeoService.listener = null
        super.handleOnDestroy()
    }

    // ---------- состояние ----------

    private fun stateObject(): JSObject {
        val o = JSObject()
        val ctx: Context = context
        o.put("location", getPermissionState("location") == PermissionState.GRANTED)
        o.put(
            "background",
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) true
            else getPermissionState("background") == PermissionState.GRANTED
        )
        o.put(
            "notifications",
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) true
            else getPermissionState("notifications") == PermissionState.GRANTED
        )
        o.put("running", NearGeoService.running)
        o.put("wanted", NearGeoService.isWanted(ctx))
        o.put("configured", NearCloudPush.isConfigured(ctx))
        o.put("gps", gpsEnabled())
        o.put("battery", ignoringBatteryOptimizations())
        val last = NearGeoService.lastFix(ctx)
        if (last != null) o.put("last", JSObject.fromJSONObject(last))
        return o
    }

    private fun gpsEnabled(): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { false }

    private fun ignoringBatteryOptimizations(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) { true }

    @PluginMethod
    fun status(call: PluginCall) = call.resolve(stateObject())

    // ---------- разрешения ----------

    @PluginMethod
    fun requestAccess(call: PluginCall) {
        if (getPermissionState("location") == PermissionState.GRANTED) {
            call.resolve(stateObject()); return
        }
        requestPermissionForAlias("location", call, "afterAccess")
    }

    @PermissionCallback
    fun afterAccess(call: PluginCall) = call.resolve(stateObject())

    /**
     * «Разрешать всегда». Начиная с Android 11 система не показывает для этого
     * обычное окно — пользователя нужно отправить в настройки приложения.
     */
    @PluginMethod
    fun requestBackground(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { call.resolve(stateObject()); return }
        if (getPermissionState("background") == PermissionState.GRANTED) { call.resolve(stateObject()); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            openAppSettings(call); return
        }
        requestPermissionForAlias("background", call, "afterBackground")
    }

    @PermissionCallback
    fun afterBackground(call: PluginCall) = call.resolve(stateObject())

    @PluginMethod
    fun requestNotifications(call: PluginCall) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { call.resolve(stateObject()); return }
        if (getPermissionState("notifications") == PermissionState.GRANTED) { call.resolve(stateObject()); return }
        requestPermissionForAlias("notifications", call, "afterNotifications")
    }

    @PermissionCallback
    fun afterNotifications(call: PluginCall) = call.resolve(stateObject())

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.fromParts("package", context.packageName, null)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Exception) { }
        call.resolve(stateObject())
    }

    /** Отключить «экономию батареи» для приложения — иначе Android душит слежение. */
    @PluginMethod
    fun openBatterySettings(call: PluginCall) {
        var ok = false
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + context.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            ok = true
        } catch (e: Exception) { }
        if (!ok) {
            try {
                val i = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            } catch (e: Exception) { }
        }
        call.resolve(stateObject())
    }

    @PluginMethod
    fun openLocationSettings(call: PluginCall) {
        try {
            val i = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Exception) { }
        call.resolve(stateObject())
    }

    // ---------- реквизиты облака ----------

    @PluginMethod
    fun configure(call: PluginCall) {
        NearCloudPush.configure(
            context,
            call.getString("apiKey"),
            call.getString("projectId"),
            call.getString("uid"),
            call.getString("idToken"),
            call.getString("refreshToken")
        )
        call.resolve(stateObject())
    }

    @PluginMethod
    fun forget(call: PluginCall) {
        NearCloudPush.forget(context)
        call.resolve(stateObject())
    }

    // ---------- запуск и остановка ----------

    @PluginMethod
    fun start(call: PluginCall) {
        if (getPermissionState("location") != PermissionState.GRANTED) {
            call.reject("no-location-permission"); return
        }
        try {
            val i = Intent(context, NearGeoService::class.java)
            i.action = NearGeoService.ACTION_START
            i.putExtra("interval", call.getInt("interval") ?: 60000)
            i.putExtra("distance", (call.getFloat("distance") ?: 0f))
            NearGeoService.setWanted(context, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        } catch (e: Exception) {
            call.reject(e.message ?: "cannot start service"); return
        }
        call.resolve(stateObject())
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        try {
            NearGeoService.setWanted(context, false)
            val i = Intent(context, NearGeoService::class.java)
            i.action = NearGeoService.ACTION_STOP
            context.startService(i)
        } catch (e: Exception) { }
        call.resolve(stateObject())
    }

    /** Забрать точки, накопленные, пока приложение было закрыто. */
    @PluginMethod
    fun drain(call: PluginCall) {
        val arr = NearGeoService.drain(context)
        val out = JSObject()
        out.put("points", try { JSArray(arr.toString()) } catch (e: Exception) { JSArray() })
        val last: JSONObject? = NearGeoService.lastFix(context)
        if (last != null) out.put("last", JSObject.fromJSONObject(last))
        out.put("running", NearGeoService.running)
        call.resolve(out)
    }
}
