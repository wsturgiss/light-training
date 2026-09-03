package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import com.thelightphone.training.model.Exercise
import com.thelightphone.training.model.IntervalScheme
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val DEFAULT_WORK_SEC = 30
private const val DEFAULT_REST_SEC = 15
private const val DEFAULT_ROUNDS = 8
private const val TIME_STEP_SEC = 5
private const val MIN_TIME_SEC = 5
private const val MIN_ROUNDS = 1
private const val CARDIO_MUSCLE_GROUP_NAME = "Cardio"

private enum class IntervalMode { SELECT_EXERCISE, SELECT_PRESET, CONFIGURE, ACTIVE }
private enum class IntervalPhase { WORK, REST }

/** The one non-persisted picker entry: start from the same defaults as everything else, and
 * let the user tweak them by hand on the configure screen. Tabata, Nordic 4x4, and HIIT
 * 30/30 are seeded as real (editable/deletable) [IntervalScheme] rows instead of being
 * hardcoded here -- see [TrainingRepository.ensureSeeded]. */
private const val CUSTOM_PRESET_TITLE = "Custom"
private const val CUSTOM_PRESET_SUBTITLE = "Set your own work, rest, and rounds"

/**
 * Mock-up for an interval workout: pick which cardio exercise, configure work/rest durations
 * and round count, then run a live work/rest countdown. Not wired to session persistence yet --
 * exploring the flow and layout only.
 */
class IntervalWorkoutScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val repository = TrainingRepository.getInstance {
        lightContext.buildDatabase(
            TrainingDatabase::class.java,
            TrainingRepository.DATABASE_NAME,
            TrainingDatabase.MIGRATION_2_3,
            TrainingDatabase.MIGRATION_3_4,
            TrainingDatabase.MIGRATION_4_5,
            TrainingDatabase.MIGRATION_5_6,
            TrainingDatabase.MIGRATION_6_7,
            TrainingDatabase.MIGRATION_7_8,
            TrainingDatabase.MIGRATION_8_9,
        )
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var mode by remember { mutableStateOf(IntervalMode.SELECT_EXERCISE) }
        var selectedExercise by remember { mutableStateOf<Exercise?>(null) }
        var workSeconds by remember { mutableStateOf(DEFAULT_WORK_SEC) }
        var restSeconds by remember { mutableStateOf(DEFAULT_REST_SEC) }
        var rounds by remember { mutableStateOf(DEFAULT_ROUNDS) }
        var cardioExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
        var isLoadingExercises by remember { mutableStateOf(true) }
        val customSchemes by repository.intervalSchemes.collectAsState(initial = emptyList())

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { repository.ensureSeeded() }
            cardioExercises = loadCardioExercises(repository)
            isLoadingExercises = false
        }

        LightTheme(colors = themeColors) {
            when (mode) {
                IntervalMode.SELECT_EXERCISE -> ExercisePickerContent(
                    title = "Interval Training",
                    exercises = cardioExercises,
                    isLoading = isLoadingExercises,
                    onBack = { goBack(Unit) },
                    onSelect = { exercise ->
                        selectedExercise = exercise
                        mode = IntervalMode.SELECT_PRESET
                    },
                )

                IntervalMode.SELECT_PRESET -> IntervalPresetContent(
                    exerciseName = selectedExercise?.name ?: "Intervals",
                    customSchemes = customSchemes,
                    onBack = { mode = IntervalMode.SELECT_EXERCISE },
                    onSelect = { work, rest, rnds ->
                        workSeconds = work
                        restSeconds = rest
                        rounds = rnds
                        mode = IntervalMode.CONFIGURE
                    },
                )

                IntervalMode.CONFIGURE -> IntervalConfigureContent(
                    exerciseName = selectedExercise?.name ?: "Intervals",
                    workSeconds = workSeconds,
                    restSeconds = restSeconds,
                    rounds = rounds,
                    onAdjustWork = { delta -> workSeconds = (workSeconds + delta).coerceAtLeast(MIN_TIME_SEC) },
                    onAdjustRest = { delta -> restSeconds = (restSeconds + delta).coerceAtLeast(MIN_TIME_SEC) },
                    onAdjustRounds = { delta -> rounds = (rounds + delta).coerceAtLeast(MIN_ROUNDS) },
                    onBack = { mode = IntervalMode.SELECT_PRESET },
                    onStart = { mode = IntervalMode.ACTIVE },
                )

                IntervalMode.ACTIVE -> IntervalActiveContent(
                    exerciseName = selectedExercise?.name ?: "Intervals",
                    workSeconds = workSeconds,
                    restSeconds = restSeconds,
                    rounds = rounds,
                    onFinish = { goBack(Unit) },
                )
            }
        }
    }
}

/** Exercises tagged with the "Cardio" muscle group, looked up by name (not a hardcoded id). */
private suspend fun loadCardioExercises(repository: TrainingRepository): List<Exercise> {
    val cardioGroupIds = repository.muscleGroups.first()
        .filter { it.name.equals(CARDIO_MUSCLE_GROUP_NAME, ignoreCase = true) }
        .map { it.id }
        .toSet()
    return repository.exercises.first().filter { it.primaryMuscleGroupId in cardioGroupIds }
}

@Composable
private fun IntervalPresetContent(
    exerciseName: String,
    customSchemes: List<IntervalScheme>,
    onBack: () -> Unit,
    onSelect: (workSeconds: Int, restSeconds: Int, rounds: Int) -> Unit,
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
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(exerciseName),
        )

        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(UiConstants.SpacedScrollPadding),
        ) {
            // Saved schemes (including the seeded defaults) first, fully manual "Custom" last.
            customSchemes.forEach { scheme ->
                IntervalPresetRow(
                    icon = LightIcons.LARGE_LIST,
                    title = scheme.name,
                    subtitle = formatSchemeSubtitle(scheme),
                    onClick = { onSelect(scheme.workSeconds, scheme.restSeconds, scheme.rounds) },
                )
            }
            IntervalPresetRow(
                icon = LightIcons.ALARM,
                title = CUSTOM_PRESET_TITLE,
                subtitle = CUSTOM_PRESET_SUBTITLE,
                onClick = { onSelect(DEFAULT_WORK_SEC, DEFAULT_REST_SEC, DEFAULT_ROUNDS) },
            )
        }
    }
}

fun formatSchemeSubtitle(scheme: IntervalScheme): String =
    "${formatDuration(scheme.workSeconds)} work, ${formatDuration(scheme.restSeconds)} rest, ${scheme.rounds} rounds"

@Composable
private fun IntervalPresetRow(
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

@Composable
private fun IntervalConfigureContent(
    exerciseName: String,
    workSeconds: Int,
    restSeconds: Int,
    rounds: Int,
    onAdjustWork: (Int) -> Unit,
    onAdjustRest: (Int) -> Unit,
    onAdjustRounds: (Int) -> Unit,
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
            center = LightTopBarCenter.Text(exerciseName),
        )

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            NudgeField(
                modifier = Modifier.weight(1f),
                value = workSeconds.toString(),
                label = "Work (sec)",
                onIncrement = { onAdjustWork(TIME_STEP_SEC) },
                onDecrement = { onAdjustWork(-TIME_STEP_SEC) },
                incrementDescription = "Increase work time",
                decrementDescription = "Decrease work time",
            )
            NudgeField(
                modifier = Modifier.weight(1f),
                value = restSeconds.toString(),
                label = "Rest (sec)",
                onIncrement = { onAdjustRest(TIME_STEP_SEC) },
                onDecrement = { onAdjustRest(-TIME_STEP_SEC) },
                incrementDescription = "Increase rest time",
                decrementDescription = "Decrease rest time",
            )
            NudgeField(
                modifier = Modifier.weight(1f),
                value = rounds.toString(),
                label = "Rounds",
                onIncrement = { onAdjustRounds(1) },
                onDecrement = { onAdjustRounds(-1) },
                incrementDescription = "Increase rounds",
                decrementDescription = "Decrease rounds",
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onStart,
                    contentDescription = "Start interval workout",
                ),
            ),
        )
    }
}

@Composable
private fun NudgeField(
    value: String,
    label: String,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    incrementDescription: String,
    decrementDescription: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LightIcon(
            icon = LightIcons.UP,
            contentDescription = incrementDescription,
            size = 3f,
            modifier = Modifier.lightClickable(onClick = onIncrement),
        )
        LightText(
            text = value,
            variant = LightTextVariant.Heading,
            modifier = Modifier.padding(top = 12.dp),
        )
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 4.dp),
        )
        LightIcon(
            icon = LightIcons.DOWN,
            contentDescription = decrementDescription,
            size = 3f,
            modifier = Modifier
                .padding(top = 20.dp)
                .lightClickable(onClick = onDecrement),
        )
    }
}

@Composable
private fun IntervalActiveContent(
    exerciseName: String,
    workSeconds: Int,
    restSeconds: Int,
    rounds: Int,
    onFinish: () -> Unit,
) {
    var phase by remember { mutableStateOf(IntervalPhase.WORK) }
    var round by remember { mutableStateOf(1) }
    var remaining by remember { mutableStateOf(workSeconds) }
    var isRunning by remember { mutableStateOf(true) }
    var finished by remember { mutableStateOf(false) }

    fun advance() {
        when {
            phase == IntervalPhase.WORK -> {
                phase = IntervalPhase.REST
                remaining = restSeconds
            }
            round < rounds -> {
                round++
                phase = IntervalPhase.WORK
                remaining = workSeconds
            }
            else -> {
                isRunning = false
                finished = true
            }
        }
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            delay(1000)
            if (remaining > 1) {
                remaining--
            } else {
                advance()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            center = LightTopBarCenter.Text(exerciseName),
        )

        Column(
            modifier = Modifier.weight(1f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (finished) {
                LightText(text = "Workout complete", variant = LightTextVariant.Title)
                LightText(
                    text = "$rounds rounds done",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LightText(
                    text = if (phase == IntervalPhase.WORK) "Work" else "Rest",
                    variant = LightTextVariant.Subtitle,
                    lighten = phase == IntervalPhase.REST,
                )
                LightText(
                    text = formatDuration(remaining),
                    variant = LightTextVariant.Title,
                    monospace = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
                LightText(
                    text = "Round $round of $rounds",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        LightBottomBar(
            items = if (finished) {
                listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.ACCEPT,
                        onClick = onFinish,
                        contentDescription = "Finish workout",
                    ),
                )
            } else {
                listOf(
                    LightBarButton.LightIcon(
                        icon = if (isRunning) LightIcons.PAUSE else LightIcons.PLAY,
                        onClick = { isRunning = !isRunning },
                        contentDescription = if (isRunning) "Pause" else "Resume",
                    ),
                    LightBarButton.LightIcon(
                        icon = LightIcons.FAST_FORWARD,
                        onClick = { advance() },
                        contentDescription = "Skip to next interval",
                    ),
                    LightBarButton.LightIcon(
                        icon = LightIcons.ACCEPT,
                        onClick = onFinish,
                        contentDescription = "Finish workout",
                    ),
                )
            },
        )
    }
}
