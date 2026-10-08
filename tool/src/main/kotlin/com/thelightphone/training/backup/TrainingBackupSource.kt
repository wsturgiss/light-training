package com.thelightphone.training.backup

import java.io.File
import java.io.InputStream

/**
 * The questions LightOS's backup system asks a tool at backup time, answered for this tool.
 *
 * This mirrors the shape of `BackupDataSource` in
 * [light-backup](https://github.com/lightphone/light-backup), but does not implement it: as of
 * writing, the SDK has no tool-facing backup API at all (the announced PRs are not yet open
 * upstream, so there is nothing to compile against). Keeping the answers here means that when
 * the SDK does expose the interface, wiring it up is one delegating method per question and no
 * logic has to move.
 *
 * Nothing in here reads the database. Every answer comes from files [SessionExportStore] has
 * already written, because a backup can run at a moment this tool is not meaningfully awake -
 * the data has to be on disk beforehand, not generated on demand.
 */
internal class TrainingBackupSource(private val exports: SessionExportStore) {

    /** The single directory this tool backs up, named as it will appear in the user's cloud. */
    fun pathsToBackUp(): List<String> = listOf(SessionExportStore.BACKUP_LABEL)

    /** Exports changed within `(lowerBound, upperBound]`, in epoch millis. */
    fun filesToBackUp(lowerBound: Long, upperBound: Long): List<File> =
        exports.filesModifiedIn(lowerBound, upperBound)

    fun readFile(file: File): InputStream = file.inputStream()

    fun hashForFile(file: File): String = exports.sha256(file)

    /**
     * The oldest timestamp that could ever need backing up, used as the starting bound the
     * first time a backup runs. Falls back to now when there is nothing to back up yet, so an
     * empty tool doesn't ask the runner to walk history it has no files in.
     */
    fun earliestPossibleBackupDate(): Long =
        exports.earliestTimestamp() ?: System.currentTimeMillis()
}
