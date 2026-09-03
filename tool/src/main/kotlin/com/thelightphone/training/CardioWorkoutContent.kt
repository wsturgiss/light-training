package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightEditableRow
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.training.model.Exercise
import com.thelightphone.training.model.TrackedField
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CARDIO_MUSCLE_GROUP_NAME = "Cardio"

private enum class CardioMode { SELECT_EXERCISE, LOG, TIMER }

/** Which field is being edited via [LightTextInputEditor] on the log screen; null means none. */
private enum class LogField { DURATION, DISTANCE, PACE }

/**
 * Steady-state cardio logging: pick which cardio exercise, then log its duration (and any
 * tracked fields, e.g. distance/pace) either by hand or with an optional live stopwatch. The
 * timer is a side effect of logging, not a requirement -- see [CardioActiveContent] for how it
 * hands its elapsed time back to the duration field.
 */
class CardioWorkoutScreen(
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
        )
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        var mode by remember { mutableStateOf(CardioMode.SELECT_EXERCISE) }
        var selectedExercise by remember { mutableStateOf<Exercise?>(null) }
        var cardioExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
        var isLoadingExercises by remember { mutableStateOf(true) }
        var durationSeconds by remember { mutableStateOf<Int?>(null) }
        var distanceValue by remember { mutableStateOf("") }
        var paceValue by remember { mutableStateOf("") }
        var editingField by remember { mutableStateOf<LogField?>(null) }
        var isSaving by remember { mutableStateOf(false) }
        // Lives at this level (not inside the timer screen's composable) so it survives
        // navigating back to the log screen and returning -- otherwise elapsed time is lost
        // the moment the user leaves the timer.
        var timerElapsedSeconds by remember { mutableStateOf(0) }
        var isTimerRunning by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { repository.ensureSeeded() }
            cardioExercises = loadCardioExercises(repository)
            isLoadingExercises = false
        }

        LaunchedEffect(isTimerRunning) {
            while (isTimerRunning) {
                delay(1000)
                timerElapsedSeconds++
            }
        }

        fun saveAndFinish() {
            val exercise = selectedExercise ?: return
            val duration = durationSeconds ?: return
            if (isSaving) return
            isSaving = true
            coroutineScope.launch {
                withContext(Dispatchers.IO) {
                    repository.insertCardioSession(
                        exerciseId = exercise.id,
                        durationSeconds = duration,
                        distance = distanceValue.trim().ifBlank { null },
                        pace = paceValue.trim().ifBlank { null },
                    )
                }
                goBack(Unit)
            }
        }

        LightTheme(colors = themeColors) {
            when (mode) {
                CardioMode.SELECT_EXERCISE -> ExercisePickerContent(
                    title = "Steady-State Cardio",
                    exercises = cardioExercises,
                    isLoading = isLoadingExercises,
                    onBack = { goBack(Unit) },
                    onSelect = { exercise ->
                        selectedExercise = exercise
                        mode = CardioMode.LOG
                    },
                )

                CardioMode.TIMER -> CardioActiveContent(
                    exerciseName = selectedExercise?.name ?: "Cardio",
                    elapsedSeconds = timerElapsedSeconds,
                    isRunning = isTimerRunning,
                    onToggleRunning = { isTimerRunning = !isTimerRunning },
                    onReset = { timerElapsedSeconds = 0 },
                    onBack = {
                        // Only carry the timer's result over when the user hasn't already
                        // logged a duration by hand -- the timer is an optional convenience,
                        // not the source of truth, so it never clobbers a manual entry. The
                        // timer keeps running in the background either way, and its value
                        // stays visible (and applicable) from the log screen.
                        if (durationSeconds == null) {
                            durationSeconds = timerElapsedSeconds
                        }
                        mode = CardioMode.LOG
                    },
                )

                CardioMode.LOG -> {
                    val fieldBeingEdited = editingField
                    if (fieldBeingEdited == null) {
                        LogResultsContent(
                            exerciseName = selectedExercise?.name ?: "Cardio",
                            trackedFields = selectedExercise?.trackedFields.orEmpty(),
                            durationSeconds = durationSeconds,
                            distanceValue = distanceValue,
                            paceValue = paceValue,
                            timerElapsedSeconds = timerElapsedSeconds,
                            isTimerRunning = isTimerRunning,
                            canSave = durationSeconds != null && !isSaving,
                            onEditDuration = { editingField = LogField.DURATION },
                            onEditField = { field ->
                                editingField = if (field == TrackedField.DISTANCE) LogField.DISTANCE else LogField.PACE
                            },
                            onOpenTimer = {
                                if (timerElapsedSeconds == 0 && !isTimerRunning) isTimerRunning = true
                                mode = CardioMode.TIMER
                            },
                            onUseTimerValue = { durationSeconds = timerElapsedSeconds },
                            onBack = { goBack(Unit) },
                            onSave = { saveAndFinish() },
                        )
                    } else if (fieldBeingEdited == LogField.DURATION) {
                        DurationNudgeEntryContent(
                            title = "Duration",
                            initialSeconds = durationSeconds ?: 0,
                            onConfirm = { seconds ->
                                durationSeconds = seconds
                                editingField = null
                            },
                            onBack = { editingField = null },
                        )
                    } else {
                        val initialText = if (fieldBeingEdited == LogField.DISTANCE) distanceValue else paceValue
                        val fieldTextFieldState = remember(fieldBeingEdited) { TextFieldState(initialText) }
                        LightTextInputEditor(
                            title = if (fieldBeingEdited == LogField.DISTANCE) {
                                TrackedField.DISTANCE.displayName
                            } else {
                                TrackedField.PACE.displayName
                            },
                            state = fieldTextFieldState,
                            keyboardOptionsFlow = rememberKeyboardOptions(),
                            onSubmit = { rawValue ->
                                val value = rawValue.toString()
                                if (fieldBeingEdited == LogField.DISTANCE) distanceValue = value else paceValue = value
                                editingField = null
                            },
                            onBack = { editingField = null },
                            singleLine = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
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

/**
 * An optional live stopwatch: counts up from zero with play/pause. Its state is owned by
 * [CardioWorkoutScreen], not this composable, so it keeps running (or stays paused at its last
 * value) across visits to this screen -- leaving via [onDone] never discards it. There's no
 * target duration -- the caller decides whether to use the result, and manual entry always
 * takes precedence (see [CardioWorkoutScreen]).
 */
@Composable
private fun CardioActiveContent(
    exerciseName: String,
    elapsedSeconds: Int,
    isRunning: Boolean,
    onToggleRunning: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
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
                contentDescription = "Back to log",
            ),
            center = LightTopBarCenter.Text(exerciseName),
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
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = if (isRunning) LightIcons.PAUSE else LightIcons.PLAY,
                    onClick = onToggleRunning,
                    contentDescription = if (isRunning) "Pause" else "Resume",
                ),
                // Only offered once paused -- resetting a running timer out from under
                // yourself is more likely a misclick than intentional.
                if (!isRunning) {
                    LightBarButton.LightIcon(
                        icon = LightIcons.REFRESH,
                        onClick = onReset,
                        contentDescription = "Reset timer",
                    )
                } else {
                    null
                },
            ),
        )
    }
}

private fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Where a cardio result gets logged: duration plus any tracked fields (distance/pace). Duration
 * can be typed in directly, or filled in by running the optional timer -- either way it's just
 * another editable row here, so the user never has to run the timer to log a workout, and can
 * still edit the duration by hand afterward even if the timer set it.
 */
@Composable
private fun LogResultsContent(
    exerciseName: String,
    trackedFields: Set<TrackedField>,
    durationSeconds: Int?,
    distanceValue: String,
    paceValue: String,
    timerElapsedSeconds: Int,
    isTimerRunning: Boolean,
    canSave: Boolean,
    onEditDuration: () -> Unit,
    onEditField: (TrackedField) -> Unit,
    onOpenTimer: () -> Unit,
    onUseTimerValue: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
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

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 32.dp, vertical = 16.dp),
        ) {
            LightText(
                text = "Log results",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            LightEditableRow(
                superscript = "Duration",
                label = durationSeconds?.let { formatElapsed(it) } ?: "Not set",
                onClick = onEditDuration,
            )
            if (timerElapsedSeconds > 0 || isTimerRunning) {
                LightEditableRow(
                    superscript = "Timer",
                    label = formatElapsed(timerElapsedSeconds) + if (isTimerRunning) " · Running" else " · Paused",
                    subscript = if (durationSeconds != timerElapsedSeconds) "Tap to use this as the duration" else null,
                    editable = false,
                    onClick = onUseTimerValue,
                )
            }
            if (TrackedField.DISTANCE in trackedFields) {
                LightEditableRow(
                    superscript = "Distance",
                    label = distanceValue.ifBlank { "Not set" },
                    onClick = { onEditField(TrackedField.DISTANCE) },
                )
            }
            if (TrackedField.PACE in trackedFields) {
                LightEditableRow(
                    superscript = "Pace",
                    label = paceValue.ifBlank { "Not set" },
                    onClick = { onEditField(TrackedField.PACE) },
                )
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ALARM,
                    onClick = onOpenTimer,
                    contentDescription = if (timerElapsedSeconds > 0 || isTimerRunning) "Open timer" else "Time this workout",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = if (canSave) onSave else null,
                    contentDescription = "Save cardio session",
                ),
            ),
        )
    }
}
