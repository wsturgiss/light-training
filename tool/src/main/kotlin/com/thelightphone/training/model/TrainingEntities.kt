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
    /** Comma-joined [TrackedField] names; empty string when there are none. */
    @ColumnInfo(name = "tracked_fields", defaultValue = "''") val trackedFields: String = "",
)

@Entity(tableName = "workout_sessions")
internal data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** ISO-8601 date string, e.g. "2024-01-31". */
    val date: String,
    /** Epoch millis, used to order same-day sessions by time; 0 for rows created before this
     * column existed. */
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0,
)

@Entity(tableName = "logged_exercises")
internal data class LoggedWeightExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
)

@Entity(tableName = "exercise_sets")
internal data class WeightSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "logged_exercise_id") val loggedWeightExerciseId: Long,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    val reps: Int,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
)

internal data class LoggedWeightExerciseWithSets(
    @Embedded val exercise: LoggedWeightExerciseEntity,
    @Relation(
        entity = WeightSetEntity::class,
        parentColumn = "id",
        entityColumn = "logged_exercise_id",
    )
    val sets: List<WeightSetEntity>,
)

@Entity(tableName = "interval_presets")
internal data class IntervalPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "work_seconds") val workSeconds: Int,
    @ColumnInfo(name = "rest_seconds") val restSeconds: Int,
    val rounds: Int,
)

/** A single logged cardio or interval session (steady-state or interval), independent of the
 * weight-training [WorkoutSessionEntity] graph. [durationSeconds] is user-entered and/or
 * filled in from the in-app timer -- see [TrainingRepository.insertCardioSession]. */
@Entity(tableName = "cardio_sessions")
internal data class CardioSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    /** ISO-8601 date string, e.g. "2024-01-31". */
    val date: String,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Int,
    val distance: String?,
    val pace: String?,
    /** Epoch millis, used to order same-day sessions by time; 0 for rows created before this
     * column existed. */
    @ColumnInfo(name = "created_at", defaultValue = "0") val createdAt: Long = 0,
)

internal data class WorkoutSessionWithExercises(
    @Embedded val session: WorkoutSessionEntity,
    @Relation(
        entity = LoggedWeightExerciseEntity::class,
        parentColumn = "id",
        entityColumn = "session_id",
    )
    val exercises: List<LoggedWeightExerciseWithSets>,
)
