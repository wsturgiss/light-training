package com.thelightphone.training.model

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 7,
    exportSchema = false,
)
abstract class TrainingDatabase : RoomDatabase() {
    internal abstract fun muscleGroupDao(): MuscleGroupDao
    internal abstract fun exerciseDao(): ExerciseDao
    internal abstract fun workoutSessionDao(): WorkoutSessionDao
    internal abstract fun intervalPresetDao(): IntervalPresetDao
    internal abstract fun cardioSessionDao(): CardioSessionDao

    companion object {
        /**
         * Adds the interval_presets table for user-defined interval schemes. Every other
         * table -- including all logged weight-training sessions -- is left untouched.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `interval_presets` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `work_seconds` INTEGER NOT NULL,
                        `rest_seconds` INTEGER NOT NULL,
                        `rounds` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Seeds three popular interval schemes (Tabata, Nordic 4x4, HIIT 30/30) for users
         * upgrading from before schemes existed. Ids/values here must stay in sync with
         * [defaultIntervalSchemes] in [TrainingRepository], which seeds the same rows for a
         * fresh install (migrations don't run when a database is created from scratch).
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "INSERT INTO `interval_presets` (`id`, `name`, `work_seconds`, `rest_seconds`, `rounds`) VALUES " +
                        "('tabata', 'Tabata', 20, 10, 8)",
                )
                db.execSQL(
                    "INSERT INTO `interval_presets` (`id`, `name`, `work_seconds`, `rest_seconds`, `rounds`) VALUES " +
                        "('nordic-4x4', 'Nordic 4x4', 240, 180, 4)",
                )
                db.execSQL(
                    "INSERT INTO `interval_presets` (`id`, `name`, `work_seconds`, `rest_seconds`, `rounds`) VALUES " +
                        "('hiit-30-30', 'HIIT 30/30', 30, 30, 10)",
                )
            }
        }

        /**
         * Adds the tracked_fields column to exercises (which optional data points, like
         * distance or pace, a cardio exercise offers when logging it). Existing rows default
         * to an empty string -- no tracked fields -- so nothing changes for them.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `tracked_fields` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Adds the cardio_sessions table for logged steady-state/interval cardio results. */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cardio_sessions` (
                        `id` TEXT NOT NULL,
                        `exercise_id` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `duration_seconds` INTEGER NOT NULL,
                        `distance` TEXT,
                        `pace` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Adds a created_at (epoch millis) column to workout_sessions and cardio_sessions so
         * same-day sessions can be ordered by time logged instead of arbitrarily. Existing
         * rows default to 0 -- there's no way to recover their original time -- so they'll
         * sort as if logged at midnight relative to newer, timestamped rows on the same day.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `workout_sessions` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `cardio_sessions` ADD COLUMN `created_at` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
