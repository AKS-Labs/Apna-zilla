package com.example

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VolumeStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("volume_guard_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_LOCK_PREFIX = "lock_stream_"
        private const val KEY_VALUE_PREFIX = "value_stream_"
        private const val KEY_INTERCEPT_LOGS = "intercept_logs"
        private const val MAX_LOGS = 50
    }

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    fun isStreamLocked(streamType: Int): Boolean {
        return prefs.getBoolean(KEY_LOCK_PREFIX + streamType, false)
    }

    fun setStreamLocked(streamType: Int, locked: Boolean) {
        prefs.edit().putBoolean(KEY_LOCK_PREFIX + streamType, locked).apply()
    }

    fun getStreamLockValue(streamType: Int, defaultValue: Int): Int {
        return prefs.getInt(KEY_VALUE_PREFIX + streamType, defaultValue)
    }

    fun setStreamLockValue(streamType: Int, value: Int) {
        prefs.edit().putInt(KEY_VALUE_PREFIX + streamType, value).apply()
    }

    fun logIntercept(streamName: String, originalVal: Int, lockedVal: Int) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logEntry = "$timestamp - Intercepted: $streamName spike from $originalVal restored to locked $lockedVal."
        
        val currentLogs = getLogs().toMutableList()
        currentLogs.add(0, logEntry) // Prepend so latest shows up at top
        
        val truncated = if (currentLogs.size > MAX_LOGS) currentLogs.take(MAX_LOGS) else currentLogs
        prefs.edit().putString(KEY_INTERCEPT_LOGS, truncated.joinToString(";;")).apply()
    }

    fun getLogs(): List<String> {
        val stored = prefs.getString(KEY_INTERCEPT_LOGS, "") ?: ""
        if (stored.isEmpty()) return emptyList()
        return stored.split(";;")
    }

    fun clearLogs() {
        prefs.edit().remove(KEY_INTERCEPT_LOGS).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
