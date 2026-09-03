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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
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
import com.thelightphone.training.model.CardioSession
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingRepository
import com.thelightphone.training.model.WorkoutSession
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.util.UUID

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

/** Combines logged weight-training sessions and cardio sessions into one home-screen feed,
 * sorted together by date and then by [createdAt] -- so two sessions logged on the same day
 * order by when they actually happened, not by an arbitrary DB/merge order. There's no
 * separate persistence model for this, it's purely a display-time merge of
 * [TrainingRepository.listSessions] and [TrainingRepository.cardioSessions]. */
sealed interface LoggedActivity {
    val date: LocalDate
    val createdAt: Long

    data class Weight(val session: WorkoutSession) : LoggedActivity {
        override val date: LocalDate get() = session.date
        override val createdAt: Long get() = session.createdAt
    }

    data class Cardio(val session: CardioSession, val exerciseName: String) : LoggedActivity {
        override val date: LocalDate get() = session.date
        override val createdAt: Long get() = session.createdAt
    }
}

class HomeScreenViewModel(private val repository: TrainingRepository) : LightViewModel<Unit>() {

    val activities = MutableStateFlow<List<LoggedActivity>>(emptyList())

    init {
        reloadSessions()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reloadSessions()
    }

    suspend fun createAndInsertNewSession(): String {
        repository.ensureSeeded()
        val session = WorkoutSession(
            id = UUID.randomUUID().toString(),
            name = "Workout ${LocalDate.now()}",
            date = LocalDate.now(),
            exercises = emptyList(),
        )
        repository.insertSession(session)
        return session.id
    }

    private fun reloadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureSeeded()
            val weightSessions = repository.listSessions().map { LoggedActivity.Weight(it) }
            val exerciseNames = repository.exercises.first().associate { it.id to it.name }
            val cardioSessions = repository.cardioSessions.first().map { cardioSession ->
                LoggedActivity.Cardio(cardioSession, exerciseNames[cardioSession.exerciseId] ?: "Cardio")
            }
            activities.value = (weightSessions + cardioSessions)
                .sortedWith(compareByDescending<LoggedActivity> { it.date }.thenByDescending { it.createdAt })
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

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

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel = HomeScreenViewModel(repository)

    @Composable
    override fun Content() {
        val activities by viewModel.activities.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text("Training Sessions"),
                )

                Column(modifier = Modifier.weight(1f)) {
                    if (activities.isEmpty()) {
                        EmptyState()
                    } else {
                        ActivityList(
                            activities = activities,
                            onSessionClick = ::openSessionDetail,
                            onCardioSessionClick = ::openCardioSessionDetail,
                        )
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            contentDescription = "Start workout",
                            onClick = {
                                navigateTo(
                                    screenFactory = { WorkoutStyleScreen(it) },
                                    resultCallback = { choice ->
                                        when (choice) {
                                            WorkoutStyleChoice.STRENGTH -> {
                                                viewModel.viewModelScope.launch {
                                                    val sessionId = withContext(Dispatchers.IO) {
                                                        viewModel.createAndInsertNewSession()
                                                    }
                                                    openSessionDetail(WorkoutSession(
                                                        id = sessionId,
                                                        name = "",
                                                        date = LocalDate.now(),
                                                        exercises = emptyList(),
                                                    ))
                                                }
                                            }
                                            WorkoutStyleChoice.CARDIO -> {
                                                navigateTo(screenFactory = { CardioWorkoutScreen(it) })
                                            }
                                            WorkoutStyleChoice.INTERVAL -> {
                                                navigateTo(screenFactory = { IntervalWorkoutScreen(it) })
                                            }
                                        }
                                    },
                                )
                            },
                        ),
                        LightBarButton.LightIcon(
                            icon = LightIcons.SETTINGS,
                            contentDescription = "Settings",
                            onClick = { openSettings() },
                        ),
                    ),
                )
            }
        }
    }

    private fun openSettings() {
        navigateTo(screenFactory = { SettingsScreen(it) })
    }

    private fun openSessionDetail(session: WorkoutSession) {
        navigateTo(screenFactory = { SessionDetailScreen(it, session.id) })
    }

    private fun openCardioSessionDetail(session: CardioSession) {
        navigateTo(screenFactory = { CardioSessionDetailScreen(it, session.id) })
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        LightText(
            text = "No workouts logged yet",
            variant = LightTextVariant.Copy,
            lighten = true,
        )
        LightText(
            text = "Tap Start Workout below to log your first session.",
            variant = LightTextVariant.Detail,
            lighten = true,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ActivityList(
    activities: List<LoggedActivity>,
    onSessionClick: (WorkoutSession) -> Unit,
    onCardioSessionClick: (CardioSession) -> Unit,
) {
    LightScrollView(
        modifier = Modifier
            .fillMaxSize()
            .padding(UiConstants.SpacedScrollPadding),
    ) {
        activities.forEach { activity ->
            when (activity) {
                is LoggedActivity.Weight -> SessionRow(activity.session, onClick = { onSessionClick(activity.session) })
                is LoggedActivity.Cardio -> CardioSessionRow(
                    activity,
                    onClick = { onCardioSessionClick(activity.session) },
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: WorkoutSession, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            val muscleGroupText = if (session.exercises.isEmpty()) {
                "Empty workout"
            } else {
                session.muscleGroups.joinToString(", ") { it.name }
            }
            LightText(
                text = muscleGroupText,
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
            LightText(
                text = session.date.format(dateFormatter),
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
    }
}

@Composable
private fun CardioSessionRow(activity: LoggedActivity.Cardio, onClick: () -> Unit) {
    val session = activity.session
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            val details = buildList {
                add(formatDuration(session.durationSeconds))
                session.distance?.let { add(it) }
                session.pace?.let { add(it) }
            }.joinToString(" · ")
            LightText(
                text = "${activity.exerciseName} — $details",
                variant = LightTextVariant.Copy,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
            )
            LightText(
                text = session.date.format(dateFormatter),
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
    }
}
