package com.thelightphone.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.training.model.WeightUnit
import kotlinx.coroutines.flow.StateFlow

private const val MIN_REPS = 1
private const val MIN_WEIGHT = 0.0
private const val WEIGHT_STEP = 5.0

private enum class ActiveField { NONE, WEIGHT, REPS }

/**
 * Full-screen entry for a single set: weight on the left, reps on the right, each nudgeable with
 * up/down arrows. Tapping either number switches to a full-screen [LightTextInputEditor] for just
 * that field; from there the user types and taps back (or DONE) to return to the combined view.
 */
@Composable
fun AddSetContent(
    title: String,
    weightUnit: WeightUnit,
    initialReps: Int,
    initialWeightText: String = "",
    keyboardOptionsFlow: StateFlow<KeyboardOptions>,
    onConfirm: (reps: Int, weightText: CharSequence) -> Unit,
    onBack: () -> Unit,
    // Must change every time a new "add set" flow starts (even if this composable's call site
    // stays mounted across sets), so the full-screen text editors below aren't bound to a stale
    // TextFieldState left over from a previous set. See LightTextInputEditor's editorKey.
    sessionKey: Any,
) {
    var activeField by remember { mutableStateOf(ActiveField.NONE) }
    val weightFieldState = rememberTextFieldState(initialWeightText)
    val repsFieldState = rememberTextFieldState(initialReps.toString())

    when (activeField) {
        ActiveField.WEIGHT -> {
            LightTextInputEditor(
                title = "Weight (${weightUnit.displayName})",
                state = weightFieldState,
                onSubmit = { activeField = ActiveField.NONE },
                onBack = { activeField = ActiveField.NONE },
                keyboardOptionsFlow = keyboardOptionsFlow,
                singleLine = true,
                submitLabel = "DONE",
                editorKey = "add-set-weight-$sessionKey",
            )
            return
        }

        ActiveField.REPS -> {
            LightTextInputEditor(
                title = "Reps",
                state = repsFieldState,
                onSubmit = { activeField = ActiveField.NONE },
                onBack = { activeField = ActiveField.NONE },
                keyboardOptionsFlow = keyboardOptionsFlow,
                singleLine = true,
                submitLabel = "DONE",
                editorKey = "add-set-reps-$sessionKey",
            )
            return
        }

        ActiveField.NONE -> Unit
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel",
            ),
            center = LightTopBarCenter.Text(title),
        )

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LightIcon(
                        icon = LightIcons.UP,
                        contentDescription = "Increase weight",
                        modifier = Modifier.lightClickable(
                            onClick = { adjustWeight(weightFieldState, +WEIGHT_STEP) },
                        ),
                    )
                    LightText(
                        text = "Weight (${weightUnit.displayName})",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LightText(
                        text = weightFieldState.text.toString().ifEmpty { "\u2014" },
                        variant = LightTextVariant.Title,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .lightClickable(onClick = { activeField = ActiveField.WEIGHT }),
                    )
                    LightIcon(
                        icon = LightIcons.DOWN,
                        contentDescription = "Decrease weight",
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .lightClickable(onClick = { adjustWeight(weightFieldState, -WEIGHT_STEP) }),
                    )
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    LightIcon(
                        icon = LightIcons.UP,
                        contentDescription = "Increase reps",
                        modifier = Modifier.lightClickable(
                            onClick = { adjustReps(repsFieldState, +1) },
                        ),
                    )
                    LightText(
                        text = "Reps",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    LightText(
                        text = repsFieldState.text.toString(),
                        variant = LightTextVariant.Title,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .lightClickable(onClick = { activeField = ActiveField.REPS }),
                    )
                    LightIcon(
                        icon = LightIcons.DOWN,
                        contentDescription = "Decrease reps",
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .lightClickable(onClick = { adjustReps(repsFieldState, -1) }),
                    )
                }
        }


        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = {
                        activeField = ActiveField.NONE
                        val reps = repsFieldState.text.toString().toIntOrNull()?.coerceAtLeast(MIN_REPS)
                            ?: initialReps
                        onConfirm(reps, weightFieldState.text)
                    },
                    contentDescription = "Confirm set",
                ),
            ),
        )
    }
}

private fun adjustReps(state: TextFieldState, delta: Int) {
    val current = state.text.toString().toIntOrNull() ?: MIN_REPS
    val updated = (current + delta).coerceAtLeast(MIN_REPS)
    state.edit {
        replace(0, length, updated.toString())
        selection = TextRange(length)
    }
}

private fun adjustWeight(state: TextFieldState, delta: Double) {
    val current = state.text.toString().toDoubleOrNull() ?: MIN_WEIGHT
    val updated = (current + delta).coerceAtLeast(MIN_WEIGHT)
    val formatted = if (updated == updated.toLong().toDouble()) {
        updated.toLong().toString()
    } else {
        updated.toString()
    }
    state.edit {
        replace(0, length, formatted)
        selection = TextRange(length)
    }
}
