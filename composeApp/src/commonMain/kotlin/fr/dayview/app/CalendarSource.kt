package fr.dayview.app

import kotlin.time.Instant

data class CalendarInfo(val id: String, val displayName: String)

data class NetTimeSettings(
    val enabled: Boolean = false,
    val includedCalendarIds: Set<String> = emptySet(),
)

interface CalendarSource {
    fun isSupported(): Boolean
    fun hasPermission(): Boolean
    fun requestPermission()
    fun availableCalendars(): List<CalendarInfo>
    fun busyIntervals(
        windowStart: Instant,
        windowEnd: Instant,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval>

    /**
     * S'abonne aux changements externes de l'agenda : [onChange] est appelé quand le fournisseur
     * de calendrier signale une modification, pour déclencher une relecture sans attendre le tick
     * de minute ni un retour au premier plan. Renvoie une poignée dont [AutoCloseable.close]
     * arrête l'observation. Par défaut, sans effet (sources incapables de pousser un changement :
     * EventKit desktop, [NoopCalendarSource]) ; Android enregistre un ContentObserver.
     */
    fun observeChanges(onChange: () -> Unit): AutoCloseable = object : AutoCloseable {
        override fun close() {}
    }
}

object NoopCalendarSource : CalendarSource {
    override fun isSupported() = false
    override fun hasPermission() = false
    override fun requestPermission() = Unit
    override fun availableCalendars(): List<CalendarInfo> = emptyList()
    override fun busyIntervals(
        windowStart: Instant,
        windowEnd: Instant,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> = emptyList()
}

expect fun createCalendarSource(): CalendarSource

/**
 * Calcule le prochain ensemble de calendriers inclus après une bascule.
 * Un ensemble vide signifie « tous inclus » ; l'inclusion de tous les calendriers
 * est renormalisée vers l'ensemble vide.
 */
fun nextIncludedCalendars(
    allIds: List<String>,
    current: Set<String>,
    toggledId: String,
    include: Boolean,
): Set<String> {
    val effective = if (current.isEmpty()) allIds.toSet() else current
    val updated = if (include) effective + toggledId else effective - toggledId
    return if (updated == allIds.toSet()) emptySet() else updated
}
