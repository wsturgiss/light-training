package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightEditableRow
import com.thelightphone.sdk.ui.LightFullscreenModal
import com.thelightphone.sdk.ui.LightIcon
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
import com.thelightphone.training.model.IntervalScheme
import com.thelightphone.training.model.MuscleGroup
import com.thelightphone.training.model.TrackedField
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingPreferences
import com.thelightphone.training.model.TrainingRepository
import com.thelightphone.training.model.WeightUnit
import com.thelightphone.training.model.weightUnitFromStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which sub-screen of the Settings flow is currently shown. */
sealed class SettingsMode {
    /** Top-level menu: "Muscle Groups" / "Exercises". */
    data object Menu : SettingsMode()

    /** List of muscle groups, with add/edit/delete. Since a muscle group
     * only has a single property (its name), edit/delete happen directly
     * from the list row (pencil to rename, trash to delete) instead of
     * going through a separate detail screen. */
    data object MuscleGroupList : SettingsMode()

    /** Text entry for a muscle group's name (add, or rename an existing
     * group tapped from the list). */
    data object MuscleGroupName : SettingsMode()

    /** List of exercises, with add/edit/delete. */
    data object ExerciseList : SettingsMode()

    /** Text entry for an exercise's name (add only; new exercises still go
     * straight through name -> primary -> secondary). */
    data object ExerciseName : SettingsMode()

    /** Single-select: pick the exercise's primary muscle group (add flow). */
    data object ExercisePrimaryGroup : SettingsMode()

    /** Multi-select: pick the exercise's secondary muscle group(s) (add flow). */
    data object ExerciseSecondaryGroups : SettingsMode()

    /** Detail view for an existing exercise: name, primary, and secondary
     * muscle groups, each individually editable via a pencil icon. */
    data object ExerciseDetail : SettingsMode()

    /** Text entry for renaming an existing exercise from the detail view. */
    data object ExerciseDetailEditName : SettingsMode()

    /** Single-select: change an existing exercise's primary muscle group
     * from the detail view. */
    data object ExerciseDetailEditPrimaryGroup : SettingsMode()

    /** Multi-select: change an existing exercise's secondary muscle groups
     * from the detail view. */
    data object ExerciseDetailEditSecondaryGroups : SettingsMode()

    /** Multi-select: change which extra data points (distance, pace, ...) a cardio
     * exercise offers when logging it. Only reachable for exercises in the Cardio
     * muscle group. */
    data object ExerciseDetailEditTrackedFields : SettingsMode()

    /** List of user-defined interval schemes, with add/rename/configure/delete. */
    data object IntervalSchemeList : SettingsMode()

    /** Text entry for a scheme's name (add, or rename an existing scheme
     * tapped from the list). */
    data object IntervalSchemeName : SettingsMode()

    /** Nudge-arrow entry for a scheme's work/rest/rounds (add, or tapping
     * an existing scheme's row to edit its numbers). */
    data object IntervalSchemeConfigure : SettingsMode()
}

data class SettingsUiState(
    val mode: SettingsMode = SettingsMode.Menu,
    val muscleGroups: List<MuscleGroup> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    val draftMuscleGroupId: String? = null,
    val draftMuscleGroupName: String = "",
    val draftExerciseId: String? = null,
    val draftExerciseName: String = "",
    val draftPrimaryMuscleGroupId: String? = null,
    val draftSecondaryMuscleGroupIds: Set<String> = emptySet(),
    val draftTrackedFields: Set<TrackedField> = emptySet(),
    val weightUnit: WeightUnit = WeightUnit.KG,
    val intervalSchemes: List<IntervalScheme> = emptyList(),
    val draftSchemeId: String? = null,
    val draftSchemeName: String = "",
    val draftSchemeWorkSeconds: Int = DEFAULT_SCHEME_WORK_SEC,
    val draftSchemeRestSeconds: Int = DEFAULT_SCHEME_REST_SEC,
    val draftSchemeRounds: Int = DEFAULT_SCHEME_ROUNDS,
    val errorModal: String? = null,
) {
    val isEditingMuscleGroup: Boolean get() = draftMuscleGroupId != null
    val isEditingScheme: Boolean get() = draftSchemeId != null
}

private const val CARDIO_MUSCLE_GROUP_NAME = "Cardio"

private const val DEFAULT_SCHEME_WORK_SEC = 30
private const val DEFAULT_SCHEME_REST_SEC = 15
private const val DEFAULT_SCHEME_ROUNDS = 8
private const val SCHEME_TIME_STEP_SEC = 5
private const val MIN_SCHEME_TIME_SEC = 5
private const val MIN_SCHEME_ROUNDS = 1

class SettingsViewModel(
    private val dataStore: DataStore<Preferences>,
    private val repository: TrainingRepository,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureSeeded()
        }
        viewModelScope.launch {
            repository.muscleGroups.collect { groups ->
                _uiState.value = _uiState.value.copy(muscleGroups = groups)
            }
        }
        viewModelScope.launch {
            repository.exercises.collect { exercises ->
                _uiState.value = _uiState.value.copy(exercises = exercises)
            }
        }
        viewModelScope.launch {
            repository.intervalSchemes.collect { schemes ->
                _uiState.value = _uiState.value.copy(intervalSchemes = schemes)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val unit = weightUnitFromStorage(dataStore.data.first()[TrainingPreferences.WEIGHT_UNIT])
            _uiState.update { it.copy(weightUnit = unit) }
        }
    }

    fun toggleWeightUnit() {
        val next = _uiState.value.weightUnit.next()
        _uiState.update { it.copy(weightUnit = next) }
        viewModelScope.launch(Dispatchers.IO) {
            dataStore.edit { prefs -> prefs[TrainingPreferences.WEIGHT_UNIT] = next.name }
        }
    }

    // --- Navigation between sub-screens ---

    fun goToMuscleGroups() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.MuscleGroupList)
    }

    fun goToExercises() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseList)
    }

    fun goToIntervalSchemes() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.IntervalSchemeList)
    }

    fun backToMenu() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.Menu)
    }

    // --- Muscle groups ---

    fun startAddMuscleGroup() {
        _uiState.value = _uiState.value.copy(
            mode = SettingsMode.MuscleGroupName,
            draftMuscleGroupId = null,
            draftMuscleGroupName = "",
        )
    }

    fun cancelMuscleGroupName() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.MuscleGroupList)
    }

    fun submitMuscleGroupName(rawName: CharSequence) {
        val name = rawName.toString().trim()
        if (name.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorModal = "Please enter a muscle group name.")
            return
        }
        val draftId = _uiState.value.draftMuscleGroupId
        viewModelScope.launch(Dispatchers.IO) {
            if (draftId == null) {
                repository.addMuscleGroup(name)
            } else {
                repository.renameMuscleGroup(draftId, name)
            }
        }
        _uiState.value = _uiState.value.copy(mode = SettingsMode.MuscleGroupList)
    }

    /** Tapping the pencil on an existing muscle group opens the rename
     * text field directly (no separate detail screen, since a muscle
     * group only has a single editable property). */
    fun startEditMuscleGroup(group: MuscleGroup) {
        _uiState.value = _uiState.value.copy(
            mode = SettingsMode.MuscleGroupName,
            draftMuscleGroupId = group.id,
            draftMuscleGroupName = group.name,
        )
    }

    /** Tapping the trash icon on an existing muscle group deletes it
     * directly from the list. */
    fun removeMuscleGroup(group: MuscleGroup) {
        val id = group.id
        val usedAsPrimary = _uiState.value.exercises.any { it.primaryMuscleGroupId == id }
        if (usedAsPrimary) {
            _uiState.value = _uiState.value.copy(
                errorModal = "This muscle group is used as the primary group for an exercise. Update that exercise first.",
            )
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeMuscleGroup(id)
            _uiState.value.exercises.forEach { exercise ->
                if (id in exercise.secondaryMuscleGroupIds) {
                    repository.updateExercise(
                        id = exercise.id,
                        name = exercise.name,
                        primaryMuscleGroupId = exercise.primaryMuscleGroupId,
                        secondaryMuscleGroupIds = exercise.secondaryMuscleGroupIds - id,
                        trackedFields = exercise.trackedFields,
                    )
                }
            }
        }
    }

    // --- Exercises ---

    fun startAddExercise() {
        _uiState.value = _uiState.value.copy(
            mode = SettingsMode.ExerciseName,
            draftExerciseId = null,
            draftExerciseName = "",
            draftPrimaryMuscleGroupId = null,
            draftSecondaryMuscleGroupIds = emptySet(),
            draftTrackedFields = emptySet(),
        )
    }

    /** Tapping an existing exercise now opens the detail view (not the
     * name -> primary -> secondary add flow). */
    fun startEditExercise(exercise: Exercise) {
        _uiState.value = _uiState.value.copy(
            mode = SettingsMode.ExerciseDetail,
            draftExerciseId = exercise.id,
            draftExerciseName = exercise.name,
            draftPrimaryMuscleGroupId = exercise.primaryMuscleGroupId,
            draftSecondaryMuscleGroupIds = exercise.secondaryMuscleGroupIds.toSet(),
            draftTrackedFields = exercise.trackedFields,
        )
    }

    fun backFromExerciseDetail() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseList)
    }

    fun cancelExerciseName() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseList)
    }

    fun submitExerciseName(rawName: CharSequence) {
        val name = rawName.toString().trim()
        if (name.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorModal = "Please enter an exercise name.")
            return
        }
        _uiState.value = _uiState.value.copy(
            draftExerciseName = name,
            mode = SettingsMode.ExercisePrimaryGroup,
        )
    }

    fun cancelPrimaryGroup() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseName)
    }

    fun selectPrimaryMuscleGroup(id: String) {
        _uiState.value = _uiState.value.copy(
            draftPrimaryMuscleGroupId = id,
            draftSecondaryMuscleGroupIds = _uiState.value.draftSecondaryMuscleGroupIds - id,
        )
    }

    fun confirmPrimaryGroup() {
        if (_uiState.value.draftPrimaryMuscleGroupId == null) {
            _uiState.value = _uiState.value.copy(errorModal = "Please choose a primary muscle group.")
            return
        }
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseSecondaryGroups)
    }

    fun cancelSecondaryGroups() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExercisePrimaryGroup)
    }

    fun toggleSecondaryMuscleGroup(id: String) {
        if (id == _uiState.value.draftPrimaryMuscleGroupId) return
        _uiState.value = _uiState.value.copy(
            draftSecondaryMuscleGroupIds = _uiState.value.draftSecondaryMuscleGroupIds.let { ids ->
                if (id in ids) ids - id else ids + id
            },
        )
    }

    fun finishExercise() {
        val state = _uiState.value
        val primaryId = state.draftPrimaryMuscleGroupId
        if (primaryId == null) {
            _uiState.value = state.copy(errorModal = "Please choose a primary muscle group.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.addExercise(
                name = state.draftExerciseName,
                primaryMuscleGroupId = primaryId,
                secondaryMuscleGroupIds = state.draftSecondaryMuscleGroupIds.toList(),
            )
        }
        _uiState.value = state.copy(mode = SettingsMode.ExerciseList)
    }

    // --- Exercise detail (edit an existing exercise field-by-field) ---

    fun startEditExerciseDetailName() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseDetailEditName)
    }

    fun cancelExerciseDetailEditName() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseDetail)
    }

    fun submitExerciseDetailName(rawName: CharSequence) {
        val name = rawName.toString().trim()
        if (name.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorModal = "Please enter an exercise name.")
            return
        }
        val state = _uiState.value
        val id = state.draftExerciseId
        if (id != null) {
            val primaryId = state.draftPrimaryMuscleGroupId ?: return
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateExercise(
                    id = id,
                    name = name,
                    primaryMuscleGroupId = primaryId,
                    secondaryMuscleGroupIds = state.draftSecondaryMuscleGroupIds.toList(),
                    trackedFields = state.draftTrackedFields,
                )
            }
        }
        _uiState.value = _uiState.value.copy(
            draftExerciseName = name,
            mode = SettingsMode.ExerciseDetail,
        )
    }

    fun startEditExerciseDetailPrimaryGroup() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseDetailEditPrimaryGroup)
    }

    fun cancelExerciseDetailEditPrimaryGroup() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseDetail)
    }

    fun selectExerciseDetailPrimaryMuscleGroup(id: String) {
        _uiState.value = _uiState.value.copy(
            draftPrimaryMuscleGroupId = id,
            draftSecondaryMuscleGroupIds = _uiState.value.draftSecondaryMuscleGroupIds - id,
        )
    }

    fun confirmExerciseDetailPrimaryGroup() {
        val state = _uiState.value
        val exerciseId = state.draftExerciseId ?: return
        val primaryId = state.draftPrimaryMuscleGroupId
        if (primaryId == null) {
            _uiState.value = state.copy(errorModal = "Please choose a primary muscle group.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateExercise(
                id = exerciseId,
                name = state.draftExerciseName,
                primaryMuscleGroupId = primaryId,
                secondaryMuscleGroupIds = state.draftSecondaryMuscleGroupIds.toList(),
                trackedFields = state.draftTrackedFields,
            )
        }
        _uiState.value = state.copy(mode = SettingsMode.ExerciseDetail)
    }

    fun startEditExerciseDetailSecondaryGroups() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseDetailEditSecondaryGroups)
    }

    fun doneEditExerciseDetailSecondaryGroups() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseDetail)
    }

    fun toggleExerciseDetailSecondaryMuscleGroup(id: String) {
        val state = _uiState.value
        if (id == state.draftPrimaryMuscleGroupId) return
        val exerciseId = state.draftExerciseId ?: return
        val newSecondary = state.draftSecondaryMuscleGroupIds.let { ids ->
            if (id in ids) ids - id else ids + id
        }
        val primaryId = state.draftPrimaryMuscleGroupId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateExercise(
                id = exerciseId,
                name = state.draftExerciseName,
                primaryMuscleGroupId = primaryId,
                secondaryMuscleGroupIds = newSecondary.toList(),
                trackedFields = state.draftTrackedFields,
            )
        }
        _uiState.value = state.copy(draftSecondaryMuscleGroupIds = newSecondary)
    }

    fun startEditExerciseDetailTrackedFields() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseDetailEditTrackedFields)
    }

    fun doneEditExerciseDetailTrackedFields() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseDetail)
    }

    fun toggleExerciseDetailTrackedField(field: TrackedField) {
        val state = _uiState.value
        val exerciseId = state.draftExerciseId ?: return
        val primaryId = state.draftPrimaryMuscleGroupId ?: return
        val newTrackedFields = state.draftTrackedFields.let { fields ->
            if (field in fields) fields - field else fields + field
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateExercise(
                id = exerciseId,
                name = state.draftExerciseName,
                primaryMuscleGroupId = primaryId,
                secondaryMuscleGroupIds = state.draftSecondaryMuscleGroupIds.toList(),
                trackedFields = newTrackedFields,
            )
        }
        _uiState.value = state.copy(draftTrackedFields = newTrackedFields)
    }

    fun removeExerciseFromDetail() {
        val id = _uiState.value.draftExerciseId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (repository.isExerciseInUse(id)) {
                _uiState.value = _uiState.value.copy(
                    errorModal = "This exercise is used in one or more logged sessions. Remove those sets first.",
                )
                return@launch
            }
            repository.removeExercise(id)
            _uiState.value = _uiState.value.copy(mode = SettingsMode.ExerciseList)
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorModal = null)
    }

    // --- Interval schemes ---

    fun startAddIntervalScheme() {
        _uiState.value = _uiState.value.copy(
            mode = SettingsMode.IntervalSchemeName,
            draftSchemeId = null,
            draftSchemeName = "",
            draftSchemeWorkSeconds = DEFAULT_SCHEME_WORK_SEC,
            draftSchemeRestSeconds = DEFAULT_SCHEME_REST_SEC,
            draftSchemeRounds = DEFAULT_SCHEME_ROUNDS,
        )
    }

    fun cancelIntervalSchemeName() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.IntervalSchemeList)
    }

    /** New schemes go name -> configure -> saved. Renaming an existing scheme (via the
     * list row's pencil icon) saves immediately and returns to the list. */
    fun submitIntervalSchemeName(rawName: CharSequence) {
        val name = rawName.toString().trim()
        if (name.isEmpty()) {
            _uiState.value = _uiState.value.copy(errorModal = "Please enter a scheme name.")
            return
        }
        val state = _uiState.value
        val draftId = state.draftSchemeId
        if (draftId == null) {
            _uiState.value = state.copy(draftSchemeName = name, mode = SettingsMode.IntervalSchemeConfigure)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                repository.updateIntervalScheme(
                    id = draftId,
                    name = name,
                    workSeconds = state.draftSchemeWorkSeconds,
                    restSeconds = state.draftSchemeRestSeconds,
                    rounds = state.draftSchemeRounds,
                )
            }
            _uiState.value = state.copy(draftSchemeName = name, mode = SettingsMode.IntervalSchemeList)
        }
    }

    /** Tapping the pencil on an existing scheme opens the rename text field directly. */
    fun startEditIntervalSchemeName(scheme: IntervalScheme) {
        _uiState.value = _uiState.value.copy(
            mode = SettingsMode.IntervalSchemeName,
            draftSchemeId = scheme.id,
            draftSchemeName = scheme.name,
            draftSchemeWorkSeconds = scheme.workSeconds,
            draftSchemeRestSeconds = scheme.restSeconds,
            draftSchemeRounds = scheme.rounds,
        )
    }

    /** Tapping a scheme's row body opens its work/rest/rounds for editing. */
    fun startConfigureIntervalScheme(scheme: IntervalScheme) {
        _uiState.value = _uiState.value.copy(
            mode = SettingsMode.IntervalSchemeConfigure,
            draftSchemeId = scheme.id,
            draftSchemeName = scheme.name,
            draftSchemeWorkSeconds = scheme.workSeconds,
            draftSchemeRestSeconds = scheme.restSeconds,
            draftSchemeRounds = scheme.rounds,
        )
    }

    fun adjustDraftSchemeWork(delta: Int) {
        _uiState.value = _uiState.value.let {
            it.copy(draftSchemeWorkSeconds = (it.draftSchemeWorkSeconds + delta).coerceAtLeast(MIN_SCHEME_TIME_SEC))
        }
    }

    fun adjustDraftSchemeRest(delta: Int) {
        _uiState.value = _uiState.value.let {
            it.copy(draftSchemeRestSeconds = (it.draftSchemeRestSeconds + delta).coerceAtLeast(MIN_SCHEME_TIME_SEC))
        }
    }

    fun adjustDraftSchemeRounds(delta: Int) {
        _uiState.value = _uiState.value.let {
            it.copy(draftSchemeRounds = (it.draftSchemeRounds + delta).coerceAtLeast(MIN_SCHEME_ROUNDS))
        }
    }

    fun cancelIntervalSchemeConfigure() {
        _uiState.value = _uiState.value.copy(mode = SettingsMode.IntervalSchemeList)
    }

    fun saveIntervalScheme() {
        val state = _uiState.value
        val draftId = state.draftSchemeId
        viewModelScope.launch(Dispatchers.IO) {
            if (draftId == null) {
                repository.addIntervalScheme(
                    name = state.draftSchemeName,
                    workSeconds = state.draftSchemeWorkSeconds,
                    restSeconds = state.draftSchemeRestSeconds,
                    rounds = state.draftSchemeRounds,
                )
            } else {
                repository.updateIntervalScheme(
                    id = draftId,
                    name = state.draftSchemeName,
                    workSeconds = state.draftSchemeWorkSeconds,
                    restSeconds = state.draftSchemeRestSeconds,
                    rounds = state.draftSchemeRounds,
                )
            }
        }
        _uiState.value = state.copy(mode = SettingsMode.IntervalSchemeList)
    }

    fun removeIntervalScheme(scheme: IntervalScheme) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeIntervalScheme(scheme.id)
        }
    }
}

class SettingsScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

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

    override fun createViewModel(): SettingsViewModel = SettingsViewModel(lightContext.dataStore, repository)

    @Composable
    override fun Content() {
        val state by viewModel.uiState.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()

        val muscleGroupNameFieldState = remember(state.mode, state.draftMuscleGroupId) {
            TextFieldState(state.draftMuscleGroupName)
        }
        val exerciseNameFieldState = remember(state.mode, state.draftExerciseId) {
            TextFieldState(state.draftExerciseName)
        }
        val exerciseDetailNameFieldState = remember(state.mode, state.draftExerciseId) {
            TextFieldState(state.draftExerciseName)
        }
        val schemeNameFieldState = remember(state.mode, state.draftSchemeId) {
            TextFieldState(state.draftSchemeName)
        }

        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                when (state.mode) {
                    SettingsMode.Menu -> MenuContent(
                        state = state,
                        onBack = { goBack(Unit) },
                        onMuscleGroups = viewModel::goToMuscleGroups,
                        onExercises = viewModel::goToExercises,
                        onIntervalSchemes = viewModel::goToIntervalSchemes,
                        onToggleWeightUnit = viewModel::toggleWeightUnit,
                    )

                    SettingsMode.MuscleGroupList -> MuscleGroupListContent(
                        state = state,
                        onBack = viewModel::backToMenu,
                        onAdd = viewModel::startAddMuscleGroup,
                        onEdit = viewModel::startEditMuscleGroup,
                        onDelete = viewModel::removeMuscleGroup,
                    )

                    SettingsMode.MuscleGroupName -> LightTextInputEditor(
                        title = if (state.isEditingMuscleGroup) "Rename muscle group" else "New muscle group",
                        state = muscleGroupNameFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitMuscleGroupName,
                        onBack = viewModel::cancelMuscleGroupName,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    SettingsMode.ExerciseList -> ExerciseListContent(
                        state = state,
                        onBack = viewModel::backToMenu,
                        onAdd = viewModel::startAddExercise,
                        onEdit = viewModel::startEditExercise,
                    )

                    SettingsMode.ExerciseName -> LightTextInputEditor(
                        title = "Exercise name",
                        state = exerciseNameFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitExerciseName,
                        onBack = viewModel::cancelExerciseName,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    SettingsMode.ExercisePrimaryGroup -> MuscleGroupSingleSelectContent(
                        muscleGroups = state.muscleGroups,
                        selectedId = state.draftPrimaryMuscleGroupId,
                        onBack = viewModel::cancelPrimaryGroup,
                        onSelect = viewModel::selectPrimaryMuscleGroup,
                        onDone = viewModel::confirmPrimaryGroup,
                    )

                    SettingsMode.ExerciseSecondaryGroups -> MuscleGroupMultiSelectContent(
                        muscleGroups = state.muscleGroups,
                        primaryMuscleGroupId = state.draftPrimaryMuscleGroupId,
                        selectedIds = state.draftSecondaryMuscleGroupIds,
                        onBack = viewModel::cancelSecondaryGroups,
                        onToggle = viewModel::toggleSecondaryMuscleGroup,
                        onDone = viewModel::finishExercise,
                    )

                    SettingsMode.ExerciseDetail -> ExerciseDetailContent(
                        state = state,
                        onBack = viewModel::backFromExerciseDetail,
                        onEditName = viewModel::startEditExerciseDetailName,
                        onEditPrimaryGroup = viewModel::startEditExerciseDetailPrimaryGroup,
                        onEditSecondaryGroups = viewModel::startEditExerciseDetailSecondaryGroups,
                        onEditTrackedFields = viewModel::startEditExerciseDetailTrackedFields,
                        onDelete = viewModel::removeExerciseFromDetail,
                    )

                    SettingsMode.ExerciseDetailEditName -> LightTextInputEditor(
                        title = "Exercise name",
                        state = exerciseDetailNameFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitExerciseDetailName,
                        onBack = viewModel::cancelExerciseDetailEditName,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    SettingsMode.ExerciseDetailEditPrimaryGroup -> MuscleGroupSingleSelectContent(
                        muscleGroups = state.muscleGroups,
                        selectedId = state.draftPrimaryMuscleGroupId,
                        onBack = viewModel::cancelExerciseDetailEditPrimaryGroup,
                        onSelect = viewModel::selectExerciseDetailPrimaryMuscleGroup,
                        onDone = viewModel::confirmExerciseDetailPrimaryGroup,
                    )

                    SettingsMode.ExerciseDetailEditSecondaryGroups -> MuscleGroupMultiSelectContent(
                        muscleGroups = state.muscleGroups,
                        primaryMuscleGroupId = state.draftPrimaryMuscleGroupId,
                        selectedIds = state.draftSecondaryMuscleGroupIds,
                        onBack = viewModel::doneEditExerciseDetailSecondaryGroups,
                        onToggle = viewModel::toggleExerciseDetailSecondaryMuscleGroup,
                        onDone = viewModel::doneEditExerciseDetailSecondaryGroups,
                    )

                    SettingsMode.ExerciseDetailEditTrackedFields -> TrackedFieldMultiSelectContent(
                        selectedFields = state.draftTrackedFields,
                        onBack = viewModel::doneEditExerciseDetailTrackedFields,
                        onToggle = viewModel::toggleExerciseDetailTrackedField,
                        onDone = viewModel::doneEditExerciseDetailTrackedFields,
                    )

                    SettingsMode.IntervalSchemeList -> IntervalSchemeListContent(
                        state = state,
                        onBack = viewModel::backToMenu,
                        onAdd = viewModel::startAddIntervalScheme,
                        onConfigure = viewModel::startConfigureIntervalScheme,
                        onRename = viewModel::startEditIntervalSchemeName,
                        onDelete = viewModel::removeIntervalScheme,
                    )

                    SettingsMode.IntervalSchemeName -> LightTextInputEditor(
                        title = if (state.isEditingScheme) "Rename scheme" else "New scheme",
                        state = schemeNameFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitIntervalSchemeName,
                        onBack = viewModel::cancelIntervalSchemeName,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    SettingsMode.IntervalSchemeConfigure -> IntervalSchemeConfigureContent(
                        state = state,
                        onAdjustWork = viewModel::adjustDraftSchemeWork,
                        onAdjustRest = viewModel::adjustDraftSchemeRest,
                        onAdjustRounds = viewModel::adjustDraftSchemeRounds,
                        onBack = viewModel::cancelIntervalSchemeConfigure,
                        onSave = viewModel::saveIntervalScheme,
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
private fun MenuContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onMuscleGroups: () -> Unit,
    onExercises: () -> Unit,
    onIntervalSchemes: () -> Unit,
    onToggleWeightUnit: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Settings"),
        )

        Column(modifier = Modifier.weight(1f)) {
            SettingsMenuRow(title = "Muscle Groups", onClick = onMuscleGroups)
            SettingsMenuRow(title = "Exercises", onClick = onExercises)
            SettingsMenuRow(title = "Interval Schemes", onClick = onIntervalSchemes)
            SettingsMenuRow(
                title = "Weight Unit",
                value = state.weightUnit.displayName.uppercase(),
                onClick = onToggleWeightUnit,
            )
        }
    }
}

@Composable
private fun SettingsMenuRow(title: String, onClick: () -> Unit, value: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = title, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        if (value != null) {
            LightText(text = value, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}

@Composable
private fun MuscleGroupListContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (MuscleGroup) -> Unit,
    onDelete: (MuscleGroup) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Muscle Groups"),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (state.muscleGroups.isEmpty()) {
                EmptyListMessage("No muscle groups yet", "Tap the add button below to create one.")
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.SpacedScrollPadding),
                ) {
                    state.muscleGroups.forEach { group ->
                        SingleFieldEditableRow(
                            label = group.name,
                            onEdit = { onEdit(group) },
                            onDelete = { onDelete(group) },
                        )
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = onAdd,
                    contentDescription = "Add muscle group",
                ),
            ),
        )
    }
}

@Composable
private fun ExerciseListContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Exercise) -> Unit,
) {
    val muscleGroupsById = state.muscleGroups.associateBy { it.id }

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
            if (state.exercises.isEmpty()) {
                EmptyListMessage("No exercises yet", "Tap the add button below to create one.")
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.DenseScrollPadding),
                ) {
                    state.exercises.forEach { exercise ->
                        val primaryName = muscleGroupsById[exercise.primaryMuscleGroupId]?.name ?: "Unknown"
                        val secondaryNames = exercise.secondaryMuscleGroupIds
                            .mapNotNull { muscleGroupsById[it]?.name }
                        val subtitle = if (secondaryNames.isEmpty()) {
                            primaryName
                        } else {
                            "$primaryName + ${secondaryNames.joinToString(", ")}"
                        }
                        LightEditableRow(
                            label = exercise.name,
                            subscript = subtitle,
                            onClick = { onEdit(exercise) },
                        )
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = onAdd,
                    contentDescription = "Add exercise",
                ),
            ),
        )
    }
}

/**
 * Row archetype for an item whose only editable property is its label (e.g.
 * a muscle group's name): no separate detail screen, just a pencil icon to
 * edit the label directly and a trash icon to delete it, both trailing the
 * label in the row itself.
 */
@Composable
private fun SingleFieldEditableRow(
    label: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(text = label, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        LightIcon(
            icon = LightIcons.PENCIL,
            size = 2f,
            contentDescription = "Edit $label",
            modifier = Modifier
                .lightClickable(onClick = onEdit)
                .padding(end = 16.dp),
        )
        LightIcon(
            icon = LightIcons.TRASH,
            size = 2f,
            contentDescription = "Delete $label",
            modifier = Modifier.lightClickable(onClick = onDelete),
        )
    }
}

@Composable
private fun ExerciseDetailContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onEditName: () -> Unit,
    onEditPrimaryGroup: () -> Unit,
    onEditSecondaryGroups: () -> Unit,
    onEditTrackedFields: () -> Unit,
    onDelete: () -> Unit,
) {
    val muscleGroupsById = state.muscleGroups.associateBy { it.id }
    val primaryGroup = muscleGroupsById[state.draftPrimaryMuscleGroupId]
    val primaryName = primaryGroup?.name ?: "None"
    val secondaryNames = state.draftSecondaryMuscleGroupIds
        .mapNotNull { muscleGroupsById[it]?.name }
    val secondaryText = if (secondaryNames.isEmpty()) "None" else secondaryNames.joinToString(", ")
    val isCardio = primaryGroup?.name?.equals(CARDIO_MUSCLE_GROUP_NAME, ignoreCase = true) == true
    val trackedFieldsText = if (state.draftTrackedFields.isEmpty()) {
        "None"
    } else {
        state.draftTrackedFields.joinToString(", ") { it.displayName }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Exercise"),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 32.dp, vertical = 16.dp),
        ) {
            LightEditableRow(superscript = "Name", label = state.draftExerciseName, onClick = onEditName)
            LightEditableRow(
                superscript = "Primary muscle group",
                label = primaryName,
                onClick = onEditPrimaryGroup,
            )
            LightEditableRow(
                superscript = "Secondary muscle groups",
                label = secondaryText,
                onClick = onEditSecondaryGroups,
            )
            if (isCardio) {
                LightEditableRow(
                    superscript = "Track",
                    label = trackedFieldsText,
                    onClick = onEditTrackedFields,
                )
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.TRASH,
                    onClick = onDelete,
                    contentDescription = "Delete exercise",
                ),
            ),
        )
    }
}

@Composable
private fun MuscleGroupSingleSelectContent(
    muscleGroups: List<MuscleGroup>,
    selectedId: String?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    onDone: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Primary muscle group"),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (muscleGroups.isEmpty()) {
                EmptyListMessage(
                    "No muscle groups yet",
                    "Add a muscle group from the Settings menu first.",
                )
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.SpacedScrollPadding),
                ) {
                    muscleGroups.forEach { group ->
                        SelectableRow(
                            title = group.name,
                            selected = group.id == selectedId,
                            onClick = { onSelect(group.id) },
                        )
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "DONE", onClick = onDone),
            ),
        )
    }
}

@Composable
private fun MuscleGroupMultiSelectContent(
    muscleGroups: List<MuscleGroup>,
    primaryMuscleGroupId: String?,
    selectedIds: Set<String>,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onDone: () -> Unit,
) {
    val selectableGroups = muscleGroups.filter { it.id != primaryMuscleGroupId }

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Secondary muscle groups"),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (selectableGroups.isEmpty()) {
                EmptyListMessage(
                    "No other muscle groups",
                    "This exercise will only be tagged with its primary muscle group.",
                )
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.SpacedScrollPadding),
                ) {
                    selectableGroups.forEach { group ->
                        SelectableRow(
                            title = group.name,
                            selected = group.id in selectedIds,
                            onClick = { onToggle(group.id) },
                        )
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "DONE", onClick = onDone),
            ),
        )
    }
}

@Composable
private fun TrackedFieldMultiSelectContent(
    selectedFields: Set<TrackedField>,
    onBack: () -> Unit,
    onToggle: (TrackedField) -> Unit,
    onDone: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Track"),
        )

        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(UiConstants.SpacedScrollPadding),
        ) {
            TrackedField.entries.forEach { field ->
                SelectableRow(
                    title = field.displayName,
                    selected = field in selectedFields,
                    onClick = { onToggle(field) },
                )
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.Text(text = "DONE", onClick = onDone),
            ),
        )
    }
}

@Composable
private fun SelectableRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        LightText(text = title, variant = LightTextVariant.Copy, modifier = Modifier.weight(1f))
        LightIcon(
            icon = if (selected) LightIcons.SELECT_ON else LightIcons.SELECT_OFF,
            size = 2f,
            contentDescription = if (selected) "Selected" else "Not selected",
        )
    }
}

@Composable
private fun EmptyListMessage(title: String, detail: String) {
    Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
        LightText(text = title, variant = LightTextVariant.Copy, lighten = true)
        LightText(
            text = detail,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun IntervalSchemeListContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onConfigure: (IntervalScheme) -> Unit,
    onRename: (IntervalScheme) -> Unit,
    onDelete: (IntervalScheme) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text("Interval Schemes"),
        )

        Column(modifier = Modifier.weight(1f)) {
            if (state.intervalSchemes.isEmpty()) {
                EmptyListMessage(
                    "No schemes yet",
                    "Tap the add button below to create one, e.g. your own Tabata variant.",
                )
            } else {
                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.DenseScrollPadding),
                ) {
                    state.intervalSchemes.forEach { scheme ->
                        IntervalSchemeRow(
                            scheme = scheme,
                            onConfigure = { onConfigure(scheme) },
                            onRename = { onRename(scheme) },
                            onDelete = { onDelete(scheme) },
                        )
                    }
                }
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ADD,
                    onClick = onAdd,
                    contentDescription = "Add interval scheme",
                ),
            ),
        )
    }
}

/** A scheme's name and work/rest/rounds summary, with a pencil to rename it and a trash icon
 * to delete it; tapping the row body itself opens its numbers for editing. */
@Composable
private fun IntervalSchemeRow(
    scheme: IntervalScheme,
    onConfigure: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onConfigure)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            LightText(text = scheme.name, variant = LightTextVariant.Copy)
            LightText(
                text = formatSchemeSubtitle(scheme),
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        LightIcon(
            icon = LightIcons.PENCIL,
            size = 2f,
            contentDescription = "Rename ${scheme.name}",
            modifier = Modifier
                .lightClickable(onClick = onRename)
                .padding(end = 16.dp),
        )
        LightIcon(
            icon = LightIcons.TRASH,
            size = 2f,
            contentDescription = "Delete ${scheme.name}",
            modifier = Modifier.lightClickable(onClick = onDelete),
        )
    }
}

private fun formatSchemeSubtitle(scheme: IntervalScheme): String {
    fun mmss(totalSeconds: Int): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }
    return "${mmss(scheme.workSeconds)} work, ${mmss(scheme.restSeconds)} rest, ${scheme.rounds} rounds"
}

@Composable
private fun IntervalSchemeConfigureContent(
    state: SettingsUiState,
    onAdjustWork: (Int) -> Unit,
    onAdjustRest: (Int) -> Unit,
    onAdjustRounds: (Int) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Cancel",
            ),
            center = LightTopBarCenter.Text(state.draftSchemeName),
        )

        Row(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            SchemeNudgeField(
                modifier = Modifier.weight(1f),
                value = state.draftSchemeWorkSeconds.toString(),
                label = "Work (sec)",
                onIncrement = { onAdjustWork(SCHEME_TIME_STEP_SEC) },
                onDecrement = { onAdjustWork(-SCHEME_TIME_STEP_SEC) },
                incrementDescription = "Increase work time",
                decrementDescription = "Decrease work time",
            )
            SchemeNudgeField(
                modifier = Modifier.weight(1f),
                value = state.draftSchemeRestSeconds.toString(),
                label = "Rest (sec)",
                onIncrement = { onAdjustRest(SCHEME_TIME_STEP_SEC) },
                onDecrement = { onAdjustRest(-SCHEME_TIME_STEP_SEC) },
                incrementDescription = "Increase rest time",
                decrementDescription = "Decrease rest time",
            )
            SchemeNudgeField(
                modifier = Modifier.weight(1f),
                value = state.draftSchemeRounds.toString(),
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
                    onClick = onSave,
                    contentDescription = "Save scheme",
                ),
            ),
        )
    }
}

@Composable
private fun SchemeNudgeField(
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
