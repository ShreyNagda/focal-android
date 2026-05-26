package com.example

import android.app.AppOpsManager
import android.util.Log
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppUsageLimit
import com.example.data.FocusSessionLog
import com.example.service.FocusService
import com.example.ui.AppInfo
import com.example.ui.FocusViewModel
import com.example.ui.theme.MyApplicationTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val viewModel: FocusViewModel by viewModels()

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.updatePermissionFlags()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request POST_NOTIFICATIONS runtime permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
        }

        setContent {
            MyApplicationTheme {
                MainAppHost(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure accurate state updates when user returns from granting permissions in settings
        viewModel.updatePermissionFlags()
        if (viewModel.hasUsageStatsPermission.value) {
            FocusService.startService(this)
        }
    }
}

@Composable
fun MainAppHost(viewModel: FocusViewModel) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf("CHRONO") }
    
    // Grayscale Matrix implementation for app-level grayscale simulation!
    val isMonochromeActive by viewModel.isMonochromeActive.collectAsStateWithLifecycle()
    val monochromeModifier = if (isMonochromeActive) {
        val colorMatrix = remember { ColorMatrix().apply { setToSaturation(0f) } }
        val colorFilter = remember(colorMatrix) { ColorFilter.colorMatrix(colorMatrix) }
        val paint = remember(colorFilter) { Paint().apply { this.colorFilter = colorFilter } }
        Modifier.drawWithContent {
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                drawContent()
                canvas.restore()
            }
        }
    } else {
        Modifier
    }

    // Permission state flows to power the Startup Authorization Dialog
    val hasUsageStats by viewModel.hasUsageStatsPermission.collectAsStateWithLifecycle()
    val hasNotificationPermission by viewModel.hasNotificationListenerPermission.collectAsStateWithLifecycle()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsStateWithLifecycle()
    val onboardingCompleted by viewModel.onboardingCompleted.collectAsStateWithLifecycle()
    var showPermissionOverlay by remember { mutableStateOf(false) }

    LaunchedEffect(hasUsageStats, hasNotificationPermission, hasOverlayPermission, onboardingCompleted) {
        showPermissionOverlay = onboardingCompleted && (!hasUsageStats || !hasNotificationPermission || !hasOverlayPermission)
    }

    if (!onboardingCompleted) {
        OnboardingScreen(
            viewModel = viewModel,
            onFinish = { viewModel.completeOnboarding() }
        )
    } else {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(monochromeModifier),
            bottomBar = {
                BottomNavBar(activeTab = activeTab, onTabSelected = { activeTab = it })
            },
            containerColor = Color.Black
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                // High-contrast clean typography Header
                DashboardHeader()

                Spacer(modifier = Modifier.height(16.dp))

                // Tab contents
                Box(modifier = Modifier.weight(1f)) {
                    when (activeTab) {
                        "CHRONO" -> ChronoTab(viewModel)
                        "EXCLUDE" -> ExcludeTab(viewModel)
                        "SYSTEM" -> SystemTab(viewModel)
                    }
                }
            }
        }
    }

    if (showPermissionOverlay) {
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(
                    text = "SYSTEM ACCESS REQUIRED",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "To block distracting apps and suppress notification banners, please authorize these permissions:",
                        color = Color(0xFFD4D4D8),
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    if (!hasUsageStats) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Grant App Usage Tracker Access", 
                                fontSize = 11.sp, 
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (!hasNotificationPermission) {
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Grant Notif Suppression Access", 
                                fontSize = 11.sp, 
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    if (!hasOverlayPermission) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    context.startActivity(intent)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Grant Draw Over Other Apps Access", 
                                fontSize = 11.sp, 
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "Note: You can configure these anytime under the System Settings tab.",
                        color = Color(0xFF71717A),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPermissionOverlay = false }) {
                    Text("Skip for Now", color = Color(0xFF8E8E93), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            },
            containerColor = Color(0xFF09090B),
            textContentColor = Color.White,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.border(1.dp, Color(0xFF27272A), RoundedCornerShape(24.dp))
        )
    }
}

@Composable
fun DashboardHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            Text(
                text = "MINIMAL FOCUS",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Deep Work Lab",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.5).sp
            )
        }
        
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(20.dp))
                .background(Color.Transparent, RoundedCornerShape(20.dp))
        ) {
            Text(
                text = "⊙",
                color = Color.White,
                fontSize = 20.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ==================== TAB 1: POMODORO CHRONO ENGINE ====================

@Composable
fun ChronoTab(viewModel: FocusViewModel) {
    val context = LocalContext.current
    val secondsRemaining by viewModel.timerSecondsRemaining.collectAsStateWithLifecycle()
    val isRunning by viewModel.isTimerRunning.collectAsStateWithLifecycle()
    val isStarted by viewModel.isTimerStarted.collectAsStateWithLifecycle()
    val sessionType by viewModel.timerSessionType.collectAsStateWithLifecycle()
    val cycle by viewModel.currentCycle.collectAsStateWithLifecycle()
    val logs by viewModel.sessionLogs.collectAsStateWithLifecycle()

    val formattedMinutes = String.format("%02d", secondsRemaining / 60)
    val formattedSeconds = String.format("%02d", secondsRemaining % 60)

    var showDetails by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Core clock interaction container (Scrollable list for clocks, actions)
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Clocks Display
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val totalSeconds = when (sessionType) {
                        "WORK" -> FocusService.pomodoroDurationMins * 60
                        "SHORT_BREAK" -> FocusService.shortBreakDurationMins * 60
                        "LONG_BREAK" -> FocusService.longBreakDurationMins * 60
                        else -> 25 * 60
                    }
                    val progress = if (totalSeconds > 0) secondsRemaining.toFloat() / totalSeconds.toFloat() else 1f

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .aspectRatio(1f)
                            .padding(16.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = 1f,
                            modifier = Modifier.fillMaxSize(),
                            color = Color(0xFF1C1C1E),
                            strokeWidth = 12.dp
                        )
                        CircularProgressIndicator(
                            progress = progress,
                            modifier = Modifier.fillMaxSize(),
                            color = Color.White,
                            strokeWidth = 18.dp
                        )
                        
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "$formattedMinutes:$formattedSeconds",
                                color = Color.White,
                                fontSize = 54.sp,
                                fontWeight = FontWeight.Light,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = (-2).sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "CYCLE $cycle/4",
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isStarted) {
                                    if (isRunning) sessionType.uppercase() else "PAUSED"
                                } else {
                                    "Focus now"
                                },
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }

            // Action controls (Start, Pause, Resume, Reset, Skip)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Play / Pause / Resume Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(Color.White, shape = RoundedCornerShape(16.dp))
                            .clickable {
                                if (isRunning) {
                                    viewModel.pauseTimer(context)
                                } else {
                                    viewModel.startTimer(context)
                                }
                            }
                            .testTag("pomodoro_action")
                    ) {
                        Text(
                            text = if (isRunning) {
                                "PAUSE"
                            } else if (isStarted) {
                                "RESUME"
                            } else {
                                "START FOCUS"
                            },
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }

                    // Reset Button
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .border(1.dp, Color(0xFF27272A), shape = RoundedCornerShape(16.dp))
                            .background(Color.Transparent, shape = RoundedCornerShape(16.dp))
                            .clickable { viewModel.resetTimer(context) }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Reset timer",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Skip Button
                    if (sessionType != "WORK") {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .border(1.dp, Color(0xFF27272A), shape = RoundedCornerShape(16.dp))
                                .background(Color.Transparent, shape = RoundedCornerShape(16.dp))
                                .clickable { viewModel.skipTimer(context) }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ArrowForward,
                                contentDescription = "Skip session",
                                tint = Color(0xFF8E8E93),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Persistent bottom bar container holding the dynamic details dropdown expander
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black)
                .padding(bottom = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDetails = !showDetails }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (showDetails) "HIDE STATS & CONFIG" else "SHOW STATS & CONFIG",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Icon(
                        imageVector = if (showDetails) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Toggle controls",
                        tint = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            if (showDetails) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Interval parameters editor
                    var isEditingDurations by remember { mutableStateOf(false) }
                    if (!isEditingDurations) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0A0A0A), RoundedCornerShape(24.dp))
                                .border(1.dp, Color(0xFF18181B), RoundedCornerShape(24.dp))
                                .clickable { isEditingDurations = true }
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "ADJUST TIME PARAMS",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 0.5.sp
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${FocusService.pomodoroDurationMins}m work // ${FocusService.shortBreakDurationMins}m short // ${FocusService.longBreakDurationMins}m long",
                                        color = Color(0xFF8E8E93),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Edit Interval parameters",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0A0A0A), RoundedCornerShape(24.dp))
                                .border(1.dp, Color.White, RoundedCornerShape(24.dp))
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "INTERVAL PARAMETERS",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )

                            var workVal by remember { mutableStateOf(FocusService.pomodoroDurationMins.toString()) }
                            var shortVal by remember { mutableStateOf(FocusService.shortBreakDurationMins.toString()) }
                            var longVal by remember { mutableStateOf(FocusService.longBreakDurationMins.toString()) }

                            Column {
                                Text("Work Session Duration (Minutes):", color = Color(0xFF8E8E93), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                OutlinedTextField(
                                    value = workVal,
                                    onValueChange = { workVal = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedBorderColor = Color.White,
                                        unfocusedBorderColor = Color(0xFF18181B)
                                    ),
                                    singleLine = true
                                )
                            }

                            Column {
                                Text("Short Break Duration (Minutes):", color = Color(0xFF8E8E93), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                OutlinedTextField(
                                    value = shortVal,
                                    onValueChange = { shortVal = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedBorderColor = Color.White,
                                        unfocusedBorderColor = Color(0xFF18181B)
                                    ),
                                    singleLine = true
                                )
                            }

                            Column {
                                Text("Long Break Duration (Minutes):", color = Color(0xFF8E8E93), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                OutlinedTextField(
                                    value = longVal,
                                    onValueChange = { longVal = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        focusedBorderColor = Color.White,
                                        unfocusedBorderColor = Color(0xFF18181B)
                                    ),
                                    singleLine = true
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .background(Color.White, shape = RoundedCornerShape(12.dp))
                                        .clickable {
                                            val wM = workVal.toIntOrNull() ?: 25
                                            val sM = shortVal.toIntOrNull() ?: 5
                                            val lM = longVal.toIntOrNull() ?: 15
                                            viewModel.updateServiceDurations(wM, sM, lM)
                                            viewModel.resetTimer(context)
                                            isEditingDurations = false
                                        }
                                ) {
                                    Text("SAVE", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .border(1.dp, Color(0xFF27272A), shape = RoundedCornerShape(12.dp))
                                        .clickable { isEditingDurations = false }
                                ) {
                                    Text("CANCEL", color = Color.White, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }

                    // Dynamic Focus History/Logs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "FOCUS PERFORMANCE LABS",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )
                        if (logs.isNotEmpty()) {
                            Text(
                                text = "CLEAR ALL",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier
                                    .graphicsLayer { alpha = 0.6f }
                                    .clickable { viewModel.clearFocusHistory() }
                            )
                        }
                    }

                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0A0A0A), RoundedCornerShape(20.dp))
                                .border(1.dp, Color(0xFF18181B), RoundedCornerShape(20.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "NO COMPLETED FOCUS SESSIONS YET.\nSTRETCH YOUR INTERVALS WITH DEEPEND FOCUS.",
                                color = Color(0xFF8E8E93),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center,
                                lineHeight = 16.sp
                            )
                        }
                    } else {
                        logs.forEach { log ->
                            FocusLogItem(log)
                        }
                    }
                }
            }
        }
    }
}

const val FLIP_CARD_WIDTH = 72
const val FLIP_CARD_HEIGHT = 120
const val FLIP_CARD_HALF_HEIGHT = 60

@Composable
fun DigitHalf(
    digit: Char,
    isTop: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FLIP_CARD_HALF_HEIGHT.dp)
            .background(Color(0xFF1E1E20))
            .clipToBounds(),
        contentAlignment = Alignment.TopCenter
    ) {
        Text(
            text = digit.toString(),
            color = Color(0xFFE5E5EA),
            fontSize = 92.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.SansSerif,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .height(FLIP_CARD_HEIGHT.dp)
                .graphicsLayer {
                    if (!isTop) {
                        translationY = -FLIP_CARD_HALF_HEIGHT.dp.toPx()
                    }
                }
        )
    }
}

@Composable
fun FlipCardDigit(digit: Char, modifier: Modifier = Modifier) {
    var previousDigit by remember { mutableStateOf(digit) }
    var targetDigit by remember { mutableStateOf(digit) }
    val animationProgress = remember { Animatable(1f) }

    LaunchedEffect(digit) {
        if (digit != targetDigit) {
            previousDigit = targetDigit
            targetDigit = digit
            animationProgress.snapTo(0f)
            animationProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)
            )
        }
    }

    val progress = animationProgress.value

    Box(
        modifier = modifier
            .width(FLIP_CARD_WIDTH.dp)
            .height(FLIP_CARD_HEIGHT.dp)
            .background(Color.Black, shape = RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF27272A), shape = RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            DigitHalf(digit = targetDigit, isTop = true, modifier = Modifier.weight(1f))
            Box(modifier = Modifier.fillMaxWidth().height(1.5.dp).background(Color.Black))
            DigitHalf(digit = previousDigit, isTop = false, modifier = Modifier.weight(1f))
        }

        if (progress <= 0.5f) {
            val rotation = -progress * 180f
            DigitHalf(
                digit = previousDigit,
                isTop = true,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(FLIP_CARD_HALF_HEIGHT.dp)
                    .graphicsLayer {
                        rotationX = rotation
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        cameraDistance = 12f * density
                    }
            )
        } else {
            val rotation = (1f - progress) * 180f
            DigitHalf(
                digit = targetDigit,
                isTop = false,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(FLIP_CARD_HALF_HEIGHT.dp)
                    .graphicsLayer {
                        rotationX = rotation
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        cameraDistance = 12f * density
                    }
            )
        }
    }
}

@Composable
fun FlipColon(isRunning: Boolean) {
    var visible by remember { mutableStateOf(true) }
    if (isRunning) {
        LaunchedEffect(Unit) {
            while (true) {
                visible = !visible
                delay(1000)
            }
        }
    } else {
        visible = true
    }

    Box(
        modifier = Modifier
            .width(20.dp)
            .height(FLIP_CARD_HEIGHT.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (visible) Color.White else Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp))
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (visible) Color.White else Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
fun FocusLogItem(log: FocusSessionLog) {
    val dateStr = SimpleDateFormat("HH:mm:ss // yyyy-MM-dd", Locale.getDefault()).format(Date(log.timestamp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "${log.sessionType.uppercase()} COMPLETED",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dateStr,
                    color = Color(0xFF71717A),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            
            Text(
                text = "+${log.durationMinutes}m",
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        Divider(color = Color(0xFF18181B), thickness = 1.dp)
    }
}

// ==================== TAB 2: EXCLUSIONS & APP LIMITER ====================

@Composable
fun ExcludeTab(viewModel: FocusViewModel) {
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    val limits by viewModel.appLimits.collectAsStateWithLifecycle()
    
    var searchQuery by remember { mutableStateOf("") }
    var selectedAppForEdit by remember { mutableStateOf<AppInfo?>(null) }

    // Map package limits for rapid lookup
    val limitsMap = remember(limits) { limits.associateBy { it.packageName } }

    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isEmpty()) apps
        else apps.filter { it.appName.contains(searchQuery, ignoreCase = true) }
    }

    // Sort apps with applied limits to the very top in a structured directory format, then by minutes used today
    val sortedApps = remember(filteredApps, limits) {
        val mappedLimits = limits.associateBy { it.packageName }
        filteredApps.sortedWith(
            compareByDescending<AppInfo> { app ->
                val limit = mappedLimits[app.packageName]
                limit != null && (limit.allowedMinutes >= 0 || limit.isHardBlocked)
            }.thenByDescending { app ->
                val limit = mappedLimits[app.packageName]
                limit?.minutesUsedToday ?: 0
            }.thenBy { it.appName.lowercase() }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "APPLICATION MANAGEMENT",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "Set system limits or toggle active focus blocks on apps.",
            color = Color(0xFF8E8E93),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // B&W Crisp Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search installed applications...", color = Color(0xFF71717A), fontSize = 15.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("app_search"),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedContainerColor = Color(0xFF0A0A0A),
                unfocusedContainerColor = Color(0xFF0A0A0A),
                focusedBorderColor = Color.White,
                unfocusedBorderColor = Color(0xFF18181B)
            ),
            leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = "Search icon", tint = Color(0xFF71717A)) }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (sortedApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF18181B), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "NO CORRESPONDING APPS FOUND.",
                    color = Color(0xFF8E8E93),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(sortedApps) { app ->
                    val appLimit = limitsMap[app.packageName]
                    AppLimiterRow(
                        app = app,
                        limit = appLimit,
                        onConfigClick = { selectedAppForEdit = app },
                        onToggleHardBlock = { isChecked ->
                            viewModel.toggleHardBlock(app.packageName, app.appName, isChecked)
                        }
                    )
                }
            }
        }

        // Limit Setup Modal Dialog
        selectedAppForEdit?.let { app ->
            val curLimit = limitsMap[app.packageName]
            EditLimitDialog(
                app = app,
                currentLimit = curLimit,
                onDismiss = { selectedAppForEdit = null },
                onSave = { mins, block ->
                    viewModel.setAppLimit(app.packageName, app.appName, mins, block)
                    selectedAppForEdit = null
                },
                onRemove = {
                    viewModel.removeLimit(app.packageName)
                    selectedAppForEdit = null
                }
            )
        }
    }
}

@Composable
fun AppLimiterRow(
    app: AppInfo,
    limit: AppUsageLimit?,
    onConfigClick: () -> Unit,
    onToggleHardBlock: (Boolean) -> Unit
) {
    val hasLimitApplied = limit != null && (limit.allowedMinutes >= 0 || limit.isHardBlocked)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onConfigClick() }
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.appName,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                
                // Limit states string details (no labels like Limit or Today)
                val minutesToday = limit?.minutesUsedToday ?: 0
                val limitStr = if (limit != null && limit.allowedMinutes >= 0) {
                    "${minutesToday}m / ${limit.allowedMinutes}m"
                } else {
                    "${minutesToday}m"
                }
                val activeBlockStr = if (limit?.isHardBlocked == true) " • LOCKED ON POMODORO" else ""

                Text(
                    text = "$limitStr$activeBlockStr".uppercase(),
                    color = if (hasLimitApplied) Color.White.copy(alpha = 0.85f) else Color(0xFF71717A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )

                // Beautifully designed progress bar for visual tracking
                if (limit != null && limit.allowedMinutes > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val progress = (limit.minutesUsedToday.toFloat() / limit.allowedMinutes.toFloat()).coerceIn(0f, 1f)
                    val progressColor = if (progress >= 1f) Color(0xFFEF4444) else Color(0xFF4CAF50)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = progressColor,
                        trackColor = Color(0xFF1E1E21)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Quick-toggle Hard Block button using elegant minimalist lock icon
                IconButton(
                    onClick = { onToggleHardBlock(limit?.isHardBlocked != true) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = if (limit?.isHardBlocked == true) Icons.Filled.Lock else Icons.Outlined.Lock,
                        contentDescription = "Toggle focus block",
                        tint = if (limit?.isHardBlocked == true) Color.White else Color(0xFF52525B)
                    )
                }

                Box(
                    modifier = Modifier
                        .clickable { onConfigClick() }
                        .then(
                            if (hasLimitApplied) {
                                Modifier.background(Color.White, shape = RoundedCornerShape(8.dp))
                            } else {
                                Modifier.border(1.dp, Color(0xFF27272A), shape = RoundedCornerShape(8.dp))
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "LIMIT",
                        color = if (hasLimitApplied) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        Divider(color = Color(0xFF18181B), thickness = 1.dp)
    }
}

@Composable
fun EditLimitDialog(
    app: AppInfo,
    currentLimit: AppUsageLimit?,
    onDismiss: () -> Unit,
    onSave: (Int, Boolean) -> Unit,
    onRemove: () -> Unit
) {
    var minutesText by remember { mutableStateOf(if (currentLimit != null && currentLimit.allowedMinutes >= 0) currentLimit.allowedMinutes.toString() else "30") }
    var shouldHardBlock by remember { mutableStateOf(currentLimit?.isHardBlocked == true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "${app.appName.uppercase()} RULES",
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Daily minutes input
                Column {
                    Text(
                        text = "Daily Usage Allowance (Minutes):",
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0A0A0A),
                            unfocusedContainerColor = Color(0xFF0A0A0A),
                            focusedBorderColor = Color.White,
                            unfocusedBorderColor = Color(0xFF27272A)
                        ),
                        singleLine = true
                    )
                    Text(
                        text = "Leave empty or enter -1 for unlimited access",
                        color = Color(0xFF71717A),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Hard block toggle integration
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { shouldHardBlock = !shouldHardBlock }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Restrict during Focus",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Diverts launch attempts to blockade screen during active Pomodoro WORK blocks.",
                            color = Color(0xFF8E8E93),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = shouldHardBlock,
                        onCheckedChange = { shouldHardBlock = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White,
                            uncheckedThumbColor = Color(0xFF8E8E93),
                            uncheckedTrackColor = Color.Black,
                            uncheckedBorderColor = Color(0xFF48484A)
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mins = minutesText.toIntOrNull() ?: -1
                    onSave(mins, shouldHardBlock)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("APPLY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentLimit != null) {
                    TextButton(onClick = onRemove) {
                        Text("REMOVE LIMIT", color = Color.Red, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("CLOSE", color = Color(0xFF8E8E93), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        containerColor = Color(0xFF0A0A0A),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.border(1.dp, Color(0xFF18181B), RoundedCornerShape(24.dp))
    )
}

// ==================== TAB 3: SYSTEM INTERVENTIONS & PERMISSIONS ====================

@Composable
fun SystemTab(viewModel: FocusViewModel) {
    val context = LocalContext.current
    
    // Permission state flows
    val hasUsageStats by viewModel.hasUsageStatsPermission.collectAsStateWithLifecycle()
    val hasNotificationPermission by viewModel.hasNotificationListenerPermission.collectAsStateWithLifecycle()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsStateWithLifecycle()
    val hasAccessibilityPermission by viewModel.hasAccessibilityPermission.collectAsStateWithLifecycle()

    // Screen grayscale simulation
    val isMonochromeScreen by viewModel.isMonochromeActive.collectAsStateWithLifecycle()

    // Notification filters
    val isNotifFilterOn by viewModel.isNotificationFilterEnabled.collectAsStateWithLifecycle()
    val whitelistApps by viewModel.notificationWhitelist.collectAsStateWithLifecycle()
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()

    var whitelistSearchQuery by remember { mutableStateOf("") }
    val filteredWhitelistApps = remember(apps, whitelistSearchQuery) {
        if (whitelistSearchQuery.isEmpty()) apps
        else apps.filter { it.appName.contains(whitelistSearchQuery.trim(), ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // SYSTEM CONTROLS
        item {
            Text(
                text = "SYSTEM INTERVENTIONS",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
            )
        }

        // Grayscale mode simulation row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xFF18181B), RoundedCornerShape(24.dp))
                    .clickable { viewModel.toggleMonochromeMode() }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                    ) {
                        Text("G", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    
                    Column {
                        Text(
                            text = "Grayscale Mode",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Enforce pitch-black monochrome matrix",
                            color = Color(0xFF71717A),
                            fontSize = 11.sp
                        )
                    }
                }
                Switch(
                    checked = isMonochromeScreen,
                    onCheckedChange = { viewModel.toggleMonochromeMode() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color.White,
                        uncheckedThumbColor = Color(0xFF8E8E93),
                        uncheckedTrackColor = Color.Black,
                        uncheckedBorderColor = Color(0xFF48484A)
                    )
                )
            }
        }

        // Action setting to trigger system grayscale
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xFF18181B), RoundedCornerShape(24.dp))
                    .clickable {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // fallback
                        }
                    }
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(32.dp)
                                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                        ) {
                            Text("S", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        
                        Column {
                            Text(
                                text = "System Grayscale Config",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Accessibility display parameters settings",
                                color = Color(0xFF71717A),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(imageVector = Icons.Filled.ArrowForward, contentDescription = "Arrow right", tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }

        // Notification Filter Suppression row
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(24.dp))
                    .border(1.dp, Color(0xFF18181B), RoundedCornerShape(24.dp))
                    .clickable { 
                        if (hasNotificationPermission) {
                            viewModel.toggleNotificationFilter(!isNotifFilterOn)
                        } else {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                    ) {
                        Text("N", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    
                    Column {
                        Text(
                            text = "Strict Notif Filter",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Suppress system banners during active focus",
                            color = Color(0xFF71717A),
                            fontSize = 11.sp
                        )
                    }
                }
                Switch(
                    checked = isNotifFilterOn,
                    onCheckedChange = { 
                        if (hasNotificationPermission) {
                            viewModel.toggleNotificationFilter(it)
                        } else {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.Black,
                        checkedTrackColor = Color.White,
                        uncheckedThumbColor = Color(0xFF8E8E93),
                        uncheckedTrackColor = Color.Black,
                        uncheckedBorderColor = Color(0xFF48484A)
                    ),
                    modifier = Modifier.testTag("notification_toggle")
                )
            }
        }

        // Whitelist Selection Area (Hidden if filter is turned off to prevent layout saturation)
        if (isNotifFilterOn) {
            item {
                Text(
                    text = "NOTIFICATION WHITELIST",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            item {
                OutlinedTextField(
                    value = whitelistSearchQuery,
                    onValueChange = { whitelistSearchQuery = it },
                    placeholder = { Text("Search whitelist packages...", color = Color(0xFF71717A), fontSize = 12.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("whitelist_search"),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color(0xFF0A0A0A),
                        unfocusedContainerColor = Color(0xFF0A0A0A),
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color(0xFF18181B)
                    ),
                    leadingIcon = { Icon(imageVector = Icons.Filled.Search, contentDescription = "Search icon", tint = Color(0xFF71717A), modifier = Modifier.size(16.dp)) }
                )
            }

            if (filteredWhitelistApps.isEmpty()) {
                item {
                    Text("No matching apps found.", color = Color(0xFF8E8E93), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0A0A0A), RoundedCornerShape(24.dp))
                            .border(1.dp, Color(0xFF18181B), RoundedCornerShape(24.dp))
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        filteredWhitelistApps.forEach { app ->
                            val isWhitelisted = whitelistApps.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleNotificationWhitelistApp(app.packageName) }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(app.appName, color = if (isWhitelisted) Color.White else Color(0xFF8E8E93), fontSize = 13.sp)
                                Icon(
                                    imageVector = if (isWhitelisted) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                    contentDescription = "Whitelist toggled status",
                                    tint = if (isWhitelisted) Color.White else Color(0xFF52525B),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Divider(color = Color(0xFF18181B))
                        }
                    }
                }
            }
        }

        // REQUIRED ACCESS & CRED_LEVEL CONFIGURATION
        item {
            Text(
                text = "SYSTEM ACCESS AUTHORIZATION",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // Usage access block
        item {
            PermissionCard(
                title = "USAGE STATISTICS API Access",
                description = "Required to detect which application is currently in the foreground and enforce focus block screens.",
                isGranted = hasUsageStats,
                onGrantClick = {
                    try {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            )
        }

        // Notification Listener access block
        item {
            PermissionCard(
                title = "NOTIF SUPPRESSION LISTENER KEY",
                description = "Required to dismiss system banner notifications from non-whitelisted apps when digital focus is active.",
                isGranted = hasNotificationPermission,
                onGrantClick = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    context.startActivity(intent)
                }
            )
        }

        // Screen Overlay access block (Draw over other apps)
        item {
            PermissionCard(
                title = "DRAW OVER OTHER APPS",
                description = "Required on Android 10+ to allow the focus blocker overlay to interrupt distracting applications from running in the background.",
                isGranted = hasOverlayPermission,
                onGrantClick = {
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        context.startActivity(intent)
                    }
                }
            )
        }

        // ACCESSIBILITY SERVICE APP BLOCKER
        item {
            PermissionCard(
                title = "ACCESSIBILITY SERVICE APP BLOCKER",
                description = "Recommended for instant, event-driven interception of distracting apps (like YouTube, Instagram) without heavy background battery drain.",
                isGranted = hasAccessibilityPermission,
                onGrantClick = {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Failed to launch accessibility settings: ${e.message}")
                    }
                }
            )
        }
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A), RoundedCornerShape(24.dp))
            .border(1.dp, if (isGranted) Color(0xFF18181B) else Color.White, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (isGranted) "AUTHORIZED" else "PENDING",
                color = if (isGranted) Color(0xFF8E8E93) else Color.Black,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(
                        color = if (isGranted) Color.Transparent else Color.White,
                        shape = RoundedCornerShape(8.dp)
                    )
                    .then(
                        if (isGranted) Modifier.border(1.dp, Color(0xFF27272A), RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        Text(
            text = description,
            color = Color(0xFF8E8E93),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )

        if (!isGranted) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(Color.White, shape = RoundedCornerShape(12.dp))
                    .clickable { onGrantClick() }
            ) {
                Text(
                    text = "GRANT AUTHORIZATION",
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun BottomNavBar(activeTab: String, onTabSelected: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(84.dp)
            .background(Color(0xFF0A0A0A))
            .drawWithContent {
                drawContent()
                // Top border line
                drawLine(
                    color = Color(0xFF18181B),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 2f
                )
            }
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabs = listOf(
            Triple("CHRONO", "◈", "FOCUS"),
            Triple("EXCLUDE", "▤", "LIMITS"),
            Triple("SYSTEM", "⚙", "SYSTEM")
        )

        tabs.forEach { (tabId, symbol, label) ->
            val isSelected = activeTab == tabId
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(tabId) }
                    .testTag("tab_select_$tabId")
                    .graphicsLayer {
                        alpha = if (isSelected) 1f else 0.4f
                    }
            ) {
                // Active pill background capsule
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 48.dp, height = 32.dp)
                        .background(
                            color = if (isSelected) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        )
                ) {
                    Text(
                        text = symbol,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

// ==================== ONBOARDING FLOW ====================

@Composable
fun OnboardingScreen(
    viewModel: FocusViewModel,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    var currentStep by remember { mutableStateOf(1) }

    val hasUsageStats by viewModel.hasUsageStatsPermission.collectAsStateWithLifecycle()
    val hasNotificationPermission by viewModel.hasNotificationListenerPermission.collectAsStateWithLifecycle()
    val hasOverlayPermission by viewModel.hasOverlayPermission.collectAsStateWithLifecycle()
    val hasAccessibilityPermission by viewModel.hasAccessibilityPermission.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Step indicator & Title
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FOCAL // ONBOARDING",
                color = Color(0xFF8E8E93),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Text(
                text = "PHASE 0$currentStep OF 03",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // Large Content Box
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (currentStep) {
                1 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "REGAIN\nCONTROL.",
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 46.sp,
                            letterSpacing = (-1).sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "Focal is an eye-safe, zero-distraction digital environment engineered to reclaim cognitive focus. By restricting visual stimulation and intercepting feedback loops, we keep your mind aligned.",
                            color = Color(0xFFD4D4D8),
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Features List
                        Text(
                            text = "CORE SYSTEM CAPABILITIES:",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.5.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        FeatureOnboardingItem(
                            icon = "⏲",
                            title = "Zen Pomodoro Timer",
                            desc = "Fully integrated high-intent work and break interval sessions with status bars, dynamic timers, and cycles."
                        )
                        FeatureOnboardingItem(
                            icon = "✕",
                            title = "Real-time App Exclusion",
                            desc = "Event-driven lockout screens that instantly intercept distracting social media (Instagram, YouTube, TikTok, etc.)."
                        )
                        FeatureOnboardingItem(
                            icon = "⑃",
                            title = "Daily App Constraints",
                            desc = "Establish specific allowed minutes for custom packages. Exceeding them triggers instant blockades."
                        )
                    }
                }
                2 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text(
                            text = "TAILOR\nYOUR FLOW.",
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 46.sp,
                            letterSpacing = (-1).sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = "A modern focus state requires strict, eye-pleasing aesthetic structures. Configure these built-in parameters inside the control center components at any time:",
                            color = Color(0xFFD4D4D8),
                            fontSize = 14.sp,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        SettingsOnboardingItem(
                            number = "01",
                            category = "TIMER CONFIG",
                            title = "Parametric Durations",
                            desc = "Set work sessions (e.g., 25m) and break intervals to align with your personal energy cycles."
                        )

                        SettingsOnboardingItem(
                            number = "02",
                            category = "VISUAL ENVIRONMENT",
                            title = "Simulated Grayscale",
                            desc = "A physical monochrome filter transforms colorful, addictive feeds into unstimulating pixels."
                        )

                        SettingsOnboardingItem(
                            number = "03",
                            category = "NOTIF SHIELD",
                            title = "Strict Filters & Whitelisting",
                            desc = "Mute unnecessary background incoming banner alerts, only allowing critical communications."
                        )
                    }
                }
                3 -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text(
                            text = "RESTRICT\nTHE NOISE.",
                            color = Color.White,
                            fontSize = 42.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 46.sp,
                            letterSpacing = (-1).sp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "To build a foolproof barrier, modern Android requires explicit permissions. Authorize the safety gateways below to begin:",
                            color = Color(0xFFD4D4D8),
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        // Permission Action Cards
                        PermissionOnboardingCard(
                            title = "1. APP USAGE TRACKING STATISTICS",
                            description = "Required to identify open activities and trigger exclusions.",
                            isGranted = hasUsageStats,
                            onGrantClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    context.startActivity(intent)
                                }
                            }
                        )

                        PermissionOnboardingCard(
                            title = "2. SYSTEM OVERLAY (DRAW OVER APPS)",
                            description = "Required to render the full-screen stay focused lockout overlay.",
                            isGranted = hasOverlayPermission,
                            onGrantClick = {
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    context.startActivity(intent)
                                }
                            }
                        )

                        PermissionOnboardingCard(
                            title = "3. NOTIFICATION LISTENER ACCREDITATION",
                            description = "Required to mute and suppress non-whitelisted incoming distraction alerts.",
                            isGranted = hasNotificationPermission,
                            onGrantClick = {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                                context.startActivity(intent)
                            }
                        )

                        PermissionOnboardingCard(
                            title = "4. ACCESSIBILITY INTERCEPTION (RECOMMENDED)",
                            description = "Recommended for instant, robust, hardware-level blocking with optimal battery stats.",
                            isGranted = hasAccessibilityPermission,
                            onGrantClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Accessibility settings launch failed: ${e.message}")
                                }
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom Navigation Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (currentStep > 1) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .border(1.dp, Color(0xFF27272A), RoundedCornerShape(12.dp))
                        .clickable { currentStep-- }
                ) {
                    Text(
                        text = "PREVIOUS",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .clickable {
                        if (currentStep < 3) {
                            currentStep++
                        } else {
                            onFinish()
                        }
                    }
            ) {
                Text(
                    text = if (currentStep < 3) "CONTINUE" else "ENTER FOCAL",
                    color = Color.Black,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun FeatureOnboardingItem(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF18181B), RoundedCornerShape(8.dp))
        ) {
            Text(
                text = icon,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                color = Color(0xFF8E8E93),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun SettingsOnboardingItem(number: String, category: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(36.dp)
        ) {
            Text(
                text = number,
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(30.dp)
                    .background(Color(0xFF18181B))
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                color = Color(0xFF8E8E93),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun PermissionOnboardingCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(Color(0xFF0A0A0A), RoundedCornerShape(16.dp))
            .border(
                1.dp,
                if (isGranted) Color(0xFF18181B) else Color.White.copy(alpha = 0.8f),
                RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (isGranted) Color(0x204CAF50) else Color(0x20F44336),
                        RoundedCornerShape(6.dp)
                    )
                    .border(
                        1.dp,
                        if (isGranted) Color(0xFF4CAF50) else Color(0xFFF44336),
                        RoundedCornerShape(6.dp)
                    )
            ) {
                Text(
                    text = if (isGranted) "✓" else "✕",
                    color = if (isGranted) Color(0xFF4CAF50) else Color(0xFFF44336),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (!isGranted) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .clickable { onGrantClick() }
            ) {
                Text(
                    text = "AUTHORIZE NOW",
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
