package fr.dayview.app.sync

import fr.dayview.app.FocusClosureOutcome
import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusPresenceInterval
import fr.dayview.app.FocusSessionRecord
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class FocusContributionDto(
    val schemaVersion: Int,
    val dayKey: Long,
    val deviceId: String,
    val presence: List<PresenceDto>,
    val session: List<PresenceDto>,
    val records: List<FocusSessionRecordDto> = emptyList(),
)

object FocusContributionMapper {
    private fun List<FocusPresenceInterval>.toDto() = map { PresenceDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds()) }
    private fun List<PresenceDto>.toIntervals() = map { FocusPresenceInterval(Instant.fromEpochMilliseconds(it.start), Instant.fromEpochMilliseconds(it.end)) }

    private fun List<FocusSessionRecord>.toRecordDtos() = map { FocusSessionRecordDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds(), it.intention, it.outcome.name) }

    private fun List<FocusSessionRecordDto>.toRecords() = mapNotNull { dto ->
        val outcome = FocusClosureOutcome.entries.firstOrNull { it.name == dto.outcome } ?: return@mapNotNull null
        FocusSessionRecord(Instant.fromEpochMilliseconds(dto.start), Instant.fromEpochMilliseconds(dto.end), dto.intention, outcome)
    }

    fun serialize(c: FocusContribution): String = SyncJson.encodeToString(
        FocusContributionDto(HISTORY_SCHEMA_VERSION, c.dayKey, c.deviceId, c.presence.toDto(), c.session.toDto(), c.records.toRecordDtos()),
    )

    fun deserialize(json: String): FocusContribution? = try {
        val d = SyncJson.decodeFromString<FocusContributionDto>(json)
        FocusContribution(d.dayKey, d.deviceId, d.presence.toIntervals(), d.session.toIntervals(), d.records.toRecords())
    } catch (e: Exception) {
        null
    }
}
