package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val storage = remember { VolumeStorage(context) }

    // Service state
    var isServiceRunning by remember { mutableStateOf(storage.isServiceEnabled) }
    
    // Notification permission status state
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
        if (isGranted) {
            Toast.makeText(context, "Notifications enabled! Guard active.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Notification permission is required to run persistent guard.", Toast.LENGTH_LONG).show()
        }
    }

    // Capture volume state values reactively
    val volumeLevels = remember { mutableStateMapOf<Int, Int>() }
    val lockStatuses = remember { mutableStateMapOf<Int, Boolean>() }
    val lockLevels = remember { mutableStateMapOf<Int, Int>() }
    var actionLogs by remember { mutableStateOf(storage.getLogs()) }

    // Synchronize local states with AudioManager and Preferences
    fun refreshState() {
        for (stream in VolumeGuardService.STREAMS_TO_MONITOR) {
            val curVol = audioManager.getStreamVolume(stream)
            volumeLevels[stream] = curVol
            lockStatuses[stream] = storage.isStreamLocked(stream)
            lockLevels[stream] = storage.getStreamLockValue(stream, curVol)
        }
        actionLogs = storage.getLogs()
        isServiceRunning = storage.isServiceEnabled
    }

    // Run when app opens and set up reactive preference listener
    LaunchedEffect(Unit) {
        refreshState()
        
        // Background loop to poll volume in case something else changes it
        while (true) {
            refreshState()
            kotlinx.coroutines.delay(1000)
        }
    }

    // Receive storage update triggers smoothly
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            refreshState()
        }
        storage.registerListener(listener)
        onDispose {
            storage.unregisterListener(listener)
        }
    }

    // Toggle service execution
    fun toggleService() {
        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        val serviceIntent = Intent(context, VolumeGuardService::class.java)
        if (!isServiceRunning) {
            serviceIntent.action = VolumeGuardService.ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Toast.makeText(context, "Ear protection guard activated!", Toast.LENGTH_SHORT).show()
        } else {
            serviceIntent.action = VolumeGuardService.ACTION_STOP
            context.stopService(serviceIntent)
            Toast.makeText(context, "Ear protection deactivated.", Toast.LENGTH_SHORT).show()
        }
        refreshState()
    }

    // Adjust specific volume levels
    fun updateVolume(stream: Int, newValue: Int) {
        try {
            audioManager.setStreamVolume(stream, newValue, 0)
            volumeLevels[stream] = newValue
            
            // If locked is enabled, we update both setting and storage
            if (lockStatuses[stream] == true) {
                storage.setStreamLockValue(stream, newValue)
                lockLevels[stream] = newValue
            }
        } catch (e: SecurityException) {
            Toast.makeText(context, "Access blocked (Possibly Do Not Disturb mode active).", Toast.LENGTH_SHORT).show()
        }
    }

    // Toggle locks for streams
    fun toggleLock(stream: Int) {
        val currentLocked = lockStatuses[stream] ?: false
        val nextState = !currentLocked
        storage.setStreamLocked(stream, nextState)
        lockStatuses[stream] = nextState
        
        if (nextState) {
            val currentVol = audioManager.getStreamVolume(stream)
            storage.setStreamLockValue(stream, currentVol)
            lockLevels[stream] = currentVol
        }
        
        // Trigger sync to the foreground service
        if (isServiceRunning) {
            val updateIntent = Intent(context, VolumeGuardService::class.java).apply {
                action = VolumeGuardService.ACTION_START
            }
            context.startService(updateIntent)
        }
        refreshState()
    }

    // Simple visual simulation of hardware defect to test clamping
    fun simulateBrokenVolumeSpike() {
        if (!isServiceRunning) {
            Toast.makeText(context, "Please activate the ear shield first to test protection!", Toast.LENGTH_LONG).show()
            return
        }
        val targetStream = AudioManager.STREAM_MUSIC
        if (lockStatuses[targetStream] != true) {
            Toast.makeText(context, "Please lock the Media volume stream first to test!", Toast.LENGTH_LONG).show()
            return
        }
        val originalMax = audioManager.getStreamMaxVolume(targetStream)
        
        // Programmatic spike sets volume to max which triggers spike detector instantly
        Toast.makeText(context, "Simulating physical volume defect spike to $originalMax...", Toast.LENGTH_SHORT).show()
        try {
            audioManager.setStreamVolume(targetStream, originalMax, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val isDark = isSystemInDarkTheme()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.navigationBars
    ) { innerPadding ->
        
        // Premium dynamic layered atmosphere brush gradient
        val backgroundGradient = if (isDark) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0F0B1E),
                    Color(0xFF15102A),
                    Color(0xFF090614)
                )
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color(0xFFF9F7FD),
                    Color(0xFFECE5F8),
                    Color(0xFFF2EDFC)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundGradient)
                .padding(innerPadding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header Zone
                item {
                    Spacer(modifier = Modifier.height(18.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                    RoundedCornerShape(12.dp)
                                )
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    RoundedCornerShape(12.dp)
                                )
                        ) {
                            Text(text = "🛡️", fontSize = 24.sp)
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "VOLUME GUARD",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.5.sp,
                                fontSize = 22.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("app_title")
                        )

                        Text(
                            text = "Intelligent limit clamp to protect ear & device sanity",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp, start = 16.dp, end = 16.dp)
                        )
                    }
                }

                // Master Protection status Capsule
                item {
                    MasterShieldWidget(
                        isActive = isServiceRunning,
                        onToggle = { toggleService() },
                        hasPermission = hasNotificationPermission,
                        onRequestPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }

                // Volume stream cards title header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Protected Channels",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                        )
                        val activeCount = VolumeGuardService.STREAMS_TO_MONITOR.count { lockStatuses[it] == true }
                        Box(
                            modifier = Modifier
                                .background(
                                    if (activeCount > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) 
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "$activeCount active locks",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // Highly Styled compact dynamic stream controller items
                items(VolumeGuardService.STREAMS_TO_MONITOR) { stream ->
                    val streamVal = volumeLevels[stream] ?: audioManager.getStreamVolume(stream)
                    val maxVal = audioManager.getStreamMaxVolume(stream)
                    val isLocked = lockStatuses[stream] ?: false
                    val lockedVal = lockLevels[stream] ?: streamVal

                    CompactVolumeStreamCard(
                        streamType = stream,
                        currentVolume = streamVal,
                        maxVolume = maxVal,
                        isLocked = isLocked,
                        lockedVal = lockedVal,
                        onVolumeChange = { newVal -> updateVolume(stream, newVal) },
                        onLockToggle = { toggleLock(stream) }
                    )
                }

                // Simulation tools box
                item {
                    DefectSimulatorCard(
                        isGuarded = isServiceRunning,
                        onSimulate = { simulateBrokenVolumeSpike() }
                    )
                }

                // Professional Activity Console
                item {
                    InterceptionLogsConsoleCard(
                        logs = actionLogs,
                        onClearLogs = {
                            storage.clearLogs()
                            actionLogs = emptyList()
                        }
                    )
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }
    }
}

@Composable
fun MasterShieldWidget(
    isActive: Boolean,
    onToggle: () -> Unit,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    
    // Smooth responsive accent color transitions based on active/inactive state
    val accentColor by animateColorAsState(
        targetValue = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        animationSpec = tween(durationMillis = 350), label = ""
    )
    
    val cardBgColor by animateColorAsState(
        targetValue = if (isActive) {
            if (isDark) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            if (isDark) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        },
        animationSpec = tween(durationMillis = 350), label = ""
    )

    // Dynamic rotation for security scan ring
    val infiniteTransition = rememberInfiniteTransition(label = "")
    val scanRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = ""
    )

    // Breathing pulse scale transition for active state
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = ""
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("master_shield_card"),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Bio-radar circular active state scanning shield element
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        if (isActive) {
                            scaleX = breathingScale
                            scaleY = breathingScale
                        }
                    }
            ) {
                // Background aura ring
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(accentColor.copy(alpha = 0.08f), CircleShape)
                        .border(1.dp, accentColor.copy(alpha = 0.15f), CircleShape)
                )

                // Rotating tactical lock pointer dash arcs when active
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize(0.9f)
                            .rotate(scanRotation)
                            .border(
                                width = 3.dp,
                                brush = Brush.sweepGradient(
                                    listOf(
                                        accentColor,
                                        Color.Transparent,
                                        accentColor.copy(alpha = 0.4f),
                                        Color.Transparent
                                    )
                                ),
                                shape = CircleShape
                            )
                    )
                }

                // Main Core Ring
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize(0.75f)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surface,
                                    accentColor.copy(alpha = 0.15f)
                                )
                            ),
                            CircleShape
                        )
                        .border(
                            2.dp,
                            Brush.linearGradient(listOf(accentColor, accentColor.copy(alpha = 0.3f))),
                            CircleShape
                        )
                ) {
                    Text(
                        text = if (isActive) "🛡️" else "🔌",
                        fontSize = 32.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = if (isActive) "ACTIVE EAR SHIELD" else "SHIELD DISARMED",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.8.sp,
                    fontSize = 15.sp
                ),
                color = accentColor
            )

            Text(
                text = if (isActive) 
                    "Ear protection service is running. Clamping and preventing hardware spikes instantly."
                    else "Instant clamp is offline. Volumes can skyrocket accidentally at any time.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                lineHeight = 16.sp
            )

            // Prompt permission setup if missing Android 13+
            if (!hasPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ),
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .testTag("grant_permission_button")
                ) {
                    Text("Grant Notification Authorization", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Balanced tactile, modern call-to-action button
            Button(
                onClick = onToggle,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    contentColor = if (isActive) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 5.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("service_toggle_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (isActive) "DEACTIVATE PROTECTION" else "ACTIVATE EAR SHIELD",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun CompactVolumeStreamCard(
    streamType: Int,
    currentVolume: Int,
    maxVolume: Int,
    isLocked: Boolean,
    lockedVal: Int,
    onVolumeChange: (Int) -> Unit,
    onLockToggle: () -> Unit
) {
    val streamName = VolumeGuardService.getStreamName(streamType)
    
    val iconEmoji = when (streamType) {
        AudioManager.STREAM_MUSIC -> "🎵"
        AudioManager.STREAM_RING -> "🔔"
        AudioManager.STREAM_NOTIFICATION -> "💬"
        AudioManager.STREAM_ALARM -> "⏰"
        AudioManager.STREAM_VOICE_CALL -> "☎️"
        else -> "🔊"
    }

    val isDark = isSystemInDarkTheme()
    
    // Accent status color mapping
    val statusColor = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    val borderStrokeColor = if (isLocked) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    }

    val cardBg = if (isLocked) {
        if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f)
    } else {
        if (isDark) MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
        else MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stream_card_$streamType"),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, borderStrokeColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Stream meta row (Compact & legible)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                if (isLocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                CircleShape
                            )
                    ) {
                        Text(text = iconEmoji, fontSize = 18.sp)
                    }
                    
                    Spacer(modifier = Modifier.width(10.dp))
                    
                    Column {
                        Text(
                            text = streamName,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isLocked) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Locked at limit: $lockedVal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Float volume limit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
                
                // Volume units read-out
                Text(
                    text = "$currentVolume / $maxVolume",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    ),
                    color = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Controller elements Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Beautiful fluid Volume Track Slider
                Slider(
                    value = currentVolume.toFloat(),
                    onValueChange = { onVolumeChange(it.toInt()) },
                    valueRange = 0f..maxVolume.toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                        .testTag("slider_$streamType"),
                    colors = SliderDefaults.colors(
                        activeTrackColor = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        inactiveTrackColor = if (isLocked) MaterialTheme.colorScheme.primary.copy(alpha = 0.24f) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                        thumbColor = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                )

                // High-End quick lock interactive capsule button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (isLocked) MaterialTheme.colorScheme.primary 
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .border(
                            1.dp,
                            if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            CircleShape
                        )
                        .clickable { onLockToggle() }
                        .testTag("lock_toggle_$streamType")
                ) {
                    Text(
                        text = if (isLocked) "🔒" else "🔓",
                        fontSize = 15.sp,
                        color = if (isLocked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun DefectSimulatorCard(
    isGuarded: Boolean,
    onSimulate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("defect_simulator_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f), CircleShape)
                ) {
                    Text("💡", fontSize = 16.sp)
                }
                
                Spacer(modifier = Modifier.width(10.dp))
                
                Column {
                    Text(
                        text = "Ear Safety Test Laboratory",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Verify clamp action under sudden audio spikes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onSimulate,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .testTag("simulate_spike_button"),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (isGuarded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                ),
                border = BorderStroke(
                    1.2.dp, 
                    if (isGuarded) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "🚨 SIMULATE SUDDEN MAX VOLUME SPIKE", 
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Black)
                )
            }
        }
    }
}

@Composable
fun InterceptionLogsConsoleCard(
    logs: List<String>,
    onClearLogs: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }
    val isDark = isSystemInDarkTheme()

    // Dynamic, responsive theme terminal styling
    val containerBg = if (isDark) {
        Color(0xFF14121E)
    } else {
        Color(0xFF262431) // Keep console beautifully dark for rich contrast and readable CLI logs in both themes
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("logs_console_card")
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerBg),
        border = BorderStroke(1.dp, Color(0xFF4AF626).copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { isExpanded = !isExpanded }
                ) {
                    Text("📟", fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp))
                    Text(
                        text = "GUARD CONSOLE",
                        color = Color(0xFF4AF626), // Retrotech phosphorescent light green
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isExpanded) "▲" else "▼",
                        color = Color(0xFF4AF626).copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                
                if (logs.isNotEmpty()) {
                    Text(
                        text = "Flush Logs",
                        color = Color(0xFFFF5252),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier
                            .clickable { onClearLogs() }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("clear_logs_button")
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded || logs.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Color(0xFF0C0913), RoundedCornerShape(10.dp))
                            .padding(8.dp)
                    ) {
                        if (logs.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Shield online. Waiting for spike clamp signals...",
                                    color = Color.Gray.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textAlign = TextAlign.Center,
                                        fontSize = 11.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(logs) { log ->
                                    Text(
                                        text = "⚡ [CLAMP] $log",
                                        color = Color(0xFF9EFF8B),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            lineHeight = 14.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

