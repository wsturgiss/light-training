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
}

@Dao
internal interface WorkoutSessionDao {
    @Insert
    suspend fun insertSession(session: WorkoutSessionEntity)

    @Insert
    suspend fun insertLoggedExercise(exercise: LoggedExerciseEntity): Long

    @Insert
    suspend fun insertSets(sets: List<ExerciseSetEntity>)

    @Transaction
    @Query("SELECT * FROM workout_sessions ORDER BY date DESC, id DESC")
    suspend fun listSessionsWithExercises(): List<WorkoutSessionWithExercises>

    @Transaction
    @Query("SELECT * FROM workout_sessions WHERE id = :id")
    suspend fun getSessionWithExercises(id: String): WorkoutSessionWithExercises?

    @Query("SELECT COUNT(*) FROM workout_sessions")
    suspend fun count(): Int

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
        exercises: List<Pair<LoggedExerciseEntity, List<ExerciseSetEntity>>>,
    ) {
        insertSession(session)
        exercises.forEach { (exercise, sets) ->
            val exerciseId = insertLoggedExercise(exercise)
            if (sets.isNotEmpty()) {
                insertSets(sets.map { it.copy(loggedExerciseId = exerciseId) })
            }
        }
    }

    /** Replaces an existing session's graph (used when editing a past session). */
    @Transaction
    suspend fun replaceFullSession(
        session: WorkoutSessionEntity,
        exercises: List<Pair<LoggedExerciseEntity, List<ExerciseSetEntity>>>,
    ) {
        deleteSetsForSession(session.id)
        deleteExercisesForSession(session.id)
        deleteSessionById(session.id)
        insertFullSession(session, exercises)
    }
}
