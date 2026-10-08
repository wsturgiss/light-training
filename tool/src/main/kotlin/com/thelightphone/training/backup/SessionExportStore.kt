package com.thelightphone.training.backup

import com.thelightphone.training.model.CardioSession
import com.thelightphone.training.model.LoggedWeightExercise
import com.thelightphone.training.model.SetStatus
import com.thelightphone.training.model.WorkoutSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * A plain-files mirror of the logged session history, written next to the Room database so
 * LightOS's backup system can copy it to the user's cloud storage.
 *
 * The database itself is deliberately *not* what gets backed up. LightOS asks a tool which of
 * its files changed inside a given time window and uploads them individually; a single
 * `training.db` would answer "all of it, every time", re-uploading the whole history on every
 * run, and would land in the user's Drive as an opaque blob. Light's stated goal is that a
 * backup is readable on arrival without the app that wrote it, so each session is exported as
 * its own small JSON file instead.
 *
 * Two properties make the window query work, and both are deliberate:
 *
 * - **Each file's modification time is set to its session's `updatedAt`**, not to the moment it
 *   was written. Re-exporting an unchanged session is therefore a no-op as far as the backup is
 *   concerned: the file stays in the window it already belongs to and is not uploaded twice.
 * - **Filenames are stable and unique per session.** Everything in one window is uploaded into
 *   a single remote directory, so two sessions must never share a name. The full session id is
 *   in the filename for that reason - an abbreviated one would collide roughly never, but
 *   "roughly never" silently loses a workout when it happens.
 *
 * Deleting a session deletes its local export but not anything already uploaded. That is the
 * intended behaviour of a backup: the cloud copy is an archive, not a mirror.
 */
internal class SessionExportStore(private val root: File) {

    /** Writes [session]'s export, replacing any previous file for the same session. */
    fun writeWorkout(session: WorkoutSession) {
        val payload = WorkoutJson(
            id = session.id,
            name = session.name,
            date = session.date.toString(),
            loggedAt = isoOrNull(session.createdAt),
            updatedAt = isoOrNull(session.updatedAt),
            totalSets = session.exercises.sumOf { exercise -> exercise.loggedSets().size },
            exercises = session.exercises.map { exercise ->
                ExerciseJson(
                    name = exercise.name,
                    muscleGroup = exercise.muscleGroup.name,
                    secondaryMuscleGroups = exercise.secondaryMuscleGroups.map { it.name },
                    sets = exercise.loggedSets().map { SetJson(reps = it.reps, weightKg = it.weightKg) },
                )
            },
        )
        write(
            id = session.id,
            fileName = fileNameFor(KIND_WEIGHTS, session.date.toString(), session.id),
            body = json.encodeToString(payload),
            modifiedAt = session.updatedAt,
        )
    }

    /**
     * Writes [session]'s export. [exerciseName] is resolved by the caller and embedded directly,
     * so the file stands on its own in cloud storage rather than referencing an exercise id the
     * reader has no way to look up.
     */
    fun writeCardio(session: CardioSession, exerciseName: String) {
        val payload = CardioJson(
            id = session.id,
            exercise = exerciseName,
            date = session.date.toString(),
            loggedAt = isoOrNull(session.createdAt),
            updatedAt = isoOrNull(session.updatedAt),
            durationSeconds = session.durationSeconds,
            duration = formatDuration(session.durationSeconds),
            distanceKm = session.distanceKm,
            pace = session.pace,
        )
        write(
            id = session.id,
            fileName = fileNameFor(KIND_CARDIO, session.date.toString(), session.id),
            body = json.encodeToString(payload),
            modifiedAt = session.updatedAt,
        )
    }

    /**
     * True when [id]'s export is already on disk and already carries [updatedAt], i.e. writing
     * it again would produce an identical file in an identical backup window. Used by the
     * startup reconciliation pass to avoid rewriting the entire history on every launch.
     *
     * Compared exactly. A filesystem that stored modification times at coarser than
     * millisecond resolution would make this always false, which costs a redundant rewrite per
     * launch but never a wrong answer -- so there is no tolerance window to get subtly wrong.
     */
    fun isUpToDate(id: String, updatedAt: Long): Boolean =
        exportsFor(id).singleOrNull()?.lastModified() == updatedAt

    /** Removes [id]'s export. Anything already backed up to the cloud is left alone. */
    fun remove(id: String) {
        exportsFor(id).forEach { it.delete() }
    }

    /** Removes exports whose session id is not in [keepIds] (sessions deleted while away). */
    fun removeAllExcept(keepIds: Set<String>) {
        exportFiles()
            .filter { sessionIdOf(it) !in keepIds }
            .forEach { it.delete() }
    }

    /**
     * Files changed within `(lowerBound, upperBound]` - the half-open window LightOS asks for.
     * Exclusive at the lower end so a file reported for one window is not reported again for
     * the next.
     */
    fun filesModifiedIn(lowerBound: Long, upperBound: Long): List<File> =
        exportFiles()
            .filter { it.lastModified() > lowerBound && it.lastModified() <= upperBound }
            .sortedBy { it.name }

    /** The oldest export's timestamp, used as the starting point the first time a backup runs. */
    fun earliestTimestamp(): Long? = exportFiles().minOfOrNull { it.lastModified() }

    fun allFiles(): List<File> = exportFiles().sortedBy { it.name }

    /** Hex SHA-256 of [file]'s current contents, for the manifest LightOS writes per window. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // Synchronized because every write of a session goes through the same scratch file name:
    // two concurrent writers would both write it, the first would move it into place, and the
    // second's rename would find nothing there.
    @Synchronized
    private fun write(id: String, fileName: String, body: String, modifiedAt: Long) {
        root.mkdirs()

        // Written to a scratch name and moved into place: a crash or a full disk partway
        // through must not leave truncated JSON where a complete file was, because the backup
        // would happily upload the truncated version. Scratch files are excluded from
        // exportFiles() for the same reason.
        val scratch = File(root, "$fileName$SCRATCH_SUFFIX")
        val target = File(root, fileName)
        scratch.writeText(body)
        if (!scratch.renameTo(target)) {
            scratch.delete()
            error("could not move $fileName into place in $root")
        }

        // A session's date is editable, and the date is part of the filename, so an edit can
        // leave the previous file behind under the old name. Prune after the new file is
        // safely in place, never before.
        exportsFor(id).filter { it.name != fileName }.forEach { it.delete() }

        // Best effort: if the filesystem refuses, the file keeps its real write time and gets
        // backed up in the current window instead of its own. Backed up late beats not at all.
        target.setLastModified(modifiedAt)
    }

    private fun exportFiles(): List<File> =
        root.listFiles()?.filter { it.isFile && it.name.endsWith(FILE_SUFFIX) }.orEmpty()

    private fun exportsFor(id: String): List<File> =
        exportFiles().filter { it.name.endsWith("$PART_SEPARATOR$id$FILE_SUFFIX") }

    private fun sessionIdOf(file: File): String =
        file.name.removeSuffix(FILE_SUFFIX).substringAfterLast(PART_SEPARATOR)

    private fun fileNameFor(kind: String, date: String, id: String): String =
        "$date$PART_SEPARATOR$kind$PART_SEPARATOR$id$FILE_SUFFIX"

    private fun isoOrNull(epochMillis: Long): String? =
        if (epochMillis <= 0) null else Instant.ofEpochMilli(epochMillis).toString()

    internal companion object {
        /** Subdirectory of the tool's files dir that exports live in. */
        const val DIRECTORY_NAME = "backup/sessions"

        /** Label this directory is backed up under, i.e. its folder name in the user's cloud. */
        const val BACKUP_LABEL = "sessions"

        private const val KIND_WEIGHTS = "weights"
        private const val KIND_CARDIO = "cardio"
        private const val FILE_SUFFIX = ".json"
        private const val SCRATCH_SUFFIX = ".partial"

        /**
         * Separates the parts of a filename. Deliberately not "-": session ids are UUIDs and
         * dates are hyphenated, so a single hyphen leaves no way to tell where the id starts,
         * and reading the id back out of a name is how [removeAllExcept] decides what to keep.
         */
        private const val PART_SEPARATOR = "__"
        private const val DIGEST_BUFFER_BYTES = 8 * 1024
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600

        private val json = Json {
            prettyPrint = true
            encodeDefaults = true
            explicitNulls = false
        }

        /** "41:30" / "1:05:02" -- so a human reading the file isn't converting seconds. */
        fun formatDuration(totalSeconds: Int): String {
            val hours = totalSeconds / SECONDS_PER_HOUR
            val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
            val seconds = totalSeconds % SECONDS_PER_MINUTE
            return if (hours > 0) {
                "%d:%02d:%02d".format(hours, minutes, seconds)
            } else {
                "%d:%02d".format(minutes, seconds)
            }
        }
    }
}

@Serializable
private data class SetJson(val reps: Int, val weightKg: Double? = null)

@Serializable
private data class ExerciseJson(
    val name: String,
    val muscleGroup: String,
    val secondaryMuscleGroups: List<String> = emptyList(),
    val sets: List<SetJson>,
)

@Serializable
private data class WorkoutJson(
    val kind: String = "weights",
    val id: String,
    val name: String,
    val date: String,
    val loggedAt: String?,
    val updatedAt: String?,
    val totalSets: Int,
    val exercises: List<ExerciseJson>,
)

@Serializable
private data class CardioJson(
    val kind: String = "cardio",
    val id: String,
    val exercise: String,
    val date: String,
    val loggedAt: String?,
    val updatedAt: String?,
    val durationSeconds: Int,
    val duration: String,
    val distanceKm: Double? = null,
    val pace: String? = null,
)

/**
 * Only sets the user actually recorded. SUGGESTED sets are templates copied from an earlier
 * workout and not yet done -- exporting them would put lifts in the backup that never happened.
 */
private fun LoggedWeightExercise.loggedSets() = sets.filter { it.status == SetStatus.LOGGED }
