package fr.dayview.app.sync

import fr.dayview.app.FocusContribution
import fr.dayview.app.FocusPresenceInterval
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class FocusContributionDto(
    val schemaVersion: Int,
    val dayKey: Long,
    val deviceId: String,
    val presence: List<PresenceDto>,
    val session: List<PresenceDto>,
)

object FocusContributionMapper {
    private fun List<FocusPresenceInterval>.toDto() = map { PresenceDto(it.start.toEpochMilliseconds(), it.end.toEpochMilliseconds()) }
    private fun List<PresenceDto>.toIntervals() = map { FocusPresenceInterval(Instant.fromEpochMilliseconds(it.start), Instant.fromEpochMilliseconds(it.end)) }

    fun serialize(c: FocusContribution): String = SyncJson.encodeToString(
        FocusContributionDto(HISTORY_SCHEMA_VERSION, c.dayKey, c.deviceId, c.presence.toDto(), c.session.toDto()),
    )

    fun deserialize(json: String): FocusContribution? = try {
        val d = SyncJson.decodeFromString<FocusContributionDto>(json)
        FocusContribution(d.dayKey, d.deviceId, d.presence.toIntervals(), d.session.toIntervals())
    } catch (e: Exception) {
        null
    }
}
