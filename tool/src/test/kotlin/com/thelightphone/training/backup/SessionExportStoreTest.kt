package com.thelightphone.training.backup

import com.thelightphone.training.model.CardioSession
import com.thelightphone.training.model.LoggedWeightExercise
import com.thelightphone.training.model.MuscleGroup
import com.thelightphone.training.model.WeightSet
import com.thelightphone.training.model.WorkoutSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * These run on the JVM, not a device: the store is deliberately plain [File] work so the part
 * that decides what LightOS uploads can be tested without an emulator.
 *
 * Most of what matters here is timestamps, because the backup runner never asks "what changed?"
 * -- it asks "what changed between these two instants?", and the answer comes entirely from the
 * modification times this class sets.
 */
class SessionExportStoreTest {

    private lateinit var root: File
    private lateinit var store: SessionExportStore

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("session-exports").toFile()
        store = SessionExportStore(root)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `workout export is written with the session's updatedAt as its modification time`() {
        store.writeWorkout(workout(id = SESSION_ID, updatedAt = UPDATED_AT))

        val file = store.allFiles().single()
        assertEquals(UPDATED_AT, file.lastModified())
    }

    @Test
    fun `workout export records the session as it was logged`() {
        store.writeWorkout(
            workout(
                id = SESSION_ID,
                name = "Push A",
                date = LocalDate.of(2026, 1, 2),
                exercises = listOf(
                    LoggedWeightExercise(
                        exerciseId = "bench-press",
                        name = "Bench Press",
                        muscleGroup = MuscleGroup("chest", "Chest"),
                        secondaryMuscleGroups = listOf(MuscleGroup("triceps", "Triceps")),
                        sets = listOf(WeightSet(reps = 8, weightKg = 60.0), WeightSet(reps = 6, weightKg = null)),
                    ),
                ),
            ),
        )

        val body = Json.parseToJsonElement(store.allFiles().single().readText()).jsonObject
        assertEquals("weights", body.string("kind"))
        assertEquals(SESSION_ID, body.string("id"))
        assertEquals("Push A", body.string("name"))
        assertEquals("2026-01-02", body.string("date"))
        assertEquals("2", body["totalSets"]?.jsonPrimitive?.content)

        val exercise = body["exercises"]!!.jsonArray.single().jsonObject
        assertEquals("Bench Press", exercise.string("name"))
        assertEquals("Chest", exercise.string("muscleGroup"))
        assertEquals(listOf("Triceps"), exercise["secondaryMuscleGroups"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, exercise["sets"]!!.jsonArray.size)
    }

    @Test
    fun `cardio export embeds the exercise name and a human-readable duration`() {
        store.writeCardio(
            cardio(id = SESSION_ID, durationSeconds = 3_902, distanceKm = 10.5, pace = "6:12"),
            exerciseName = "Running",
        )

        val body = Json.parseToJsonElement(store.allFiles().single().readText()).jsonObject
        assertEquals("cardio", body.string("kind"))
        assertEquals("Running", body.string("exercise"))
        assertEquals("1:05:02", body.string("duration"))
        assertEquals("3902", body["durationSeconds"]?.jsonPrimitive?.content)
        assertEquals("6:12", body.string("pace"))
    }

    /** `loggedAt`/`updatedAt` are omitted rather than exported as "1970-01-01T00:00:00Z". */
    @Test
    fun `a session with no recorded timestamps omits them instead of exporting the epoch`() {
        store.writeCardio(cardio(id = SESSION_ID, createdAt = 0L, updatedAt = 0L), exerciseName = "Rowing")

        val body = Json.parseToJsonElement(store.allFiles().single().readText()).jsonObject
        assertFalse("loggedAt" in body, "loggedAt should be absent, was ${body["loggedAt"]}")
        assertFalse("updatedAt" in body, "updatedAt should be absent, was ${body["updatedAt"]}")
    }

    @Test
    fun `isUpToDate is true only for an export carrying that exact updatedAt`() {
        assertFalse(store.isUpToDate(SESSION_ID, UPDATED_AT), "nothing written yet")

        store.writeWorkout(workout(id = SESSION_ID, updatedAt = UPDATED_AT))

        assertTrue(store.isUpToDate(SESSION_ID, UPDATED_AT))
        assertFalse(store.isUpToDate(SESSION_ID, UPDATED_AT + 1), "a later edit must re-export")
        assertFalse(store.isUpToDate("some-other-session", UPDATED_AT))
    }

    /**
     * The date is part of the filename, so moving a session to a different date would otherwise
     * leave the old file behind -- and the backup would keep uploading a session that no longer
     * exists in that form.
     */
    @Test
    fun `re-exporting a session after its date changed leaves exactly one file`() {
        val session = workout(id = SESSION_ID, date = LocalDate.of(2026, 1, 2))
        store.writeWorkout(session)
        store.writeWorkout(session.copy(date = LocalDate.of(2026, 3, 4), updatedAt = UPDATED_AT + 1))

        val file = store.allFiles().single()
        assertTrue(file.name.startsWith("2026-03-04"), "unexpected name ${file.name}")
    }

    /**
     * Ids are UUIDs and dates are hyphenated, so anything that reads the id back out of a
     * filename has to cope with hyphens on both sides of it.
     */
    @Test
    fun `sessions sharing a date and kind do not collide`() {
        val date = LocalDate.of(2026, 1, 2)
        store.writeWorkout(workout(id = "11111111-2222-3333-4444-555555555555", date = date))
        store.writeWorkout(workout(id = "66666666-7777-8888-9999-000000000000", date = date))

        assertEquals(2, store.allFiles().size)
    }

    @Test
    fun `remove deletes only the named session's export`() {
        store.writeWorkout(workout(id = "a-1"))
        store.writeWorkout(workout(id = "b-2"))

        store.remove("a-1")

        assertEquals(1, store.allFiles().size)
        assertTrue(store.allFiles().single().name.endsWith("b-2.json"))
    }

    @Test
    fun `removeAllExcept drops exports whose sessions are gone and keeps the rest`() {
        val kept = "11111111-2222-3333-4444-555555555555"
        val deleted = "66666666-7777-8888-9999-000000000000"
        store.writeWorkout(workout(id = kept))
        store.writeWorkout(workout(id = deleted))

        store.removeAllExcept(setOf(kept))

        assertEquals(1, store.allFiles().size)
        assertTrue(store.allFiles().single().name.endsWith("$kept.json"))
    }

    @Test
    fun `removeAllExcept with every id present is a no-op`() {
        val ids = listOf("11111111-2222-3333-4444-555555555555", "66666666-7777-8888-9999-000000000000")
        ids.forEach { store.writeWorkout(workout(id = it)) }

        store.removeAllExcept(ids.toSet())

        assertEquals(2, store.allFiles().size)
    }

    /**
     * The window is `(lowerBound, upperBound]`. Exclusive at the bottom is the half that
     * matters: the runner walks consecutive windows, and an inclusive lower bound would
     * re-upload the last file of every window as the first file of the next.
     */
    @Test
    fun `filesModifiedIn is exclusive at the lower bound and inclusive at the upper`() {
        store.writeWorkout(workout(id = "before", updatedAt = 1_000L))
        store.writeWorkout(workout(id = "lower-edge", updatedAt = 2_000L))
        store.writeWorkout(workout(id = "inside", updatedAt = 2_500L))
        store.writeWorkout(workout(id = "upper-edge", updatedAt = 3_000L))
        store.writeWorkout(workout(id = "after", updatedAt = 4_000L))

        val found = store.filesModifiedIn(lowerBound = 2_000L, upperBound = 3_000L).map { sessionIdOf(it) }

        assertEquals(listOf("inside", "upper-edge"), found.sorted())
    }

    @Test
    fun `filesModifiedIn is empty when nothing changed in the window`() {
        store.writeWorkout(workout(id = SESSION_ID, updatedAt = 1_000L))

        assertTrue(store.filesModifiedIn(lowerBound = 5_000L, upperBound = 6_000L).isEmpty())
    }

    @Test
    fun `earliestTimestamp is the oldest export, or null when there is nothing to back up`() {
        assertNull(store.earliestTimestamp(), "an empty store must not claim a start date")

        store.writeWorkout(workout(id = "newer", updatedAt = 9_000L))
        store.writeWorkout(workout(id = "older", updatedAt = 1_000L))

        assertEquals(1_000L, store.earliestTimestamp())
    }

    /**
     * A half-written file must never be visible to the backup, which would upload the truncated
     * version and record it as a successfully backed-up session.
     */
    @Test
    fun `a leftover scratch file is not reported as an export`() {
        store.writeWorkout(workout(id = SESSION_ID))
        File(root, "2026-01-02__weights__crashed.json.partial").writeText("{\"id\":\"crash")

        assertEquals(1, store.allFiles().size)
        assertEquals(1, store.filesModifiedIn(0L, Long.MAX_VALUE).size)
    }

    @Test
    fun `sha256 hashes the file's contents`() {
        val file = File(root, "fixture.txt").apply { writeText("abc") }

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", store.sha256(file))
    }

    @Test
    fun `sha256 changes when the contents change and not when only the name does`() {
        store.writeWorkout(workout(id = SESSION_ID, name = "Push A"))
        val first = store.sha256(store.allFiles().single())

        store.writeWorkout(workout(id = SESSION_ID, name = "Push B", updatedAt = UPDATED_AT + 1))

        assertNotEquals(first, store.sha256(store.allFiles().single()))
    }

    @Test
    fun `durations are formatted with hours only when there are hours`() {
        assertEquals("0:09", SessionExportStore.formatDuration(9))
        assertEquals("41:30", SessionExportStore.formatDuration(2_490))
        assertEquals("1:05:02", SessionExportStore.formatDuration(3_902))
        assertEquals("0:00", SessionExportStore.formatDuration(0))
    }

    @Test
    fun `the export directory is created on first write`() {
        val nested = SessionExportStore(File(root, "does/not/exist/yet"))

        nested.writeWorkout(workout(id = SESSION_ID))

        assertEquals(1, nested.allFiles().size)
    }

    private fun workout(
        id: String,
        name: String = "Session",
        date: LocalDate = LocalDate.of(2026, 1, 2),
        exercises: List<LoggedWeightExercise> = listOf(
            LoggedWeightExercise(
                exerciseId = "back-squat",
                name = "Back Squat",
                muscleGroup = MuscleGroup("quads", "Quads"),
                sets = listOf(WeightSet(reps = 5, weightKg = 100.0)),
            ),
        ),
        createdAt: Long = CREATED_AT,
        updatedAt: Long = UPDATED_AT,
    ) = WorkoutSession(
        id = id,
        name = name,
        date = date,
        exercises = exercises,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun cardio(
        id: String,
        durationSeconds: Int = 1_800,
        distanceKm: Double? = null,
        pace: String? = null,
        createdAt: Long = CREATED_AT,
        updatedAt: Long = UPDATED_AT,
    ) = CardioSession(
        id = id,
        exerciseId = "running",
        date = LocalDate.of(2026, 1, 2),
        durationSeconds = durationSeconds,
        distanceKm = distanceKm,
        pace = pace,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** Mirrors the store's own filename convention, so a test failure names the session. */
    private fun sessionIdOf(file: File) = file.name.removeSuffix(".json").substringAfterLast("__")

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content

    private companion object {
        const val SESSION_ID = "11111111-2222-3333-4444-555555555555"
        const val CREATED_AT = 1_767_000_000_000
        const val UPDATED_AT = 1_767_300_000_000
    }
}
