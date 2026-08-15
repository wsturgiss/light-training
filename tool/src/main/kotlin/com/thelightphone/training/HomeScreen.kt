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
import com.thelightphone.training.model.TrainingDatabase
import com.thelightphone.training.model.TrainingRepository
import com.thelightphone.training.model.WorkoutSession
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

class HomeScreenViewModel(private val repository: TrainingRepository) : LightViewModel<Unit>() {

    val sessions = MutableStateFlow<List<WorkoutSession>>(emptyList())

    init {
        reloadSessions()
    }

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        reloadSessions()
    }

    fun onWorkoutFinished(session: WorkoutSession?) {
        if (session == null) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertSession(session)
            reloadSessions()
        }
    }

    private fun reloadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureSeeded()
            sessions.value = repository.listSessions()
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    private val repository = TrainingRepository.getInstance {
        lightContext.buildDatabase(TrainingDatabase::class.java, TrainingRepository.DATABASE_NAME)
    }

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel = HomeScreenViewModel(repository)

    @Composable
    override fun Content() {
        val sessions by viewModel.sessions.collectAsState()
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
                    if (sessions.isEmpty()) {
                        EmptyState()
                    } else {
                        SessionList(sessions, onSessionClick = ::openSessionDetail)
                    }
                }

                LightBottomBar(
                    items = listOf(
                        LightBarButton.LightIcon(
                            icon = LightIcons.ADD,
                            contentDescription = "Start workout",
                            onClick = { startWorkout() },
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

    private fun startWorkout() {
        navigateTo(screenFactory = { WorkoutInProgressScreen(it) }) { session ->
            viewModel.onWorkoutFinished(session)
        }
    }

    private fun openSettings() {
        navigateTo(screenFactory = { SettingsScreen(it) })
    }

    private fun openSessionDetail(session: WorkoutSession) {
        navigateTo(screenFactory = { SessionDetailScreen(it, session.id) })
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
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
private fun SessionList(sessions: List<WorkoutSession>, onSessionClick: (WorkoutSession) -> Unit) {
    LightScrollView(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 16.dp),
    ) {
        sessions.forEach { session ->
            SessionRow(session, onClick = { onSessionClick(session) })
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
            LightText(
                text = session.muscleGroups.joinToString(", ") { it.name },
                variant = LightTextVariant.Copy,
                modifier = Modifier.weight(0.5f),
            )
            LightText(
                text = session.date.format(dateFormatter),
                variant = LightTextVariant.Detail,
                lighten = true,
            )
        }
    }
}
