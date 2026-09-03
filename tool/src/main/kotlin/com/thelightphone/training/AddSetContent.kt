package com.thelightphone.training

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.thelightphone.training.model.WeightUnit

private const val MIN_REPS = 1
private const val MAX_REPS = 300
private const val WEIGHT_STEP = 5
private const val MAX_WEIGHT_STEPS = 200 // 200 * 5 = 1000 (kg or lb)

/**
 * Full-screen entry for a single set: weight on the left, reps on the right, each spun on a
 * wheel. Built on [NudgeWheelEntryContent], the generic multi-field layout also used by the
 * cardio duration editors.
 */
@Composable
fun AddSetContent(
    title: String,
    weightUnit: WeightUnit,
    initialReps: Int,
    initialWeightText: String = "",
    onConfirm: (reps: Int, weightText: CharSequence) -> Unit,
    onBack: () -> Unit,
    // Must change every time a new "add set" flow starts, even if this composable's call site
    // stays mounted across sets, so the wheels below reset to the new set's initial values
    // instead of keeping stale state from a previous one.
    sessionKey: Any,
) {
    val weightFieldState = remember(sessionKey) { TextFieldState(initialWeightText) }
    val repsFieldState = remember(sessionKey) { TextFieldState(initialReps.toString()) }
    val weightLabel = "Weight (${weightUnit.displayName})"

    // The wheel only lands on whole WEIGHT_STEP multiples -- a typed/prefilled decimal (e.g.
    // "62.5") stays exactly as-is until the wheel itself is turned, which then snaps it to the
    // nearest step.
    val weightWheelValue = ((weightFieldState.text.toString().toDoubleOrNull() ?: 0.0) / WEIGHT_STEP)
        .let { Math.round(it).toInt() }
        .coerceIn(0, MAX_WEIGHT_STEPS)
    val repsWheelValue = (repsFieldState.text.toString().toIntOrNull() ?: initialReps).coerceIn(MIN_REPS, MAX_REPS)

    NudgeWheelEntryContent(
        title = title,
        fields = listOf(
            NudgeFieldConfig(
                label = weightLabel,
                fieldState = weightFieldState,
                wheelRange = 0..MAX_WEIGHT_STEPS,
                wheelValue = weightWheelValue,
                wheelFormat = { (it * WEIGHT_STEP).toString() },
                onWheelValueChange = { setIntField(weightFieldState, it * WEIGHT_STEP) },
            ),
            NudgeFieldConfig(
                label = "Reps",
                fieldState = repsFieldState,
                wheelRange = MIN_REPS..MAX_REPS,
                wheelValue = repsWheelValue,
                onWheelValueChange = { setIntField(repsFieldState, it) },
            ),
        ),
        onConfirm = {
            val reps = repsFieldState.text.toString().toIntOrNull()?.coerceAtLeast(MIN_REPS) ?: initialReps
            onConfirm(reps, weightFieldState.text)
        },
        onBack = onBack,
    )
}
