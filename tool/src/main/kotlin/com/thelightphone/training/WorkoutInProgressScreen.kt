package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import com.thelightphone.training.model.Exercise
import com.thelightphone.training.model.ExerciseSet
import com.thelightphone.training.model.LoggedExercise
import com.thelightphone.training.model.MuscleGroup
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingPreferences
import com.thelightphone.training.model.TrainingRepository
import com.thelightphone.training.model.WeightUnit
import com.thelightphone.training.model.WorkoutSession
import com.thelightphone.training.model.weightUnitFromStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/** Which sub-screen of the "start workout" flow is currently shown. */
sealed class WorkoutMode {
    /** Overview of exercises added so far, with "add exercise" / "finish" actions. */
    data object ExerciseList : WorkoutMode()

    /** Pick an exercise from the library. */
    data object PickExercise : WorkoutMode()

    /** Set entry for the exercise currently being built. */
    data object AddExerciseSets : WorkoutMode()

    /** Stepper entry for the rep count of a new set. */
    data object AddSetReps : WorkoutMode()

    /** Text entry for the weight of a new set (in the user's preferred unit). */
    data object AddSetWeight : WorkoutMode()
}

data class WorkoutUiState(
    val mode: WorkoutMode = WorkoutMode.ExerciseList,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val exercises: List<LoggedExercise> = emptyList(),
    val exerciseLibrary: List<Exercise> = emptyList(),
    val muscleGroups: List<MuscleGroup> = emptyList(),
    val draftExercise: Exercise? = null,
    val draftSets: List<ExerciseSet> = emptyList(),
    val draftReps: Int = DEFAULT_REPS,
    val errorModal: String? = null,
)

private const val DEFAULT_REPS = 10
private const val MIN_REPS = 1

class WorkoutInProgressViewModel(
    private val dataStore: DataStore<Preferences>,
    private val repository: TrainingRepository,
) : LightViewModel<WorkoutSession?>() {

    private val _uiState = MutableStateFlow(WorkoutUiState())
    val uiState: StateFlow<WorkoutUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureSeeded()
            val unit = weightUnitFromStorage(dataStore.data.first()[TrainingPreferences.WEIGHT_UNIT])
            _uiState.update { it.copy(weightUnit = unit) }
        }
        viewModelScope.launch {
            repository.muscleGroups.collect { groups ->
                _uiState.update { it.copy(muscleGroups = groups) }
            }
        }
        viewModelScope.launch {
            repository.exercises.collect { library ->
                _uiState.update { it.copy(exerciseLibrary = library) }
            }
        }
    }

    fun startAddExercise() {
        _uiState.update {
            it.copy(
                mode = WorkoutMode.PickExercise,
                draftExercise = null,
                draftSets = emptyList(),
            )
        }
    }

    fun cancelPickExercise() {
        _uiState.update { it.copy(mode = WorkoutMode.ExerciseList) }
    }

    fun selectLibraryExercise(exercise: Exercise) {
        _uiState.update {
            it.copy(
                draftExercise = exercise,
                draftSets = emptyList(),
                mode = WorkoutMode.AddExerciseSets,
            )
        }
    }

    fun startAddSet() {
        val prefillReps = _uiState.value.draftSets.lastOrNull()?.reps ?: DEFAULT_REPS
        _uiState.update { it.copy(mode = WorkoutMode.AddSetReps, draftReps = prefillReps) }
    }

    fun cancelAddSet() {
        _uiState.update { it.copy(mode = WorkoutMode.AddExerciseSets) }
    }

    fun incrementReps() {
        _uiState.update { it.copy(draftReps = it.draftReps + 1) }
    }

    fun decrementReps() {
        _uiState.update { it.copy(draftReps = (it.draftReps - 1).coerceAtLeast(MIN_REPS)) }
    }

    fun confirmReps() {
        _uiState.update { it.copy(mode = WorkoutMode.AddSetWeight) }
    }

    fun submitWeight(rawWeight: CharSequence) {
        val trimmed = rawWeight.toString().trim()
        val reps = _uiState.value.draftReps
        val unit = _uiState.value.weightUnit
        val weightKg: Double? = if (trimmed.isEmpty()) {
            null
        } else {
            val parsed = trimmed.toDoubleOrNull()
            if (parsed == null || parsed < 0) {
                _uiState.update { it.copy(errorModal = "Please enter a valid weight, or leave blank for bodyweight.") }
                return
            }
            unit.toKg(parsed)
        }
        _uiState.update {
            it.copy(
                draftSets = it.draftSets + ExerciseSet(reps = reps, weightKg = weightKg),
                mode = WorkoutMode.AddExerciseSets,
            )
        }
    }

    fun finishExercise() {
        val state = _uiState.value
        if (state.draftSets.isEmpty()) {
            _uiState.update { it.copy(errorModal = "Add at least one set before finishing this exercise.") }
            return
        }
        val exercise = state.draftExercise ?: run {
            _uiState.update { it.copy(errorModal = "No exercise selected.") }
            return
        }
        val primaryGroup = state.muscleGroups.find { it.id == exercise.primaryMuscleGroupId }
            ?: MuscleGroup(id = "", name = "Unknown")
        val secondaryGroups = exercise.secondaryMuscleGroupIds.mapNotNull { id ->
            state.muscleGroups.find { it.id == id }
        }
        val logged = LoggedExercise(
            exerciseId = exercise.id,
            name = exercise.name,
            muscleGroup = primaryGroup,
            secondaryMuscleGroups = secondaryGroups,
            sets = state.draftSets,
        )
        _uiState.update {
            it.copy(
                exercises = it.exercises + logged,
                mode = WorkoutMode.ExerciseList,
                draftExercise = null,
                draftSets = emptyList(),
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }

    fun buildSession(): WorkoutSession? {
        val exercises = _uiState.value.exercises
        if (exercises.isEmpty()) return null
        return WorkoutSession(
            id = UUID.randomUUID().toString(),
            name = "Workout ${LocalDate.now()}",
            date = LocalDate.now(),
            exercises = exercises,
        )
    }
}

class WorkoutInProgressScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<WorkoutSession?, WorkoutInProgressViewModel>(sealedActivity) {

    private val repository = TrainingRepository.getInstance {
        lightContext.buildDatabase(TrainingDatabase::class.java, TrainingRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<WorkoutInProgressViewModel>
        get() = WorkoutInProgressViewModel::class.java

    override fun createViewModel(): WorkoutInProgressViewModel =
        WorkoutInProgressViewModel(lightContext.dataStore, repository)

    @Composable
    override fun Content() {
        val state by viewModel.uiState.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val weightFieldState = rememberTextFieldState()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (state.mode) {
                    WorkoutMode.ExerciseList -> ExerciseListContent(
                        state = state,
                        onBack = { goBack(null) },
                        onAddExercise = viewModel::startAddExercise,
                        onFinish = { goBack(viewModel.buildSession()) },
                    )

                    WorkoutMode.PickExercise -> PickExerciseContent(
                        state = state,
                        onBack = viewModel::cancelPickExercise,
                        onSelectExercise = viewModel::selectLibraryExercise,
                    )

                    WorkoutMode.AddExerciseSets -> AddExerciseSetsContent(
                        state = state,
                        onBack = viewModel::cancelPickExercise,
                        onAddSet = viewModel::startAddSet,
                        onFinishExercise = viewModel::finishExercise,
                    )

                    WorkoutMode.AddSetReps -> RepsStepperContent(
                        title = "Reps",
                        reps = state.draftReps,
                        onIncrement = viewModel::incrementReps,
                        onDecrement = viewModel::decrementReps,
                        onConfirm = viewModel::confirmReps,
                        onBack = viewModel::cancelAddSet,
                    )

                    WorkoutMode.AddSetWeight -> LightTextInputEditor(
                        title = "Weight (${state.weightUnit.displayName}, blank for bodyweight)",
                        state = weightFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitWeight,
                        onBack = viewModel::cancelAddSet,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                state.errorModal?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun ExerciseListContent(
    state: WorkoutUiState,
    onBack: () -> Unit,
    onAddExercise: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel workout",
            ),
            center = LightTopBarCenter.Text("New Workout"),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (state.exercises.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                    LightText(
                        text = "No exercises added yet",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                    LightText(
                        text = "Tap the add button below to log your first exercise.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                ) {
                    state.exercises.forEach { exercise ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                LightText(
                                    text = exercise.name,
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.weight(1f),
                                )
                                LightText(
                                    text = "${exercise.sets.size} sets",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                            LightText(
                                text = muscleGroupSummary(exercise),
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = onAddExercise,
                    contentDescription = "Add exercise",
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

@Composable
private fun PickExerciseContent(
    state: WorkoutUiState,
    onBack: () -> Unit,
    onSelectExercise: (Exercise) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel",
            ),
            center = LightTopBarCenter.Text("Pick exercise"),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (state.exerciseLibrary.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                    LightText(
                        text = "No exercises in your library yet",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                    LightText(
                        text = "Add exercises in Settings first.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                ) {
                    state.exerciseLibrary.forEach { exercise ->
                        val primaryName = state.muscleGroups
                            .find { it.id == exercise.primaryMuscleGroupId }
                            ?.name
                        val secondaryNames = exercise.secondaryMuscleGroupIds
                            .mapNotNull { id -> state.muscleGroups.find { it.id == id }?.name }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable(onClick = { onSelectExercise(exercise) })
                                .padding(vertical = 12.dp),
                        ) {
                            LightText(text = exercise.name, variant = LightTextVariant.Copy)
                            if (primaryName != null) {
                                val subtitle = if (secondaryNames.isEmpty()) primaryName
                                    else "$primaryName, Also: ${secondaryNames.joinToString(", ")}"
                                LightText(
                                    text = subtitle,
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddExerciseSetsContent(
    state: WorkoutUiState,
    onBack: () -> Unit,
    onAddSet: () -> Unit,
    onFinishExercise: () -> Unit,
) {
    val exercise = state.draftExercise ?: return
    val primaryName = state.muscleGroups.find { it.id == exercise.primaryMuscleGroupId }?.name
    val secondaryNames = exercise.secondaryMuscleGroupIds
        .mapNotNull { id -> state.muscleGroups.find { it.id == id }?.name }

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel exercise",
            ),
            center = LightTopBarCenter.Text(exercise.name),
        )

        Column(modifier = Modifier.weight(1f).padding(horizontal = 32.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                LightText(text = "Muscle group", variant = LightTextVariant.Detail, lighten = true)
                LightText(
                    text = primaryName ?: "Unknown",
                    variant = LightTextVariant.Heading,
                )
                if (secondaryNames.isNotEmpty()) {
                    LightText(
                        text = "Also: " + secondaryNames.joinToString(", "),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (state.draftSets.isEmpty()) {
                LightText(
                    text = "No sets logged yet",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                LightScrollView(modifier = Modifier.fillMaxWidth()) {
                    state.draftSets.forEachIndexed { index, set ->
                        val weightText = set.weightKg?.let { kg ->
                            val displayValue = state.weightUnit.fromKg(kg)
                            "${formatWeight(displayValue)} ${state.weightUnit.displayName}"
                        } ?: "bodyweight"
                        LightText(
                            text = "Set ${index + 1}: ${set.reps} reps @ $weightText",
                            variant = LightTextVariant.Detail,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = onAddSet,
                    contentDescription = "Add set",
                ),
                LightBarButton.Text(text = "DONE", onClick = onFinishExercise),
            ),
        )
    }
}

private fun muscleGroupSummary(exercise: LoggedExercise): String {
    val primary = exercise.muscleGroup.name
    return if (exercise.secondaryMuscleGroups.isEmpty()) primary
    else "$primary, Also: ${exercise.secondaryMuscleGroups.joinToString(", ") { it.name }}"
}

private fun formatWeight(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
