package net.duhowpi.ftmsbridge

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var autoconnectMinRssi: Int
        get() = prefs.getInt(KEY_AUTOCONNECT_MIN_RSSI, DEFAULT_AUTOCONNECT_MIN_RSSI)
        set(value) { prefs.edit().putInt(KEY_AUTOCONNECT_MIN_RSSI, value).apply() }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_AUTOCONNECT_MIN_RSSI = "autoconnect_min_rssi"
        const val DEFAULT_AUTOCONNECT_MIN_RSSI = -40
    }
}
