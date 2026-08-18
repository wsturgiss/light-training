# light-training

## Unify new-workout creation with editing + reordering + delete workout (option B)
- [x] Add `suspend fun deleteSession(id: String)` to `TrainingRepository` (uses existing dao `deleteSetsForSession`/`deleteExercisesForSession`/`deleteSessionById`).
- [x] Add workout-level delete support + confirmation in `SessionDetailViewModel` (plus new modes + mutators).
- [x] Add exercise reordering + per-exercise delete:
  - `moveExerciseUp(index)`, `moveExerciseDown(index)`, `deleteExercise(index)` in the ViewModel.
  - Persist via existing `updateSession` (order from list position).
- [x] Add `ManageExercises` and `ConfirmDeleteWorkout` to `SessionDetailMode`.
- [x] Implement `ManageExercisesContent` (gear destination): list of exercises with per-row UP (↑) DOWN (↓) TRASH (🗑) for reordering/deleting that exercise. Bottom bar has TRASH for whole-workout delete.
- [x] Implement `ConfirmDeleteWorkoutContent`: confirmation screen with DENY/ACCEPT icons on bottom bar.
- [x] Update `SessionOverviewContent` bottom bar to **ADD + SETTINGS** (exactly 2; gear opens manage). Enhance empty state for 0 exercises.
- [x] Unify flows:
  - Changed `HomeScreen.startWorkout()`: create minimal `WorkoutSession` (uuid, name, today's date, empty exercises), `insertSession`, `navigateTo(SessionDetailScreen(id))` directly. No result callback.
  - Removed `onWorkoutFinished` + result path entirely.
  - Deleted `WorkoutInProgressScreen.kt` (whole file) and all duplicated wizard code.
- [x] Graceful empty-session handling (no auto-prune):
  - Updated `SessionRow` to label empties nicely ("Empty workout" on left + date).
  - Enhanced empty state in unified overview (0 exercises: "No exercises added yet" + "Tap the add button...").
  - Empty sessions persist in list until explicit TRASH (in manage).
- [x] Updated navigation / onScreenShow reload paths (home list stays fresh after create/edit/delete).
- [ ] Clean up: dead code, any now-unused imports, possible light renaming of "Session*" composables.
- [ ] Manual verification (you build in Android Studio): start workout (goes straight to edit view), add/reorder/delete exercises, delete whole workout, empty sessions in home list, persistence across restarts.

# existing / lower priority
- [ ] on the workout edit page, it seems like we are wasting space in the bottom toolbar.  I think it might be taller than the toolbar is in other places.  Also, it seems like there is blank space on the right and maybe the left that we could use.  Maybe there is padding there? Can we make the edit page have more space?  It feels a bit cramped
#WAIT TO DO THIS STUFF BELOW
- [ ] Muscle group volume/frequency rollups (e.g. "chest trained 2x this
      week") once enough session data exists.
- [ ] Cardio support — sessions/exercises that track duration/distance
      instead of (or alongside) reps+weight.
