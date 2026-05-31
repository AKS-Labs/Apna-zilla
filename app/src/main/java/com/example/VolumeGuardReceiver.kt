package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.util.Log

class VolumeGuardReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WATCHDOG_TICK = "com.example.action.WATCHDOG_TICK"
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        
        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, VolumeGuardReceiver::class.java).apply {
                action = ACTION_WATCHDOG_TICK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                404,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            // Cancel any existing one
            try {
                alarmManager.cancel(pendingIntent)
            } catch (e: Exception) {
                // Ignore
            }

            // Schedule the watchdog. Use setAndAllowWhileIdle to guarantee execution even in deep doze mode
            val triggerAtMs = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAtMs,
                        pendingIntent
                    )
                }
                Log.d("VolumeGuardReceiver", "Successfully scheduled watchdog tick in 15 minutes.")
            } catch (e: Exception) {
                Log.e("VolumeGuardReceiver", "Failed to schedule watchdog: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("VolumeGuardReceiver", "Received broadcast: $action")
        
        val storage = VolumeStorage(context)
        if (storage.isServiceEnabled) {
            // Restart the service to ensure it is constantly up and running
            try {
                val serviceIntent = Intent(context, VolumeGuardService::class.java).apply {
                    this.action = VolumeGuardService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d("VolumeGuardReceiver", "Foreground service restarted in response to $action")
            } catch (e: Exception) {
                Log.e("VolumeGuardReceiver", "Failed to auto-restart service: ${e.message}")
            }
        }

        // Schedule next watchdog iteration if active
        if (storage.isServiceEnabled) {
            scheduleWatchdog(context)
        }
    }
}
