package com.thelightphone.training.model

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore preference keys for the training tool: the user's preferred
 * [WeightUnit] for displaying/entering weights, and [DistanceUnit] for
 * displaying/entering cardio distances.
 */
internal object TrainingPreferences {
    val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
    val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
}

internal fun weightUnitFromStorage(stored: String?): WeightUnit =
    WeightUnit.entries.firstOrNull { it.name == stored } ?: WeightUnit.KG

internal fun distanceUnitFromStorage(stored: String?): DistanceUnit =
    DistanceUnit.entries.firstOrNull { it.name == stored } ?: DistanceUnit.KM
