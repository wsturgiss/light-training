package com.thelightphone.training.model

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "muscle_groups")
internal data class MuscleGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "exercises")
internal data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "primary_muscle_group_id") val primaryMuscleGroupId: String,
    /** Comma-joined muscle group ids; empty string when there are none. */
    @ColumnInfo(name = "secondary_muscle_group_ids") val secondaryMuscleGroupIds: String,
)

@Entity(tableName = "workout_sessions")
internal data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** ISO-8601 date string, e.g. "2024-01-31". */
    val date: String,
)

@Entity(tableName = "logged_exercises")
internal data class LoggedExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val name: String,
    @ColumnInfo(name = "muscle_group_id") val muscleGroupId: String,
    @ColumnInfo(name = "muscle_group_name") val muscleGroupName: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
)

@Entity(tableName = "exercise_sets")
internal data class ExerciseSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "logged_exercise_id") val loggedExerciseId: Long,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    val reps: Int,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
)

internal data class LoggedExerciseWithSets(
    @Embedded val exercise: LoggedExerciseEntity,
    @Relation(
        entity = ExerciseSetEntity::class,
        parentColumn = "id",
        entityColumn = "logged_exercise_id",
    )
    val sets: List<ExerciseSetEntity>,
)

internal data class WorkoutSessionWithExercises(
    @Embedded val session: WorkoutSessionEntity,
    @Relation(
        entity = LoggedExerciseEntity::class,
        parentColumn = "id",
        entityColumn = "session_id",
    )
    val exercises: List<LoggedExerciseWithSets>,
)
