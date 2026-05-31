package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class VolumeGuardService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var storage: VolumeStorage
    private var volumeObserver: ContentObserver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var mediaSession: MediaSession? = null
    
    private val isEnforcing = AtomicBoolean(false)

    companion object {
        const val CHANNEL_ID = "volume_guard_channel"
        const val NOTIFICATION_ID = 101

        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_VOL_UP = "com.example.action.VOL_UP"
        const val ACTION_VOL_DOWN = "com.example.action.VOL_DOWN"
        const val ACTION_TOGGLE_LOCK = "com.example.action.TOGGLE_LOCK"

        val STREAMS_TO_MONITOR = listOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL
        )

        fun getStreamName(streamType: Int): String {
            return when (streamType) {
                AudioManager.STREAM_MUSIC -> "Media/Music"
                AudioManager.STREAM_RING -> "Ringtone"
                AudioManager.STREAM_NOTIFICATION -> "Notification"
                AudioManager.STREAM_ALARM -> "Alarm"
                AudioManager.STREAM_VOICE_CALL -> "Voice Call"
                else -> "System"
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        storage = VolumeStorage(this)

        createNotificationChannel()

        // Setup seekable MediaSession to project a volume slider into the notifications panel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaSession = MediaSession(this, "VolumeGuardSession").apply {
                setCallback(object : MediaSession.Callback() {
                    override fun onSeekTo(pos: Long) {
                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        // Map pos (milliseconds/1000) to device volume units
                        val targetVol = (pos / 1000L).toInt().coerceIn(0, maxVolume)
                        
                        storage.setStreamLockValue(AudioManager.STREAM_MUSIC, targetVol)
                        storage.setStreamLocked(AudioManager.STREAM_MUSIC, true)
                        
                        setStreamVolumeSilently(AudioManager.STREAM_MUSIC, targetVol)
                        storage.logIntercept("Notification Slider", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), targetVol)
                        
                        updateMediaSessionState()
                        updateNotification()
                    }

                    override fun onPlay() {
                        storage.setStreamLocked(AudioManager.STREAM_MUSIC, true)
                        enforceLockedVolumes()
                        updateMediaSessionState()
                        updateNotification()
                    }

                    override fun onPause() {
                        storage.setStreamLocked(AudioManager.STREAM_MUSIC, false)
                        updateMediaSessionState()
                        updateNotification()
                    }
                })
                isActive = true
            }
        }

        // Register ContentObserver to capture any system volume change
        volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                enforceLockedVolumes()
            }
        }
        contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            volumeObserver!!
        )

        // Also register volume changed broadcast receiver for backup
        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.media.VOLUME_CHANGED_ACTION") {
                    enforceLockedVolumes()
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        registerReceiver(volumeReceiver, filter)

        // Sync initial volumes
        initializeLockSettingsIfNeeded()
        updateMediaSessionState()
    }

    private fun initializeLockSettingsIfNeeded() {
        for (stream in STREAMS_TO_MONITOR) {
            val current = audioManager.getStreamVolume(stream)
            // If lock volume is not initialized, set it to current volume
            if (storage.getStreamLockValue(stream, -1) == -1) {
                storage.setStreamLockValue(stream, current)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                storage.isServiceEnabled = true
                updateMediaSessionState()
                startForegroundServiceCompat()
                enforceLockedVolumes()
                VolumeGuardReceiver.scheduleWatchdog(this)
            }
            ACTION_STOP -> {
                storage.isServiceEnabled = false
                stopSelf()
            }
            ACTION_VOL_UP -> {
                adjustMediaVolume(1)
            }
            ACTION_VOL_DOWN -> {
                adjustMediaVolume(-1)
            }
            ACTION_TOGGLE_LOCK -> {
                val currentLock = storage.isStreamLocked(AudioManager.STREAM_MUSIC)
                storage.setStreamLocked(AudioManager.STREAM_MUSIC, !currentLock)
                enforceLockedVolumes()
                updateMediaSessionState()
                updateNotification()
            }
            else -> {
                // If it's started without action, make sure we are foregrounded
                updateMediaSessionState()
                startForegroundServiceCompat()
                VolumeGuardReceiver.scheduleWatchdog(this)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        volumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        volumeReceiver?.let { unregisterReceiver(it) }
        
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        
        // Clear flag if stopped
        storage.isServiceEnabled = false
    }

    private fun adjustMediaVolume(step: Int) {
        val stream = AudioManager.STREAM_MUSIC
        val currentMax = audioManager.getStreamMaxVolume(stream)
        val currentLockVal = storage.getStreamLockValue(stream, audioManager.getStreamVolume(stream))
        val newVal = (currentLockVal + step).coerceIn(0, currentMax)
        
        storage.setStreamLockValue(stream, newVal)
        // Ensure lock is enabled when adjusting from notification for convenience
        storage.setStreamLocked(stream, true)
        
        // Apply immediately
        setStreamVolumeSilently(stream, newVal)
        updateMediaSessionState()
        updateNotification()
    }

    private fun updateMediaSessionState() {
        val session = mediaSession ?: return
        val stream = AudioManager.STREAM_MUSIC
        val maxVol = audioManager.getStreamMaxVolume(stream)
        val curVol = audioManager.getStreamVolume(stream)

        val durationMs = maxVol * 1000L
        val currentPositionMs = curVol * 1000L
        val isLocked = storage.isStreamLocked(stream)

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Volume Lock: " + if (isLocked) "Locked (🛡️ Active)" else "Unlocked")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Drag Slider to Clank Volume Target Level")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            .build()
        session.setMetadata(metadata)

        val stateBuilder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE
            )
            // State is set to PLAYING so the seek bar is draggable, speed 0.0f prevents the ticker/slider from self-advancing
            .setState(
                if (isLocked) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                currentPositionMs,
                0.0f
            )
        session.setPlaybackState(stateBuilder.build())
    }

    private fun enforceLockedVolumes() {
        if (!isEnforcing.compareAndSet(false, true)) return
        try {
            var volumeChanged = false
            for (stream in STREAMS_TO_MONITOR) {
                if (storage.isStreamLocked(stream)) {
                    val currentVolume = audioManager.getStreamVolume(stream)
                    val expectedVolume = storage.getStreamLockValue(stream, currentVolume)
                    if (currentVolume != expectedVolume) {
                        setStreamVolumeSilently(stream, expectedVolume)
                        volumeChanged = true
                        
                        // Log this occurrence
                        storage.logIntercept(getStreamName(stream), currentVolume, expectedVolume)
                    }
                }
            }
            if (volumeChanged) {
                updateMediaSessionState()
                updateNotification()
            }
        } finally {
            isEnforcing.set(false)
        }
    }

    private fun setStreamVolumeSilently(stream: Int, value: Int) {
        try {
            // Set volume with no visual overlay flags to prevent broken physical key overlay flicker
            audioManager.setStreamVolume(stream, value, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startForegroundServiceCompat() {
        val notification = buildServiceNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildServiceNotification())
    }

    private fun buildServiceNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val volUpIntent = Intent(this, VolumeGuardService::class.java).apply { action = ACTION_VOL_UP }
        val volUpPendingIntent = PendingIntent.getService(
            this, 1, volUpIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val volDownIntent = Intent(this, VolumeGuardService::class.java).apply { action = ACTION_VOL_DOWN }
        val volDownPendingIntent = PendingIntent.getService(
            this, 2, volDownIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleLockIntent = Intent(this, VolumeGuardService::class.java).apply { action = ACTION_TOGGLE_LOCK }
        val toggleLockPendingIntent = PendingIntent.getService(
            this, 3, toggleLockIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val mediaLockStatus = if (storage.isStreamLocked(AudioManager.STREAM_MUSIC)) "🔒 LOCKED" else "🔓 UNLOCKED"
        val mediaVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        val activeLocksCount = STREAMS_TO_MONITOR.count { storage.isStreamLocked(it) }
        
        val contentTitle = "Volume Guard is Protectively Locked"
        val contentText = "Media level: $mediaVol/$mediaMax ($mediaLockStatus) | $activeLocksCount protected streams"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(openAppPendingIntent)
                .setStyle(Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1)
                )
                .addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_previous),
                    "Lower",
                    volDownPendingIntent
                ).build())
                .addAction(Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_next),
                    "Raise",
                    volUpPendingIntent
                ).build())
                
            return builder.build()
        } else {
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openAppPendingIntent)
                .addAction(android.R.drawable.ic_media_previous, "Lower", volDownPendingIntent)
                .addAction(android.R.drawable.ic_media_next, "Raise", volUpPendingIntent)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Volume Ear Lock Notification",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Volume lock protection persistent control service"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
