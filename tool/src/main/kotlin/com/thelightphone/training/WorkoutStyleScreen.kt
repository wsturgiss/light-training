package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable

/** Which style of workout the user picked on [WorkoutStyleScreen]. */
enum class WorkoutStyleChoice { STRENGTH, CARDIO, INTERVAL }

/**
 * Lets the user pick which style of workout to start. Strength training reuses the existing
 * session flow; cardio and interval are mock-up flows exploring a new layout direction.
 *
 * Delivers the choice back to whoever navigated here (via [goBack]/[SimpleLightScreen]'s result
 * callback) instead of navigating onward itself -- that way this screen pops off the back stack
 * *before* the chosen workout screen goes on, so finishing e.g. a cardio session returns
 * straight to the caller (typically the home screen) instead of back through this picker.
 */
class WorkoutStyleScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<WorkoutStyleChoice>(sealedActivity) {

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(null) },
                        contentDescription = "Cancel",
                    ),
                    center = LightTopBarCenter.Text("New Training Session"),
                )

                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.SpacedScrollPadding),
                ) {
                    WorkoutStyleRow(
                        icon = LightIcons.LARGE_LIST,
                        title = "Strength Training",
                        subtitle = "Log exercises, sets, and weights",
                        onClick = { goBack(WorkoutStyleChoice.STRENGTH) },
                    )
                    WorkoutStyleRow(
                        icon = LightIcons.LOOP,
                        title = "Steady-State Cardio",
                        subtitle = "Track one continuous effort by duration",
                        onClick = { goBack(WorkoutStyleChoice.CARDIO) },
                    )
                    WorkoutStyleRow(
                        icon = LightIcons.ALARM,
                        title = "Interval Training",
                        subtitle = "Coming soon",
                        enabled = false,
                        onClick = { goBack(WorkoutStyleChoice.INTERVAL) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutStyleRow(
    icon: LightIconConfiguration,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.lightClickable(onClick = onClick) else it }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = icon,
            size = 3f,
            contentDescription = null,
            modifier = Modifier
                .padding(end = 16.dp)
                .let { if (enabled) it else it.alpha(0.4f) },
        )
        Column {
            LightText(text = title, variant = LightTextVariant.Copy, lighten = !enabled)
            LightText(
                text = subtitle,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
