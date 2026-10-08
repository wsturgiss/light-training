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
    version = 11,
    // Writes a JSON snapshot of this schema to tool/schemas/ on every build (see
    // room.schemaLocation in build.gradle.kts). Light's sandbox allows no dependency that can
    // open SQLite off-device -- no androidx.test runner, no androidx.sqlite, no Robolectric --
    // so Room migrations cannot be tested here. The snapshots are the next best thing: they
    // make the schema Room *expects* a committed file, so bumping the version produces a diff
    // showing exactly what a migration has to produce.
    exportSchema = true,
)
abstract class TrainingDatabase : RoomDatabase() {
    internal abstract fun muscleGroupDao(): MuscleGroupDao
    internal abstract fun exerciseDao(): ExerciseDao
    internal abstract fun workoutSessionDao(): WorkoutSessionDao
    internal abstract fun intervalPresetDao(): IntervalPresetDao
    internal abstract fun cardioSessionDao(): CardioSessionDao

    companion object {
        /** Must match [DistanceUnit]'s conversion factor. */
        private const val KM_PER_MILE = 1.609344

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
                    "INSERT OR IGNORE INTO `interval_presets` (`id`, `name`, `work_seconds`, `rest_seconds`, `rounds`) VALUES " +
                        "('tabata', 'Tabata', 20, 10, 8)",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `interval_presets` (`id`, `name`, `work_seconds`, `rest_seconds`, `rounds`) VALUES " +
                        "('nordic-4x4', 'Nordic 4x4', 240, 180, 4)",
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `interval_presets` (`id`, `name`, `work_seconds`, `rest_seconds`, `rounds`) VALUES " +
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

        /**
         * Makes distance a default tracked field for the "running" exercise, for users
         * upgrading from before distance tracking was the default (migrations don't run on a
         * fresh install -- see [TrainingRepository.defaultExercises]). Only touches the
         * "running" row, and only if the user hasn't already customized its tracked fields.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE `exercises` SET `tracked_fields` = 'DISTANCE' WHERE `id` = 'running' AND `tracked_fields` = ''",
                )
            }
        }

        /**
         * Replaces cardio_sessions' free-text `distance` column with a numeric `distance_km`
         * one, so distance can be displayed/entered in the user's preferred unit (km or mi)
         * instead of whatever the user happened to type. Existing values are best-effort
         * parsed: a leading number is read out of the old text, treated as miles if it
         * mentions "mi", otherwise as km (the only unit the app supported before this
         * migration). Values that don't parse are dropped -- there's no reliable way to
         * recover their original meaning.
         */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE `cardio_sessions_new` (
                        `id` TEXT NOT NULL,
                        `exercise_id` TEXT NOT NULL,
                        `date` TEXT NOT NULL,
                        `duration_seconds` INTEGER NOT NULL,
                        `distance_km` REAL,
                        `pace` TEXT,
                        `created_at` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `cardio_sessions_new`
                        (`id`, `exercise_id`, `date`, `duration_seconds`, `distance_km`, `pace`, `created_at`)
                    SELECT `id`, `exercise_id`, `date`, `duration_seconds`, NULL, `pace`, `created_at`
                    FROM `cardio_sessions`
                    """.trimIndent(),
                )

                val numberRegex = Regex("""[-+]?[0-9]*\.?[0-9]+""")
                db.query("SELECT `id`, `distance` FROM `cardio_sessions` WHERE `distance` IS NOT NULL").use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val distanceIndex = cursor.getColumnIndexOrThrow("distance")
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex)
                        val raw = cursor.getString(distanceIndex) ?: continue
                        val value = numberRegex.find(raw)?.value?.toDoubleOrNull() ?: continue
                        val km = if (raw.lowercase().contains("mi")) value * KM_PER_MILE else value
                        db.execSQL(
                            "UPDATE `cardio_sessions_new` SET `distance_km` = ? WHERE `id` = ?",
                            arrayOf(km, id),
                        )
                    }
                }

                db.execSQL("DROP TABLE `cardio_sessions`")
                db.execSQL("ALTER TABLE `cardio_sessions_new` RENAME TO `cardio_sessions`")
            }
        }

        /**
         * Adds a status column to exercise_sets so each set can be either LOGGED (actually
         * recorded) or SUGGESTED (template from a copied workout, awaiting accept). Existing
         * rows default to LOGGED -- they were already recorded before this distinction existed.
         */
        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `exercise_sets` ADD COLUMN `status` TEXT NOT NULL DEFAULT 'LOGGED'",
                )
            }
        }

        /**
         * Adds updated_at (epoch millis) to both session tables, so an edit to an old session
         * can be noticed by the backup exporter -- created_at alone can't distinguish "logged
         * last March" from "logged last March, corrected today".
         *
         * Existing rows are backfilled rather than left at 0, because a session whose
         * updated_at is 0 would be bucketed into a 1970 backup window and, in practice, never
         * reached. In order of preference: the row's created_at; failing that (rows predating
         * [MIGRATION_6_7], which have created_at = 0) midnight UTC on its logged date; failing
         * that (an unparseable date) the time of this migration, so the session is at least
         * backed up rather than silently skipped.
         */
        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("workout_sessions", "cardio_sessions").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL(
                        """
                        UPDATE `$table` SET `updated_at` = CASE
                            WHEN `created_at` > 0 THEN `created_at`
                            WHEN strftime('%s', `date`) IS NOT NULL THEN strftime('%s', `date`) * 1000
                            ELSE strftime('%s', 'now') * 1000
                        END
                        """.trimIndent(),
                    )
                }
            }
        }

        /**
         * Every migration, in order. Pass this to `buildDatabase` rather than listing
         * migrations at the call site, so there is exactly one list to keep complete.
         */
        val MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
            )
    }
}
