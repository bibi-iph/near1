package com.fit_up.health.capacitor

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Отправка координат прямо из фоновой службы в Firestore.
 *
 * Зачем не через WebView: когда экран выключен, Android замораживает JavaScript,
 * и отправка из приложения останавливается. Служба ходит в облако сама,
 * обычным HTTPS-запросом, поэтому передача не прерывается.
 *
 * Реквизиты (ключ проекта, uid и токены) приходят из JS один раз при включении
 * слежения и лежат в приватных настройках приложения. Токен доступа живёт час,
 * поэтому обновляем его по refresh-токену — так же, как это делает сам Firebase.
 */
object NearCloudPush {

    private const val PREFS = "near_cloud"
    private const val K_API = "apiKey"
    private const val K_PID = "projectId"
    private const val K_UID = "uid"
    private const val K_ID_TOKEN = "idToken"
    private const val K_REFRESH = "refreshToken"
    private const val K_EXP = "tokenExp"

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun configure(
        ctx: Context, apiKey: String?, projectId: String?, uid: String?,
        idToken: String?, refreshToken: String?
    ) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (!apiKey.isNullOrEmpty()) e.putString(K_API, apiKey)
        if (!projectId.isNullOrEmpty()) e.putString(K_PID, projectId)
        if (!uid.isNullOrEmpty()) e.putString(K_UID, uid)
        if (!idToken.isNullOrEmpty()) {
            e.putString(K_ID_TOKEN, idToken)
            // считаем токен годным 50 минут — Firebase выдаёт его на час
            e.putLong(K_EXP, System.currentTimeMillis() + 50 * 60_000L)
        }
        if (!refreshToken.isNullOrEmpty()) e.putString(K_REFRESH, refreshToken)
        e.apply()
    }

    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun isConfigured(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return !p.getString(K_PID, null).isNullOrEmpty() && !p.getString(K_UID, null).isNullOrEmpty()
    }

    fun pushAsync(ctx: Context, lat: Double, lng: Double, acc: Double, ts: Long) {
        try {
            worker.execute {
                try { push(ctx, lat, lng, acc, ts) } catch (e: Exception) {
                    Log.w("NearCloudPush", "push failed: " + (e.message ?: ""))
                }
            }
        } catch (e: Exception) { }
    }

    private fun push(ctx: Context, lat: Double, lng: Double, acc: Double, ts: Long): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pid = p.getString(K_PID, null) ?: return false
        val uid = p.getString(K_UID, null) ?: return false
        val token = validToken(ctx) ?: return false

        val doc = "projects/$pid/databases/(default)/documents/pensioners/$uid/live/state"
        val fields = JSONObject()
        fields.put("lat", JSONObject().put("doubleValue", lat))
        fields.put("lng", JSONObject().put("doubleValue", lng))
        fields.put("geoAcc", JSONObject().put("doubleValue", acc))
        fields.put("geoTs", JSONObject().put("integerValue", ts.toString()))
        fields.put("ts", JSONObject().put("integerValue", System.currentTimeMillis().toString()))
        fields.put("geoOn", JSONObject().put("booleanValue", true))
        fields.put("geoBg", JSONObject().put("booleanValue", true))

        val update = JSONObject()
        update.put("name", doc)
        update.put("fields", fields)

        val mask = JSONObject()
        mask.put("fieldPaths", org.json.JSONArray(
            listOf("lat", "lng", "geoAcc", "geoTs", "ts", "geoOn", "geoBg")
        ))

        val write = JSONObject()
        write.put("update", update)
        write.put("updateMask", mask)

        val body = JSONObject()
        body.put("writes", org.json.JSONArray(listOf(write)))

        // :commit — обычный POST, в отличие от PATCH его умеет HttpURLConnection
        val url = "https://firestore.googleapis.com/v1/projects/$pid/databases/(default)/documents:commit"
        val (code, resp) = httpPost(url, body.toString(), "application/json", token)
        if (code == 401 || code == 403) {
            // токен протух раньше времени — обновляем принудительно и пробуем ещё раз
            val fresh = refreshToken(ctx, force = true) ?: return false
            val (code2, _) = httpPost(url, body.toString(), "application/json", fresh)
            return code2 in 200..299
        }
        if (code !in 200..299) Log.w("NearCloudPush", "firestore $code: ${resp.take(200)}")
        return code in 200..299
    }

    private fun validToken(ctx: Context): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val tok = p.getString(K_ID_TOKEN, null)
        val exp = p.getLong(K_EXP, 0L)
        if (!tok.isNullOrEmpty() && System.currentTimeMillis() < exp) return tok
        return refreshToken(ctx, force = false) ?: tok
    }

    private fun refreshToken(ctx: Context, force: Boolean): String? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val api = p.getString(K_API, null) ?: return null
        val rt = p.getString(K_REFRESH, null) ?: return null
        if (!force) {
            val exp = p.getLong(K_EXP, 0L)
            val tok = p.getString(K_ID_TOKEN, null)
            if (!tok.isNullOrEmpty() && System.currentTimeMillis() < exp) return tok
        }
        return try {
            val url = "https://securetoken.googleapis.com/v1/token?key=" + URLEncoder.encode(api, "UTF-8")
            val form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(rt, "UTF-8")
            val (code, resp) = httpPost(url, form, "application/x-www-form-urlencoded", null)
            if (code !in 200..299) {
                Log.w("NearCloudPush", "token refresh $code")
                return null
            }
            val j = JSONObject(resp)
            val newTok = j.optString("id_token", "")
            val newRt = j.optString("refresh_token", "")
            val secs = j.optString("expires_in", "3600").toLongOrNull() ?: 3600L
            if (newTok.isEmpty()) return null
            val e = p.edit()
            e.putString(K_ID_TOKEN, newTok)
            e.putLong(K_EXP, System.currentTimeMillis() + (secs - 300) * 1000L)
            if (newRt.isNotEmpty()) e.putString(K_REFRESH, newRt)
            e.apply()
            newTok
        } catch (e: Exception) {
            Log.w("NearCloudPush", "token refresh failed: " + (e.message ?: ""))
            null
        }
    }

    private fun httpPost(
        url: String, body: String, contentType: String, bearer: String?
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            val out: OutputStream = conn.outputStream
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush(); out.close()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: ""
            Pair(code, text)
        } catch (e: Exception) {
            Pair(0, e.message ?: "network error")
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }
}
