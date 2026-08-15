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
import com.thelightphone.sdk.buildDatabase
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

    /** Pick an exercise from the library, or start typing a brand new one. */
    data object PickExercise : WorkoutMode()

    /** Text entry for a new exercise's name. */
    data object AddExerciseName : WorkoutMode()

    /** Muscle group + sets for the exercise currently being built. */
    data object AddExerciseSets : WorkoutMode()

    /** Text entry for the rep count of a new set. */
    data object AddSetReps : WorkoutMode()

    /** Text entry for the weight of a new set (in the user's preferred unit). */
    data object AddSetWeight : WorkoutMode()
}

data class WorkoutUiState(
    val mode: WorkoutMode = WorkoutMode.ExerciseList,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val exercises: List<LoggedExercise> = emptyList(),
    val exerciseLibrary: List<Exercise> = emptyList(),
    val draftExerciseName: String = "",
    val draftFromLibrary: Boolean = false,
    val muscleGroups: List<MuscleGroup> = emptyList(),
    val draftMuscleGroup: MuscleGroup? = null,
    val draftSecondaryMuscleGroups: List<MuscleGroup> = emptyList(),
    val draftSets: List<ExerciseSet> = emptyList(),
    val draftReps: Int? = null,
    val errorModal: String? = null,
)

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
                _uiState.update {
                    it.copy(
                        muscleGroups = groups,
                        draftMuscleGroup = it.draftMuscleGroup ?: groups.firstOrNull(),
                    )
                }
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
                draftExerciseName = "",
                draftFromLibrary = false,
                draftSecondaryMuscleGroups = emptyList(),
                draftSets = emptyList(),
            )
        }
    }

    fun cancelPickExercise() {
        _uiState.update { it.copy(mode = WorkoutMode.ExerciseList) }
    }

    fun selectLibraryExercise(exercise: Exercise) {
        _uiState.update { state ->
            val primary = state.muscleGroups.find { it.id == exercise.primaryMuscleGroupId }
                ?: state.muscleGroups.firstOrNull()
            val secondary = exercise.secondaryMuscleGroupIds.mapNotNull { id ->
                state.muscleGroups.find { it.id == id }
            }
            state.copy(
                draftExerciseName = exercise.name,
                draftFromLibrary = true,
                draftMuscleGroup = primary,
                draftSecondaryMuscleGroups = secondary,
                draftSets = emptyList(),
                mode = WorkoutMode.AddExerciseSets,
            )
        }
    }

    fun startTypeNewExercise() {
        _uiState.update {
            it.copy(
                mode = WorkoutMode.AddExerciseName,
                draftExerciseName = "",
                draftFromLibrary = false,
                draftMuscleGroup = it.muscleGroups.firstOrNull(),
                draftSecondaryMuscleGroups = emptyList(),
            )
        }
    }

    fun cancelAddExercise() {
        _uiState.update { it.copy(mode = WorkoutMode.PickExercise) }
    }

    fun submitExerciseName(rawName: CharSequence) {
        val name = rawName.toString().trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(errorModal = "Please enter an exercise name.") }
            return
        }
        _uiState.update {
            it.copy(draftExerciseName = name, draftFromLibrary = false, mode = WorkoutMode.AddExerciseSets)
        }
    }

    fun cycleDraftMuscleGroup() {
        _uiState.update { state ->
            val groups = state.muscleGroups
            if (groups.isEmpty()) return@update state
            val currentIndex = groups.indexOf(state.draftMuscleGroup).let { if (it < 0) 0 else it }
            state.copy(draftMuscleGroup = groups[(currentIndex + 1) % groups.size])
        }
    }

    fun startAddSet() {
        _uiState.update { it.copy(mode = WorkoutMode.AddSetReps, draftReps = null) }
    }

    fun cancelAddSet() {
        _uiState.update { it.copy(mode = WorkoutMode.AddExerciseSets) }
    }

    fun submitReps(rawReps: CharSequence) {
        val reps = rawReps.toString().trim().toIntOrNull()
        if (reps == null || reps <= 0) {
            _uiState.update { it.copy(errorModal = "Please enter a whole number of reps.") }
            return
        }
        _uiState.update { it.copy(draftReps = reps, mode = WorkoutMode.AddSetWeight) }
    }

    fun submitWeight(rawWeight: CharSequence) {
        val trimmed = rawWeight.toString().trim()
        val reps = _uiState.value.draftReps ?: return
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
                draftReps = null,
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
        val muscleGroup = state.draftMuscleGroup ?: run {
            _uiState.update { it.copy(errorModal = "Add a muscle group before finishing this exercise.") }
            return
        }
        val exercise = LoggedExercise(
            name = state.draftExerciseName,
            muscleGroup = muscleGroup,
            sets = state.draftSets,
        )
        _uiState.update {
            it.copy(
                exercises = it.exercises + exercise,
                mode = WorkoutMode.ExerciseList,
                draftExerciseName = "",
                draftFromLibrary = false,
                draftSecondaryMuscleGroups = emptyList(),
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
            name = "Workout",
            date = LocalDate.now(),
            exercises = exercises,
        )
    }
}

class WorkoutInProgressScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<WorkoutSession?, WorkoutInProgressViewModel>(sealedActivity) {

    override val viewModelClass: Class<WorkoutInProgressViewModel>
        get() = WorkoutInProgressViewModel::class.java

    private val repository = TrainingRepository.getInstance {
        lightContext.buildDatabase(TrainingDatabase::class.java, TrainingRepository.DATABASE_NAME)
    }

    override fun createViewModel(): WorkoutInProgressViewModel =
        WorkoutInProgressViewModel(lightContext.dataStore, repository)

    @Composable
    override fun Content() {
        val state by viewModel.uiState.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val repsFieldState = rememberTextFieldState("")
        val weightFieldState = rememberTextFieldState("")
        val nameFieldState = rememberTextFieldState("")
        val keyboardOptionsFlow = rememberKeyboardOptions()

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
                        onAddNew = viewModel::startTypeNewExercise,
                    )

                    WorkoutMode.AddExerciseName -> LightTextInputEditor(
                        title = "Exercise name",
                        state = nameFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitExerciseName,
                        onBack = viewModel::cancelAddExercise,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkoutMode.AddExerciseSets -> AddExerciseSetsContent(
                        state = state,
                        onBack = viewModel::cancelAddExercise,
                        onCycleMuscleGroup = viewModel::cycleDraftMuscleGroup,
                        onAddSet = viewModel::startAddSet,
                        onFinishExercise = viewModel::finishExercise,
                    )

                    WorkoutMode.AddSetReps -> LightTextInputEditor(
                        title = "Reps",
                        state = repsFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitReps,
                        onBack = viewModel::cancelAddSet,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
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
                                text = exercise.muscleGroup.name,
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
                LightBarButton.Text(text = "FINISH", onClick = onFinish),
            ),
        )
    }
}

@Composable
private fun PickExerciseContent(
    state: WorkoutUiState,
    onBack: () -> Unit,
    onSelectExercise: (Exercise) -> Unit,
    onAddNew: () -> Unit,
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
                        text = "Tap the add button below to type a new one.",
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
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable(onClick = { onSelectExercise(exercise) })
                                .padding(vertical = 12.dp),
                        ) {
                            LightText(text = exercise.name, variant = LightTextVariant.Copy)
                            if (primaryName != null) {
                                LightText(
                                    text = primaryName,
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

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = onAddNew,
                    contentDescription = "Type a new exercise",
                ),
            ),
        )
    }
}

@Composable
private fun AddExerciseSetsContent(
    state: WorkoutUiState,
    onBack: () -> Unit,
    onCycleMuscleGroup: () -> Unit,
    onAddSet: () -> Unit,
    onFinishExercise: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel exercise",
            ),
            center = LightTopBarCenter.Text(state.draftExerciseName),
        )

        Column(modifier = Modifier.weight(1f).padding(horizontal = 32.dp)) {
            if (state.draftFromLibrary) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    LightText(text = "Muscle group", variant = LightTextVariant.Detail, lighten = true)
                    LightText(
                        text = state.draftMuscleGroup?.name ?: "None",
                        variant = LightTextVariant.Heading,
                    )
                    if (state.draftSecondaryMuscleGroups.isNotEmpty()) {
                        LightText(
                            text = "Also: " + state.draftSecondaryMuscleGroups.joinToString(", ") { it.name },
                            variant = LightTextVariant.Detail,
                            lighten = true,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .lightClickable(onClick = onCycleMuscleGroup)
                        .padding(vertical = 12.dp),
                ) {
                    LightText(text = "Muscle group", variant = LightTextVariant.Detail, lighten = true)
                    LightText(
                        text = state.draftMuscleGroup?.name ?: "None",
                        variant = LightTextVariant.Heading,
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

private fun formatWeight(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
