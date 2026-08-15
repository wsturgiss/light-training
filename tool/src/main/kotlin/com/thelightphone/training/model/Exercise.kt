package com.thelightphone.training.model

/**
 * A predefined exercise in the user's library: a name plus the muscle
 * group(s) it trains. [primaryMuscleGroupId] is the main group it targets;
 * [secondaryMuscleGroupIds] are any other groups it also works. Editable
 * from the Settings screen, persisted via [TrainingRepository].
 */
data class Exercise(
    val id: String,
    val name: String,
    val primaryMuscleGroupId: String,
    val secondaryMuscleGroupIds: List<String> = emptyList(),
)
