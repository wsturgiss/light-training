package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay

private const val DEFAULT_DURATION_MIN = 30
private const val MIN_DURATION_MIN = 5
private const val DURATION_STEP_MIN = 5

private enum class CardioMode { CONFIGURE, ACTIVE }

/**
 * Mock-up for a steady-state cardio workout: pick a target duration, then run a live
 * stopwatch against it. Not wired to persistence -- exploring the flow and layout only.
 */
class CardioWorkoutScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var mode by remember { mutableStateOf(CardioMode.CONFIGURE) }
        var targetMinutes by remember { mutableStateOf(DEFAULT_DURATION_MIN) }

        LightTheme(colors = themeColors) {
            when (mode) {
                CardioMode.CONFIGURE -> CardioConfigureContent(
                    targetMinutes = targetMinutes,
                    onAdjust = { delta ->
                        targetMinutes = (targetMinutes + delta).coerceAtLeast(MIN_DURATION_MIN)
                    },
                    onBack = { goBack(Unit) },
                    onStart = { mode = CardioMode.ACTIVE },
                )

                CardioMode.ACTIVE -> CardioActiveContent(
                    targetMinutes = targetMinutes,
                    onFinish = { goBack(Unit) },
                )
            }
        }
    }
}

@Composable
private fun CardioConfigureContent(
    targetMinutes: Int,
    onAdjust: (Int) -> Unit,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel",
            ),
            center = LightTopBarCenter.Text("Steady-State Cardio"),
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LightIcon(
                icon = LightIcons.UP,
                contentDescription = "Increase duration",
                size = 4f,
                modifier = Modifier.lightClickable(onClick = { onAdjust(DURATION_STEP_MIN) }),
            )
            LightText(
                text = targetMinutes.toString(),
                variant = LightTextVariant.Title,
                modifier = Modifier.padding(top = 16.dp),
            )
            LightText(
                text = "Minutes",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 4.dp),
            )
            LightIcon(
                icon = LightIcons.DOWN,
                contentDescription = "Decrease duration",
                size = 4f,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .lightClickable(onClick = { onAdjust(-DURATION_STEP_MIN) }),
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onStart,
                    contentDescription = "Start cardio workout",
                ),
            ),
        )
    }
}

@Composable
private fun CardioActiveContent(
    targetMinutes: Int,
    onFinish: () -> Unit,
) {
    var elapsedSeconds by remember { mutableStateOf(0) }
    var isRunning by remember { mutableStateOf(true) }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            delay(1000)
            elapsedSeconds++
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text("Steady-State Cardio"),
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LightText(
                text = formatElapsed(elapsedSeconds),
                variant = LightTextVariant.Title,
                monospace = true,
            )
            LightText(
                text = "Target: $targetMinutes min",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = if (isRunning) LightIcons.PAUSE else LightIcons.PLAY,
                    onClick = { isRunning = !isRunning },
                    contentDescription = if (isRunning) "Pause" else "Resume",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onFinish,
                    contentDescription = "Finish workout",
                ),
            ),
        )
    }
}

private fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
