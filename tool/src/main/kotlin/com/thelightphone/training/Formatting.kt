package com.thelightphone.training

/** Formats a duration as `mm:ss`, rolling over to `h:mm:ss` once it reaches an hour. */
fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Formats a distance value, dropping decimal places when the value is a whole number. */
fun formatDistance(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value)

/**
 * Computes pace as `mm:ss` per distance unit from a duration and a distance already expressed in
 * that unit (e.g. km or mi -- not always km, unlike [com.thelightphone.training.model.CardioSession.distanceKm]).
 * Returns null when the distance is zero or negative, since pace is undefined in that case.
 */
fun formatPace(totalSeconds: Int, distanceInUnit: Double, unitLabel: String): String? {
    if (distanceInUnit <= 0.0) return null
    val secondsPerUnit = (totalSeconds / distanceInUnit).roundToLong()
    val minutes = secondsPerUnit / 60
    val seconds = secondsPerUnit % 60
    return "%d:%02d / %s".format(minutes, seconds, unitLabel)
}

private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
