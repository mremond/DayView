# Détours en arcs extérieurs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer les billes de détour par des arcs sur une voie concentrique juste à l'extérieur de l'anneau, encodant la durée réelle (avec un balayage minimum), zones de focus et calendrier inchangés.

**Architecture:** `DetourBody` passe d'un point (angle du milieu + `sizeFraction`) à un arc (`startAngleDegrees` + `sweepDegrees`), projeté comme `BusyBlockArc`. Le rendu remplace les trois `drawCircle` par un arc à deux passes (glow + core) sur une voie extérieure carvée en élargissant l'`inset` du canvas. Le hit-test et le scrub basculent sur l'appartenance à l'arc via `angularDistanceToArc`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (Canvas/DrawScope), kotlinx-datetime, kotlin.test.

## Global Constraints

- Commits et messages en anglais ; **aucune** référence à Claude/Anthropic/IA dans les commits (ni footer, ni trailer).
- ktlint est imposé : `./gradlew ktlintCheck` doit passer (ou `ktlintFormat`).
- Gate complet avant de déclarer terminé : `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest` sans erreur ni stderr.
- Tests Compose : ne jamais asserter du texte via `stringResource` (non résolu sous `runComposeUiTest` en CI) — utiliser tags/données seedées.
- DRY, YAGNI, TDD, commits fréquents.
- Ne pas modifier les zones de focus (`focusArcs`), les créneaux calendrier (`busyBlockArcs`), `MiniRing`, ni la capture de détours (`DetourEpisode`).

---

### Task 1: Modèle et projection des arcs de détour (module `core`)

Remplace le modèle « bille » par un modèle « arc » dans `Detours.kt`, met à jour la projection, le hit-test et le scrub, puis les tests `core`. À la fin de cette tâche, **le module `composeApp` ne compilera plus** (il référence encore `body.angleDegrees` / `body.sizeFraction`) — c'est attendu et réparé en Task 2. La vérification de cette tâche porte donc uniquement sur `:core:jvmTest`.

**Files:**
- Modify: `core/src/commonMain/kotlin/fr/dayview/app/Detours.kt` (data class ~173-181, `detourBodies` ~192-218, `hitTestDetourBody` ~230-245, `detourBodyAtAngle` ~252-254, `angularDistance` ~220-223, imports/constantes ~183-184)
- Test: `core/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`

**Interfaces:**
- Consumes : `angularDistanceToArc(startAngleDegrees, sweepDegrees, angleDegrees): Float` et `arcContainsAngle(...)` — déjà définis dans `CalendarNetTime.kt` (même package), utilisés par `busyBlockArcs`/`ringReadoutAt`.
- Produces :
  - `data class DetourBody(startAngleDegrees: Float, sweepDegrees: Float, colorIndex: Int, category: String, description: String, start: Instant, end: Instant)`
  - `detourBodies(windowStart: Instant, windowEnd: Instant, episodes: List<DetourEpisode>): List<DetourBody>` (signature inchangée)
  - `hitTestDetourBody(x: Float, y: Float, width: Int, height: Int, bodies: List<DetourBody>): DetourBody?` (signature inchangée)
  - `detourBodyAtAngle(bodies: List<DetourBody>, angleDegrees: Float): DetourBody?` (signature inchangée)

- [ ] **Step 1: Réécrire les tests `core` qui portent sur le modèle bille**

Dans `core/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt`, remplacer intégralement le test `bodiesSitAtTheEpisodeMidpointAngle` (~158-165) par :

```kotlin
    @Test
    fun bodiesFormArcsAcrossTheirDuration() {
        val start = t(0L)
        val end = t(36_000_000L) // 10 h window
        // 0 → 1 h episode: starts at the window start (-90°), spans 1/10 of the window (36°).
        val body = detourBodies(start, end, listOf(DetourEpisode(t(0L), t(3_600_000L), "Slack"))).single()
        assertEquals(-90f, body.startAngleDegrees, absoluteTolerance = .01f)
        assertEquals(36f, body.sweepDegrees, absoluteTolerance = .01f)
    }
```

Remplacer intégralement le test `bodySizeFractionScalesBySqrtUpToThreeHours` (~167-185) par :

```kotlin
    @Test
    fun shortDetoursGetAMinimumSweepCenteredOnTheMidpoint() {
        val start = t(0L)
        val end = t(86_400_000L) // 24 h window: 15°/h
        // 6 h → 6 h 04 min: raw sweep 1° is floored to 3.5°, kept centred on the 06:02 midpoint.
        val body = detourBodies(
            start,
            end,
            listOf(DetourEpisode(t(21_600_000L), t(21_840_000L), "x")),
        ).single()
        assertEquals(3.5f, body.sweepDegrees, absoluteTolerance = .01f)
        // Midpoint fraction .251388 → angle 0.5°; arc centre = start + sweep/2.
        assertEquals(0.5f, body.startAngleDegrees + body.sweepDegrees / 2f, absoluteTolerance = .05f)
    }
```

Remplacer intégralement le test `hitTestFindsTheBodyUnderThePointer` (~195-212) par :

```kotlin
    @Test
    fun hitTestFindsTheArcUnderThePointer() {
        val body = DetourBody(
            startAngleDegrees = -100f,
            sweepDegrees = 20f, // covers -100°..-80°, i.e. the top of the dial
            colorIndex = 0,
            category = "Slack",
            description = "",
            start = t(0L),
            end = t(1L),
        )
        // Top of a 400×400 dial, on the outer detour lane (radiusFraction .925): found.
        assertEquals(body, hitTestDetourBody(200f, 15f, 400, 400, listOf(body)))
        // Center of the dial: not on the lane.
        assertEquals(null, hitTestDetourBody(200f, 200f, 400, 400, listOf(body)))
        // Bottom of the dial: on the lane but 180° away.
        assertEquals(null, hitTestDetourBody(200f, 385f, 400, 400, listOf(body)))
        // Inner band (radiusFraction .80), where the old beads lived: no longer a hit.
        assertEquals(null, hitTestDetourBody(200f, 40f, 400, 400, listOf(body)))
    }
```

Remplacer intégralement le test `detourBodyAtAngleFindsBodyNearItsMidpointAngle` (~311-323) par :

```kotlin
    @Test
    fun detourBodyAtAngleFindsBodyWithinItsArc() {
        val windowStart = t(0L)
        val windowEnd = t(24L * 60 * 60 * 1000) // 24 h window: 15°/h
        // A 5 h → 7 h detour: arc from -15° to 15°.
        val episodes = listOf(DetourEpisode(t(5L * 3_600_000), t(7L * 3_600_000), "Slack"))
        val bodies = detourBodies(windowStart, windowEnd, episodes)
        assertEquals(1, bodies.size)
        val body = bodies.first()
        // Inside the arc it is found; far away (180° across) it is not.
        assertEquals(body, detourBodyAtAngle(bodies, 0f))
        assertNull(detourBodyAtAngle(bodies, 180f))
    }
```

- [ ] **Step 2: Lancer les tests `core` pour vérifier qu'ils échouent (compilation)**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DetoursTest"`
Expected: FAIL — compilation errors (`DetourBody` n'a pas encore `startAngleDegrees`/`sweepDegrees`, `angleDegrees`/`sizeFraction` encore présents).

- [ ] **Step 3: Réécrire le modèle, la projection, le hit-test et le scrub dans `Detours.kt`**

Remplacer la data class `DetourBody` (~173-181) par :

```kotlin
/** A detour episode projected on the ring as an arc, ready to draw. */
data class DetourBody(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
    val colorIndex: Int,
    val category: String,
    val description: String,
    val start: Instant,
    val end: Instant,
)
```

Supprimer les constantes devenues inutiles `MIN_BODY_DURATION` / `MAX_BODY_DURATION` (~183-184) et les remplacer par :

```kotlin
/** Floor sweep so a very short detour still reads as a visible arc. */
private const val MIN_DETOUR_SWEEP_DEGREES = 3.5f

/** Angular tolerance around a detour arc for hover / scrub picking. */
private const val DETOUR_ANGLE_TOLERANCE_DEGREES = 6f
```

Remplacer le corps de `detourBodies` (~192-218) et son KDoc par :

```kotlin
/**
 * Project episodes to arcs threaded on a lane outside the ring: start/sweep from the episode
 * bounds clipped to the window (same `-90° = window start` convention as [busyBlockArcs]).
 * Very short detours are floored to [MIN_DETOUR_SWEEP_DEGREES], the arc kept centred on the
 * episode midpoint. Episodes whose midpoint falls outside the window are dropped.
 */
fun detourBodies(
    windowStart: Instant,
    windowEnd: Instant,
    episodes: List<DetourEpisode>,
): List<DetourBody> {
    val total = windowEnd - windowStart
    if (total <= Duration.ZERO) return emptyList()
    val colorBySource = detourSources(episodes).associate { sourceKey(it.label) to it.colorIndex }
    return episodes.sortedBy { it.start }.mapNotNull { episode ->
        val colorIndex = colorBySource[sourceKey(episode.category)] ?: return@mapNotNull null
        if (detourMidpointOutsideWindow(episode, windowStart, windowEnd)) return@mapNotNull null
        val clippedStart = episode.start.coerceIn(windowStart, windowEnd)
        val clippedEnd = episode.end.coerceIn(windowStart, windowEnd)
        val fStart = ((clippedStart - windowStart) / total).toFloat()
        val fEnd = ((clippedEnd - windowStart) / total).toFloat()
        val rawSweep = (fEnd - fStart) * 360f
        val sweep = maxOf(rawSweep, MIN_DETOUR_SWEEP_DEGREES)
        // Keep the arc centred on the real detour when the floor widens it.
        val startAngle = -90f + fStart * 360f - (sweep - rawSweep) / 2f
        DetourBody(
            startAngleDegrees = startAngle,
            sweepDegrees = sweep,
            colorIndex = colorIndex,
            category = sanitizeDetourCategory(episode.category),
            description = sanitizeDetourDescription(episode.description),
            start = episode.start,
            end = episode.end,
        )
    }
}
```

Supprimer la fonction privée `angularDistance` (~220-223) — elle n'est plus utilisée.

Remplacer `hitTestDetourBody` (~230-245) et `detourBodyAtAngle` (~247-254) par :

```kotlin
/**
 * The detour arc under the pointer, or null. Detours ride a lane just outside the ring, so the
 * radial band is the outer region; the angular pick uses arc containment (with a small
 * tolerance so thin arcs stay reachable), the same test as the scrub readout.
 */
fun hitTestDetourBody(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    bodies: List<DetourBody>,
): DetourBody? {
    val dx = x - width / 2f
    val dy = y - height / 2f
    val radiusFraction = hypot(dx, dy) / (minOf(width, height) / 2f)
    if (radiusFraction !in 0.90f..1.06f) return null
    val angle = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
    return detourBodyAtAngle(bodies, angle)
}

/**
 * The detour arc containing [angleDegrees] (or nearest within tolerance), radius-independent —
 * for the touch scrub and, via [hitTestDetourBody], the mouse hover. Null if none is close.
 */
fun detourBodyAtAngle(bodies: List<DetourBody>, angleDegrees: Float): DetourBody? = bodies
    .minByOrNull { angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) }
    ?.takeIf {
        angularDistanceToArc(it.startAngleDegrees, it.sweepDegrees, angleDegrees) <= DETOUR_ANGLE_TOLERANCE_DEGREES
    }
```

Retirer l'import devenu inutile `import kotlin.math.sqrt` en tête de fichier (conserver `PI`, `atan2`, `hypot`, encore utilisés par `hitTestDetourBody`).

- [ ] **Step 4: Lancer les tests `core` et vérifier qu'ils passent**

Run: `./gradlew :core:jvmTest --tests "fr.dayview.app.DetoursTest"`
Expected: PASS (tous les tests `DetoursTest`, y compris les tests inchangés `bodiesOutsideTheWindowAreDropped`, `bodiesKeepEpisodesWithLongTruncatedCategories`, `midpointOutsideWindowMatchesBodyDrop`, `offWindowTotalSumsOnlyDroppedEpisodes`).

- [ ] **Step 5: Lancer ktlint sur le module core et l'ensemble des tests core**

Run: `./gradlew ktlintCheck :core:jvmTest`
Expected: PASS, aucun warning ktlint, aucun stderr.

- [ ] **Step 6: Commit**

```bash
git add core/src/commonMain/kotlin/fr/dayview/app/Detours.kt core/src/commonTest/kotlin/fr/dayview/app/DetoursTest.kt
git commit -m "Model detours as outer-lane arcs instead of beads"
```

---

### Task 2: Rendu des arcs extérieurs et raccords `composeApp`

Carve la voie extérieure dans le `Canvas`, remplace les billes par un arc à deux passes, et répare l'unique référence de test cassée (`RingScrubTest`). Cette tâche rétablit la compilation complète et fait passer le gate entier.

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt` (calcul `inset` ~1042-1044 ; bloc `detourBodies.forEach` ~1214-1235)
- Modify: `composeApp/src/commonTest/kotlin/fr/dayview/app/RingScrubTest.kt` (~63)

**Interfaces:**
- Consumes (de Task 1) : `DetourBody.startAngleDegrees`, `DetourBody.sweepDegrees`, `DetourBody.colorIndex`, `detourBodyAtAngle(...)` via `ringReadoutAt`.
- Produces : rien de nouveau (rendu visuel + test réparé).

- [ ] **Step 1: Réparer la référence de test cassée dans `RingScrubTest`**

Dans `composeApp/src/commonTest/kotlin/fr/dayview/app/RingScrubTest.kt`, le test `detourUnderAngleIsReported` passe `body.angleDegrees` (champ supprimé). Le détour vient de l'épisode `5 h → 7 h` sur une fenêtre 24 h, soit un arc `-15°..15°`. Remplacer la ligne 63 :

```kotlin
            body.angleDegrees,
```

par :

```kotlin
            0f, // inside the detour arc (-15°..15°)
```

- [ ] **Step 2: Lancer le test de scrub pour confirmer l'échec de compilation actuel du module**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.RingScrubTest"`
Expected: FAIL — le module `composeApp` ne compile pas encore (le `Canvas` référence toujours `body.angleDegrees` / `body.sizeFraction`).

- [ ] **Step 3: Carver la voie extérieure dans le calcul d'inset**

Dans `DayViewTodayScreen.kt`, remplacer les lignes ~1042-1044 :

```kotlin
                    val strokeWidth = size.minDimension * .055f
                    val inset = strokeWidth / 2 + 4.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
```

par :

```kotlin
                    val strokeWidth = size.minDimension * .055f
                    // Reserve a concentric lane just outside the ring for detour arcs (mirror of
                    // the calendar-busy lane inside it); the ring itself shrinks by that width.
                    val detourLaneOutset = strokeWidth * .95f
                    val inset = strokeWidth / 2 + 4.dp.toPx() + detourLaneOutset
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
```

- [ ] **Step 4: Remplacer les billes par un arc à deux passes sur la voie extérieure**

Dans `DayViewTodayScreen.kt`, remplacer intégralement le bloc `detourBodies.forEach { body -> ... }` (~1214-1235) par :

```kotlin
                    // Detours ride a concentric lane just OUTSIDE the ring — the mirror of the
                    // calendar-busy lane inside it. Each episode is an arc across its real
                    // duration (floored to a visible minimum), coloured per category. A wide
                    // low-alpha pass gives the glow, a narrower bright pass the core.
                    val detourInset = inset - detourLaneOutset
                    val detourLaneSize = Size(size.width - detourInset * 2, size.height - detourInset * 2)
                    detourBodies.forEach { body ->
                        val col = colors.detours[body.colorIndex % colors.detours.size]
                        drawArc(
                            color = col.copy(alpha = .16f),
                            startAngle = body.startAngleDegrees,
                            sweepAngle = body.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(detourInset, detourInset),
                            size = detourLaneSize,
                            style = Stroke(strokeWidth * .7f, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = col.copy(alpha = .92f),
                            startAngle = body.startAngleDegrees,
                            sweepAngle = body.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(detourInset, detourInset),
                            size = detourLaneSize,
                            style = Stroke(strokeWidth * .42f, cap = StrokeCap.Round),
                        )
                    }
```

- [ ] **Step 5: Lancer les tests `composeApp` (desktop + Android) et vérifier qu'ils passent**

Run: `./gradlew :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS — `RingScrubTest` (dont `detourUnderAngleIsReported`) et tous les tests UI existants passent.

- [ ] **Step 6: Lancer le gate complet**

Run: `./gradlew ktlintCheck :core:jvmTest :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: PASS, aucun warning ktlint, aucun stderr.

- [ ] **Step 7: Vérification visuelle**

Lancer l'app desktop et confirmer de visu : les détours apparaissent comme des arcs colorés **à l'extérieur** de l'anneau (pas des billes) ; un détour court reste visible ; le survol souris (tooltip catégorie/heure) et le grattage tactile ciblent bien l'arc extérieur ; zones de focus (menthe sur la ligne) et créneaux calendrier (arcs intérieurs) inchangés.

Run: `./gradlew :composeApp:run`
Expected: rendu conforme ci-dessus (ajouter au besoin un détour via la capture pour l'observer).

- [ ] **Step 8: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt composeApp/src/commonTest/kotlin/fr/dayview/app/RingScrubTest.kt
git commit -m "Draw detours as arcs on an outer ring lane"
```

---

## Notes de vérification (self-review)

- **Couverture du spec :**
  - Voie extérieure carvée (spec §1) → Task 2 Step 3.
  - Arc deux passes, couleur catégorie, épaisseur uniforme, suppression des `drawCircle` (spec §2) → Task 2 Step 4.
  - `DetourBody` gagne `startAngleDegrees`/`sweepDegrees`, perd `angleDegrees`/`sizeFraction` ; balayage minimum recentré ; critère milieu-hors-fenêtre conservé (spec §3) → Task 1 Step 3.
  - Hit-test/scrub sur la voie extérieure via `arcContainsAngle`/`angularDistanceToArc` (spec §4) → Task 1 Step 3 ; readout inchangé côté `RingReadout.kt` (déjà via `detourBodyAtAngle`).
  - Historique/`MiniRing` (spec §5) → aucun changement requis (réutilisation automatique).
  - Tests core + UI (spec Tests) → Task 1 Step 1/4, Task 2 Step 1/5.
- **Balayage minimum :** `MIN_DETOUR_SWEEP_DEGREES = 3.5f`, `DETOUR_ANGLE_TOLERANCE_DEGREES = 6f`, `detourLaneOutset = strokeWidth * .95f` — valeurs de départ ajustables visuellement à Task 2 Step 7 sans changer la structure.
- **Cohérence des noms :** `startAngleDegrees`/`sweepDegrees` (comme `BusyBlockArc`), `detourLaneOutset`/`detourInset`/`detourLaneSize` cohérents entre Task 2 Steps 3-4.
