package com.thelightphone.training.model

import java.time.LocalDate

/**
 * The unit weights are displayed/entered in. Sets are always stored
 * internally in kilograms ([WeightSet.weightKg]); this is purely a
 * display/input preference, converted at the UI layer.
 */
enum class WeightUnit(val displayName: String) {
    KG("kg"),
    LB("lb");

    /** Converts a value already in this unit into kilograms for storage. */
    fun toKg(value: Double): Double = when (this) {
        KG -> value
        LB -> value / KG_PER_LB
    }

    /** Converts a stored kilogram value into this unit for display. */
    fun fromKg(kg: Double): Double = when (this) {
        KG -> kg
        LB -> kg * KG_PER_LB
    }

    fun next(): WeightUnit = entries[(ordinal + 1) % entries.size]

    companion object {
        private const val KG_PER_LB = 2.2046226218
    }
}

/**
 * A single completed set of a weight exercise: how many reps, and the weight used.
 * [weightKg] is null for bodyweight-only sets.
 */
data class WeightSet(
    val reps: Int,
    val weightKg: Double?,
)

/**
 * One weight exercise performed during a [WorkoutSession], e.g. "Bench Press",
 * referencing the library [Exercise] it came from via [exerciseId], and
 * carrying the resolved [muscleGroup] (primary) and [secondaryMuscleGroups]
 * populated at load time from the current exercise/muscle-group tables. Cardio
 * exercises aren't logged this way -- see CardioWorkoutContent.kt / IntervalWorkoutContent.kt.
 */
data class LoggedWeightExercise(
    val exerciseId: String,
    val name: String,
    val muscleGroup: MuscleGroup,
    val secondaryMuscleGroups: List<MuscleGroup> = emptyList(),
    val sets: List<WeightSet>,
) {
    /** Total reps across all sets. */
    val totalReps: Int
        get() = sets.sumOf { it.reps }

    /** Total weight moved (reps * weight, summed across sets). Ignores bodyweight sets. */
    val totalVolumeKg: Double
        get() = sets.sumOf { (it.weightKg ?: 0.0) * it.reps }

    /** Heaviest weight used for this exercise in this session, if any. */
    val topWeightKg: Double?
        get() = sets.mapNotNull { it.weightKg }.maxOrNull()
}

/**
 * A single training session containing one or more exercises.
 */
data class WorkoutSession(
    val id: String,
    val name: String,
    val date: LocalDate,
    val exercises: List<LoggedWeightExercise>,
    /** When this session was created, in epoch millis -- used to order same-day sessions by
     * time instead of arbitrarily (see [TrainingRepository]/HomeScreen's combined feed). */
    val createdAt: Long = System.currentTimeMillis(),
) {
    val muscleGroups: List<MuscleGroup>
        get() = exercises.map { it.muscleGroup }.distinct()

    val totalSets: Int
        get() = exercises.sumOf { it.sets.size }
}

/**
 * A single logged cardio or interval result: which exercise, how long it took, and any
 * optional tracked fields (distance/pace) configured for that exercise. [durationSeconds] is
 * either typed in by hand or carried over from the in-app timer -- see
 * [TrainingRepository.insertCardioSession].
 */
data class CardioSession(
    val id: String,
    val exerciseId: String,
    val date: LocalDate,
    val durationSeconds: Int,
    val distance: String? = null,
    val pace: String? = null,
    /** When this session was created, in epoch millis -- used to order same-day sessions by
     * time instead of arbitrarily (see [TrainingRepository]/HomeScreen's combined feed). */
    val createdAt: Long = System.currentTimeMillis(),
)
