package com.thelightphone.training

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter

/** One field of a [NudgeWheelEntryContent]: a value spun on a [WheelNumberPicker]. [fieldState]
 * is the underlying storage the caller reads at confirm time -- [onWheelValueChange] should keep
 * it in sync (see [setIntField]). */
class NudgeFieldConfig(
    val label: String,
    val fieldState: TextFieldState,
    val wheelRange: IntRange,
    val wheelValue: Int,
    val onWheelValueChange: (Int) -> Unit,
    val wheelFormat: (Int) -> String = { it.toString() },
    /** True if spinning past either end of [wheelRange] should continue into the other end
     * (e.g. minutes/seconds on a clock) instead of stopping. */
    val wheelWraps: Boolean = false,
)

/**
 * Full-screen entry for several related numeric values side by side (e.g. weight+reps for a
 * logged set, or hours+minutes+seconds for a duration), each spun on a [WheelNumberPicker] that
 * fills the available space. Shared by [AddSetContent] and the cardio duration editors so this
 * layout isn't duplicated per use.
 */
@Composable
fun NudgeWheelEntryContent(
    title: String,
    fields: List<NudgeFieldConfig>,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    // Shows a ":" between fields, e.g. for hours/minutes/seconds duration entry. Not appropriate
    // for unrelated fields side by side (e.g. weight+reps).
    showFieldSeparators: Boolean = false,
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

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            fields.forEachIndexed { index, field ->
                if (index > 0 && showFieldSeparators) {
                    // Mirrors NudgeFieldColumn's wheel-then-label structure so the colon centers
                    // on the wheel itself, not on the wheel+label column as a whole.
                    Column(modifier = Modifier.fillMaxHeight()) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            LightText(
                                text = ":",
                                variant = LightTextVariant.Title,
                                modifier = Modifier.offset(y = (-6).dp),
                            )
                        }
                        LightText(
                            text = "",
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                NudgeFieldColumn(config = field)
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onConfirm,
                    contentDescription = "Confirm",
                ),
            ),
        )
    }
}

@Composable
private fun RowScope.NudgeFieldColumn(config: NudgeFieldConfig) {
    Column(
        modifier = Modifier.weight(1f).fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WheelNumberPicker(
            value = config.wheelValue,
            range = config.wheelRange,
            onValueChange = config.onWheelValueChange,
            formatValue = config.wheelFormat,
            wraps = config.wheelWraps,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        LightText(
            text = config.label,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

/** Hours+minutes+seconds duration entry, built on [NudgeWheelEntryContent]. Used by the cardio
 * logging and edit flows. */
@Composable
fun DurationNudgeEntryContent(
    title: String,
    initialSeconds: Int,
    onConfirm: (seconds: Int) -> Unit,
    onBack: () -> Unit,
) {
    val hoursFieldState = rememberTextFieldState((initialSeconds / 3600).toString())
    val minutesFieldState = rememberTextFieldState(((initialSeconds % 3600) / 60).toString())
    val secondsFieldState = rememberTextFieldState((initialSeconds % 60).toString())

    NudgeWheelEntryContent(
        title = title,
        fields = listOf(
            NudgeFieldConfig(
                label = "Hours",
                fieldState = hoursFieldState,
                wheelRange = 0..23,
                wheelValue = hoursFieldState.text.toString().toIntOrNull() ?: 0,
                onWheelValueChange = { setIntField(hoursFieldState, it) },
                wheelFormat = { "%02d".format(it) },
            ),
            NudgeFieldConfig(
                label = "Minutes",
                fieldState = minutesFieldState,
                wheelRange = 0..59,
                wheelValue = minutesFieldState.text.toString().toIntOrNull() ?: 0,
                onWheelValueChange = { setIntField(minutesFieldState, it) },
                wheelWraps = true,
                wheelFormat = { "%02d".format(it) },
            ),
            NudgeFieldConfig(
                label = "Seconds",
                fieldState = secondsFieldState,
                wheelRange = 0..59,
                wheelValue = secondsFieldState.text.toString().toIntOrNull() ?: 0,
                onWheelValueChange = { setIntField(secondsFieldState, it) },
                wheelWraps = true,
                wheelFormat = { "%02d".format(it) },
            ),
        ),
        showFieldSeparators = true,
        onConfirm = {
            val hours = hoursFieldState.text.toString().toIntOrNull() ?: 0
            val minutes = minutesFieldState.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
            val seconds = secondsFieldState.text.toString().toIntOrNull()?.coerceIn(0, 59) ?: 0
            onConfirm(hours * 3600 + minutes * 60 + seconds)
        },
        onBack = onBack,
    )
}

/** Overwrites an integer-valued [TextFieldState] with [value]. */
fun setIntField(state: TextFieldState, value: Int) {
    state.edit {
        replace(0, length, value.toString())
        selection = TextRange(length)
    }
}
