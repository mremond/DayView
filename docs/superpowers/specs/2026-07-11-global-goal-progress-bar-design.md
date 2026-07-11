# Barre de progression de l'objectif global — Conception

Date : 2026-07-11

## Objectif

Ajouter une barre de progression temporelle au panneau « OBJECTIF GLOBAL » de
l'écran principal. La barre représente le **temps de travail écoulé** entre le
moment où l'objectif a été fixé (le « départ ») et l'échéance, en réutilisant la
logique d'heures ouvrées déjà en place.

## Décisions produit

- La barre représente le **temps écoulé** (progression automatique), pas un
  avancement saisi manuellement.
- Le point de départ (0 %) est l'instant où l'échéance est validée, **modifiable**
  par l'utilisateur via un champ « Début ».
- Hors périmètre (YAGNI) : avancement manuel, barre dans le widget Android,
  barre dans la mini-app.

## Modèle de données & persistance

Ajouter un champ persisté `goalStartMillis: Long?` à côté de `goalDeadlineMillis`.

- `DayViewState` (`DayPreferences.kt`) : ajouter `goalStartMillis: Long? = null`
  et le charger dans `loadState()`.
- `DayPreferences` interface :
  - `fun loadGoalStartMillis(): Long?`
  - remplacer `saveGlobalGoal(title, deadlineMillis)` par
    `saveGlobalGoal(title: String, deadlineMillis: Long?, startMillis: Long?)`.
- `AndroidDayPreferences` : nouvelle clé `KEY_GOAL_START`, même schéma que
  `KEY_GOAL_DEADLINE` (`getLong`/sentinelle `NO_DEADLINE`).
- `DesktopDayPreferences` : idem avec `java.util.prefs`.
- `NoopDayPreferences` : `loadGoalStartMillis() = null`, signature `saveGlobalGoal`
  mise à jour.

## Règle du point de départ (`DayViewController`)

State interne : ajouter `goalStartText: String` et `goalStartMillis: Long?`.

- `commitGoalDeadline()` : après parsing de l'échéance,
  - si l'échéance est non nulle et que `goalStartMillis` est absent → fixer
    `goalStartMillis = nowMillis` ;
  - si l'échéance devient nulle → `goalStartMillis = null` ;
  - modifier une échéance existante ne réinitialise pas un départ déjà défini.
  - persister via `saveGlobalGoal(title, deadline, start)`.
- Nouvelles actions `setGoalStartText(value)` et `commitGoalStart()`, symétriques
  de l'échéance :
  - parsing via `parseGoalDeadline`, formatage via `formatGoalDeadline` ;
  - un départ vide est invalide tant qu'une échéance existe (on garde le départ
    courant) ;
  - contrainte : départ strictement antérieur à l'échéance, sinon champ en erreur
    (pas de persistance de la valeur invalide).
- `toState()` : exposer `goalStartText` (= `formatGoalDeadline(goalStartMillis)`
  ou vide) et `goalStartMillis`. `goalStartText` est un champ transitoire préservé
  au même titre que `goalDeadlineText`.

## Calcul de la progression (`GlobalGoal.kt`)

Fonction pure, testable :

```
fun calculateGoalProgress(
    nowMillis: Long,
    startMillis: Long,
    deadlineMillis: Long,
    startMinutesOfDay: Int,
    endMinutesOfDay: Int,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
): Float
```

- `total = calculateGoalWorkingMillis(startMillis, deadlineMillis, ...)`.
- `remaining = calculateGoalWorkingMillis(max(now, start), deadlineMillis, ...)`.
- Si `total <= 0` → `1f` si `now >= deadline` sinon `0f`.
- Sinon `progress = ((total - remaining).toFloat() / total).coerceIn(0f, 1f)`.

Cohérent avec le libellé « Encore X h » déjà affiché, qui dérive du même
`calculateGoalWorkingMillis`.

## UI (`GlobalGoalPanel`, `DayViewTodayScreen.kt`)

Affiché uniquement quand `deadlineMillis != null`.

- Sous la rangée titre/échéance existante : une **barre fine pleine largeur**.
  - Piste de fond : `colors.overlay` atténué ; remplissage : `colors.mint`.
  - Coins arrondis, hauteur ~6 dp, fraction animée via `animateFloatAsState`.
  - Pourcentage écoulé (`(progress * 100).roundToInt()`) affiché à droite.
  - Rendu via `Box`/`Canvas` (pas de dépendance Material `LinearProgressIndicator`
    imposée ; suivre le style existant du panneau).
- En dessous : un champ compact **« Début »** (`GoalTextField`, ~148 dp),
  pré-rempli avec `goalStartText`, éditable, `keyboardType = Number`,
  `onFocusLost = commitGoalStart`, en erreur si départ invalide/≥ échéance.
- Le libellé « Encore X h » en haut à droite reste inchangé.
- La progression est mémoïsée sur `nowMillis / 60_000`, `startMillis`,
  `deadlineMillis`, `workStartMinutes`, `workEndMinutes` (même schéma que le calcul
  des heures restantes existant).

## Tests

- `GlobalGoalTest` : `calculateGoalProgress`
  - `now == start` → 0 %,
  - `now < start` → 0 %,
  - mi-parcours (valeur attendue calculée sur heures ouvrées),
  - `now >= deadline` → 100 %,
  - `total <= 0` (start == deadline) → 0 % avant, 100 % à/après l'échéance.
- `AndroidDayPreferencesTest` / `DesktopDayPreferencesTest` : round-trip de
  `goalStartMillis` (save puis load), y compris l'effacement (null).
- `DayViewControllerTest` : `commitGoalDeadline` fixe le départ à `nowMillis`
  quand absent ; effacer l'échéance efface le départ ; `commitGoalStart` rejette
  un départ ≥ échéance.

## Fichiers touchés

- `composeApp/src/commonMain/kotlin/fr/dayview/app/GlobalGoal.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt`
- `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`
- `composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt`
- `composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt`
- Tests correspondants.
