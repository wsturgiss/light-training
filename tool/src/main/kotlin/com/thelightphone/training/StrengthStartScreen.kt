package com.thelightphone.training

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
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
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingRepository
import com.thelightphone.training.model.WorkoutSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val startDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/** How the user wants their new strength session seeded, chosen on [StrengthStartScreen]. */
sealed interface StrengthStartChoice {
    /** Start from nothing and add exercises by hand. */
    data object Empty : StrengthStartChoice

    /** Duplicate the exercises and sets of the already-logged session with this id. */
    data class CopyOf(val sessionId: String) : StrengthStartChoice
}

class StrengthStartViewModel(
    private val repository: TrainingRepository,
) : LightViewModel<StrengthStartChoice>() {

    /** Past sessions offered as templates, newest first. */
    val templates = MutableStateFlow<List<WorkoutSession>>(emptyList())
    val loading = MutableStateFlow(true)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureSeeded()
            // Sessions with no exercises would copy to nothing, so they'd be indistinguishable
            // from starting empty -- leave them out rather than offer a no-op template.
            templates.value = repository.listSessions().filter { it.exercises.isNotEmpty() }
            loading.value = false
        }
    }
}

/**
 * Shown after picking Strength Training on [WorkoutStyleScreen]: start an empty session, or seed
 * the new one from a session already logged.
 *
 * Like [WorkoutStyleScreen], this reports the choice back to its caller (typically the home
 * screen) via [goBack] rather than acting on it here, so this screen pops off the back stack
 * before the session detail screen goes on -- backing out of the new workout lands on the home
 * feed instead of on this picker.
 */
class StrengthStartScreen(
    sealedActivity: SealedLightActivity,
) : LightScreen<StrengthStartChoice, StrengthStartViewModel>(sealedActivity) {

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

    override val viewModelClass: Class<StrengthStartViewModel>
        get() = StrengthStartViewModel::class.java

    override fun createViewModel(): StrengthStartViewModel = StrengthStartViewModel(repository)

    @Composable
    override fun Content() {
        val templates by viewModel.templates.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack(null) },
                        contentDescription = "Cancel",
                    ),
                    center = LightTopBarCenter.Text("Strength Training"),
                )

                LightScrollView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(UiConstants.SpacedScrollPadding),
                ) {
                    StartOptionRow(
                        title = "Start empty workout",
                        subtitle = "Add exercises as you go",
                        onClick = { goBack(StrengthStartChoice.Empty) },
                    )

                    // Nothing is rendered for the template section while loading, so the empty
                    // hint below never flashes up before the real list arrives.
                    if (!loading) {
                        if (templates.isEmpty()) {
                            LightText(
                                text = "Once you've logged a strength session, it'll show up here to copy.",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        } else {
                            LightText(
                                text = "Or copy a previous workout",
                                variant = LightTextVariant.Detail,
                                lighten = true,
                                modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
                            )
                            templates.forEach { session ->
                                TemplateRow(
                                    session = session,
                                    onClick = { goBack(StrengthStartChoice.CopyOf(session.id)) },
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
private fun StartOptionRow(
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
            icon = LightIcons.ADD,
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
private fun TemplateRow(session: WorkoutSession, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            LightText(
                text = session.muscleGroups.joinToString(", ") { it.name },
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
            LightText(
                text = session.date.format(startDateFormatter),
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
        LightText(
            text = templateSummary(session),
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun templateSummary(session: WorkoutSession): String {
    val exercises = session.exercises.size
    val sets = session.totalSets
    return "$exercises ${plural(exercises, "exercise")} · $sets ${plural(sets, "set")}"
}

private fun plural(count: Int, singular: String): String =
    if (count == 1) singular else "${singular}s"
