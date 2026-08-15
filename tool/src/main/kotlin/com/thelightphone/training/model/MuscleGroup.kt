package com.thelightphone.training.model

/**
 * A muscle group an exercise can target, e.g. "Chest" or "Back". User
 * creatable/editable from the Settings screen, persisted via
 * [TrainingRepository].
 */
data class MuscleGroup(
    val id: String,
    val name: String,
)
