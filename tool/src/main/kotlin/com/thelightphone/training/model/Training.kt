package com.thelightphone.training.model

import java.time.LocalDate

/**
 * The unit weights are displayed/entered in. Sets are always stored
 * internally in kilograms ([ExerciseSet.weightKg]); this is purely a
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
 * A single completed set of an exercise: how many reps, and the weight used.
 * [weightKg] is null for bodyweight-only sets (or cardio-style exercises).
 */
data class ExerciseSet(
    val reps: Int,
    val weightKg: Double?,
)

/**
 * One exercise performed during a [WorkoutSession], e.g. "Bench Press",
 * tagged with the [muscleGroup] it primarily trains, plus the sets done.
 */
data class LoggedExercise(
    val name: String,
    val muscleGroup: MuscleGroup,
    val sets: List<ExerciseSet>,
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
    val exercises: List<LoggedExercise>,
) {
    val muscleGroups: List<MuscleGroup>
        get() = exercises.map { it.muscleGroup }.distinct()

    val totalSets: Int
        get() = exercises.sumOf { it.sets.size }
}
