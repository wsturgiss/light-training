package com.thelightphone.training.model

import com.thelightphone.training.backup.SessionExportStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Room-backed store for muscle groups, the exercise library, and logged
 * workout sessions. Replaces the previous in-memory
 * [MuscleGroupRepository]/[ExerciseRepository] singletons and
 * `SampleWorkoutData`.
 *
 * Every session write is mirrored to [exportStore] as a standalone file, which is what LightOS
 * actually backs up -- see [SessionExportStore] for why the database itself isn't.
 */
class TrainingRepository private constructor(
    database: TrainingDatabase,
    private val exportStore: SessionExportStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val muscleGroupDao = database.muscleGroupDao()
    private val exerciseDao = database.exerciseDao()
    private val sessionDao = database.workoutSessionDao()
    private val intervalPresetDao = database.intervalPresetDao()
    private val cardioSessionDao = database.cardioSessionDao()

    val muscleGroups: Flow<List<MuscleGroup>> =
        muscleGroupDao.observeAll().map { entities -> entities.map { it.toModel() } }

    val exercises: Flow<List<Exercise>> =
        exerciseDao.observeAll().map { entities -> entities.map { it.toModel() } }

    val intervalSchemes: Flow<List<IntervalScheme>> =
        intervalPresetDao.observeAll().map { entities -> entities.map { it.toModel() } }

    val cardioSessions: Flow<List<CardioSession>> =
        cardioSessionDao.observeAll().map { entities -> entities.map { it.toModel() } }

    /**
     * Seeds the default muscle groups/exercises the first time the app runs. Once either
     * table has any rows, this is a no-op forever after -- edits and deletions are left
     * entirely to the user, including newly-added defaults from a later app update.
     */
    suspend fun ensureSeeded() {
        if (muscleGroupDao.count() == 0) {
            defaultMuscleGroups().forEach { muscleGroupDao.insert(it.toEntity()) }
        }
        if (exerciseDao.count() == 0) {
            defaultExercises().forEach { exerciseDao.insert(it.toEntity()) }
        }
        if (intervalPresetDao.count() == 0) {
            defaultIntervalSchemes().forEach { intervalPresetDao.insert(it.toEntity()) }
        }
        syncExports()
    }

    /**
     * Brings the exported files back in line with the database: writes anything missing or
     * stale, and drops exports for sessions that no longer exist.
     *
     * This is what gets every pre-existing session into the backup -- the export files only
     * start being written from the version that introduced them, so without a reconciliation
     * pass a user's entire logged history would be invisible to backups. It also self-heals
     * after a write that failed partway, so it's worth running on every launch rather than
     * once behind a "migrated" flag. Sessions already up to date are skipped, so the cost is a
     * directory listing plus a timestamp comparison per session.
     */
    suspend fun syncExports() {
        if (exportsSynced) return

        val workouts = listSessions()
        workouts.forEach { session ->
            if (!exportStore.isUpToDate(session.id, session.updatedAt)) {
                exportStore.writeWorkout(session)
            }
        }

        val cardio = cardioSessionDao.getAll().map { it.toModel() }
        val names = exerciseNamesFor(cardio.map { it.exerciseId })
        cardio.forEach { session ->
            if (!exportStore.isUpToDate(session.id, session.updatedAt)) {
                exportStore.writeCardio(session, names.nameFor(session.exerciseId))
            }
        }

        exportStore.removeAllExcept((workouts.map { it.id } + cardio.map { it.id }).toSet())
        exportsSynced = true
    }

    @Volatile
    private var exportsSynced = false

    private suspend fun exerciseNamesFor(exerciseIds: List<String>): Map<String, String> =
        exerciseDao.getByIds(exerciseIds.distinct()).associate { it.id to it.name }

    /** Falls back to the raw id so an export is never written with a blank exercise name. */
    private fun Map<String, String>.nameFor(exerciseId: String): String =
        this[exerciseId] ?: exerciseId

    /** Re-reads the persisted session so the export reflects what is actually in the database. */
    private suspend fun exportWorkout(id: String) {
        getSession(id)?.let { exportStore.writeWorkout(it) }
    }

    private suspend fun exportCardio(id: String) {
        val session = cardioSessionDao.getById(id)?.toModel() ?: return
        val names = exerciseNamesFor(listOf(session.exerciseId))
        exportStore.writeCardio(session, names.nameFor(session.exerciseId))
    }

    // --- Muscle groups ---

    suspend fun addMuscleGroup(name: String): MuscleGroup {
        val group = MuscleGroup(id = UUID.randomUUID().toString(), name = name)
        muscleGroupDao.insert(group.toEntity())
        return group
    }

    suspend fun renameMuscleGroup(id: String, name: String) {
        muscleGroupDao.update(MuscleGroupEntity(id = id, name = name))
    }

    suspend fun removeMuscleGroup(id: String) {
        muscleGroupDao.deleteById(id)
    }

    // --- Exercises ---

    suspend fun addExercise(
        name: String,
        primaryMuscleGroupId: String,
        secondaryMuscleGroupIds: List<String>,
        trackedFields: Set<TrackedField> = emptySet(),
    ): Exercise {
        val exercise = Exercise(
            id = UUID.randomUUID().toString(),
            name = name,
            primaryMuscleGroupId = primaryMuscleGroupId,
            secondaryMuscleGroupIds = secondaryMuscleGroupIds,
            trackedFields = trackedFields,
        )
        exerciseDao.insert(exercise.toEntity())
        return exercise
    }

    /** [trackedFields] has no default -- every caller must pass the exercise's current value
     * explicitly, since this replaces the whole row and silently dropping it would erase a
     * cardio exercise's tracked fields on the next unrelated edit (e.g. a rename). */
    suspend fun updateExercise(
        id: String,
        name: String,
        primaryMuscleGroupId: String,
        secondaryMuscleGroupIds: List<String>,
        trackedFields: Set<TrackedField>,
    ) {
        exerciseDao.update(
            Exercise(
                id = id,
                name = name,
                primaryMuscleGroupId = primaryMuscleGroupId,
                secondaryMuscleGroupIds = secondaryMuscleGroupIds,
                trackedFields = trackedFields,
            ).toEntity(),
        )
    }

    suspend fun removeExercise(id: String) {
        exerciseDao.deleteById(id)
    }

    /** Returns true if any logged weight-training session or cardio session references this
     * exercise. */
    suspend fun isExerciseInUse(id: String): Boolean =
        sessionDao.countByExerciseId(id) > 0 || cardioSessionDao.countByExerciseId(id) > 0

    // --- Interval schemes ---

    suspend fun addIntervalScheme(name: String, workSeconds: Int, restSeconds: Int, rounds: Int): IntervalScheme {
        val scheme = IntervalScheme(
            id = UUID.randomUUID().toString(),
            name = name,
            workSeconds = workSeconds,
            restSeconds = restSeconds,
            rounds = rounds,
        )
        intervalPresetDao.insert(scheme.toEntity())
        return scheme
    }

    suspend fun updateIntervalScheme(id: String, name: String, workSeconds: Int, restSeconds: Int, rounds: Int) {
        intervalPresetDao.update(
            IntervalScheme(
                id = id,
                name = name,
                workSeconds = workSeconds,
                restSeconds = restSeconds,
                rounds = rounds,
            ).toEntity(),
        )
    }

    suspend fun removeIntervalScheme(id: String) {
        intervalPresetDao.deleteById(id)
    }

    // --- Cardio sessions ---

    /** Logs a completed cardio/interval result. [durationSeconds] may come from manual entry,
     * the in-app timer, or a combination of the two -- resolved by the caller before this is
     * called. [distanceKm]/[pace] are only meaningful when the exercise has those tracked fields
     * configured, but are accepted as-is here. [distanceKm] is always kilometers -- callers
     * convert from the user's display [DistanceUnit] before calling this. */
    suspend fun insertCardioSession(
        exerciseId: String,
        durationSeconds: Int,
        distanceKm: Double?,
        pace: String?,
        date: java.time.LocalDate = java.time.LocalDate.now(),
    ): CardioSession {
        val timestamp = now()
        val session = CardioSession(
            id = UUID.randomUUID().toString(),
            exerciseId = exerciseId,
            date = date,
            durationSeconds = durationSeconds,
            distanceKm = distanceKm,
            pace = pace,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        cardioSessionDao.insert(session.toEntity())
        exportCardio(session.id)
        return session
    }

    suspend fun getCardioSession(id: String): CardioSession? =
        cardioSessionDao.getById(id)?.toModel()

    /** Replaces an already-persisted cardio session's duration/distance/pace (used when
     * editing it after the fact). Id, exercise, and date are left as they were. */
    suspend fun updateCardioSession(
        id: String,
        durationSeconds: Int,
        distanceKm: Double?,
        pace: String?,
    ) {
        val existing = cardioSessionDao.getById(id) ?: return
        cardioSessionDao.update(
            existing.copy(
                durationSeconds = durationSeconds,
                distanceKm = distanceKm,
                pace = pace,
                updatedAt = now(),
            ),
        )
        exportCardio(id)
    }

    suspend fun deleteCardioSession(id: String) {
        cardioSessionDao.deleteById(id)
        exportStore.remove(id)
    }

    // --- Workout sessions ---

    suspend fun listSessions(): List<WorkoutSession> {
        val rows = sessionDao.listSessionsWithExercises()
        return rows.map { resolveSession(it) }
    }

    suspend fun getSession(id: String): WorkoutSession? {
        val row = sessionDao.getSessionWithExercises(id) ?: return null
        return resolveSession(row)
    }

    suspend fun insertSession(session: WorkoutSession) {
        val (sessionEntity, exercisesWithSets) = session.copy(updatedAt = now()).toEntities()
        sessionDao.insertFullSession(sessionEntity, exercisesWithSets)
        exportWorkout(session.id)
    }

    /** Replaces an already-persisted session's exercises/sets (e.g. after editing it). */
    suspend fun updateSession(session: WorkoutSession) {
        val (sessionEntity, exercisesWithSets) = session.copy(updatedAt = now()).toEntities()
        sessionDao.replaceFullSession(sessionEntity, exercisesWithSets)
        exportWorkout(session.id)
    }

    /**
     * Persists a new session dated [date] that duplicates [sourceId]'s exercises -- same
     * exercises in the same order, each with the same sets (reps *and* weights) -- so it can be
     * used as a template and then adjusted. Copied sets are always [SetStatus.SUGGESTED]
     * (templates until the user accepts them); the source session's set statuses are not
     * carried over. Returns the new session, or null if [sourceId] no longer exists. The copy
     * is fully independent: editing either session afterwards leaves the other untouched.
     */
    suspend fun copySession(sourceId: String, date: java.time.LocalDate = java.time.LocalDate.now()): WorkoutSession? {
        val source = getSession(sourceId) ?: return null
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = "Workout $date",
            date = date,
            createdAt = System.currentTimeMillis(),
            exercises = source.exercises.map { exercise ->
                exercise.copy(
                    sets = exercise.sets.map { set ->
                        set.copy(status = SetStatus.SUGGESTED)
                    },
                )
            },
        )
        insertSession(copy)
        return copy
    }

    /** Deletes a session and all its exercises and sets. */
    suspend fun deleteSession(id: String) {
        sessionDao.deleteSetsForSession(id)
        sessionDao.deleteExercisesForSession(id)
        sessionDao.deleteSessionById(id)
        exportStore.remove(id)
    }

    /**
     * Resolves a [WorkoutSessionWithExercises] into a [WorkoutSession] by
     * fetching the referenced [ExerciseEntity]s and current muscle groups
     * from the database, so name and muscle-group tagging always reflect the
     * latest library definitions.
     */
    private suspend fun resolveSession(row: WorkoutSessionWithExercises): WorkoutSession {
        val exerciseIds = row.exercises.map { it.exercise.exerciseId }.distinct()
        val exerciseMap = exerciseDao.getByIds(exerciseIds).associateBy { it.id }
        val muscleGroupMap = muscleGroupDao.getAll().associateBy { it.id }

        return WorkoutSession(
            id = row.session.id,
            name = row.session.name,
            date = java.time.LocalDate.parse(row.session.date),
            createdAt = row.session.createdAt,
            updatedAt = row.session.updatedAt,
            exercises = row.exercises
                .sortedBy { it.exercise.orderIndex }
                .map { exerciseWithSets ->
                    val exerciseEntity = exerciseMap[exerciseWithSets.exercise.exerciseId]
                    val primaryGroup = exerciseEntity?.let { muscleGroupMap[it.primaryMuscleGroupId] }
                    val secondaryGroups = exerciseEntity
                        ?.secondaryMuscleGroupIds
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.mapNotNull { muscleGroupMap[it] }
                        ?.map { MuscleGroup(id = it.id, name = it.name) }
                        ?: emptyList()
                    LoggedWeightExercise(
                        exerciseId = exerciseWithSets.exercise.exerciseId,
                        name = exerciseEntity?.name ?: exerciseWithSets.exercise.exerciseId,
                        muscleGroup = primaryGroup?.let { MuscleGroup(id = it.id, name = it.name) }
                            ?: MuscleGroup(id = "", name = "Unknown"),
                        secondaryMuscleGroups = secondaryGroups,
                        sets = exerciseWithSets.sets
                            .sortedBy { it.orderIndex }
                            .map {
                                WeightSet(
                                    reps = it.reps,
                                    weightKg = it.weightKg,
                                    status = setStatusFromStorage(it.status),
                                )
                            },
                    )
                },
        )
    }

    companion object {
        const val DATABASE_NAME = "training.db"

        @Volatile
        private var instance: TrainingRepository? = null

        /** Internal because [SessionExportStore] is: construct one via `trainingRepository()`. */
        internal fun getInstance(
            databaseProvider: () -> TrainingDatabase,
            exportStoreProvider: () -> SessionExportStore,
        ): TrainingRepository {
            return instance ?: synchronized(this) {
                instance ?: TrainingRepository(databaseProvider(), exportStoreProvider())
                    .also { instance = it }
            }
        }
    }
}

private fun MuscleGroupEntity.toModel(): MuscleGroup = MuscleGroup(id = id, name = name)

private fun MuscleGroup.toEntity(): MuscleGroupEntity = MuscleGroupEntity(id = id, name = name)

private fun ExerciseEntity.toModel(): Exercise = Exercise(
    id = id,
    name = name,
    primaryMuscleGroupId = primaryMuscleGroupId,
    secondaryMuscleGroupIds = secondaryMuscleGroupIds
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() },
    trackedFields = trackedFields
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { fieldName -> TrackedField.entries.firstOrNull { it.name == fieldName } }
        .toSet(),
)

private fun Exercise.toEntity(): ExerciseEntity = ExerciseEntity(
    id = id,
    name = name,
    primaryMuscleGroupId = primaryMuscleGroupId,
    secondaryMuscleGroupIds = secondaryMuscleGroupIds.joinToString(","),
    trackedFields = trackedFields.joinToString(",") { it.name },
)

private fun IntervalPresetEntity.toModel(): IntervalScheme = IntervalScheme(
    id = id,
    name = name,
    workSeconds = workSeconds,
    restSeconds = restSeconds,
    rounds = rounds,
)

private fun IntervalScheme.toEntity(): IntervalPresetEntity = IntervalPresetEntity(
    id = id,
    name = name,
    workSeconds = workSeconds,
    restSeconds = restSeconds,
    rounds = rounds,
)

private fun CardioSessionEntity.toModel(): CardioSession = CardioSession(
    id = id,
    exerciseId = exerciseId,
    date = java.time.LocalDate.parse(date),
    durationSeconds = durationSeconds,
    distanceKm = distanceKm,
    pace = pace,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun CardioSession.toEntity(): CardioSessionEntity = CardioSessionEntity(
    id = id,
    exerciseId = exerciseId,
    date = date.toString(),
    durationSeconds = durationSeconds,
    distanceKm = distanceKm,
    pace = pace,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun WorkoutSession.toEntities(): Pair<WorkoutSessionEntity, List<Pair<LoggedWeightExerciseEntity, List<WeightSetEntity>>>> {
    val sessionEntity = WorkoutSessionEntity(
        id = id,
        name = name,
        date = date.toString(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
    val exercisesWithSets = exercises.mapIndexed { exerciseIndex, exercise ->
        val exerciseEntity = LoggedWeightExerciseEntity(
            sessionId = id,
            exerciseId = exercise.exerciseId,
            orderIndex = exerciseIndex,
        )
        val setEntities = exercise.sets.mapIndexed { setIndex, set ->
            WeightSetEntity(
                loggedWeightExerciseId = 0,
                orderIndex = setIndex,
                reps = set.reps,
                weightKg = set.weightKg,
                status = set.status.name,
            )
        }
        exerciseEntity to setEntities
    }
    return sessionEntity to exercisesWithSets
}

private fun defaultMuscleGroups(): List<MuscleGroup> = listOf(
    MuscleGroup("chest", "Chest"),
    MuscleGroup("back", "Back"),
    MuscleGroup("shoulders", "Shoulders"),
    MuscleGroup("biceps", "Biceps"),
    MuscleGroup("triceps", "Triceps"),
    MuscleGroup("quads", "Quads"),
    MuscleGroup("hamstrings", "Hamstrings"),
    MuscleGroup("glutes", "Glutes"),
    MuscleGroup("calves", "Calves"),
    MuscleGroup("core", "Core"),
    MuscleGroup("cardio", "Cardio"),
    MuscleGroup("full_body", "Full Body"),
)

/** "running"'s tracked fields must stay in sync with [TrainingDatabase.MIGRATION_7_8], which
 * sets the same value for users upgrading from before distance tracking was the default
 * (migrations don't run on a fresh install). */
private fun defaultExercises(): List<Exercise> = listOf(
    Exercise(
        id = "bench-press",
        name = "Bench Press",
        primaryMuscleGroupId = "chest",
        secondaryMuscleGroupIds = listOf("triceps", "shoulders"),
    ),
    Exercise(
        id = "overhead-press",
        name = "Overhead Press",
        primaryMuscleGroupId = "shoulders",
        secondaryMuscleGroupIds = listOf("triceps"),
    ),
    Exercise(
        id = "back-squat",
        name = "Back Squat",
        primaryMuscleGroupId = "quads",
        secondaryMuscleGroupIds = listOf("glutes", "hamstrings"),
    ),
    Exercise(id = "running", name = "Running", primaryMuscleGroupId = "cardio", trackedFields = setOf(TrackedField.DISTANCE)),
    Exercise(id = "rowing", name = "Rowing", primaryMuscleGroupId = "cardio"),
    Exercise(id = "cycling", name = "Cycling", primaryMuscleGroupId = "cardio"),
    Exercise(id = "swimming", name = "Swimming", primaryMuscleGroupId = "cardio"),
    Exercise(id = "jump-rope", name = "Jump Rope", primaryMuscleGroupId = "cardio"),
)

/** Must stay in sync with [TrainingDatabase.MIGRATION_3_4], which seeds the same rows for
 * users upgrading from before schemes existed (migrations don't run on a fresh install). */
private fun defaultIntervalSchemes(): List<IntervalScheme> = listOf(
    IntervalScheme(id = "tabata", name = "Tabata", workSeconds = 20, restSeconds = 10, rounds = 8),
    IntervalScheme(id = "nordic-4x4", name = "Nordic 4x4", workSeconds = 240, restSeconds = 180, rounds = 4),
    IntervalScheme(id = "hiit-30-30", name = "HIIT 30/30", workSeconds = 30, restSeconds = 30, rounds = 10),
)


private fun setStatusFromStorage(raw: String): SetStatus =
    SetStatus.entries.firstOrNull { it.name == raw } ?: SetStatus.LOGGED
