package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.training.model.Exercise

/**
 * A simple, single-column exercise picker used by the cardio and interval mock-up flows.
 * Unlike [SessionPickExerciseContent] it takes an already-filtered exercise list (e.g. just
 * the "Cardio" muscle group) and skips the muscle-group subtitle, since it's implied.
 */
@Composable
fun ExercisePickerContent(
    title: String,
    exercises: List<Exercise>,
    isLoading: Boolean = false,
    onBack: () -> Unit,
    onSelect: (Exercise) -> Unit,
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
            center = LightTopBarCenter.Text(title),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                // Avoid flashing the "no exercises" empty state while the initial load is in flight.
            } else if (exercises.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                    LightText(
                        text = "No cardio exercises yet",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                    LightText(
                        text = "Add one to the Cardio muscle group in Settings first.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.DenseScrollPadding),
                ) {
                    exercises.forEach { exercise ->
                        LightText(
                            text = exercise.name,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable(onClick = { onSelect(exercise) })
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }
}
