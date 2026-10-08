package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import android.os.SystemClock
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.training.model.DistanceUnit
import com.thelightphone.training.model.Exercise
import com.thelightphone.training.model.TrackedField
import com.thelightphone.training.model.TrainingPreferences
import com.thelightphone.training.model.TrainingRepository
import com.thelightphone.training.model.distanceUnitFromStorage
import com.thelightphone.training.model.trainingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CARDIO_MUSCLE_GROUP_NAME = "Cardio"

private enum class CardioMode { SELECT_EXERCISE, LOG }

/** Which field is being edited via a nudge-wheel picker on the log screen; null means none.
 * Pace is not editable -- it's computed from duration and distance, see [formatPace]. */
private enum class LogField { DURATION, DISTANCE }

/**
 * Steady-state cardio logging: pick which cardio exercise, then log its duration (and any
 * tracked fields, e.g. distance/pace) either by hand or with an optional live stopwatch. The
 * timer lives directly on the duration wheel picker (see [DurationNudgeEntryContent]'s
 * `timerControls`) -- there's no separate timer screen, and no separate "timer value" to reconcile
 * with the duration: running the timer *is* setting the duration.
 */
class CardioWorkoutScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val repository = lightContext.trainingRepository()

    // Held on the screen (not inside `remember`) so `willShow()` -- called by the SDK when this
    // screen becomes visible again, e.g. waking from sleep -- can resync it. See
    // [syncDurationFromWallClock].
    private var isTimerRunning by mutableStateOf(false)
    private var durationSeconds by mutableStateOf<Int?>(null)
    private var timerStartRealtimeMillis by mutableStateOf(0L)
    private var timerBaseSecondsAtStart by mutableStateOf(0)

    /**
     * Anchors the running timer to a wall-clock reference instead of counting 1-second ticks,
     * since the tick loop can get suspended while the screen is asleep (missed/delayed
     * `delay(1000)` wakeups) and would otherwise silently under-count. `elapsedRealtime` keeps
     * advancing through sleep (unlike `currentTimeMillis`, it's immune to clock changes).
     */
    private fun syncDurationFromWallClock() {
        if (!isTimerRunning) return
        val elapsedSeconds = (SystemClock.elapsedRealtime() - timerStartRealtimeMillis) / 1000
        durationSeconds = timerBaseSecondsAtStart + elapsedSeconds.toInt()
    }

    // Catches the timer up immediately when the screen becomes visible again (e.g. waking from
    // sleep), rather than waiting for the next 1-second tick.
    override fun willShow() {
        syncDurationFromWallClock()
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val coroutineScope = rememberCoroutineScope()
        var mode by remember { mutableStateOf(CardioMode.SELECT_EXERCISE) }
        var selectedExercise by remember { mutableStateOf<Exercise?>(null) }
        var cardioExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
        var isLoadingExercises by remember { mutableStateOf(true) }
        var distanceTenths by remember { mutableStateOf<Int?>(null) }
        var editingField by remember { mutableStateOf<LogField?>(null) }
        var isSaving by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var distanceUnit by remember { mutableStateOf(DistanceUnit.KM) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { repository.ensureSeeded() }
            cardioExercises = loadCardioExercises(repository)
            isLoadingExercises = false
            distanceUnit = distanceUnitFromStorage(
                withContext(Dispatchers.IO) { lightContext.dataStore.data.first()[TrainingPreferences.DISTANCE_UNIT] },
            )
        }

        LaunchedEffect(isTimerRunning) {
            if (isTimerRunning) {
                timerStartRealtimeMillis = SystemClock.elapsedRealtime()
                timerBaseSecondsAtStart = durationSeconds ?: 0
            }
            while (isTimerRunning) {
                delay(1000)
                syncDurationFromWallClock()
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
                        distanceKm = distanceTenths?.let { distanceUnit.toKm(it / 10.0) },
                        pace = distanceTenths?.let { formatPace(duration, it / 10.0, distanceUnit.displayName) },
                    )
                }
                goBack(Unit)
            }
        }

        LightTheme(colors = themeColors) {
            Box(modifier = Modifier.fillMaxSize()) {
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

                    CardioMode.LOG -> {
                        val fieldBeingEdited = editingField
                        if (fieldBeingEdited == null) {
                            LogResultsContent(
                                exerciseName = selectedExercise?.name ?: "Cardio",
                                trackedFields = selectedExercise?.trackedFields.orEmpty(),
                                durationSeconds = durationSeconds,
                                distanceTenths = distanceTenths,
                                distanceUnit = distanceUnit,
                                isTimerRunning = isTimerRunning,
                                canSave = durationSeconds != null && !isSaving,
                                onEditDuration = { editingField = LogField.DURATION },
                                onEditDistance = { editingField = LogField.DISTANCE },
                                onBack = { goBack(Unit) },
                                onSave = {
                                    if (isTimerRunning) {
                                        errorMessage = "Pause the timer before saving."
                                    } else {
                                        saveAndFinish()
                                    }
                                },
                            )
                        } else if (fieldBeingEdited == LogField.DURATION) {
                            DurationNudgeEntryContent(
                                title = "Duration",
                                seconds = durationSeconds ?: 0,
                                onSecondsChange = { durationSeconds = it },
                                onDone = { editingField = null },
                                onBack = { editingField = null },
                                timerControls = TimerControls(
                                    isRunning = isTimerRunning,
                                    onToggleRunning = { isTimerRunning = !isTimerRunning },
                                    onReset = {
                                        durationSeconds = 0
                                        timerBaseSecondsAtStart = 0
                                        timerStartRealtimeMillis = SystemClock.elapsedRealtime()
                                    },
                                ),
                            )
                        } else {
                            DistanceNudgeEntryContent(
                                title = "Distance (${distanceUnit.displayName})",
                                tenths = distanceTenths ?: 0,
                                onTenthsChange = { distanceTenths = it },
                                onDone = { editingField = null },
                                onBack = { editingField = null },
                            )
                        }
                    }
                }

                errorMessage?.let { message ->
                    LightFullscreenModal(message = message, onClose = { errorMessage = null })
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
 * Where a cardio result gets logged: duration plus any tracked fields (distance/pace). Duration
 * can be typed in directly, or timed live -- both happen on the same wheel picker (see
 * [DurationNudgeEntryContent]'s `timerControls`), so it's just another editable row here.
 */
@Composable
private fun LogResultsContent(
    exerciseName: String,
    trackedFields: Set<TrackedField>,
    durationSeconds: Int?,
    distanceTenths: Int?,
    distanceUnit: DistanceUnit,
    isTimerRunning: Boolean,
    canSave: Boolean,
    onEditDuration: () -> Unit,
    onEditDistance: () -> Unit,
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
                label = (durationSeconds?.let { formatDuration(it) } ?: "Not set") +
                    if (isTimerRunning) " · Running" else "",
                onClick = onEditDuration,
            )
            if (TrackedField.DISTANCE in trackedFields) {
                LightEditableRow(
                    superscript = "Distance",
                    label = distanceTenths?.let { "%.1f %s".format(it / 10.0, distanceUnit.displayName) } ?: "Not set",
                    onClick = onEditDistance,
                )
            }
            if (TrackedField.PACE in trackedFields) {
                val pace = if (durationSeconds != null && distanceTenths != null) {
                    formatPace(durationSeconds, distanceTenths / 10.0, distanceUnit.displayName)
                } else {
                    null
                }
                DetailRow(label = "Pace", value = pace ?: "Add duration and distance to see pace")
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = if (canSave) onSave else null,
                    contentDescription = "Save cardio session",
                ),
            ),
        )
    }
}
