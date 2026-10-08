package com.thelightphone.training.model

import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import com.thelightphone.training.backup.SessionExportStore
import java.io.File

/**
 * The single place the repository is constructed.
 *
 * Every screen used to open the database itself; screens now call this instead, so there is one
 * place that knows how the database and export store are built.
 */
internal fun SealedLightContext.trainingRepository(): TrainingRepository =
    TrainingRepository.getInstance(
        databaseProvider = {
            buildDatabase(TrainingDatabase::class.java, TrainingRepository.DATABASE_NAME)
        },
        exportStoreProvider = {
            SessionExportStore(File(filesDir, SessionExportStore.DIRECTORY_NAME))
        },
    )
