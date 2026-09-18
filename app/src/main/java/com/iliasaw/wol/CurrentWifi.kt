package com.iliasaw.wol

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.location.LocationManagerCompat
import android.Manifest

/** Чтение SSID текущей Wi-Fi сети с учётом ограничений Android 9–13+. */
object CurrentWifi {

    enum class Reason { OK, PERMISSION, NOT_ON_WIFI, LOCATION_OFF, HIDDEN }

    data class Result(val reason: Reason, val ssid: String? = null)

    /** Для UI: возвращает SSID или причину, по которой он недоступен. */
    fun current(context: Context): Result {
        // Без разрешения (NEARBY_WIFI_DEVICES на 13+ / Геолокация на 9–12)
        // система отдаёт "<unknown ssid>", поэтому проверяем его первым делом.
        val permissionGranted = if (Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!permissionGranted) return Result(Reason.PERMISSION)

        val cm = context.getSystemService(ConnectivityManager::class.java)
        var sawWifi = false
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            sawWifi = true
            clean((caps.transportInfo as? WifiInfo)?.ssid)?.let { return Result(Reason.OK, it) }
        }
        // запасной путь через WifiManager (на Android 8 его достаточно)
        clean(wmSsid(context))?.let { return Result(Reason.OK, it) }
        if (sawWifi) {
            // Samsung и часть оболочек отдают "<unknown ssid>", пока выключена геолокация
            val lm = context.getSystemService(LocationManager::class.java)
            return if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
                Result(Reason.LOCATION_OFF)
            } else {
                Result(Reason.HIDDEN)
            }
        }
        return Result(Reason.NOT_ON_WIFI)
    }

    /** Полное чтение SSID: прямые запросы по всем сетям + запасной путь WifiManager.
     *  Вызывается из сервиса, где разрешение уже проверено. */
    fun ssid(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            clean((caps.transportInfo as? WifiInfo)?.ssid)?.let { return it }
        }
        clean(wmSsid(context))?.let { return it }
        return null
    }

    private fun wmSsid(context: Context): String? = try {
        context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
    } catch (_: Exception) {
        null
    }

    private fun clean(raw: String?): String? {
        val ssid = raw?.removeSurrounding("\"")
        return if (ssid.isNullOrBlank() || ssid == "<unknown ssid>") null else ssid
    }
}
