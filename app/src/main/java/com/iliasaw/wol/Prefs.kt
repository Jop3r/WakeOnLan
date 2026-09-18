package com.iliasaw.wol

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("wol", Context.MODE_PRIVATE)

    var mac: String
        get() = sp.getString(KEY_MAC, "").orEmpty()
        set(value) = sp.edit().putString(KEY_MAC, value).apply()

    var ssid: String
        get() = sp.getString(KEY_SSID, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SSID, value).apply()

    var subnet: String
        get() = sp.getString(KEY_SUBNET, "").orEmpty()
        set(value) = sp.edit().putString(KEY_SUBNET, value).apply()

    var minAwayMinutes: Int
        get() = sp.getInt(KEY_MIN_AWAY, DEFAULT_MIN_AWAY)
        set(value) = sp.edit().putInt(KEY_MIN_AWAY, value).apply()

    /** Когда телефон был в домашней сети в последний раз. */
    var lastHomeSeenAt: Long
        get() = sp.getLong(KEY_LAST_HOME, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_HOME, value).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = sp.edit().putInt(KEY_PORT, value).apply()

    companion object {
        const val DEFAULT_PORT = 9
        const val DEFAULT_MIN_AWAY = 20
        private const val KEY_MAC = "mac"
        private const val KEY_SSID = "ssid"
        private const val KEY_SUBNET = "subnet"
        private const val KEY_MIN_AWAY = "min_away"
        private const val KEY_LAST_HOME = "last_home"
        private const val KEY_PORT = "port"
    }
}
