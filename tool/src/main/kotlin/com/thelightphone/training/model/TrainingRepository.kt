package com.thelightphone.training.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Room-backed store for muscle groups, the exercise library, and logged
 * workout sessions. Replaces the previous in-memory
 * [MuscleGroupRepository]/[ExerciseRepository] singletons and
 * `SampleWorkoutData`.
 */
class TrainingRepository private constructor(database: TrainingDatabase) {
    private val muscleGroupDao = database.muscleGroupDao()
    private val exerciseDao = database.exerciseDao()
    private val sessionDao = database.workoutSessionDao()

    val muscleGroups: Flow<List<MuscleGroup>> =
        muscleGroupDao.observeAll().map { entities -> entities.map { it.toModel() } }

    val exercises: Flow<List<Exercise>> =
        exerciseDao.observeAll().map { entities -> entities.map { it.toModel() } }

    /** Seeds the default muscle groups/exercises the first time the app runs. */
    suspend fun ensureSeeded() {
        if (muscleGroupDao.count() == 0) {
            defaultMuscleGroups().forEach { muscleGroupDao.insert(it.toEntity()) }
        }
        if (exerciseDao.count() == 0) {
            defaultExercises().forEach { exerciseDao.insert(it.toEntity()) }
        }
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
    ): Exercise {
        val exercise = Exercise(
            id = UUID.randomUUID().toString(),
            name = name,
            primaryMuscleGroupId = primaryMuscleGroupId,
            secondaryMuscleGroupIds = secondaryMuscleGroupIds,
        )
        exerciseDao.insert(exercise.toEntity())
        return exercise
    }

    suspend fun updateExercise(
        id: String,
        name: String,
        primaryMuscleGroupId: String,
        secondaryMuscleGroupIds: List<String>,
    ) {
        exerciseDao.update(
            Exercise(
                id = id,
                name = name,
                primaryMuscleGroupId = primaryMuscleGroupId,
                secondaryMuscleGroupIds = secondaryMuscleGroupIds,
            ).toEntity(),
        )
    }

    suspend fun removeExercise(id: String) {
        exerciseDao.deleteById(id)
    }

    // --- Workout sessions ---

    suspend fun listSessions(): List<WorkoutSession> =
        sessionDao.listSessionsWithExercises().map { it.toModel() }

    suspend fun getSession(id: String): WorkoutSession? =
        sessionDao.getSessionWithExercises(id)?.toModel()

    suspend fun insertSession(session: WorkoutSession) {
        val (sessionEntity, exercisesWithSets) = session.toEntities()
        sessionDao.insertFullSession(sessionEntity, exercisesWithSets)
    }

    /** Replaces an already-persisted session's exercises/sets (e.g. after editing it). */
    suspend fun updateSession(session: WorkoutSession) {
        val (sessionEntity, exercisesWithSets) = session.toEntities()
        sessionDao.replaceFullSession(sessionEntity, exercisesWithSets)
    }

    companion object {
        const val DATABASE_NAME = "training.db"

        @Volatile
        private var instance: TrainingRepository? = null

        fun getInstance(databaseProvider: () -> TrainingDatabase): TrainingRepository {
            return instance ?: synchronized(this) {
                instance ?: TrainingRepository(databaseProvider()).also { instance = it }
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
)

private fun Exercise.toEntity(): ExerciseEntity = ExerciseEntity(
    id = id,
    name = name,
    primaryMuscleGroupId = primaryMuscleGroupId,
    secondaryMuscleGroupIds = secondaryMuscleGroupIds.joinToString(","),
)

private fun WorkoutSession.toEntities(): Pair<WorkoutSessionEntity, List<Pair<LoggedExerciseEntity, List<ExerciseSetEntity>>>> {
    val sessionEntity = WorkoutSessionEntity(
        id = id,
        name = name,
        date = date.toString(),
    )
    val exercisesWithSets = exercises.mapIndexed { exerciseIndex, exercise ->
        val exerciseEntity = LoggedExerciseEntity(
            sessionId = id,
            name = exercise.name,
            muscleGroupId = exercise.muscleGroup.id,
            muscleGroupName = exercise.muscleGroup.name,
            orderIndex = exerciseIndex,
        )
        val setEntities = exercise.sets.mapIndexed { setIndex, set ->
            ExerciseSetEntity(
                loggedExerciseId = 0,
                orderIndex = setIndex,
                reps = set.reps,
                weightKg = set.weightKg,
            )
        }
        exerciseEntity to setEntities
    }
    return sessionEntity to exercisesWithSets
}

private fun WorkoutSessionWithExercises.toModel(): WorkoutSession = WorkoutSession(
    id = session.id,
    name = session.name,
    date = java.time.LocalDate.parse(session.date),
    exercises = exercises
        .sortedBy { it.exercise.orderIndex }
        .map { exerciseWithSets ->
            LoggedExercise(
                name = exerciseWithSets.exercise.name,
                muscleGroup = MuscleGroup(
                    id = exerciseWithSets.exercise.muscleGroupId,
                    name = exerciseWithSets.exercise.muscleGroupName,
                ),
                sets = exerciseWithSets.sets
                    .sortedBy { it.orderIndex }
                    .map { ExerciseSet(reps = it.reps, weightKg = it.weightKg) },
            )
        },
)

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
)
