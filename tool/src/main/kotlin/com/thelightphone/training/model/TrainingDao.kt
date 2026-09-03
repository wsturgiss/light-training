package com.thelightphone.training.model

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MuscleGroupDao {
    @Query("SELECT * FROM muscle_groups ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<MuscleGroupEntity>>

    @Insert
    suspend fun insert(group: MuscleGroupEntity)

    @Update
    suspend fun update(group: MuscleGroupEntity)

    @Query("DELETE FROM muscle_groups WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM muscle_groups")
    suspend fun count(): Int

    @Query("SELECT * FROM muscle_groups")
    suspend fun getAll(): List<MuscleGroupEntity>
}

@Dao
internal interface ExerciseDao {
    @Query("SELECT * FROM exercises ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Insert
    suspend fun insert(exercise: ExerciseEntity)

    @Update
    suspend fun update(exercise: ExerciseEntity)

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int

    @Query("SELECT * FROM exercises WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<ExerciseEntity>
}

@Dao
internal interface IntervalPresetDao {
    @Query("SELECT * FROM interval_presets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<IntervalPresetEntity>>

    @Insert
    suspend fun insert(preset: IntervalPresetEntity)

    @Update
    suspend fun update(preset: IntervalPresetEntity)

    @Query("DELETE FROM interval_presets WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM interval_presets")
    suspend fun count(): Int
}

@Dao
internal interface CardioSessionDao {
    @Query("SELECT * FROM cardio_sessions ORDER BY date DESC, created_at DESC, id DESC")
    fun observeAll(): Flow<List<CardioSessionEntity>>

    @Insert
    suspend fun insert(session: CardioSessionEntity)

    @Update
    suspend fun update(session: CardioSessionEntity)

    @Query("SELECT * FROM cardio_sessions WHERE id = :id")
    suspend fun getById(id: String): CardioSessionEntity?

    @Query("DELETE FROM cardio_sessions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM cardio_sessions WHERE exercise_id = :exerciseId")
    suspend fun countByExerciseId(exerciseId: String): Int
}

@Dao
internal interface WorkoutSessionDao {
    @Insert
    suspend fun insertSession(session: WorkoutSessionEntity)

    @Insert
    suspend fun insertLoggedWeightExercise(exercise: LoggedWeightExerciseEntity): Long

    @Insert
    suspend fun insertSets(sets: List<WeightSetEntity>)

    @Transaction
    @Query("SELECT * FROM workout_sessions ORDER BY date DESC, created_at DESC, id DESC")
    suspend fun listSessionsWithExercises(): List<WorkoutSessionWithExercises>

    @Transaction
    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun getSessionWithExercises(id: String): WorkoutSessionWithExercises?

    @Query("SELECT COUNT(*) FROM workout_sessions")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM logged_exercises WHERE exercise_id = :exerciseId")
    suspend fun countByExerciseId(exerciseId: String): Int

    @Query("DELETE FROM exercise_sets WHERE logged_exercise_id IN (SELECT id FROM logged_exercises WHERE session_id = :sessionId)")
    suspend fun deleteSetsForSession(sessionId: String)

    @Query("DELETE FROM logged_exercises WHERE session_id = :sessionId")
    suspend fun deleteExercisesForSession(sessionId: String)

    @Query("DELETE FROM workout_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    /** Inserts a full session graph (session row, its logged exercises, and their sets)
     * in a single transaction. */
    @Transaction
    suspend fun insertFullSession(
        session: WorkoutSessionEntity,
        exercises: List<Pair<LoggedWeightExerciseEntity, List<WeightSetEntity>>>,
    ) {
        insertSession(session)
        exercises.forEach { (exercise, sets) ->
            val exerciseId = insertLoggedWeightExercise(exercise)
            if (sets.isNotEmpty()) {
                insertSets(sets.map { it.copy(loggedWeightExerciseId = exerciseId) })
            }
        }
    }

    /** Replaces an existing session's graph (used when editing a past session). */
    @Transaction
    suspend fun replaceFullSession(
        session: WorkoutSessionEntity,
        exercises: List<Pair<LoggedWeightExerciseEntity, List<WeightSetEntity>>>,
    ) {
        deleteSetsForSession(session.id)
        deleteExercisesForSession(session.id)
        deleteSessionById(session.id)
        insertFullSession(session, exercises)
    }
}
