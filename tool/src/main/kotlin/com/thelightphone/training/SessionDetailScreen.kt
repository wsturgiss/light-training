package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
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
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

private val detailDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Which set a rep/weight entry step is being logged against. */
sealed class SetTarget {
    /** A set being added to an exercise that's already part of the session. */
    data class Existing(val exerciseIndex: Int) : SetTarget()

    /** A set being added to a brand new exercise not yet appended to the session. */
    data object Draft : SetTarget()
}

/** Which sub-screen of the session detail flow is currently shown. */
sealed class SessionDetailMode {
    /** Full breakdown of the session's exercises and sets. */
    data object Overview : SessionDetailMode()

    /** Pick an exercise from the library. */
    data object PickExercise : SessionDetailMode()

    /** Set entry for the new exercise currently being built. */
    data object AddExerciseSets : SessionDetailMode()

    /** Combined weight + reps entry for a new set. */
    data class AddSet(val target: SetTarget) : SessionDetailMode()

    /** List of exercises with reorder/delete controls. */
    data object ManageExercises : SessionDetailMode()

    /** Confirmation before deleting the whole workout. */
    data object ConfirmDeleteWorkout : SessionDetailMode()
}

data class SessionDetailUiState(
    val mode: SessionDetailMode = SessionDetailMode.Overview,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val session: WorkoutSession? = null,
    val loading: Boolean = true,
    val exerciseLibrary: List<Exercise> = emptyList(),
    val muscleGroups: List<MuscleGroup> = emptyList(),
    val draftExercise: Exercise? = null,
    val draftSets: List<ExerciseSet> = emptyList(),
    val draftReps: Int = DEFAULT_REPS,
    val draftWeightText: String = "",
    val errorModal: String? = null,
    // Bumped every time a new add-set flow starts; see AddSetContent's sessionKey parameter.
    val addSetSession: Long = 0L,
)

private const val DEFAULT_REPS = 10

class SessionDetailViewModel(
    private val sessionId: String,
    private val dataStore: DataStore<Preferences>,
    private val repository: TrainingRepository,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        reload()
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

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val unit = weightUnitFromStorage(dataStore.data.first()[TrainingPreferences.WEIGHT_UNIT])
            val session = repository.getSession(sessionId)
            _uiState.update { it.copy(weightUnit = unit, session = session, loading = false) }
        }
    }

    fun startAddSet(exerciseIndex: Int) {
        val lastSet = _uiState.value.session?.exercises?.getOrNull(exerciseIndex)?.sets?.lastOrNull()
        val prefillReps = lastSet?.reps ?: DEFAULT_REPS
        val prefillWeightText = lastSet?.weightKg
            ?.let { formatWeight(_uiState.value.weightUnit.fromKg(it)) } ?: ""
        _uiState.update {
            it.copy(
                mode = SessionDetailMode.AddSet(SetTarget.Existing(exerciseIndex)),
                draftReps = prefillReps,
                draftWeightText = prefillWeightText,
                addSetSession = it.addSetSession + 1,
            )
        }
    }

    fun cancelAddSet() {
        val target = (_uiState.value.mode as? SessionDetailMode.AddSet)?.target
        _uiState.update {
            it.copy(mode = if (target is SetTarget.Draft) SessionDetailMode.AddExerciseSets else SessionDetailMode.Overview)
        }
    }

    fun submitSet(reps: Int, rawWeight: CharSequence) {
        val target = (_uiState.value.mode as? SessionDetailMode.AddSet)?.target ?: return
        val trimmed = rawWeight.toString().trim()
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
        val newSet = ExerciseSet(reps = reps, weightKg = weightKg)
        when (target) {
            is SetTarget.Existing -> {
                val session = _uiState.value.session ?: return
                val updatedSession = session.copy(
                    exercises = session.exercises.mapIndexed { index, exercise ->
                        if (index == target.exerciseIndex) exercise.copy(sets = exercise.sets + newSet) else exercise
                    },
                )
                _uiState.update {
                    it.copy(session = updatedSession, mode = SessionDetailMode.Overview)
                }
                persist(updatedSession)
            }

            SetTarget.Draft -> {
                _uiState.update {
                    it.copy(
                        draftSets = it.draftSets + newSet,
                        mode = SessionDetailMode.AddExerciseSets,
                    )
                }
            }
        }
    }

    // --- Adding a whole new exercise to this past session ---

    fun startAddExercise() {
        _uiState.update {
            it.copy(
                mode = SessionDetailMode.PickExercise,
                draftExercise = null,
                draftSets = emptyList(),
            )
        }
    }

    fun cancelPickExercise() {
        _uiState.update { it.copy(mode = SessionDetailMode.Overview) }
    }

    fun selectLibraryExercise(exercise: Exercise) {
        _uiState.update {
            it.copy(
                draftExercise = exercise,
                draftSets = emptyList(),
                mode = SessionDetailMode.AddExerciseSets,
            )
        }
    }

    fun cancelAddExercise() {
        _uiState.update { it.copy(mode = SessionDetailMode.PickExercise) }
    }

    fun startAddDraftSet() {
        val lastSet = _uiState.value.draftSets.lastOrNull()
        val prefillReps = lastSet?.reps ?: DEFAULT_REPS
        val prefillWeightText = lastSet?.weightKg
            ?.let { formatWeight(_uiState.value.weightUnit.fromKg(it)) } ?: ""
        _uiState.update {
            it.copy(
                mode = SessionDetailMode.AddSet(SetTarget.Draft),
                draftReps = prefillReps,
                draftWeightText = prefillWeightText,
                addSetSession = it.addSetSession + 1,
            )
        }
    }

    fun finishNewExercise() {
        val state = _uiState.value
        val exercise = state.draftExercise ?: run {
            _uiState.update { it.copy(errorModal = "No exercise selected.") }
            return
        }
        val session = state.session ?: return
        val primaryGroup = state.muscleGroups.find { it.id == exercise.primaryMuscleGroupId }
            ?: MuscleGroup(id = "", name = "Unknown")
        val secondaryGroups = exercise.secondaryMuscleGroupIds.mapNotNull { id ->
            state.muscleGroups.find { it.id == id }
        }
        val newExercise = LoggedExercise(
            exerciseId = exercise.id,
            name = exercise.name,
            muscleGroup = primaryGroup,
            secondaryMuscleGroups = secondaryGroups,
            sets = state.draftSets,
        )
        val updatedSession = session.copy(exercises = session.exercises + newExercise)
        _uiState.update {
            it.copy(
                session = updatedSession,
                mode = SessionDetailMode.Overview,
                draftExercise = null,
                draftSets = emptyList(),
            )
        }
        persist(updatedSession)
    }

    fun deleteSet(exerciseIndex: Int, setIndex: Int) {
        val session = _uiState.value.session ?: return
        val updatedSession = session.copy(
            exercises = session.exercises.mapIndexed { index, exercise ->
                if (index == exerciseIndex) {
                    exercise.copy(sets = exercise.sets.filterIndexed { i, _ -> i != setIndex })
                } else {
                    exercise
                }
            },
        )
        _uiState.update { it.copy(session = updatedSession) }
        persist(updatedSession)
    }

    // --- Managing exercises (reorder, delete) ---

    fun startManageExercises() {
        _uiState.update { it.copy(mode = SessionDetailMode.ManageExercises) }
    }

    fun cancelManageExercises() {
        _uiState.update { it.copy(mode = SessionDetailMode.Overview) }
    }

    fun moveExerciseUp(index: Int) {
        if (index <= 0) return
        val session = _uiState.value.session ?: return
        val exercises = session.exercises.toMutableList()
        val temp = exercises[index - 1]
        exercises[index - 1] = exercises[index]
        exercises[index] = temp
        val updatedSession = session.copy(exercises = exercises)
        _uiState.update { it.copy(session = updatedSession) }
        persist(updatedSession)
    }

    fun moveExerciseDown(index: Int) {
        val session = _uiState.value.session ?: return
        if (index >= session.exercises.size - 1) return
        val exercises = session.exercises.toMutableList()
        val temp = exercises[index + 1]
        exercises[index + 1] = exercises[index]
        exercises[index] = temp
        val updatedSession = session.copy(exercises = exercises)
        _uiState.update { it.copy(session = updatedSession) }
        persist(updatedSession)
    }

    fun deleteExercise(index: Int) {
        val session = _uiState.value.session ?: return
        val updatedSession = session.copy(
            exercises = session.exercises.filterIndexed { i, _ -> i != index },
        )
        _uiState.update { it.copy(session = updatedSession) }
        persist(updatedSession)
    }

    fun startDeleteWorkout() {
        _uiState.update { it.copy(mode = SessionDetailMode.ConfirmDeleteWorkout) }
    }

    fun cancelDeleteWorkout() {
        _uiState.update { it.copy(mode = SessionDetailMode.ManageExercises) }
    }

    suspend fun confirmDeleteWorkout() {
        val sessionId = _uiState.value.session?.id ?: return
        withContext(Dispatchers.IO) {
            repository.deleteSession(sessionId)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorModal = null) }
    }

    private fun persist(session: WorkoutSession) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateSession(session)
        }
    }
}

class SessionDetailScreen(
    sealedActivity: SealedLightActivity,
    private val sessionId: String,
) : LightScreen<Unit, SessionDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<SessionDetailViewModel>
        get() = SessionDetailViewModel::class.java

    private val repository = TrainingRepository.getInstance {
        lightContext.buildDatabase(TrainingDatabase::class.java, TrainingRepository.DATABASE_NAME)
    }

    override fun createViewModel(): SessionDetailViewModel =
        SessionDetailViewModel(sessionId, lightContext.dataStore, repository)

    @Composable
    override fun Content() {
        val state by viewModel.uiState.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (val mode = state.mode) {
                    SessionDetailMode.Overview -> SessionOverviewContent(
                        state = state,
                        onBack = { goBack(Unit) },
                        onAddSet = viewModel::startAddSet,
                        onDeleteSet = viewModel::deleteSet,
                        onAddExercise = viewModel::startAddExercise,
                        onManageExercises = viewModel::startManageExercises,
                    )

                    SessionDetailMode.PickExercise -> SessionPickExerciseContent(
                        state = state,
                        onBack = viewModel::cancelPickExercise,
                        onSelectExercise = viewModel::selectLibraryExercise,
                    )

                    SessionDetailMode.AddExerciseSets -> SessionAddExerciseSetsContent(
                        state = state,
                        onBack = viewModel::cancelAddExercise,
                        onAddSet = viewModel::startAddDraftSet,
                        onFinishExercise = viewModel::finishNewExercise,
                    )

                    is SessionDetailMode.AddSet -> AddSetContent(
                        title = when (mode.target) {
                            is SetTarget.Existing -> state.session
                                ?.exercises?.getOrNull(mode.target.exerciseIndex)?.name ?: "Add set"
                            SetTarget.Draft -> state.draftExercise?.name ?: "Add set"
                        },
                        weightUnit = state.weightUnit,
                        initialReps = state.draftReps,
                        initialWeightText = state.draftWeightText,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onConfirm = viewModel::submitSet,
                        onBack = viewModel::cancelAddSet,
                        sessionKey = state.addSetSession,
                    )

                    SessionDetailMode.ManageExercises -> ManageExercisesContent(
                        state = state,
                        onBack = viewModel::cancelManageExercises,
                        onMoveUp = viewModel::moveExerciseUp,
                        onMoveDown = viewModel::moveExerciseDown,
                        onDeleteExercise = viewModel::deleteExercise,
                        onDeleteWorkout = viewModel::startDeleteWorkout,
                    )

                    SessionDetailMode.ConfirmDeleteWorkout -> {
                        ConfirmDeleteWorkoutContent(
                            onCancel = viewModel::cancelDeleteWorkout,
                            onConfirm = {
                                viewModel.viewModelScope.launch {
                                    viewModel.confirmDeleteWorkout()
                                }
                            },
                            onConfirmAndNavigate = {
                                viewModel.viewModelScope.launch {
                                    viewModel.confirmDeleteWorkout()
                                    goBack(Unit)
                                }
                            },
                        )
                    }
                }

                state.errorModal?.let { message ->
                    LightFullscreenModal(message = message, onClose = viewModel::dismissError)
                }
            }
        }
    }
}

@Composable
private fun SessionOverviewContent(
    state: SessionDetailUiState,
    onBack: () -> Unit,
    onAddSet: (Int) -> Unit,
    onDeleteSet: (Int, Int) -> Unit,
    onAddExercise: () -> Unit,
    onManageExercises: () -> Unit,
) {
    val session = state.session

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Exercises"),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (session == null) {
                if (!state.loading) {
                    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                        LightText(
                            text = "This session couldn't be found.",
                            variant = LightTextVariant.Copy,
                            lighten = true,
                        )
                    }
                }
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.DenseScrollPadding),
                ) {
                    LightText(
                        text = session.date.format(detailDateFormatter),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

                    if (session.exercises.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp)) {
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
                    }

                    session.exercises.forEachIndexed { exerciseIndex, exercise ->
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                LightText(
                                    text = exercise.name,
                                    variant = LightTextVariant.Copy,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            LightText(
                                text = muscleGroupSummary(exercise),
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                            )

                            if (exercise.sets.isEmpty()) {
                                LightText(
                                    text = "No sets logged",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            } else {
                                exercise.sets.forEachIndexed { setIndex, set ->
                                    val weightText = set.weightKg?.let { kg ->
                                        val displayValue = state.weightUnit.fromKg(kg)
                                        "${formatWeight(displayValue)} ${state.weightUnit.displayName}"
                                    } ?: "bodyweight"
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        LightText(
                                            text = "Set ${setIndex + 1}: ${set.reps} reps @ $weightText",
                                            variant = LightTextVariant.Detail,
                                            modifier = Modifier.weight(1f),
                                        )
                                        LightIcon(
                                            icon = LightIcons.TRASH,
                                            size = 2f,
                                            contentDescription = "Delete set",
                                            modifier = Modifier.lightClickable(
                                                onClick = { onDeleteSet(exerciseIndex, setIndex) },
                                            ),
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .lightClickable(onClick = { onAddSet(exerciseIndex) })
                                    .padding(top = 8.dp, start = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LightIcon(
                                    icon = LightIcons.ADD,
                                    size = 2f,
                                    contentDescription = "Add set",
                                )
                                LightText(
                                    text = "Add set",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(start = 8.dp),
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
                    onClick = onAddExercise,
                    contentDescription = "Add exercise",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.SETTINGS,
                    onClick = onManageExercises,
                    contentDescription = "Manage Session",
                ),
            ),
        )
    }
}

@Composable
private fun SessionPickExerciseContent(
    state: SessionDetailUiState,
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
                        .padding(UiConstants.DenseScrollPadding),
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
                                .padding(vertical = 6.dp),
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
private fun SessionAddExerciseSetsContent(
    state: SessionDetailUiState,
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
                LightScrollView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(UiConstants.DenseScrollPadding),
                ) {
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
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onFinishExercise,
                    contentDescription = "Complete exercise",
                ),
            ),
        )
    }
}

@Composable
private fun ManageExercisesContent(
    state: SessionDetailUiState,
    onBack: () -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onDeleteExercise: (Int) -> Unit,
    onDeleteWorkout: () -> Unit,
) {
    val session = state.session

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Manage exercises"),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (session == null || session.exercises.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                    LightText(
                        text = "No exercises to manage",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                }
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.DenseScrollPadding),
                ) {
                    session.exercises.forEachIndexed { index, exercise ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                LightText(
                                    text = exercise.name,
                                    variant = LightTextVariant.Copy,
                                )
                                LightText(
                                    text = muscleGroupSummary(exercise),
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }

                            // Reorder controls
                            if (index > 0) {
                                LightIcon(
                                    icon = LightIcons.ARROW_DOWN,
                                    size = 2f,
                                    contentDescription = "Move up",
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .padding(horizontal = 8.dp)
                                        .rotate(180f)
                                        .lightClickable(onClick = { onMoveUp(index) }),
                                )
                            } else {
                                Box(modifier = Modifier.size(24.dp)) // Spacer for alignment
                            }

                            if (index < session.exercises.size - 1) {
                                LightIcon(
                                    icon = LightIcons.ARROW_DOWN,
                                    size = 2f,
                                    contentDescription = "Move down",
                                    modifier = Modifier
                                        .align(Alignment.CenterVertically)
                                        .padding(horizontal = 8.dp)
                                        .lightClickable(onClick = { onMoveDown(index) }),
                                )
                            } else {
                                Box(modifier = Modifier.size(24.dp)) // Spacer for alignment
                            }

                            // Delete exercise
                            LightIcon(
                                icon = LightIcons.TRASH,
                                size = 2f,
                                contentDescription = "Delete exercise",
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(horizontal = 8.dp)
                                    .lightClickable(onClick = { onDeleteExercise(index) }),
                            )
                        }
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.TRASH,
                    onClick = onDeleteWorkout,
                    contentDescription = "Delete workout",
                ),
            ),
        )
    }
}

@Composable
private fun ConfirmDeleteWorkoutContent(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    onConfirmAndNavigate: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onCancel,
                contentDescription = "Cancel",
            ),
            center = LightTopBarCenter.Text("Delete workout"),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = "Are you sure you want to delete this workout? This cannot be undone.",
                variant = LightTextVariant.Copy,
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.DENY,
                    onClick = onCancel,
                    contentDescription = "Cancel",
                ),
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = onConfirmAndNavigate,
                    contentDescription = "Delete",
                ),
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
