package fr.dayview.app.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val SYNC_SCHEMA_VERSION = 1

@Serializable data class DayWindow(val start: Int, val end: Int)

@Serializable
data class SoundDto(
    val enabled: Boolean,
    val startCue: Boolean,
    val intervalCue: Boolean,
    val endCue: Boolean,
    val intervalMinutes: Int,
    val volumePercent: Int,
)

@Serializable data class GoalDto(val title: String, val deadline: Long, val start: Long)

@Serializable data class PomodoroDto(val minutes: Int, val end: Long)

@Serializable data class DetourDto(val start: Long, val end: Long, val motif: String)

@Serializable
data class CleanDto(
    val dayKey: Long,
    val cleanToday: Int,
    val streakDays: Int,
    val streakLastDayKey: Long,
)

@Serializable
data class SyncDocument(
    val schemaVersion: Int,
    val dayWindow: Versioned<DayWindow>,
    val showSeconds: Versioned<Boolean>,
    val sound: Versioned<SoundDto>,
    val goal: Versioned<GoalDto>,
    val pomodoro: Versioned<PomodoroDto>,
    val focusIntention: Versioned<String>,
    val themeMode: Versioned<String>,
    val netTimeEnabled: Versioned<Boolean>,
    val detours: DayScoped<DetourDto>,
    val plannedObligations: DayScoped<String>,
    val recentDetourMotifs: List<SyncItem<String>>,
    val cleanSessions: Versioned<CleanDto>,
)

val SyncJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

fun SyncDocument.encodeToString(): String = SyncJson.encodeToString(this)

fun decodeSyncDocument(text: String): SyncDocument = SyncJson.decodeFromString(text)
