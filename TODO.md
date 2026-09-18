# light-training

## Suggested vs logged strength sets
- [x] `SetStatus` (LOGGED | SUGGESTED) on `WeightSet` + Room `exercise_sets.status` column;
      `MIGRATION_9_10` (default existing rows to LOGGED); version 10; all `buildDatabase` call
      sites updated.
- [x] `TrainingRepository.copySession`: copied sets always land as SUGGESTED (templates).
- [x] Manual add-set mid-workout creates LOGGED sets; editing a suggested set preserves SUGGESTED
      (no auto-promote); accept/confirm promotes SUGGESTED → LOGGED.
- [x] Session overview UI: **Logged** section (full contrast) then **Suggested** (muted/faded);
      section headers hidden when empty; suggested rows have ACCEPT + TRASH; tap row opens wheel
      editor (no separate edit button).
- [x] Compiles clean (`./gradlew :tool:compileDebugKotlin`).
- [ ] Manual on-device verification: copy previous workout → sets appear under Suggested (muted)
      with accept+trash; accept moves set into Logged; edit a suggested set (tap row) and confirm
      it stays Suggested; add a set mid-workout → appears under Logged; trash works in both
      sections; empty sections hide their headers; existing (pre-migration) sessions still show
      sets as Logged.

## Copy a previous workout when starting a strength session
- [x] `TrainingRepository.copySession(sourceId, date)`: duplicates a session's exercises (same
      order) and sets (reps *and* weights) into a new session dated today; returns null if the
      source is gone.
- [x] New `StrengthStartScreen` (+ `StrengthStartChoice` result): shown after picking Strength on
      `WorkoutStyleScreen`. Offers "Start empty workout" plus a list of past strength sessions
      (muscle groups, date, exercise/set counts). Sessions with no exercises are filtered out.
      Reports the choice back via `goBack` so it pops before `SessionDetailScreen` goes on.
- [x] `HomeScreen`: STRENGTH now routes through `StrengthStartScreen`;
      `HomeScreenViewModel.createAndInsertCopyOf` falls back to an empty session if the source
      was deleted underneath the picker.
- [x] Compiles clean (`./gradlew :tool:compileDebugKotlin`).
- [ ] Manual on-device verification: pick Strength → start empty still works;
      copy a session and confirm exercises/sets/weights/order match; back out of the new workout
      and land on home (not the picker); copy shows as a separate session dated today, and
      editing it leaves the original untouched; picker with zero logged sessions shows the hint.

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
- [x] Clean up: dead code, any now-unused imports, possible light renaming of "Session*" composables.
- [x] Manual verification (you build in Android Studio): start workout (goes straight to edit view), add/reorder/delete exercises, delete whole workout, empty sessions in home list, persistence across restarts.

# existing / lower priority
- [x] NOTE: filed as an upstream issue against the SDK.  on the workout edit page, it seems like we are wasting space in the bottom toolbar.  I think it might be taller than the toolbar is in other places.  Also, it seems like there is blank space on the right and maybe the left that we could use.  Maybe there is padding there? Can we make the edit page have more space?  It feels a bit cramped
- [x] pace could be calculated if we have duration and distance...maybe users don't need to edit it 
- [ ] notes on exercises?

# on hold
#WAIT TO DO THIS STUFF BELOW
- [ ] Muscle group volume/frequency rollups (e.g. "chest trained 2x this
      week") once enough session data exists.
- [ ] play with picker wheel selected size...it currently snaps up to the selected size.  Try out a smoother transition to the selected size

