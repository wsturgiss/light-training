package com.thelightphone.training.model

import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.training.backup.SessionExportStore
import java.io.File

/**
 * The single place the repository is constructed.
 *
 * Every screen used to open the database itself, each repeating the full migration list. Since
 * `buildDatabase` applies `fallbackToDestructiveMigration()`, one screen falling behind that
 * list would not fail loudly -- it would drop the user's training history and carry on. The
 * list now lives only in [TrainingDatabase.MIGRATIONS], and screens call this.
 */
internal fun SealedLightContext.trainingRepository(): TrainingRepository =
    TrainingRepository.getInstance(
        databaseProvider = {
            buildDatabase(
                TrainingDatabase::class.java,
                TrainingRepository.DATABASE_NAME,
                *TrainingDatabase.MIGRATIONS,
            )
        },
        exportStoreProvider = {
            SessionExportStore(File(filesDir, SessionExportStore.DIRECTORY_NAME))
        },
    )
