package com.thelightphone.training

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable

/**
 * Full-screen reps entry using an up/down stepper instead of the keyboard, since reps are
 * always a small whole number and stepping is faster than typing on the LP3 keyboard.
 */
@Composable
fun RepsStepperContent(
    title: String,
    reps: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel",
            ),
            center = LightTopBarCenter.Text(title),
        )

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightIcon(
                    icon = LightIcons.UP,
                    contentDescription = "Increase reps",
                    modifier = Modifier.lightClickable(onClick = onIncrement),
                )
                LightText(
                    text = "$reps",
                    variant = LightTextVariant.Title,
                )
                LightIcon(
                    icon = LightIcons.DOWN,
                    contentDescription = "Decrease reps",
                    modifier = Modifier.lightClickable(onClick = onDecrement),
                )
            }
        }

        LightBottomBar(
            items = listOf(
                null,
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onConfirm,
                    contentDescription = "Confirm reps",
                ),
                null,
            ),
        )
    }
}
