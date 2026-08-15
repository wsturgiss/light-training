package com.thelightphone.training.model

import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore preference keys for the training tool. Currently just the
 * user's preferred [WeightUnit] for displaying/entering weights.
 */
internal object TrainingPreferences {
    val WEIGHT_UNIT = stringPreferencesKey("weight_unit")
}

internal fun weightUnitFromStorage(stored: String?): WeightUnit =
    WeightUnit.entries.firstOrNull { it.name == stored } ?: WeightUnit.KG
