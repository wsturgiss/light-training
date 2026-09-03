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
    /** False to block manual spinning, e.g. while [TimerControls] is running the wheel itself. */
    val wheelEnabled: Boolean = true,
)

/** Play/pause + reset controls shown on [NudgeWheelEntryContent]'s bottom bar, for a wheel that
 * doubles as a live-ticking timer (see [DurationNudgeEntryContent]). Play/pause sits bottom-left,
 * reset bottom-right -- reset is omitted while running, since resetting a running timer out from
 * under yourself is more likely a misclick than intentional. */
class TimerControls(
    val isRunning: Boolean,
    val onToggleRunning: () -> Unit,
    val onReset: () -> Unit,
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
    // Shows [fieldSeparator] between fields, e.g. ":" for hours/minutes/seconds duration entry,
    // or "." for whole/tenths distance entry. Not appropriate for unrelated fields side by side
    // (e.g. weight+reps).
    showFieldSeparators: Boolean = false,
    fieldSeparator: String = ":",
    // One label centered under the whole row of wheels, instead of each field showing its own
    // label under just its own wheel (e.g. "Distance" under a whole+tenths pair, where a
    // per-field label would read as attached to the whole-number wheel alone). Mutually exclusive
    // with per-field labels -- pass empty [NudgeFieldConfig.label]s when using this.
    centeredLabel: String? = null,
    // Adds play/pause (bottom-left) and reset (bottom-right, paused only) alongside the confirm
    // button, for a wheel that doubles as a live timer.
    timerControls: TimerControls? = null,
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

        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                fields.forEachIndexed { index, field ->
                    if (index > 0 && showFieldSeparators) {
                        // Mirrors NudgeFieldColumn's wheel-then-label structure so the colon
                        // centers on the wheel itself, not on the wheel+label column as a whole.
                        Column(modifier = Modifier.fillMaxHeight()) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                LightText(
                                    text = fieldSeparator,
                                    variant = LightTextVariant.Title,
                                    modifier = Modifier.offset(y = (-6).dp),
                                )
                            }
                            if (centeredLabel == null) {
                                LightText(
                                    text = "",
                                    variant = LightTextVariant.Detail,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                    NudgeFieldColumn(config = field, showLabel = centeredLabel == null)
                }
            }
            if (centeredLabel != null) {
                LightText(
                    text = centeredLabel,
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }

        LightBottomBar(
            items = if (timerControls == null) {
                listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.ACCEPT,
                        onClick = onConfirm,
                        contentDescription = "Confirm",
                    ),
                )
            } else {
                listOf(
                    LightBarButton.LightIcon(
                        icon = if (timerControls.isRunning) LightIcons.PAUSE else LightIcons.PLAY,
                        onClick = timerControls.onToggleRunning,
                        contentDescription = if (timerControls.isRunning) "Pause" else "Resume",
                    ),
                    LightBarButton.LightIcon(
                        icon = LightIcons.ACCEPT,
                        onClick = onConfirm,
                        contentDescription = "Confirm",
                    ),
                    if (!timerControls.isRunning) {
                        LightBarButton.LightIcon(
                            icon = LightIcons.REFRESH,
                            onClick = timerControls.onReset,
                            contentDescription = "Reset timer",
                        )
                    } else {
                        null
                    },
                )
            },
        )
    }
}

@Composable
private fun RowScope.NudgeFieldColumn(config: NudgeFieldConfig, showLabel: Boolean = true) {
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
            enabled = config.wheelEnabled,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        if (showLabel) {
            LightText(
                text = config.label,
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * Hours+minutes+seconds duration entry, built on [NudgeWheelEntryContent]. Used by the cardio
 * logging and edit flows.
 *
 * A controlled component -- [seconds] flows in and [onSecondsChange] flows every change back out
 * immediately, rather than buffering edits until confirm -- so that when [timerControls] is
 * supplied, a running timer can drive the wheel (via the caller ticking [seconds] up once a
 * second) exactly the same way a manual spin does. [onDone] is the confirm/accept action; unlike
 * [onSecondsChange] it fires once, when the user is finished with this screen.
 */
@Composable
fun DurationNudgeEntryContent(
    title: String,
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    timerControls: TimerControls? = null,
) {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    // Field states exist only to satisfy NudgeFieldConfig's shape (shared with AddSetContent,
    // which does read them back at confirm time) -- this component ignores them and reads/writes
    // `seconds` directly instead, so the wheel can be driven externally (the running timer).
    val hoursFieldState = rememberTextFieldState(hours.toString())
    val minutesFieldState = rememberTextFieldState(minutes.toString())
    val secondsFieldState = rememberTextFieldState(secs.toString())
    val wheelEnabled = timerControls?.isRunning != true

    NudgeWheelEntryContent(
        title = title,
        fields = listOf(
            NudgeFieldConfig(
                label = "Hours",
                fieldState = hoursFieldState,
                wheelRange = 0..23,
                wheelValue = hours,
                onWheelValueChange = { onSecondsChange(it * 3600 + minutes * 60 + secs) },
                wheelFormat = { "%02d".format(it) },
                wheelEnabled = wheelEnabled,
            ),
            NudgeFieldConfig(
                label = "Minutes",
                fieldState = minutesFieldState,
                wheelRange = 0..59,
                wheelValue = minutes,
                onWheelValueChange = { onSecondsChange(hours * 3600 + it * 60 + secs) },
                wheelWraps = true,
                wheelFormat = { "%02d".format(it) },
                wheelEnabled = wheelEnabled,
            ),
            NudgeFieldConfig(
                label = "Seconds",
                fieldState = secondsFieldState,
                wheelRange = 0..59,
                wheelValue = secs,
                onWheelValueChange = { onSecondsChange(hours * 3600 + minutes * 60 + it) },
                wheelWraps = true,
                wheelFormat = { "%02d".format(it) },
                wheelEnabled = wheelEnabled,
            ),
        ),
        showFieldSeparators = true,
        onConfirm = onDone,
        onBack = onBack,
        timerControls = timerControls,
    )
}

/**
 * Whole+tenths distance entry (e.g. "10.5"), built on [NudgeWheelEntryContent]. Used by the
 * cardio logging and edit flows. [tenths] is the distance in the user's display unit, scaled by
 * 10 (e.g. 105 for "10.5") so it can be represented as an [Int] the same way [DurationNudgeEntryContent]
 * represents a duration as whole seconds.
 */
@Composable
fun DistanceNudgeEntryContent(
    title: String,
    tenths: Int,
    onTenthsChange: (Int) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val whole = tenths / 10
    val fraction = tenths % 10
    val wholeFieldState = rememberTextFieldState(whole.toString())
    val fractionFieldState = rememberTextFieldState(fraction.toString())

    NudgeWheelEntryContent(
        title = title,
        fields = listOf(
            NudgeFieldConfig(
                label = "",
                fieldState = wholeFieldState,
                wheelRange = 0..99,
                wheelValue = whole,
                onWheelValueChange = { onTenthsChange(it * 10 + fraction) },
                wheelFormat = { it.toString() },
            ),
            NudgeFieldConfig(
                label = "",
                fieldState = fractionFieldState,
                wheelRange = 0..9,
                wheelValue = fraction,
                onWheelValueChange = { onTenthsChange(whole * 10 + it) },
                wheelWraps = true,
                wheelFormat = { it.toString() },
            ),
        ),
        showFieldSeparators = true,
        centeredLabel = "Distance",
        fieldSeparator = ".",
        onConfirm = onDone,
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
