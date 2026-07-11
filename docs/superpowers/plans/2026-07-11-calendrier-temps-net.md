# Intégration calendrier — temps net — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Afficher un temps net (temps brut de la journée moins les plages « occupé » du calendrier) en information secondaire, avec les plages grisées sur le cercle et le nom de l'événement au survol.

**Architecture:** Un cœur **pur et partagé** (`commonMain`) modélise, fusionne et soustrait les plages occupées, puis les projette en arcs. La lecture des calendriers passe par un `expect/actual CalendarSource` (Android : `CalendarContract` ; macOS : helper natif EventKit lancé en processus accessoire, comme `MacFocusStatusItem`), en **lecture seule**. Le rendu et les réglages vivent dans `App.kt`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime, `kotlin.test`, JNA (déjà présent), Android Calendar Provider, EventKit via helper natif.

## Global Constraints

- Fonction **désactivée par défaut** ; s'active après consentement explicite + autorisation système.
- Lecture **strictement locale et en lecture seule** : aucune écriture de calendrier, aucun accès réseau, aucun stockage disque du contenu des événements.
- Le **décompte brut reste inchangé** : le temps net et les arcs occupés ne modifient jamais `DayProgress`, l'angle du repère, ni le grand chiffre.
- Le **calcul** ne repose que sur les bornes horaires. Seuls les événements au statut **occupé/opaque, hors journée entière**, comptent (tentatifs et « disponible » exclus).
- Le **titre** n'est lu que pour l'overlay de survol, gardé en mémoire le temps de l'affichage, **jamais persisté**.
- UI et copie en **français**, dans le style de l'écran Réglages existant.
- Plateformes : Android 7.0+, macOS 13+. Un refus d'autorisation ou une erreur de lecture laisse le temps brut intact.
- `commonTest` tourne via `./gradlew :composeApp:desktopTest`. Package : `fr.dayview.app`.

---

## File Structure

- **Create** `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt` — modèle `BusyInterval`, `NetTime`, `BusyArc` ; fonctions pures `mergeBusyIntervals`, `dayWindowMillis`, `calculateNetTime`, `busyArcs`.
- **Create** `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt` — `CalendarInfo`, `NetTimeSettings`, interface `CalendarSource`, `NoopCalendarSource`, `expect fun createCalendarSource()`.
- **Modify** `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt` — persistance de `NetTimeSettings`.
- **Create** `composeApp/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt` — `actual` via `CalendarContract`.
- **Create** `composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt` — `actual` via helper natif EventKit.
- **Create** `scripts/macos-eventkit-helper.swift` (+ intégration build ressources) — helper natif imprimant les événements occupés.
- **Modify** `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` — arcs occupés grisés, overlay de survol, panneau de réglages, câblage du rafraîchissement.
- **Create** `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt` — tests des fonctions pures.
- **Modify** `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesTest.kt` — round-trip des réglages.

---

## Task 1: Modèle et fusion des plages occupées

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Produces:
  - `data class BusyInterval(val startMillis: Long, val endMillis: Long, val titles: List<String> = emptyList())`
  - `fun mergeBusyIntervals(intervals: List<BusyInterval>): List<BusyInterval>` — trie par `startMillis`, fusionne les plages qui se chevauchent **ou se touchent** (`next.start <= current.end`), concatène les `titles` dans l'ordre, ignore les plages vides/inversées (`end <= start`).

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarNetTimeTest {
    private fun interval(start: Long, end: Long, vararg titles: String) =
        BusyInterval(start, end, titles.toList())

    @Test
    fun mergeCombinesOverlappingAndTouchingIntervals() {
        val merged = mergeBusyIntervals(
            listOf(
                interval(300, 400, "B"),
                interval(100, 200, "A"),
                interval(200, 250, "C"), // touche A
            ),
        )
        assertEquals(2, merged.size)
        assertEquals(BusyInterval(100, 250, listOf("A", "C")), merged[0])
        assertEquals(BusyInterval(300, 400, listOf("B")), merged[1])
    }

    @Test
    fun mergeDropsEmptyOrInvertedIntervals() {
        val merged = mergeBusyIntervals(listOf(interval(500, 500), interval(700, 600)))
        assertEquals(emptyList(), merged)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — compilation error, `mergeBusyIntervals` / `BusyInterval` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app

data class BusyInterval(
    val startMillis: Long,
    val endMillis: Long,
    val titles: List<String> = emptyList(),
)

fun mergeBusyIntervals(intervals: List<BusyInterval>): List<BusyInterval> {
    val sorted = intervals.filter { it.endMillis > it.startMillis }.sortedBy { it.startMillis }
    val merged = mutableListOf<BusyInterval>()
    for (interval in sorted) {
        val last = merged.lastOrNull()
        if (last != null && interval.startMillis <= last.endMillis) {
            merged[merged.lastIndex] = last.copy(
                endMillis = maxOf(last.endMillis, interval.endMillis),
                titles = last.titles + interval.titles,
            )
        } else {
            merged.add(interval)
        }
    }
    return merged
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "feat(net): modèle et fusion des plages occupées"
```

---

## Task 2: Fenêtre du jour et calcul du temps net

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Consumes: `BusyInterval`, `mergeBusyIntervals` (Task 1) ; `DayProgress`, `calculateDayProgress` (existant, `DayProgress.kt`).
- Produces:
  - `data class NetTime(val netDayMillis: Long, val netRemainingMillis: Long, val busyRemainingMillis: Long)`
  - `fun dayWindowMillis(nowMillis: Long, startMinutesOfDay: Int, endMinutesOfDay: Int, timeZone: TimeZone = TimeZone.currentSystemDefault()): Pair<Long, Long>` — bornes absolues `[début, fin]` du jour courant, avec les **mêmes** garde-fous que `calculateDayProgress` (`start` ∈ [0, 23:29], `end` ≥ `start`+30 min).
  - `fun calculateNetTime(progress: DayProgress, nowMillis: Long, windowStartMillis: Long, windowEndMillis: Long, busy: List<BusyInterval>): NetTime` — rogne `busy` à la fenêtre, fusionne, puis :
    - `netDayMillis` = durée fenêtre − total occupé rogné (≥ 0) ;
    - `busyRemainingMillis` = occupé dans `[max(now, windowStart), windowEnd]` ;
    - `netRemainingMillis` = `(progress.remainingMillis − busyRemainingMillis)` borné à 0.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun dayWindowReturnsAbsoluteBounds() {
        val zone = kotlinx.datetime.TimeZone.of("Europe/Paris")
        val noon = kotlinx.datetime.LocalDateTime(2026, 7, 11, 12, 0)
            .toInstant(zone).toEpochMilliseconds()
        val (start, end) = dayWindowMillis(noon, 8 * 60, 18 * 60, zone)
        assertEquals(4L * 3_600_000, noon - start) // 08:00 -> 4 h avant midi
        assertEquals(6L * 3_600_000, end - noon)   // 18:00 -> 6 h après midi
    }

    @Test
    fun netTimeSubtractsBusyStillAhead() {
        val zone = kotlinx.datetime.TimeZone.of("Europe/Paris")
        val noon = kotlinx.datetime.LocalDateTime(2026, 7, 11, 12, 0)
            .toInstant(zone).toEpochMilliseconds()
        val (start, end) = dayWindowMillis(noon, 8 * 60, 18 * 60, zone)
        val progress = calculateDayProgress(noon, 8 * 60, 18 * 60, zone)
        // Réunion 09:00-10:00 (passée) + 14:00-15:30 (à venir)
        val busy = listOf(
            interval(start + 1L * 3_600_000, start + 2L * 3_600_000, "Standup"),
            interval(noon + 2L * 3_600_000, noon + 3L * 3_600_000 + 1_800_000, "Atelier"),
        )
        val net = calculateNetTime(progress, noon, start, end, busy)
        assertEquals(2L * 3_600_000 + 1_800_000, net.busyRemainingMillis)      // 1 h 30 à venir
        assertEquals(progress.remainingMillis - net.busyRemainingMillis, net.netRemainingMillis)
        assertEquals((end - start) - (3_600_000 + 3_600_000 + 1_800_000), net.netDayMillis)
    }
```

Ajoute les imports en tête du fichier de test : `import kotlinx.datetime.toInstant`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `dayWindowMillis` / `calculateNetTime` / `NetTime` unresolved.

- [ ] **Step 3: Write minimal implementation**

Ajoute à `CalendarNetTime.kt` :

```kotlin
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

data class NetTime(
    val netDayMillis: Long,
    val netRemainingMillis: Long,
    val busyRemainingMillis: Long,
)

fun dayWindowMillis(
    nowMillis: Long,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Long, Long> {
    val safeStart = startMinutesOfDay.coerceIn(0, 23 * 60 + 29)
    val safeEnd = endMinutesOfDay.coerceIn(safeStart + 30, 23 * 60 + 59)
    val localNow = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone)
    fun at(minutes: Int) = LocalDateTime(
        year = localNow.year, month = localNow.month, day = localNow.day,
        hour = minutes / 60, minute = minutes % 60,
    ).toInstant(timeZone).toEpochMilliseconds()
    return at(safeStart) to at(safeEnd)
}

private fun overlapMillis(start: Long, end: Long, from: Long, to: Long): Long =
    (minOf(end, to) - maxOf(start, from)).coerceAtLeast(0)

fun calculateNetTime(
    progress: DayProgress,
    nowMillis: Long,
    windowStartMillis: Long,
    windowEndMillis: Long,
    busy: List<BusyInterval>,
): NetTime {
    val clipped = mergeBusyIntervals(
        busy.map {
            it.copy(
                startMillis = it.startMillis.coerceIn(windowStartMillis, windowEndMillis),
                endMillis = it.endMillis.coerceIn(windowStartMillis, windowEndMillis),
            )
        },
    )
    val totalBusy = clipped.sumOf { it.endMillis - it.startMillis }
    val aheadFrom = nowMillis.coerceIn(windowStartMillis, windowEndMillis)
    val busyRemaining = clipped.sumOf { overlapMillis(it.startMillis, it.endMillis, aheadFrom, windowEndMillis) }
    val windowDuration = (windowEndMillis - windowStartMillis).coerceAtLeast(0)
    return NetTime(
        netDayMillis = (windowDuration - totalBusy).coerceAtLeast(0),
        netRemainingMillis = (progress.remainingMillis - busyRemaining).coerceAtLeast(0),
        busyRemainingMillis = busyRemaining,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "feat(net): calcul du temps net à partir des plages occupées"
```

---

## Task 3: Projection des plages en arcs du cercle

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Consumes: `BusyInterval`, `mergeBusyIntervals` (Tasks 1-2).
- Produces:
  - `data class BusyArc(val startAngleDegrees: Float, val sweepDegrees: Float, val titles: List<String>)`
  - `fun busyArcs(windowStartMillis: Long, windowEndMillis: Long, busy: List<BusyInterval>): List<BusyArc>` — même convention que `currentMomentAngleDegrees` : fraction écoulée `f = (t - windowStart) / durée`, `angle = -90 + f * 360`. Plages rognées à la fenêtre et fusionnées avant projection ; fenêtre de durée nulle → liste vide.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun busyArcsProjectAtElapsedFraction() {
        // Fenêtre 0..1000. Plage 250..500 -> quart..moitié.
        val arcs = busyArcs(0, 1000, listOf(interval(250, 500, "X")))
        assertEquals(1, arcs.size)
        assertEquals(-90f + 0.25f * 360f, arcs[0].startAngleDegrees)
        assertEquals(0.25f * 360f, arcs[0].sweepDegrees)
        assertEquals(listOf("X"), arcs[0].titles)
    }

    @Test
    fun busyArcsClipToWindow() {
        val arcs = busyArcs(0, 1000, listOf(interval(-200, 200, "Y")))
        assertEquals(-90f, arcs[0].startAngleDegrees)
        assertEquals(0.2f * 360f, arcs[0].sweepDegrees)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `busyArcs` / `BusyArc` unresolved.

- [ ] **Step 3: Write minimal implementation**

Ajoute à `CalendarNetTime.kt` :

```kotlin
data class BusyArc(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
    val titles: List<String>,
)

fun busyArcs(
    windowStartMillis: Long,
    windowEndMillis: Long,
    busy: List<BusyInterval>,
): List<BusyArc> {
    val duration = (windowEndMillis - windowStartMillis).toFloat()
    if (duration <= 0f) return emptyList()
    val clipped = mergeBusyIntervals(
        busy.map {
            it.copy(
                startMillis = it.startMillis.coerceIn(windowStartMillis, windowEndMillis),
                endMillis = it.endMillis.coerceIn(windowStartMillis, windowEndMillis),
            )
        },
    )
    return clipped.map {
        val fStart = (it.startMillis - windowStartMillis) / duration
        val fEnd = (it.endMillis - windowStartMillis) / duration
        BusyArc(
            startAngleDegrees = -90f + fStart * 360f,
            sweepDegrees = (fEnd - fStart) * 360f,
            titles = it.titles,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "feat(net): projection des plages occupées en arcs"
```

---

## Task 4: Réglages du temps net et leur persistance

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/*DayPreferences*.kt` (impl Android existante) et `composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesTest.kt`

**Interfaces:**
- Produces:
  - `data class CalendarInfo(val id: String, val displayName: String)`
  - `data class NetTimeSettings(val enabled: Boolean = false, val includedCalendarIds: Set<String> = emptySet())` — `includedCalendarIds` vide = **tous** les calendriers.
  - `DayPreferences.loadNetTimeSettings(): NetTimeSettings` et `saveNetTimeSettings(settings: NetTimeSettings)`.
- Note : la persistance sérialise `includedCalendarIds` en chaîne jointe par `\n` ; `enabled` en booléen. Suivre le style des impls existantes (mêmes clés `SharedPreferences` / `Preferences` que les autres réglages).

- [ ] **Step 1: Write the failing test**

Ajoute à `DayPreferencesTest.kt` (utilise le faux/`InMemory` déjà présent dans ce fichier ; s'il n'existe pas, teste via l'impl desktop `DesktopDayPreferences` avec un dossier temporaire comme le fait `DesktopDayPreferencesTest.kt`).

```kotlin
    @Test
    fun netTimeSettingsRoundTrip() {
        val prefs = newPreferencesUnderTest() // helper déjà utilisé par les autres tests du fichier
        prefs.saveNetTimeSettings(
            NetTimeSettings(enabled = true, includedCalendarIds = setOf("work", "perso")),
        )
        val loaded = prefs.loadNetTimeSettings()
        assertEquals(true, loaded.enabled)
        assertEquals(setOf("work", "perso"), loaded.includedCalendarIds)
    }

    @Test
    fun netTimeSettingsDefaultsToDisabled() {
        assertEquals(NetTimeSettings(), DefaultDayPreferences.loadNetTimeSettings())
    }
```

> Si `DayPreferencesTest.kt` n'a pas de fabrique réutilisable, remplace `newPreferencesUnderTest()` par la construction concrète déjà utilisée en tête de ce fichier de test.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesTest"`
Expected: FAIL — `loadNetTimeSettings` / `NetTimeSettings` unresolved.

- [ ] **Step 3: Write minimal implementation**

Dans `CalendarSource.kt` :

```kotlin
package fr.dayview.app

data class CalendarInfo(val id: String, val displayName: String)

data class NetTimeSettings(
    val enabled: Boolean = false,
    val includedCalendarIds: Set<String> = emptySet(),
)
```

Dans `DayPreferences.kt`, ajoute à l'interface :

```kotlin
    fun loadNetTimeSettings(): NetTimeSettings
    fun saveNetTimeSettings(settings: NetTimeSettings)
```

et à `DefaultDayPreferences` :

```kotlin
    override fun loadNetTimeSettings(): NetTimeSettings = NetTimeSettings()
    override fun saveNetTimeSettings(settings: NetTimeSettings) = Unit
```

Dans chaque impl plateforme (Android + `DesktopDayPreferences`), suis exactement le pattern des réglages voisins (ex. `loadShowSeconds`/`saveShowSeconds`). Clés : `net_time_enabled` (Boolean), `net_time_calendars` (String, ids joints par `\n`). Exemple pour l'impl desktop (adapter au store réel du fichier) :

```kotlin
    override fun loadNetTimeSettings(): NetTimeSettings = NetTimeSettings(
        enabled = store.getBoolean("net_time_enabled", false),
        includedCalendarIds = store.getString("net_time_calendars", "")
            .split("\n").filter { it.isNotBlank() }.toSet(),
    )

    override fun saveNetTimeSettings(settings: NetTimeSettings) {
        store.putBoolean("net_time_enabled", settings.enabled)
        store.putString("net_time_calendars", settings.includedCalendarIds.joinToString("\n"))
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DayPreferencesTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt \
        composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt \
        composeApp/src/androidMain/kotlin/fr/dayview/app \
        composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/DayPreferencesTest.kt
git commit -m "feat(net): réglages du temps net et persistance"
```

---

## Task 5: Contrat CalendarSource + implémentation par défaut

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt`

**Interfaces:**
- Consumes: `BusyInterval` (Task 1), `CalendarInfo`, `NetTimeSettings` (Task 4).
- Produces:
  - ```kotlin
    interface CalendarSource {
        fun isSupported(): Boolean
        fun hasPermission(): Boolean
        fun requestPermission()
        fun availableCalendars(): List<CalendarInfo>
        fun busyIntervals(windowStartMillis: Long, windowEndMillis: Long, includedCalendarIds: Set<String>): List<BusyInterval>
    }
    ```
  - `object NoopCalendarSource : CalendarSource` — `isSupported()`/`hasPermission()` = `false`, listes vides, `requestPermission()` = no-op.
  - `expect fun createCalendarSource(): CalendarSource`

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun noopCalendarSourceIsInertAndSafe() {
        assertEquals(false, NoopCalendarSource.isSupported())
        assertEquals(false, NoopCalendarSource.hasPermission())
        assertEquals(emptyList(), NoopCalendarSource.availableCalendars())
        assertEquals(emptyList(), NoopCalendarSource.busyIntervals(0, 1000, emptySet()))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: FAIL — `NoopCalendarSource` unresolved.

- [ ] **Step 3: Write minimal implementation**

Ajoute à `CalendarSource.kt` :

```kotlin
interface CalendarSource {
    fun isSupported(): Boolean
    fun hasPermission(): Boolean
    fun requestPermission()
    fun availableCalendars(): List<CalendarInfo>
    fun busyIntervals(
        windowStartMillis: Long,
        windowEndMillis: Long,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval>
}

object NoopCalendarSource : CalendarSource {
    override fun isSupported() = false
    override fun hasPermission() = false
    override fun requestPermission() = Unit
    override fun availableCalendars(): List<CalendarInfo> = emptyList()
    override fun busyIntervals(
        windowStartMillis: Long,
        windowEndMillis: Long,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> = emptyList()
}

expect fun createCalendarSource(): CalendarSource
```

Ajoute les `actual` minimales pour compiler (elles seront étoffées aux Tasks 6-7) :
- `androidMain/.../CalendarSource.android.kt` : `actual fun createCalendarSource(): CalendarSource = NoopCalendarSource`
- `desktopMain/.../CalendarSource.desktop.kt` : `actual fun createCalendarSource(): CalendarSource = NoopCalendarSource`

- [ ] **Step 4: Run test + full compile**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.CalendarNetTimeTest"`
Expected: PASS
Run: `./gradlew :composeApp:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL (les `actual` existent)

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarSource.kt \
        composeApp/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt \
        composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt \
        composeApp/src/commonTest/kotlin/fr/dayview/app/CalendarNetTimeTest.kt
git commit -m "feat(net): contrat CalendarSource et source par défaut"
```

---

## Task 6: CalendarSource Android (Calendar Provider)

**Files:**
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/CalendarSource.android.kt`
- Modify: `composeApp/src/androidMain/AndroidManifest.xml` (permission `READ_CALENDAR`)
- Modify: impl Android de `DayPreferences` **non concernée** ; l'accès `Context` suit le pattern déjà utilisé (ex. `SoundCuePlayer.android.kt` / providers Android existants).

**Interfaces:**
- Consumes: `CalendarSource`, `BusyInterval`, `CalendarInfo` (Tasks 1, 5).
- Produces: `actual fun createCalendarSource(): CalendarSource` retournant une impl `CalendarContract`.
- Note : Android n'a pas de contexte statique ici. Suivre le pattern d'injection déjà présent pour les autres services Android (récupération du `Context` applicatif comme les impls sœurs). Si aucun pattern statique n'existe, exposer une fonction `initCalendarSource(context: Context)` appelée depuis `MainActivity`, sur le modèle des autres services.

- [ ] **Step 1: Ajouter la permission**

Dans `AndroidManifest.xml`, avant `<application>` :

```xml
<uses-permission android:name="android.permission.READ_CALENDAR" />
```

- [ ] **Step 2: Implémenter la source**

Requête « occupé, hors journée entière » via `CalendarContract.Instances` sur `[windowStart, windowEnd]` :

```kotlin
package fr.dayview.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

private lateinit var appContext: Context

fun initCalendarSource(context: Context) { appContext = context.applicationContext }

private class AndroidCalendarSource(private val context: Context) : CalendarSource {
    override fun isSupported() = true

    override fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    // La demande d'autorisation est déclenchée par l'UI (ActivityResult) ; ici on ne fait rien.
    override fun requestPermission() = Unit

    override fun availableCalendars(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(id = c.getLong(0).toString(), displayName = c.getString(1) ?: "")
            }
        }
        return out
    }

    override fun busyIntervals(
        windowStartMillis: Long,
        windowEndMillis: Long,
        includedCalendarIds: Set<String>,
    ): List<BusyInterval> {
        if (!hasPermission()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(windowStartMillis.toString())
            .appendPath(windowEndMillis.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val out = mutableListOf<BusyInterval>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val allDay = c.getInt(3) == 1
                val availability = c.getInt(4) // 0 = BUSY
                val calId = c.getLong(5).toString()
                if (allDay) continue
                if (availability != CalendarContract.Instances.AVAILABILITY_BUSY) continue
                if (includedCalendarIds.isNotEmpty() && calId !in includedCalendarIds) continue
                out += BusyInterval(c.getLong(0), c.getLong(1), listOfNotNull(c.getString(2)))
            }
        }
        return out
    }
}

actual fun createCalendarSource(): CalendarSource =
    if (::appContext.isInitialized) AndroidCalendarSource(appContext) else NoopCalendarSource
```

Dans `MainActivity.onCreate` (avant `setContent`), ajoute `initCalendarSource(this)`.

- [ ] **Step 3: Vérifier la compilation**

Run: `./gradlew :composeApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Vérification manuelle**

Installe (`./gradlew :composeApp:installDebug`), accorde la permission calendrier, crée un événement « occupé » dans la journée, active le temps net (après Task 10) et vérifie qu'un arc grisé apparaît et que le temps net diminue. Vérifie qu'un événement « disponible » ou journée entière **n'apparaît pas**.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/androidMain
git commit -m "feat(net): lecture des calendriers Android via Calendar Provider"
```

---

## Task 7: CalendarSource macOS (helper EventKit)

**Files:**
- Create: `scripts/macos-eventkit-helper.swift`
- Modify: `composeApp/build.gradle.kts` (compiler le helper et l'embarquer en ressource desktop, sur le modèle de `macos-focus-status-helper`)
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt`

**Interfaces:**
- Consumes: `CalendarSource`, `BusyInterval`, `CalendarInfo` (Tasks 1, 5).
- Produces: `actual fun createCalendarSource(): CalendarSource` pilotant le helper via stdin/stdout, comme `MacFocusStatusItem`.
- Protocole texte du helper (une commande par ligne sur stdin, réponse terminée par une ligne `END`) :
  - `PERMISSION` → `GRANTED` | `DENIED` | `NOTDETERMINED`
  - `REQUEST` → déclenche la demande, répond `GRANTED`/`DENIED`
  - `CALENDARS` → lignes `id\tdisplayName`, puis `END`
  - `BUSY <startMillis> <endMillis>` → lignes `startMillis\tendMillis\tcalendarId\ttitle` (uniquement `EKEventAvailability.busy`, `isAllDay == false`), puis `END`

- [ ] **Step 1: Écrire le helper Swift**

`scripts/macos-eventkit-helper.swift` : boucle de lecture stdin, `EKEventStore`, filtre `event.availability == .busy && !event.isAllDay`, imprime les lignes selon le protocole. (Le filtrage par calendrier inclus est fait côté Kotlin pour rester simple.)

- [ ] **Step 2: Intégrer au build**

Réplique le mécanisme d'`macos-focus-status-helper` : tâche Gradle qui `swiftc` le helper et copie le binaire dans les ressources desktop (`/macos-eventkit-helper`). Voir la tâche existante pour le status helper comme référence exacte.

- [ ] **Step 3: Implémenter le pont Kotlin**

`CalendarSource.desktop.kt` : classe qui extrait le binaire en fichier temporaire exécutable (comme `extractHelper()` de `MacFocusStatusItem`), lance le processus, écrit les commandes et lit jusqu'à `END`. `isSupported()` = `isMacOS`. Parsing tolérant : toute erreur d'E/S → retour vide (le temps brut reste intact).

```kotlin
actual fun createCalendarSource(): CalendarSource =
    if (isMacOS) MacEventKitCalendarSource() else NoopCalendarSource
```

- [ ] **Step 4: Vérifier la compilation et le helper**

Run: `./gradlew :composeApp:desktopTest`
Expected: PASS (aucune régression ; la source desktop n'est pas unit-testée).
Run: `./gradlew :composeApp:run`
Expected: l'app démarre ; à la première activation du temps net, macOS demande l'accès au calendrier.

- [ ] **Step 5: Vérification manuelle**

Accorde l'accès, crée un événement « occupé » dans la journée, active le temps net : un arc grisé apparaît, le temps net diminue, le survol affiche le titre. Un événement « disponible »/journée entière est ignoré.

- [ ] **Step 6: Commit**

```bash
git add scripts/macos-eventkit-helper.swift composeApp/build.gradle.kts \
        composeApp/src/desktopMain/kotlin/fr/dayview/app/CalendarSource.desktop.kt
git commit -m "feat(net): lecture des calendriers macOS via helper EventKit"
```

---

## Task 8: Arcs occupés grisés sur le cercle

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (bloc `Canvas` ~1033-1075, état du temps net)

**Interfaces:**
- Consumes: `busyArcs`, `BusyArc`, `NetTime`, `calculateNetTime`, `dayWindowMillis` (Tasks 2-3) ; `NetTimeSettings`, `createCalendarSource` (Tasks 4-5).
- Produces: état `busyArcsState: List<BusyArc>` et `netTimeState: NetTime?` calculés dans le composable, dessinés en arcs gris.

- [ ] **Step 1: État du temps net dans `App`**

Près de `soundPlayer` (App.kt:181), ajoute :

```kotlin
val calendarSource = remember { createCalendarSource() }
var netTimeSettings by remember(preferences) { mutableStateOf(preferences.loadNetTimeSettings()) }
var busyIntervalsState by remember { mutableStateOf<List<BusyInterval>>(emptyList()) }
```

Un `LaunchedEffect(nowMillis / 60000, netTimeSettings, startMinutes, endMinutes)` (recalé chaque minute) recharge, hors thread UI, `calendarSource.busyIntervals(...)` quand `netTimeSettings.enabled && calendarSource.hasPermission()`, sinon vide. Enveloppe l'appel dans `runCatching { }.getOrDefault(emptyList())`.

- [ ] **Step 2: Dériver arcs et temps net**

```kotlin
val (windowStart, windowEnd) = remember(nowMillis / 60000, startMinutes, endMinutes) {
    dayWindowMillis(nowMillis, startMinutes, endMinutes)
}
val busyArcsState = remember(busyIntervalsState, windowStart, windowEnd) {
    busyArcs(windowStart, windowEnd, busyIntervalsState)
}
val netTime = remember(progress, nowMillis / 1000, busyIntervalsState) {
    if (netTimeSettings.enabled) calculateNetTime(progress, nowMillis, windowStart, windowEnd, busyIntervalsState) else null
}
```

- [ ] **Step 3: Dessiner les arcs gris**

Dans le `Canvas`, **après** l'arc de fond et **avant** l'arc restant (App.kt:1063), ajoute :

```kotlin
busyArcsState.forEach { arc ->
    drawArc(
        color = colors.overlay.copy(alpha = .35f),
        startAngle = arc.startAngleDegrees,
        sweepAngle = arc.sweepDegrees,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = arcSize,
        style = Stroke(strokeWidth, cap = StrokeCap.Butt),
    )
}
```

- [ ] **Step 4: Vérification manuelle**

Run: `./gradlew :composeApp:run`
Avec le temps net activé et un événement occupé du jour, vérifie qu'un arc gris apparaît à la bonne position, distinct de l'arc restant, en thème clair et sombre. Vérifie que le grand chiffre brut est **inchangé**.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "feat(net): arcs occupés grisés sur le cercle"
```

---

## Task 9: Overlay du nom de l'événement au survol

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt`

**Interfaces:**
- Consumes: `busyArcsState: List<BusyArc>` (Task 8).
- Produces: overlay affichant `titles` de l'arc survolé + ses horaires. Le survol est détecté sur les plateformes à pointeur ; l'absence de pointeur (tactile) n'affiche rien.

- [ ] **Step 1: Détecter le survol d'un arc**

Sur le `Box` du cercle, ajoute un `Modifier.pointerInput` (`onPointerEvent(PointerEventType.Move)` via `androidx.compose.ui.input.pointer`) qui convertit la position en angle (`atan2` autour du centre, normalisé sur `[-90, 270)`), puis trouve l'arc dont `[startAngle, startAngle+sweep]` contient cet angle. Stocke `hoveredArc: BusyArc?`.

- [ ] **Step 2: Afficher l'overlay**

Quand `hoveredArc != null`, affiche près du curseur une petite carte (fond `colors.surface`, coins arrondis) listant `hoveredArc.titles` et l'intervalle horaire dérivé de l'angle → heure (`windowStart + fraction * durée`, formaté `HH:mm`). Masque-la quand le pointeur sort.

- [ ] **Step 3: Vérification manuelle**

Run: `./gradlew :composeApp:run`
Survole un arc gris : le nom de l'événement et ses horaires s'affichent ; en dehors, l'overlay disparaît. Pour une plage fusionnée (plusieurs titres), tous s'affichent.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "feat(net): overlay du nom de l'événement au survol"
```

---

## Task 10: Panneau de réglages du temps net + affichage secondaire

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (`SettingsScreen`/`SettingsPanel` ~643-1004, et affichage sous le décompte)

**Interfaces:**
- Consumes: `netTimeSettings`, `netTime: NetTime?`, `calendarSource` (Tasks 4, 5, 8).
- Produces: un panneau de réglages `NetTimeSettingsPanel` (interrupteur, autorisation, sélection des calendriers) sur le modèle de `SoundSettingsPanel` ; et la ligne de temps net sous le grand chiffre.

- [ ] **Step 1: Ligne de temps net secondaire**

Sous le décompte brut (là où le grand chiffre est composé), si `netTime != null` et `netTime.busyRemainingMillis > 0`, affiche une ligne discrète :

```text
Net : {net.netRemainingMillis en HH h MM}  ·  {busyRemaining} occupé
```

(réutilise le formatage heures/minutes existant du décompte).

- [ ] **Step 2: Panneau de réglages**

Ajoute `NetTimeSettingsPanel(settings, available, onSettingsChange, onRequestPermission)` calqué sur `SoundSettingsPanel` (App.kt:857) :
- interrupteur `enabled` ;
- si activé et permission manquante : bouton « Autoriser l'accès au calendrier » → `calendarSource.requestPermission()` (Android : lance la demande d'`ActivityResult` ; macOS : le helper déclenche la demande) ;
- liste des calendriers de `calendarSource.availableCalendars()` avec cases à cocher pilotant `includedCalendarIds` (vide = tous) ;
- texte d'aide : « Seuls les événements marqués occupé (hors journée entière) sont soustraits. »

Câble-le dans `SettingsScreen` à côté de `SoundSettingsPanel` (App.kt:839), et propage `onNetTimeSettingsChange` jusqu'à `App`, qui fait `netTimeSettings = it ; preferences.saveNetTimeSettings(it)` (comme le pattern `soundSettings` en App.kt:242).

- [ ] **Step 3: Vérification manuelle**

Run: `./gradlew :composeApp:run` puis, sur Android, `./gradlew :composeApp:installDebug`.
- Par défaut : aucun réglage actif, écran identique à aujourd'hui, aucune ligne de temps net.
- Active le temps net → demande d'autorisation → arcs gris + ligne « Net : … ».
- Décoche un calendrier → ses événements disparaissent des arcs et du calcul.
- Relance l'app : le réglage est conservé.

- [ ] **Step 4: Full test + commit**

Run: `./gradlew :composeApp:desktopTest`
Expected: PASS

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt
git commit -m "feat(net): réglages du temps net et affichage secondaire"
```

---

## Notes de fin

- **Documentation** : mettre à jour `README.md` (section « Temps net ») une fois la fonction validée — hors périmètre des tâches ci-dessus, à faire en clôture.
- **Ordre** : les Tasks 1-5 sont indépendantes de la plateforme et entièrement testables. Les Tasks 6-7 (plateformes) et 8-10 (UI) nécessitent une vérification manuelle. Task 8+ suppose 4-5 faites pour un rendu visible.
