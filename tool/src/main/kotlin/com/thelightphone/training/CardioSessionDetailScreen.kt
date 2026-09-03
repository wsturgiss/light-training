package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightEditableRow
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.training.model.CardioSession
import com.thelightphone.training.model.DistanceUnit
import com.thelightphone.training.model.TrackedField
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingPreferences
import com.thelightphone.training.model.TrainingRepository
import com.thelightphone.training.model.distanceUnitFromStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val cardioDetailDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Which sub-screen of the cardio detail flow is currently shown. Mirrors the shape of
 * [SessionDetailMode] in SessionDetailScreen.kt (Overview / gear-accessed Manage / confirm
 * delete) so editing a cardio session feels consistent with editing a strength workout. */
enum class CardioDetailMode { OVERVIEW, MANAGE, EDIT, CONFIRM_DELETE }

/** Which field is being edited via [LightTextInputEditor] on the edit screen; null means none. */
enum class CardioEditField { DURATION, DISTANCE, PACE }

data class CardioSessionDetailUiState(
    val session: CardioSession? = null,
    val exerciseName: String = "Cardio",
    val trackedFields: Set<TrackedField> = emptySet(),
    val loading: Boolean = true,
    val mode: CardioDetailMode = CardioDetailMode.OVERVIEW,
    val editingField: CardioEditField? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.KM,
    val draftDurationSeconds: Int? = null,
    val draftDistanceTenths: Int? = null,
    val draftPace: String = "",
) {
    val isManaging: Boolean get() = mode == CardioDetailMode.MANAGE
    val isEditing: Boolean get() = mode == CardioDetailMode.EDIT
    val isConfirmingDelete: Boolean get() = mode == CardioDetailMode.CONFIRM_DELETE
}

class CardioSessionDetailViewModel(
    private val sessionId: String,
    private val dataStore: DataStore<Preferences>,
    private val repository: TrainingRepository,
) : LightViewModel<Unit>() {

    private val _uiState = MutableStateFlow(CardioSessionDetailUiState())
    val uiState: StateFlow<CardioSessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val session = repository.getCardioSession(sessionId)
            val exercise = session?.let { s -> repository.exercises.first().find { it.id == s.exerciseId } }
            val unit = distanceUnitFromStorage(dataStore.data.first()[TrainingPreferences.DISTANCE_UNIT])
            _uiState.update {
                it.copy(
                    session = session,
                    exerciseName = exercise?.name ?: "Cardio",
                    trackedFields = exercise?.trackedFields.orEmpty(),
                    distanceUnit = unit,
                    loading = false,
                )
            }
        }
    }

    fun startManage() {
        _uiState.update { it.copy(mode = CardioDetailMode.MANAGE) }
    }

    fun cancelManage() {
        _uiState.update { it.copy(mode = CardioDetailMode.OVERVIEW) }
    }

    fun startEdit() {
        val session = _uiState.value.session ?: return
        val unit = _uiState.value.distanceUnit
        _uiState.update {
            it.copy(
                mode = CardioDetailMode.EDIT,
                draftDurationSeconds = session.durationSeconds,
                draftDistanceTenths = session.distanceKm?.let { km -> (unit.fromKm(km) * 10).roundToInt() },
                draftPace = session.pace.orEmpty(),
            )
        }
    }

    fun cancelEdit() {
        _uiState.update { it.copy(mode = CardioDetailMode.MANAGE, editingField = null) }
    }

    fun startEditField(field: CardioEditField) {
        _uiState.update { it.copy(editingField = field) }
    }

    fun cancelEditField() {
        _uiState.update { it.copy(editingField = null) }
    }

    fun updateDraftDuration(seconds: Int) {
        _uiState.update { it.copy(draftDurationSeconds = seconds) }
    }

    fun updateDraftDistanceTenths(tenths: Int) {
        _uiState.update { it.copy(draftDistanceTenths = tenths) }
    }

    /** Handles the PACE text editor; DURATION and DISTANCE are set directly via
     * [updateDraftDuration]/[updateDraftDistanceTenths] from their nudge editors instead. */
    fun submitEditField(rawValue: CharSequence) {
        val field = _uiState.value.editingField ?: return
        val value = rawValue.toString()
        _uiState.update {
            when (field) {
                CardioEditField.DURATION, CardioEditField.DISTANCE -> it
                CardioEditField.PACE -> it.copy(draftPace = value)
            }.copy(editingField = null)
        }
    }

    suspend fun saveEdit() {
        val state = _uiState.value
        val duration = state.draftDurationSeconds ?: return
        val distanceKm = state.draftDistanceTenths?.let { state.distanceUnit.toKm(it / 10.0) }
        val pace = state.draftPace.trim().ifBlank { null }
        withContext(Dispatchers.IO) {
            repository.updateCardioSession(id = sessionId, durationSeconds = duration, distanceKm = distanceKm, pace = pace)
        }
        _uiState.update {
            it.copy(
                mode = CardioDetailMode.OVERVIEW,
                session = it.session?.copy(durationSeconds = duration, distanceKm = distanceKm, pace = pace),
            )
        }
    }

    fun startDelete() {
        _uiState.update { it.copy(mode = CardioDetailMode.CONFIRM_DELETE) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(mode = CardioDetailMode.MANAGE) }
    }

    suspend fun confirmDelete() {
        withContext(Dispatchers.IO) {
            repository.deleteCardioSession(sessionId)
        }
    }
}

class CardioSessionDetailScreen(
    sealedActivity: SealedLightActivity,
    private val sessionId: String,
) : LightScreen<Unit, CardioSessionDetailViewModel>(sealedActivity) {

    override val viewModelClass: Class<CardioSessionDetailViewModel>
        get() = CardioSessionDetailViewModel::class.java

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

    override fun createViewModel(): CardioSessionDetailViewModel =
        CardioSessionDetailViewModel(sessionId, lightContext.dataStore, repository)

    @Composable
    override fun Content() {
        val state by viewModel.uiState.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            when {
                state.isConfirmingDelete -> ConfirmDeleteCardioSessionContent(
                    exerciseName = state.exerciseName,
                    onCancel = viewModel::cancelDelete,
                    onConfirm = {
                        viewModel.viewModelScope.launch {
                            viewModel.confirmDelete()
                            goBack(Unit)
                        }
                    },
                )

                state.isManaging -> ManageCardioSessionContent(
                    onBack = viewModel::cancelManage,
                    onEdit = viewModel::startEdit,
                    onDelete = viewModel::startDelete,
                )

                state.isEditing -> {
                    val fieldBeingEdited = state.editingField
                    if (fieldBeingEdited == null) {
                        EditCardioSessionContent(
                            state = state,
                            onEditDuration = { viewModel.startEditField(CardioEditField.DURATION) },
                            onEditField = { field ->
                                viewModel.startEditField(
                                    if (field == TrackedField.DISTANCE) CardioEditField.DISTANCE else CardioEditField.PACE,
                                )
                            },
                            onBack = viewModel::cancelEdit,
                            onSave = { viewModel.viewModelScope.launch { viewModel.saveEdit() } },
                        )
                    } else if (fieldBeingEdited == CardioEditField.DURATION) {
                        DurationNudgeEntryContent(
                            title = "Duration",
                            seconds = state.draftDurationSeconds ?: 0,
                            onSecondsChange = viewModel::updateDraftDuration,
                            onDone = viewModel::cancelEditField,
                            onBack = viewModel::cancelEditField,
                        )
                    } else if (fieldBeingEdited == CardioEditField.DISTANCE) {
                        DistanceNudgeEntryContent(
                            title = "Distance (${state.distanceUnit.displayName})",
                            tenths = state.draftDistanceTenths ?: 0,
                            onTenthsChange = viewModel::updateDraftDistanceTenths,
                            onDone = viewModel::cancelEditField,
                            onBack = viewModel::cancelEditField,
                        )
                    } else {
                        val fieldTextFieldState = remember(fieldBeingEdited) { TextFieldState(state.draftPace) }
                        LightTextInputEditor(
                            title = TrackedField.PACE.displayName,
                            state = fieldTextFieldState,
                            keyboardOptionsFlow = rememberKeyboardOptions(),
                            onSubmit = viewModel::submitEditField,
                            onBack = viewModel::cancelEditField,
                            singleLine = true,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                else -> CardioSessionOverviewContent(
                    state = state,
                    onBack = { goBack(Unit) },
                    onManage = viewModel::startManage,
                )
            }
        }
    }
}

@Composable
private fun CardioSessionOverviewContent(
    state: CardioSessionDetailUiState,
    onBack: () -> Unit,
    onManage: () -> Unit,
) {
    val session = state.session

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
            center = LightTopBarCenter.Text(state.exerciseName),
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
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 16.dp),
                ) {
                    LightText(
                        text = session.date.format(cardioDetailDateFormatter),
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 16.dp),
                    )
                    DetailRow(label = "Duration", value = formatDuration(session.durationSeconds))
                    session.distanceKm?.let {
                        DetailRow(
                            label = "Distance",
                            value = "${formatDistance(state.distanceUnit.fromKm(it))} ${state.distanceUnit.displayName}",
                        )
                    }
                    session.pace?.let { DetailRow(label = "Pace", value = it) }
                }
            }
        }

        if (session != null) {
            LightBottomBar(
                items = listOf(
                    LightBarButton.LightIcon(
                        icon = LightIcons.SETTINGS,
                        onClick = onManage,
                        contentDescription = "Manage session",
                    ),
                ),
            )
        }
    }
}

/** Reached via the gear button on the overview -- same pattern as SessionDetailScreen's
 * ManageExercisesContent -- and offers Edit and Delete. */
@Composable
private fun ManageCardioSessionContent(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
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
            center = LightTopBarCenter.Text("Manage session"),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 32.dp, vertical = 8.dp),
        ) {
            LightEditableRow(
                label = "Edit session",
                onClick = onEdit,
                editable = false,
            )
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.TRASH,
                    onClick = onDelete,
                    contentDescription = "Delete session",
                ),
            ),
        )
    }
}

@Composable
private fun EditCardioSessionContent(
    state: CardioSessionDetailUiState,
    onEditDuration: () -> Unit,
    onEditField: (TrackedField) -> Unit,
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
            center = LightTopBarCenter.Text(state.exerciseName),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 32.dp, vertical = 16.dp),
        ) {
            LightEditableRow(
                superscript = "Duration",
                label = state.draftDurationSeconds?.let { formatDuration(it) } ?: "Not set",
                onClick = onEditDuration,
            )
            if (TrackedField.DISTANCE in state.trackedFields) {
                LightEditableRow(
                    superscript = "Distance",
                    label = state.draftDistanceTenths?.let { "%.1f %s".format(it / 10.0, state.distanceUnit.displayName) }
                        ?: "Not set",
                    onClick = { onEditField(TrackedField.DISTANCE) },
                )
            }
            if (TrackedField.PACE in state.trackedFields) {
                LightEditableRow(
                    superscript = "Pace",
                    label = state.draftPace.ifBlank { "Not set" },
                    onClick = { onEditField(TrackedField.PACE) },
                )
            }
        }

        LightBottomBar(
            items = listOf(
                LightBarButton.LightIcon(
                    icon = LightIcons.ACCEPT,
                    onClick = if (state.draftDurationSeconds != null) onSave else null,
                    contentDescription = "Save changes",
                ),
            ),
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        LightText(
            text = label,
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        LightText(text = value, variant = LightTextVariant.Copy)
    }
}

@Composable
private fun ConfirmDeleteCardioSessionContent(
    exerciseName: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LightThemeTokens.colors.background),
    ) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(
                icon = LightIcons.BACK,
                onClick = onCancel,
                contentDescription = "Cancel",
            ),
            center = LightTopBarCenter.Text("Delete session"),
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = "Are you sure you want to delete this $exerciseName session? This cannot be undone.",
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
                    onClick = onConfirm,
                    contentDescription = "Delete",
                ),
            ),
        )
    }
}
