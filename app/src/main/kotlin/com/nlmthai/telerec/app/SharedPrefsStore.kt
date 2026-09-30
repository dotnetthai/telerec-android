package com.nlmthai.telerec.app

import android.content.SharedPreferences
import com.nlmthai.telerec.core.KeyValueStore

/** `SettingsStore`'s backing store in the app. */
class SharedPrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getBoolean(key: String): Boolean? = if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    override fun getDouble(key: String): Double? =
        if (prefs.contains(key)) java.lang.Double.longBitsToDouble(prefs.getLong(key, 0)) else null

    override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    override fun putDouble(key: String, value: Double) =
        prefs.edit().putLong(key, java.lang.Double.doubleToRawLongBits(value)).apply()
}
