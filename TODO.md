# light-training

## Aim

A Light tool for logging strength training workouts (and later cardio),
built on the light-sdk. A workout session is made up of exercises, each
tagged with the muscle group(s) it trains, and each exercise is made up of
sets with reps and the weight used. Tracking muscle group per exercise is
meant to eventually let us answer questions like "how much have I trained
chest this week/month" by rolling up volume/frequency across sessions.

## Status

First pass of the home screen layout is in place, using sample/in-memory
data (no persistence yet):

- `tool/src/main/kotlin/com/thelightphone/training/model/Training.kt` —
  `MuscleGroup`, `ExerciseSet` (reps + weight), `LoggedExercise`
  (name + muscle group + sets), `WorkoutSession` (name/date/exercises),
  plus `SampleWorkoutData` fake sessions.
- `tool/src/main/kotlin/com/thelightphone/training/HomeScreen.kt` — initial
  screen: top bar, empty state vs. scrollable session history list, bottom
  bar "START WORKOUT" button (currently a no-op stub).
- `ToolEntryPoint.kt` moved into the new `com.thelightphone.training`
  package (renamed from the stock `com.thelightphone.sample`).
- `lighttool.toml` updated: app id `com.thelightphone.training`, label
  "Training".

## Decisions so far

- Data model: Session -> Exercises -> Sets (not free-text notes).
- Each exercise records reps *and* weight per set (nullable weight for
  bodyweight/cardio-style entries).
- Each exercise records a `MuscleGroup` for later volume/frequency rollups.
- Home screen leads with a "Start Workout" action + empty state; a session
  history list appears once sessions exist.
- Persistence: not wired up yet — currently using in-memory sample data.
  When we get to it, follow the light-amp/authenticator pattern: Room
  `@Entity`/`@Dao`/`@Database`, built via
  `lightContext.buildDatabase(...)`, wrapped in a repository singleton
  created in the screen and passed into the ViewModel.
- Navigation: light-sdk uses a custom stack router
  (`navigateTo(screenFactory, resultCallback?)` / `goBack(result?)`), not
  Compose Navigation.

## Next steps

- [x] Move from kgs to a configurable unit of weight (currently just kg or lb)
      — `WeightUnit` (KG/LB) added to `model/Training.kt` with `toKg`/`fromKg`
      conversion; sets are still stored in kg internally. Preference is
      persisted via DataStore (`model/TrainingPreferences.kt`,
      `weight_unit` key) and toggled from a button in the home screen's top
      bar (cycles KG ⇄ LB); the in-progress workout flow reads/writes
      weights in whatever unit is currently selected.
- [x] Build out the "start workout" flow: an in-progress workout screen
      where the user picks/adds exercises and logs sets (reps + weight)
      as they go. — `WorkoutInProgressScreen.kt`: mode-switch screen
      (`WorkoutMode`: exercise list → add exercise name → muscle group +
      sets for that exercise → add set reps → add set weight) built with
      `LightTextInputEditor` for text/number entry and a tap-to-cycle row
      for muscle group, following the weather example's patterns. "START
      WORKOUT" on the home screen now navigates here and prepends the
      finished `WorkoutSession` to the in-memory session list on return
      (still no persistence — lost on process death until Room lands).
      Exercise names are still free-typed (no picker yet, see below).
- [x] Change the home screen to have a Plus button (look in icons) to take us to the start a workout page
      — `LightTopBar`'s `leftButton` now uses `LightIcons.ADD`, sharing the
      same `startWorkout()` nav call as the bottom bar's "START WORKOUT"
      button.
- [x] Change the home screen to have a gear button (look in icons) to take us to the settings page.  The settings page should let us create/edit muscle groups and exercises.  Exercises should have one primary muscle group and one or more secondary muscle groups.
      — `model/MuscleGroup.kt`: `MuscleGroup` is now a plain `data class(id, name)`
      (no longer an enum) backed by `MuscleGroupRepository`, an in-memory
      singleton `StateFlow<List<MuscleGroup>>` seeded with the previous fixed
      set (Chest, Back, ... Full Body) but now add/rename/remove-able.
      `model/Exercise.kt`: new `Exercise(id, name, primaryMuscleGroupId,
      secondaryMuscleGroupIds)` + `ExerciseRepository` (same in-memory
      singleton pattern), seeded with Bench Press/Overhead Press/Back Squat.
      `SettingsScreen.kt`: mode-switch screen (menu → muscle group list
      (add/rename via `LightTextInputEditor`, delete via trailing icon,
      blocked if a group is used as an exercise's primary) → exercise list
      (add/edit name → pick primary muscle group from a single-select list →
      pick secondary muscle groups from a multi-select list with
      `SELECT_ON`/`SELECT_OFF` checkmarks) → delete). `HomeScreen.kt`'s
      `LightTopBar` right button is now the gear (`LightIcons.SETTINGS`)
      navigating here; the weight-unit toggle moved into the bottom bar
      alongside "START WORKOUT" to make room.
      Still TODO: wiring the workout-in-progress exercise step to pick from
      this library instead of free-typing a name (see below).
	-Note from QA: when testing the exercise editing, if you click an exercise, you can edit its name, then you click submit and it takes you to select the primary muscle group.  I think I'd like to change this flow.  I think instead of an X next to the exercise, I'd like to see an edit icon, maybe a pencil.  Then when we click it, it shows the name, primary muscle group, and secondary muscle groups.  Then each of those has an edit icon.  I can click each one, and edit just that one.
      — Done: `SettingsScreen.kt`'s exercise list row now shows a pencil icon
      (no more inline delete "X") that opens a new `ExerciseDetail` screen
      showing name/primary/secondary, each with its own pencil to edit just
      that field (`ExerciseDetailEditName` /
      `ExerciseDetailEditPrimaryGroup` / `ExerciseDetailEditSecondaryGroups`).
      Each field-level edit saves immediately via `ExerciseRepository.update`
      and returns to the detail view; back from the detail view returns to
      the exercise list. Deleting an exercise moved from the list row into
      the detail view's bottom bar (delete icon). The original
      name -> primary -> secondary flow is kept only for adding a brand new
      exercise.
- [x] change the delete icon on exercise to be the trash can icon
      — `ExerciseDetailContent`'s bottom-bar delete button in
      `SettingsScreen.kt` now uses `LightIcons.TRASH` instead of
      `LightIcons.DELETE`. Muscle groups' `DeletableRow` delete icon is
      unchanged (still `LightIcons.DELETE`), since this item only asked
      about exercises.
- [x] change the edit icon on exercises to be more in line with the general row they are editing.  So like on the exercises list, the app displays the exercise name and below it the muscle groups.  I'd like the edit symbol to be vertically centered on the container or row the name and muscle groups for the exercise sit in.  Ask me if this doesn't make sense.  Similarly, on the exercise edit page, the icon seems to be too high, like maybe its not centered on the row
      — `SettingsScreen.kt`: both `EditableRow` (exercise list) and
      `DetailFieldRow` (exercise detail page) now set
      `verticalAlignment = Alignment.CenterVertically` on their `Row`, so the
      pencil icon is centered against the (possibly two-line) name/subtitle
      text instead of aligning to the top.
- [x] when editing muscle groups for an exercise, on primary, if I select one, it takes me back to the previous page automatically but on secondary I have to select DONE.  I understand its trying to save clicks but it is not a cohesive feeling UX.  I'd like back, cancel, confirm, etc to generally follow the same flow and be placed in the same locations.  
	- In general, I'd like to see page icons (that control the whole current page) on the bottom, either bottom left, center, or bottom right
      — `MuscleGroupSingleSelectContent` (used for both the add-exercise
      primary-group step and the exercise-detail primary-group edit) now
      matches the multi-select screen: tapping a row just updates the
      selection in place, and a `LightBottomBar` "DONE" button confirms and
      navigates on, instead of auto-advancing/auto-saving on tap.
      `SettingsViewModel.selectPrimaryMuscleGroup` /
      `selectExerciseDetailPrimaryMuscleGroup` now only update draft state;
      new `confirmPrimaryGroup` / `confirmExerciseDetailPrimaryGroup`
      validate a primary group is chosen, persist (for the detail-edit
      case), and move back to the previous screen — mirroring
      `toggleSecondaryMuscleGroup` + `doneEditExerciseDetailSecondaryGroups`.
- [x] change muscle groups UX to be more similar to exercises.
      — Muscle group list rows now use the same `EditableRow` (pencil icon)
      as exercises, opening a new `MuscleGroupDetail` screen (name field with
      its own pencil to rename via `MuscleGroupDetailEditName`, delete moved
      into the bottom bar as `LightIcons.TRASH`), instead of inline
      delete-on-the-row. `SettingsViewModel`: `startEditMuscleGroup` now
      opens the detail view; `startEditMuscleGroupDetailName` /
      `removeMuscleGroupFromDetail` added to mirror the exercise-detail
      pattern. The old `DeletableRow` composable (unused now) was removed.
- [x] for any row we are editing, if there is only one property on that row, it doesn't need a page that shows the rows properties.  Muscle groups for example.  Since the only actions are editing the string or deleting it, just give me an edit icon and a trash icon.  This might break the pattern of the editable row. Maybe this is a new archetype... not sure how to handle it
      — New archetype `SingleFieldEditableRow` in `SettingsScreen.kt`: a row
      with the label plus trailing pencil and trash icons, no detail page.
      Muscle group list rows now use this instead of `LightEditableRow` +
      `MuscleGroupDetail`. `SettingsMode.MuscleGroupDetail` /
      `MuscleGroupDetailEditName` removed; tapping the pencil now goes
      straight to the rename text field (`MuscleGroupName` mode, reusing the
      existing add/rename text editor), and tapping the trash deletes
      directly from the list (`SettingsViewModel.removeMuscleGroup`,
      replacing `removeMuscleGroupFromDetail`). Exercises are unaffected
      (still use their multi-field detail page) since they have more than
      one editable property.
- [x] move the lb / kg toggle into the settings page
      — `HomeScreen.kt`'s bottom bar no longer has a weight-unit button (and
      the top bar's left ADD button was removed too, see next item);
      `HomeScreenViewModel` no longer owns `weightUnit`/DataStore at all.
      `SettingsScreen.kt`: `SettingsViewModel` now takes the DataStore,
      loads/persists `WeightUnit` the same way the home screen used to, and
      exposes it on `SettingsUiState.weightUnit`. The `Menu` screen gained a
      third row ("Weight Unit", showing "KG"/"LB") via `SettingsMenuRow`'s
      new optional trailing `value` param; tapping it cycles the unit
      in-place (no navigation), same one-tap toggle behavior as before.
- [x] on the settings page, put in an edit icon next to editable things (muscle groups and exercises)
      — Already satisfied by earlier work: muscle group rows use
      `SingleFieldEditableRow` (pencil + trash) and exercise rows use
      `LightEditableRow` (pencil), both in `SettingsScreen.kt`. No changes
      needed for this item.
- [x] On the home screen, move the + icon for starting a training session to the bottom center and remove the two dupe create session buttons
      — `HomeScreen.kt`: removed the top bar's left ADD button and the
      bottom bar's "START WORKOUT" text button (the two dupes); the bottom
      bar now has a single `LightIcons.ADD` icon button, which
      `LightBottomBar` automatically centers when there's only one item.
      Top bar is now just the "Training" title + gear/settings button on
      the right.
- [x] In the workout-in-progress flow, replace free-typing an exercise name
      with picking from the `ExerciseRepository` library (auto-filling its
      primary/secondary muscle groups instead of the tap-to-cycle picker),
      so exercises aren't re-typed from scratch each time.
      — `WorkoutInProgressScreen.kt`: new `WorkoutMode.PickExercise` step
      between "add exercise" and "add sets": lists `ExerciseRepository`
      entries (name + primary muscle group), tapping one
      (`selectLibraryExercise`) auto-fills `draftMuscleGroup` (primary) and
      new `draftSecondaryMuscleGroups`, then jumps straight to
      `AddExerciseSets` — no more tap-to-cycle for library exercises; that
      screen now shows the primary/secondary groups as read-only text when
      `draftFromLibrary` is true. The bottom-bar ADD icon on the picker
      still opens the old free-text name entry (`startTypeNewExercise` ->
      `AddExerciseName`) for one-off exercises not in the library, which
      keeps the original tap-to-cycle muscle group picker in `AddExerciseSets`.
- [x] Wire up Room persistence for sessions/exercises/sets, replacing
      `SampleWorkoutData`.
      — New `model/TrainingEntities.kt` (`MuscleGroupEntity`, `ExerciseEntity`
      with secondary group ids stored as a comma-joined string,
      `WorkoutSessionEntity`, `LoggedExerciseEntity`, `ExerciseSetEntity`,
      plus `@Relation` POJOs `LoggedExerciseWithSets` /
      `WorkoutSessionWithExercises`), `model/TrainingDao.kt`
      (`MuscleGroupDao`/`ExerciseDao` expose `Flow<List<Entity>>` for reactive
      lists; `WorkoutSessionDao` has a `@Transaction` `insertFullSession`
      default method that inserts a session row plus its logged
      exercises/sets atomically, and `listSessionsWithExercises()` for
      reading everything back), `model/TrainingDatabase.kt` (Room database,
      version 1). `model/TrainingRepository.kt` is the new singleton
      (`TrainingRepository.getInstance { lightContext.buildDatabase(...) }`,
      same pattern as the authenticator example's `TotpAccountRepository`),
      replacing the old in-memory `MuscleGroupRepository`/`ExerciseRepository`
      objects and `SampleWorkoutData` (all removed); it exposes
      `muscleGroups`/`exercises` as `Flow`s mapped from Room, suspend
      add/rename/remove/update functions, `ensureSeeded()` (seeds the same
      default muscle groups/exercises as before on first run, idempotent —
      checked via a row count), and `listSessions()` /
      `insertSession(session)`. `SettingsScreen.kt` and
      `WorkoutInProgressScreen.kt` now take a `TrainingRepository` alongside
      the DataStore and call it instead of the old singletons (writes
      wrapped in `viewModelScope.launch(Dispatchers.IO)`).
      `HomeScreen.kt`'s `HomeScreenViewModel` now loads sessions from
      `repository.listSessions()` on init/`onScreenShow`, and
      `onWorkoutFinished` persists the finished session via
      `repository.insertSession` before reloading — sessions now survive
      process death.
- [x] Session detail screen (tap a row in the history list to see full
      exercise/set breakdown) — `SessionRow`'s `lightClickable` is already
      stubbed for this.
	- also allow going into old sessions and editing them, adding sets, etc
      — `SessionDetailScreen.kt`: tapping a `SessionRow` on the home screen
      navigates here with the session's id (`HomeScreen.kt`'s
      `onSessionClick` -> `navigateTo { SessionDetailScreen(it, session.id) }`).
      Loads the full session via `TrainingRepository.getSession(id)` and
      shows date + each exercise's muscle group and sets (reps @ weight in
      the current unit, or "bodyweight"). Each set has a trash icon to
      delete it; a "+ Add set" row per exercise opens a two-step reps ->
      weight text entry (mirroring the workout-in-progress flow's
      `LightTextInputEditor` steps). Both add and delete update the
      in-memory session state immediately and persist via
      `TrainingRepository.updateSession` ->
      `WorkoutSessionDao.replaceFullSession` (deletes and re-inserts the
      session's logged exercises/sets), so edits to past sessions survive
      process death. Editing the exercise list itself (adding/removing
      whole exercises from a past session, not just sets) isn't supported
      yet — only adding/removing sets on existing exercises.
- [x] add the ability to add new exercises to past workouts.  I think that maybe the new workout flow and edit workout flow should be very similar, maybe even the same.
- [x] use the same + icon on add set within a workout as you do to add an exercise
	- also review any other plus icons we use and if there are any others, lets go over it and decide what to do together
      — Found one straggler: `SessionDetailScreen.kt`'s past-session "+ Add
      set" row per exercise was plain text; it now uses a
      `LightIcon(LightIcons.ADD)` + "Add set" label, matching the trash-icon
      row above it. Every other add action in the tool (home screen's start
      workout, workout-in-progress's add exercise/add set, session detail's
      add exercise/add set-in-new-exercise, settings' add muscle
      group/exercise) already used `LightIcons.ADD` as an icon button, so no
      other spots needed changes.
- [ ] In the workout edit page, when we show an exercise, we currently only show the primary muscle group, can we append the secondary muscle groups too?
#WAIT TO DO THIS STUFF BELOW
- [ ] Muscle group volume/frequency rollups (e.g. "chest trained 2x this
      week") once enough session data exists.
- [ ] Cardio support — sessions/exercises that track duration/distance
      instead of (or alongside) reps+weight.
