package com.fit_up.health.capacitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * После перезагрузки телефона слежение должно возобновиться само —
 * пожилой человек не обязан помнить, что нужно открыть приложение.
 *
 * Оговорка: начиная с Android 14 система запрещает поднимать службу
 * с типом «местоположение» прямо из загрузки. Тогда мы не падаем,
 * а просто ждём первого открытия приложения — JS запустит службу сам.
 */
class NearBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        if (!NearGeoService.isWanted(context)) return

        val fine = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return

        try {
            val i = Intent(context, NearGeoService::class.java)
            i.action = NearGeoService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        } catch (e: Exception) {
            Log.w("NearBoot", "не удалось поднять слежение после перезагрузки: " + (e.message ?: ""))
        }
    }
}
