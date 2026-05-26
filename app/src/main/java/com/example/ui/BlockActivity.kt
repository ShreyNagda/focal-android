package com.example.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.MainActivity
import com.example.service.FocusService

class BlockActivity : ComponentActivity() {

    private val blockedAppNameState = mutableStateOf("App")
    private val blockReasonState = mutableStateOf("Time limit exceeded")
    private val targetPackageState = mutableStateOf<String?>(null)
    private val remainingMinutesState = mutableStateOf(-1)
    private val showOpenButtonState = mutableStateOf(false)
    private val minutesUsedTodayState = mutableStateOf(0)
    private val allowedMinutesState = mutableStateOf(-1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        blockedAppNameState.value = intent.getStringExtra("BLOCKED_APP_NAME") ?: "App"
        blockReasonState.value = intent.getStringExtra("BLOCK_REASON") ?: "Time limit exceeded"
        targetPackageState.value = intent.getStringExtra("TARGET_PACKAGE_NAME")
        remainingMinutesState.value = intent.getIntExtra("REMAINING_MINUTES", -1)
        showOpenButtonState.value = intent.getBooleanExtra("SHOW_OPEN_BUTTON", false)
        minutesUsedTodayState.value = intent.getIntExtra("MINUTES_USED_TODAY", 0)
        allowedMinutesState.value = intent.getIntExtra("ALLOWED_MINUTES", -1)

        setContent {
            BlockScreen(
                appName = blockedAppNameState.value,
                reason = blockReasonState.value,
                remainingMinutes = remainingMinutesState.value,
                showOpenButton = showOpenButtonState.value,
                minutesUsedToday = minutesUsedTodayState.value,
                allowedMinutes = allowedMinutesState.value,
                onDismiss = {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(homeIntent)
                    finish()
                },
                onOpenApp = {
                    val pkg = targetPackageState.value
                    if (pkg != null) {
                        FocusService.authorizedPackage = pkg
                        FocusService.justAuthorizedByBlockActivity = true
                        FocusService.authorizationTimestamp = System.currentTimeMillis()
                        try {
                            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                            if (launchIntent != null) {
                                startActivity(launchIntent)
                            }
                        } catch (e: Exception) {
                            Log.e("BlockActivity", "Launch failed for package $pkg: ${e.message}")
                        }
                    }
                    finish()
                },
                onOpenMain = {
                    val mainIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(mainIntent)
                    finish()
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        blockedAppNameState.value = intent.getStringExtra("BLOCKED_APP_NAME") ?: "App"
        blockReasonState.value = intent.getStringExtra("BLOCK_REASON") ?: "Time limit exceeded"
        targetPackageState.value = intent.getStringExtra("TARGET_PACKAGE_NAME")
        remainingMinutesState.value = intent.getIntExtra("REMAINING_MINUTES", -1)
        showOpenButtonState.value = intent.getBooleanExtra("SHOW_OPEN_BUTTON", false)
        minutesUsedTodayState.value = intent.getIntExtra("MINUTES_USED_TODAY", 0)
        allowedMinutesState.value = intent.getIntExtra("ALLOWED_MINUTES", -1)
    }

    override fun onStart() {
        super.onStart()
        FocusService.isBlockActivityShowing = true
    }

    override fun onStop() {
        super.onStop()
        FocusService.isBlockActivityShowing = false
    }

    override fun onDestroy() {
        super.onDestroy()
        FocusService.isBlockActivityShowing = false
    }

    override fun onBackPressed() {
        // Prevent back press to block app access
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        finish()
    }
}

@Composable
fun BlockScreen(
    appName: String,
    reason: String,
    remainingMinutes: Int,
    showOpenButton: Boolean,
    minutesUsedToday: Int,
    allowedMinutes: Int = -1,
    onDismiss: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenMain: () -> Unit
) {
    val isMonochromeActive = FocusService.isMonochromeScreenToggleActive.collectAsState().value
    val monochromeModifier = if (isMonochromeActive) {
        Modifier.drawWithContent {
            drawIntoCanvas { canvas ->
                val paint = Paint().apply {
                    colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
                }
                canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
                drawContent()
                canvas.restore()
            }
        }
    } else {
        Modifier
    }

    // Dynamic mindfulness delay: 5 seconds base + 2 seconds per minute used today
    val baseSecondsDelay = 5
    val delaySeconds = baseSecondsDelay + (2 * minutesUsedToday)
    var secondsRemaining by remember(appName, minutesUsedToday) { mutableStateOf(delaySeconds) }

    LaunchedEffect(appName, minutesUsedToday) {
        while (secondsRemaining > 0) {
            kotlinx.coroutines.delay(1000L)
            secondsRemaining--
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(monochromeModifier)
            .background(Color.Black)
            .padding(24.dp)
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top status
        Text(
            text = "MINIMAL FOCUS // INTERVENTION",
            color = Color(0xFFC4C4C6),
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.5.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 16.dp)
        )

        // Center Message
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(
                text = "STAY\nFOCUSED.",
                color = Color.White,
                fontSize = 52.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                lineHeight = 56.sp,
                textAlign = TextAlign.Center,
                letterSpacing = (-1).sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Thin border detail box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0A0A), RoundedCornerShape(24.dp))
                    .border(1.5.dp, Color(0xFF27272A), RoundedCornerShape(24.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = if (showOpenButton) "SCREEN TIME LIMIT" else "APP EXCLUSION",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = appName.uppercase(),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = reason,
                        color = Color(0xFFE5E5EA),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center
                    )
                    
                    // Show beautifully formatted clean usage numbers (no labels like Used Today or Limit)
                    val usageStatsText = when {
                        allowedMinutes >= 0 -> "$minutesUsedToday m / $allowedMinutes m"
                        else -> "$minutesUsedToday m"
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = usageStatsText,
                        color = Color(0xFFEF4444),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        // Action Buttons
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            if (showOpenButton) {
                val buttonEnabled = secondsRemaining <= 0
                // "Open App" - Primary button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            if (buttonEnabled) Color.White else Color.White.copy(alpha = 0.15f), 
                            RoundedCornerShape(14.dp)
                        )
                        .clickable(enabled = buttonEnabled) { onOpenApp() }
                ) {
                    Text(
                        text = if (buttonEnabled) "OPEN APP" else "TAKE DEEP BREATHS (${secondsRemaining}s)",
                        color = if (buttonEnabled) Color.Black else Color.White.copy(alpha = 0.5f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // "Close App" - Outline option
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Color.Transparent, RoundedCornerShape(14.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                        .clickable { onDismiss() }
                ) {
                    Text(
                        text = "CLOSE APP",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            } else {
                // "Exit to launcher" button - pure white text / black background with white outline
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(Color.White, RoundedCornerShape(14.dp))
                        .clickable { onDismiss() }
                ) {
                    Text(
                        text = "CLOSE APP",
                        color = Color.Black,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // "Open Focus App" link
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Color.Transparent, RoundedCornerShape(14.dp))
                    .border(1.5.dp, Color(0xFF27272A), RoundedCornerShape(14.dp))
                    .clickable { onOpenMain() }
            ) {
                Text(
                    text = "OPEN FOCUS CENTRE",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
