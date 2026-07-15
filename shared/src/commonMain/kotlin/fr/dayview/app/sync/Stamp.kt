package fr.dayview.app.sync

import kotlinx.serialization.Serializable

@Serializable
data class Stamp(val at: Long, val by: String)

/** True when this stamp should overwrite [other]: newer wins, ties break by higher deviceId. */
fun Stamp.wins(other: Stamp): Boolean = when {
    at != other.at -> at > other.at
    else -> by > other.by
}

@Serializable
data class Versioned<T>(val value: T, val stamp: Stamp)

@Serializable
data class SyncItem<T>(
    val id: String,
    val value: T,
    val deleted: Boolean,
    val stamp: Stamp,
)

@Serializable
data class DayScoped<T>(val dayKey: Long, val items: List<SyncItem<T>>)
