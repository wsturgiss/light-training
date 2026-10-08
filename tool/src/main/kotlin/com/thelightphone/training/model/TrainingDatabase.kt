package com.thelightphone.training.model

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        MuscleGroupEntity::class,
        ExerciseEntity::class,
        WorkoutSessionEntity::class,
        LoggedWeightExerciseEntity::class,
        WeightSetEntity::class,
        IntervalPresetEntity::class,
        CardioSessionEntity::class,
    ],
    version = 11,
    // Writes a JSON snapshot of this schema to src/main/assets/schemas/ on every build (see
    // room.schemaLocation in build.gradle.kts). Commit it with every version bump.
    //
    // Schema changes must use Room auto-migrations, declared here as
    //   autoMigrations = [AutoMigration(from = 11, to = 12, spec = ...)]
    // Hand-written Migration objects are not an option: they have to be passed to the database
    // builder, and the SDK's buildDatabase() does not accept them
    // (https://github.com/lightphone/light-sdk/issues/227). Arbitrary SQL can still run in an
    // AutoMigrationSpec's onPostMigrate(), but only after Room's own schema change -- so a
    // "copy data out, then drop the table" change has to be split across two versions.
    //
    // Auto-migrations read the snapshots at compile time, which is why they live under
    // src/main/assets: Light's builder only copies src/main/{kotlin,java,res,assets} from this
    // module, so a snapshot anywhere else would be missing from the release build.
    exportSchema = true,
)
abstract class TrainingDatabase : RoomDatabase() {
    internal abstract fun muscleGroupDao(): MuscleGroupDao
    internal abstract fun exerciseDao(): ExerciseDao
    internal abstract fun workoutSessionDao(): WorkoutSessionDao
    internal abstract fun intervalPresetDao(): IntervalPresetDao
    internal abstract fun cardioSessionDao(): CardioSessionDao
}
