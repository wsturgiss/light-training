package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.training.model.Exercise
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val DEFAULT_DURATION_MIN = 30
private const val MIN_DURATION_MIN = 5
private const val DURATION_STEP_MIN = 5
private const val CARDIO_MUSCLE_GROUP_NAME = "Cardio"

private enum class CardioMode { SELECT_EXERCISE, CONFIGURE, ACTIVE }

/**
 * Mock-up for a steady-state cardio workout: pick which cardio exercise, pick a target
 * duration, then run a live stopwatch against it. Not wired to session persistence yet --
 * exploring the flow and layout only.
 */
class CardioWorkoutScreen(
    sealedActivity: SealedLightActivity,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val repository = TrainingRepository.getInstance {
        lightContext.buildDatabase(TrainingDatabase::class.java, TrainingRepository.DATABASE_NAME)
    }

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        var mode by remember { mutableStateOf(CardioMode.SELECT_EXERCISE) }
        var selectedExercise by remember { mutableStateOf<Exercise?>(null) }
        var targetMinutes by remember { mutableStateOf(DEFAULT_DURATION_MIN) }
        var cardioExercises by remember { mutableStateOf<List<Exercise>>(emptyList()) }
        var isLoadingExercises by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) { repository.ensureSeeded() }
            cardioExercises = loadCardioExercises(repository)
            isLoadingExercises = false
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
                        mode = CardioMode.CONFIGURE
                    },
                )

                CardioMode.CONFIGURE -> CardioConfigureContent(
                    exerciseName = selectedExercise?.name ?: "Cardio",
                    targetMinutes = targetMinutes,
                    onAdjust = { delta ->
                        targetMinutes = (targetMinutes + delta).coerceAtLeast(MIN_DURATION_MIN)
                    },
                    onBack = { mode = CardioMode.SELECT_EXERCISE },
                    onStart = { mode = CardioMode.ACTIVE },
                )

                CardioMode.ACTIVE -> CardioActiveContent(
                    exerciseName = selectedExercise?.name ?: "Cardio",
                    targetMinutes = targetMinutes,
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
private fun CardioConfigureContent(
    exerciseName: String,
    targetMinutes: Int,
    onAdjust: (Int) -> Unit,
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

        Column(
            modifier = Modifier.weight(1f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LightIcon(
                icon = LightIcons.UP,
                contentDescription = "Increase duration",
                size = 4f,
                modifier = Modifier.lightClickable(onClick = { onAdjust(DURATION_STEP_MIN) }),
            )
            LightText(
                text = targetMinutes.toString(),
                variant = LightTextVariant.Title,
                modifier = Modifier.padding(top = 16.dp),
            )
            LightText(
                text = "Minutes",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 4.dp),
            )
            LightIcon(
                icon = LightIcons.DOWN,
                contentDescription = "Decrease duration",
                size = 4f,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .lightClickable(onClick = { onAdjust(-DURATION_STEP_MIN) }),
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onStart,
                    contentDescription = "Start cardio workout",
                ),
            ),
        )
    }
}

@Composable
private fun CardioActiveContent(
    exerciseName: String,
    targetMinutes: Int,
    onFinish: () -> Unit,
) {
    var elapsedSeconds by remember { mutableStateOf(0) }
    var isRunning by remember { mutableStateOf(true) }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            delay(1000)
            elapsedSeconds++
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
            LightText(
                text = formatElapsed(elapsedSeconds),
                variant = LightTextVariant.Title,
                monospace = true,
            )
            LightText(
                text = "Target: $targetMinutes min",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = if (isRunning) LightIcons.PAUSE else LightIcons.PLAY,
                    onClick = { isRunning = !isRunning },
                    contentDescription = if (isRunning) "Pause" else "Resume",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onFinish,
                    contentDescription = "Finish workout",
                ),
            ),
        )
    }
}

private fun formatElapsed(totalSeconds: Int): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
