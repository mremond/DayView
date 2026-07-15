# Détours en arcs extérieurs (remplacement des billes)

Date : 2026-07-15
Statut : conçu, en attente d'implémentation

## Contexte

L'anneau de `CountdownRing` (dans
[`DayViewTodayScreen.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt),
bloc `Canvas` ~ lignes 1039–1248) empile plusieurs couches par catégorie de données :

- **Zones de focus** : arcs menthe **sur la ligne** de l'anneau (`focusArcs.forEach`, ~1069).
- **Créneaux de calendrier** : arcs sur une voie concentrique **juste à l'intérieur** de
  l'anneau (`busyBlockArcs.forEach`, ~1190, voie `busyInset = inset + strokeWidth * .95f`).
- **Détours** : petites **billes** (halo + corps + reflet, trois `drawCircle`) placées à
  l'angle du milieu de l'épisode, chevauchant la ligne selon leur poids (`detourBodies.forEach`,
  ~1214).

Les billes encodent la durée par la taille (`sizeFraction`) et par un décalage radial
(légères = dehors, lourdes = dedans). Elles se distinguent mal des autres marqueurs ronds
(marqueur du moment, marqueur de grattage) et n'exploitent pas l'axe temporel.

## Objectif

Remplacer les billes par des **arcs de détour à l'extérieur du cercle**, formant un système
à trois voies concentriques lisible :

- **Dehors** : détours (nouveau).
- **Sur la ligne** : zones de focus (inchangé).
- **Dedans** : créneaux de calendrier (inchangé).

Les zones de focus et les créneaux de calendrier ne changent pas.

## Design

### 1. Voie extérieure des détours

L'anneau est aujourd'hui dessiné très près du bord du canvas :
`inset = strokeWidth / 2 + 4.dp`, donc la marge extérieure au-delà du trait n'est que de
~4.dp — trop étroite pour une voie extérieure lisible.

On **creuse une voie extérieure symétrique à la voie calendrier intérieure** en augmentant
l'`inset` de base d'une marge dédiée (`detourLaneWidth`, de l'ordre de `strokeWidth * .95f`
comme la voie calendrier). Concrètement :

- Nouvelle marge : `inset = strokeWidth / 2 + 4.dp + detourLaneOutset`, où
  `detourLaneOutset ≈ strokeWidth * .95f`. Toutes les couches existantes continuent d'utiliser
  les mêmes variables `inset` / `arcSize` qu'aujourd'hui — seule leur valeur de base grandit,
  donc l'anneau principal (track, sweep, focus, ticks, marqueurs, calendrier) rétrécit
  légèrement de façon uniforme, sans changer aucun de ses calculs relatifs.
- La voie détours vit au rayon `arcSize` + `detourLaneOutset` : `detourInset = inset -
  detourLaneOutset`, `detourLaneSize = Size(size - detourInset*2, …)`, `topLeft =
  Offset(detourInset, detourInset)`. Elle est donc concentrique et extérieure, miroir de la
  voie calendrier intérieure.

Valeurs exactes (largeur de voie, marge) à ajuster visuellement à l'implémentation ; la
contrainte dure est que tout reste dans les bornes du canvas (pas de rognage aux bords).

### 2. Rendu de chaque arc de détour

Pour chaque `DetourBody`, on remplace les trois `drawCircle` par un rendu d'arc à deux
passes, calqué sur les arcs de calendrier pour la cohérence visuelle :

- Passe halo : `color.copy(alpha = .16f)`, `Stroke(strokeWidth * .7f, cap = Round)`.
- Passe cœur : `color.copy(alpha = .92f)`, `Stroke(strokeWidth * .42f, cap = Round)`.
- `startAngle = body.startAngleDegrees`, `sweepAngle = body.sweepDegrees`.
- Couleur : `colors.detours[body.colorIndex % colors.detours.size]` (palette inchangée).
- Épaisseur **uniforme** : la longueur de l'arc porte l'information de durée, pas l'épaisseur.

(Les valeurs d'alpha/épaisseur peuvent être calées pour distinguer visuellement les détours
des créneaux de calendrier — p. ex. cap différent ou alpha légèrement plus soutenu — mais on
part du style calendrier comme base.)

### 3. Modèle de données (`Detours.kt`)

`DetourBody` gagne l'angle de départ et le balayage, calculés comme `BusyBlockArc` :

```kotlin
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

Dans `detourBodies(...)` :

- `fStart = ((start - windowStart) / total)`, `fEnd = ((end - windowStart) / total)`, clampés
  à la fenêtre (comme `busyBlockArcs`).
- `startAngleDegrees = -90f + fStart * 360f`.
- Balayage réel : `rawSweep = (fEnd - fStart) * 360f`.
- **Balayage minimum garanti** : `sweepDegrees = max(rawSweep, MIN_DETOUR_SWEEP)` avec
  `MIN_DETOUR_SWEEP ≈ 3.5f`. Quand on applique le plancher, on **recentre** l'arc sur le
  milieu du détour : `startAngleDegrees -= (sweepDegrees - rawSweep) / 2f`, pour que l'arc
  reste positionné sur le moment réel du détour.
- Critère d'inclusion : conserver le comportement actuel — un détour dont le **milieu** est
  hors fenêtre est écarté (`detourMidpointOutsideWindow`), pour ne pas changer le total
  hors-fenêtre (`offWindowDetoursTotal`) ni l'écran d'historique.

`angleDegrees` et `sizeFraction` sont **retirés** de `DetourBody` s'ils ne servent plus
ailleurs (voir §4). Le calcul du milieu reste disponible en interne pour le recentrage et le
critère d'inclusion.

### 4. Survol et lecture au grattage (hit-test)

Les détecteurs passent d'une logique « bille au point milieu » à une logique
« appartenance à l'arc, sur la voie extérieure » :

- `hitTestDetourBody(x, y, …)` : la bande radiale passe d'autour du trait (`0.70..1.02`) vers
  l'**extérieur** (~`0.99..1.10`, à caler sur la géométrie réelle de la voie), et la sélection
  angulaire utilise l'appartenance à l'arc (`startAngleDegrees .. startAngleDegrees +
  sweepDegrees`, cf. `arcContainsAngle`) avec une petite tolérance, au lieu de la distance au
  point milieu.
- `detourBodyAtAngle(bodies, angle)` : même bascule vers l'appartenance à l'arc, sans
  contrainte de rayon (utilisé par le grattage tactile).
- `RingReadout.kt` (`ringReadoutAt`, `ringReadoutAt` détour) : réutilise `detourBodyAtAngle`
  mis à jour ; le tooltip de survol
  ([`DayViewTodayScreen.kt` ~1458–1477](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt))
  et le readout de grattage (~1550–1561) restent, adaptés à la nouvelle géométrie si besoin
  (mêmes champs `category`/`description`/`start`/`end`).

### 5. Cohérence sur les autres écrans

- `MiniRing.kt` (vignettes d'historique) ne dessine pas les détours → inchangé.
- `HistoryDayScreen.kt` et `HistoryWeekScreen.kt` réutilisent la même vue d'anneau et
  `detourBodiesState` → bénéficient automatiquement du nouveau rendu, rien à faire de
  spécifique.

## Tests

- **`core` (`Detours.kt`)** : tests unitaires de `detourBodies(...)` —
  - un détour normal produit `startAngleDegrees`/`sweepDegrees` cohérents avec ses bornes
    (mêmes valeurs qu'un `busyBlockArc` équivalent) ;
  - un détour court (< plancher) obtient `sweepDegrees == MIN_DETOUR_SWEEP` et reste centré
    sur son milieu (`startAngleDegrees + sweepDegrees/2` ≈ angle du milieu) ;
  - un détour dont le milieu est hors fenêtre est toujours écarté ;
  - `hitTestDetourBody` / `detourBodyAtAngle` : un point sur la voie extérieure dans le
    balayage d'un arc le sélectionne ; un point sur l'ancienne bande intérieure ne le
    sélectionne plus.
- **`composeApp` (UI)** : suivre les contraintes de test Compose du projet (tags/données
  seedées, pas d'assertion sur `stringResource`). Vérifier au minimum que le rendu ne
  régresse pas (l'anneau et les couches existantes se dessinent).

## Hors périmètre (YAGNI)

- Pas d'encodage de la durée par l'épaisseur du trait (la longueur suffit).
- Pas de changement des zones de focus ni des créneaux de calendrier.
- Pas de changement du modèle `DetourEpisode` ni de la capture de détours.
- Pas de refonte de `MiniRing`.
