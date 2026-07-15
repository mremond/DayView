package fr.dayview.app.sync

fun SyncDocument.merge(remote: SyncDocument?): SyncDocument {
    if (remote == null) return this
    return copy(
        schemaVersion = maxOf(schemaVersion, remote.schemaVersion),
        dayWindow = pick(dayWindow, remote.dayWindow),
        showSeconds = pick(showSeconds, remote.showSeconds),
        sound = pick(sound, remote.sound),
        goal = pickGoal(goal, remote.goal),
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

/**
 * Last-writer-wins for the goal, except a goal with content always beats an empty one — unless the
 * empty side is a voluntary clear ([GoalDto.cleared]) that is strictly newer. This stops a
 * never-set default (e.g. a freshly paired device) from wiping a real goal on another device while
 * still letting a deliberate deletion propagate. Symmetric, so [merge] stays commutative.
 */
private fun pickGoal(a: Versioned<GoalDto>, b: Versioned<GoalDto>): Versioned<GoalDto> {
    val aHas = a.value.hasContent()
    val bHas = b.value.hasContent()
    if (aHas == bHas) return pick(a, b)
    val content = if (aHas) a else b
    val empty = if (aHas) b else a
    return if (empty.value.cleared && empty.stamp.wins(content.stamp)) empty else content
}

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
