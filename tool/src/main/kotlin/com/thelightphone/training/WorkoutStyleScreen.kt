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

/**
 * Lets the user pick which style of workout to start. Strength training reuses the existing
 * session flow; cardio and interval are mock-up flows exploring a new layout direction.
 */
class WorkoutStyleScreen(
    sealedActivity: SealedLightActivity,
    private val onSelectStrength: () -> Unit,
    private val onSelectCardio: () -> Unit,
    private val onSelectInterval: () -> Unit,
) : SimpleLightScreen<Unit>(sealedActivity) {

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
                        onClick = { goBack(Unit) },
                        contentDescription = "Cancel",
                    ),
                    center = LightTopBarCenter.Text("New Workout"),
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
                        onClick = onSelectStrength,
                    )
                    WorkoutStyleRow(
                        icon = LightIcons.LOOP,
                        title = "Steady-State Cardio",
                        subtitle = "Track one continuous effort by duration",
                        onClick = onSelectCardio,
                    )
                    WorkoutStyleRow(
                        icon = LightIcons.ALARM,
                        title = "Interval Training",
                        subtitle = "Alternate work and rest for a set number of rounds",
                        onClick = onSelectInterval,
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
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightIcon(
            icon = icon,
            size = 3f,
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp),
        )
        Column {
            LightText(text = title, variant = LightTextVariant.Copy)
            LightText(
                text = subtitle,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
