package com.thelightphone.training.model

import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.training.backup.SessionExportStore
import java.io.File

/**
 * The single place the repository is constructed.
 *
 * Every screen used to open the database itself, each repeating the full migration list, so
 * one screen falling behind that list was a matter of time. The list now lives only in
 * [TrainingDatabase.MIGRATIONS], and screens call this.
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
