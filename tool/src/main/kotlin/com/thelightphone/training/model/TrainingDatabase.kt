package com.thelightphone.training.model

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MuscleGroupEntity::class,
        ExerciseEntity::class,
        WorkoutSessionEntity::class,
        LoggedExerciseEntity::class,
        ExerciseSetEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class TrainingDatabase : RoomDatabase() {
    internal abstract fun muscleGroupDao(): MuscleGroupDao
    internal abstract fun exerciseDao(): ExerciseDao
    internal abstract fun workoutSessionDao(): WorkoutSessionDao
}
