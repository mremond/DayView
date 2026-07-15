package fr.dayview.app.sync

fun SyncDocument.merge(remote: SyncDocument?): SyncDocument {
    if (remote == null) return this
    return copy(
        schemaVersion = maxOf(schemaVersion, remote.schemaVersion),
        dayWindow = pick(dayWindow, remote.dayWindow),
        showSeconds = pick(showSeconds, remote.showSeconds),
        sound = pick(sound, remote.sound),
        goal = pick(goal, remote.goal),
        pomodoro = pick(pomodoro, remote.pomodoro),
        openDetour = pick(openDetour, remote.openDetour),
        focusIntention = pick(focusIntention, remote.focusIntention),
        themeMode = pick(themeMode, remote.themeMode),
        netTimeEnabled = pick(netTimeEnabled, remote.netTimeEnabled),
        detours = mergeDayScoped(detours, remote.detours),
        plannedObligations = mergeDayScoped(plannedObligations, remote.plannedObligations),
        plannedObligationsCompleted = mergeDayScoped(plannedObligationsCompleted, remote.plannedObligationsCompleted),
        recentDetourMotifs = boundRecentMotifItems(mergeItems(recentDetourMotifs, remote.recentDetourMotifs)),
        cleanSessions = mergeClean(cleanSessions, remote.cleanSessions),
        historyDays = (historyDays + remote.historyDays).distinct().sorted(),
        focusContributions = (focusContributions + remote.focusContributions).distinct().sorted(),
    )
}

private fun <T> pick(a: Versioned<T>, b: Versioned<T>): Versioned<T> = if (b.stamp.wins(a.stamp)) b else a

private fun <T> mergeDayScoped(a: DayScoped<T>, b: DayScoped<T>): DayScoped<T> = when {
    a.dayKey > b.dayKey -> a
    b.dayKey > a.dayKey -> b
    else -> DayScoped(a.dayKey, mergeItems(a.items, b.items))
}

private fun <T> mergeItems(a: List<SyncItem<T>>, b: List<SyncItem<T>>): List<SyncItem<T>> = (a + b).groupBy { it.id }
    .map { (_, group) -> group.reduce { x, y -> if (y.stamp.wins(x.stamp)) y else x } }
    .sortedBy { it.id }

private fun mergeClean(a: Versioned<CleanDto>, b: Versioned<CleanDto>): Versioned<CleanDto> {
    val streakDays = when {
        a.value.streakLastDayKey > b.value.streakLastDayKey -> a.value.streakDays
        b.value.streakLastDayKey > a.value.streakLastDayKey -> b.value.streakDays
        else -> maxOf(a.value.streakDays, b.value.streakDays)
    }
    val streakLastDayKey = maxOf(a.value.streakLastDayKey, b.value.streakLastDayKey)
    val cleanToday = when {
        a.value.dayKey > b.value.dayKey -> a.value.cleanToday
        b.value.dayKey > a.value.dayKey -> b.value.cleanToday
        else -> maxOf(a.value.cleanToday, b.value.cleanToday)
    }
    val dayKey = maxOf(a.value.dayKey, b.value.dayKey)
    val merged = CleanDto(dayKey, cleanToday, streakDays, streakLastDayKey)
    val stamp = if (b.stamp.wins(a.stamp)) b.stamp else a.stamp
    return Versioned(merged, stamp)
}
