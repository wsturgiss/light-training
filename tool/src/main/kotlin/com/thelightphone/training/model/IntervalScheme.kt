package com.thelightphone.training.model

/**
 * A user-defined interval workout scheme: a name plus work/rest durations and round count.
 * Offered alongside the built-in presets (Tabata, Nordic 4x4) when starting an interval
 * workout. User creatable/editable/deletable from the Settings screen, persisted via
 * [TrainingRepository].
 */
data class IntervalScheme(
    val id: String,
    val name: String,
    val workSeconds: Int,
    val restSeconds: Int,
    val rounds: Int,
)
