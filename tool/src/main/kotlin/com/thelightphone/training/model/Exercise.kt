package com.thelightphone.training.model

/**
 * A predefined exercise in the user's library: a name plus the muscle
 * group(s) it trains. [primaryMuscleGroupId] is the main group it targets;
 * [secondaryMuscleGroupIds] are any other groups it also works.
 * [trackedFields] are the extra data points (e.g. distance, pace) offered
 * when logging this exercise -- only surfaced in Settings for exercises
 * in the Cardio muscle group. Editable from the Settings screen, persisted
 * via [TrainingRepository].
 */
data class Exercise(
    val id: String,
    val name: String,
    val primaryMuscleGroupId: String,
    val secondaryMuscleGroupIds: List<String> = emptyList(),
    val trackedFields: Set<TrackedField> = emptySet(),
)

/** An optional extra data point offered when logging a cardio exercise. */
enum class TrackedField(val displayName: String) {
    DISTANCE("Distance"),
    PACE("Pace"),
}
