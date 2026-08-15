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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.thelightphone.training.model.ExerciseSet
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
import java.time.format.DateTimeFormatter

private val detailDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Which sub-screen of the session detail flow is currently shown. */
sealed class SessionDetailMode {
    /** Full breakdown of the session's exercises and sets. */
    data object Overview : SessionDetailMode()

    /** Text entry for the rep count of a new set being added to an exercise. */
    data class AddSetReps(val exerciseIndex: Int) : SessionDetailMode()

    /** Text entry for the weight of a new set (in the user's preferred unit). */
    data class AddSetWeight(val exerciseIndex: Int) : SessionDetailMode()
}

data class SessionDetailUiState(
    val mode: SessionDetailMode = SessionDetailMode.Overview,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val session: WorkoutSession? = null,
    val loading: Boolean = true,
    val draftReps: Int? = null,
    val errorModal: String? = null,
)

class SessionDetailViewModel(
    private val sessionId: String,
    private val dataStore: DataStore<Preferences>,
    private val repository: TrainingRepository,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        reload()
    }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            val unit = weightUnitFromStorage(dataStore.data.first()[TrainingPreferences.WEIGHT_UNIT])
            val session = repository.getSession(sessionId)
            _uiState.update { it.copy(weightUnit = unit, session = session, loading = false) }
        }
    }

    fun startAddSet(exerciseIndex: Int) {
        _uiState.update { it.copy(mode = SessionDetailMode.AddSetReps(exerciseIndex), draftReps = null) }
    }

    fun cancelAddSet() {
        _uiState.update { it.copy(mode = SessionDetailMode.Overview) }
    }

    fun submitReps(rawReps: CharSequence) {
        val reps = rawReps.toString().trim().toIntOrNull()
        if (reps == null || reps <= 0) {
            _uiState.update { it.copy(errorModal = "Please enter a whole number of reps.") }
            return
        }
        val exerciseIndex = (_uiState.value.mode as? SessionDetailMode.AddSetReps)?.exerciseIndex ?: return
        _uiState.update { it.copy(draftReps = reps, mode = SessionDetailMode.AddSetWeight(exerciseIndex)) }
    }

    fun submitWeight(rawWeight: CharSequence) {
        val exerciseIndex = (_uiState.value.mode as? SessionDetailMode.AddSetWeight)?.exerciseIndex ?: return
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
        val session = _uiState.value.session ?: return
        val newSet = ExerciseSet(reps = reps, weightKg = weightKg)
        val updatedSession = session.copy(
            exercises = session.exercises.mapIndexed { index, exercise ->
                if (index == exerciseIndex) exercise.copy(sets = exercise.sets + newSet) else exercise
            },
        )
        _uiState.update {
            it.copy(session = updatedSession, draftReps = null, mode = SessionDetailMode.Overview)
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
        val repsFieldState = rememberTextFieldState("")
        val weightFieldState = rememberTextFieldState("")
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
                    )

                    is SessionDetailMode.AddSetReps -> LightTextInputEditor(
                        title = "Reps",
                        state = repsFieldState,
                        keyboardOptionsFlow = keyboardOptionsFlow,
                        onSubmit = viewModel::submitReps,
                        onBack = viewModel::cancelAddSet,
                        singleLine = true,
                        modifier = Modifier.fillMaxSize(),
                    )

                    is SessionDetailMode.AddSetWeight -> LightTextInputEditor(
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
private fun SessionOverviewContent(
    state: SessionDetailUiState,
    onBack: () -> Unit,
    onAddSet: (Int) -> Unit,
    onDeleteSet: (Int, Int) -> Unit,
) {
    val session = state.session

    Column(modifier = Modifier.fillMaxSize()) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onBack,
                contentDescription = "Back",
            ),
            center = LightTopBarCenter.Text(session?.name ?: "Workout"),
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
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                ) {
                    LightText(
                        text = session.date.format(detailDateFormatter),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )

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
                                text = exercise.muscleGroup.name,
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
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                                    .padding(top = 8.dp),
                            ) {
                                LightText(
                                    text = "+ Add set",
                                    variant = LightTextVariant.Detail,
                                    lighten = true,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatWeight(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
