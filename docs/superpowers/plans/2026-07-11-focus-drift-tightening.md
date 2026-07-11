# Focus-Drift Tightening & Intense-Focus Arcs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give a goal a set of on-goal apps so DayView can (a) nudge on sustained off-goal drift and (b) draw intense-focus arcs and a "Focused today" total on the day ring — all from DayView's own 1 s macOS frontmost sampling.

**Architecture:** One per-tick classification (on-goal / off-goal / neutral) of the frontmost app feeds two consumers during a focus session: the existing `FocusDriftDetector` (adds a sustained-off-goal rule beside churn) and a new `PresenceAccumulator` (runs of on-goal presence → intervals → arcs). The on-goal app list and the accumulated intervals are durable in `DayPreferences`, reaching the UI through the existing reactive preferences→controller→state path.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, kotlinx-datetime, JNA (macOS Objective-C runtime), `java.util.prefs` (desktop) / `SharedPreferences` (Android), kotlin.test.

## Global Constraints

- JDK 21 (`jvmToolchain(21)`); Android compileSdk 36.
- ktlint enforced — run `ktlintFormat` before committing.
- Full check command: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Pure-logic units live in `commonMain` (tested in `commonTest`); macOS-only code lives in `desktopMain` (tested in `desktopTest`, guarded by `os.name`).
- User-facing copy is **French**, matching existing strings.
- macOS desktop only for detection, nudge, and arcs. Android/common unchanged except shared preference fields (which return empty there).
- DayView's own bundle id is `fr.dayview.app`.
- Commit messages describe the change only — no "Generated with Claude Code", no `Co-Authored-By`, no test/verification section, no reference to internal planning docs.

---

## Increment A — On-goal app allowlist (durable config + settings picker)

Ships: you can pick on-goal apps for the goal in settings; the choice persists across restarts. No behavior change yet.

### Task A1: `AppRef` model + serialization (commonMain)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/OnGoalApps.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/OnGoalAppsTest.kt`

**Interfaces:**
- Produces: `data class AppRef(val bundleId: String, val displayName: String)`; `const val DEFAULT_GOAL_ID: String`; `fun encodeAppRefs(apps: Set<AppRef>): String`; `fun decodeAppRefs(encoded: String): Set<AppRef>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class OnGoalAppsTest {
    @Test
    fun encodeThenDecodeRoundTripsAppRefs() {
        val apps = setOf(
            AppRef("com.processone.draftline", "Draftline"),
            AppRef("com.apple.dt.Xcode", "Xcode"),
        )
        assertEquals(apps, decodeAppRefs(encodeAppRefs(apps)))
    }

    @Test
    fun decodeSkipsBlankAndMalformedLines() {
        val encoded = "com.processone.draftline\tDraftline\n\n\tOrphan\nnotabs"
        assertEquals(setOf(AppRef("com.processone.draftline", "Draftline")), decodeAppRefs(encoded))
    }

    @Test
    fun decodeOfEmptyStringIsEmptySet() {
        assertEquals(emptySet(), decodeAppRefs(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.OnGoalAppsTest"`
Expected: FAIL — unresolved reference `AppRef` / `encodeAppRefs`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app

/** An application eligible to count as on-goal presence. `bundleId` is the match key. */
data class AppRef(val bundleId: String, val displayName: String)

/** Id of the single global goal today; storage is goal-keyed for future multi-goal. */
const val DEFAULT_GOAL_ID: String = "default"

/** Serialize to one `bundleId\tdisplayName` line per app for preference storage. */
fun encodeAppRefs(apps: Set<AppRef>): String =
    apps.joinToString("\n") { "${it.bundleId}\t${it.displayName}" }

/** Inverse of [encodeAppRefs]; skips blank / malformed lines and empty bundle ids. */
fun decodeAppRefs(encoded: String): Set<AppRef> =
    encoded.split("\n")
        .mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size == 2 && parts[0].isNotBlank()) AppRef(parts[0], parts[1]) else null
        }
        .toSet()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.OnGoalAppsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/OnGoalApps.kt composeApp/src/commonTest/kotlin/fr/dayview/app/OnGoalAppsTest.kt
git commit -m "feat: add on-goal AppRef model and serialization"
```

### Task A2: Persist on-goal apps in `DayPreferences` (interface + 3 impls)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DesktopDayPreferencesTest.kt` (add case)

**Interfaces:**
- Consumes: `AppRef`, `DEFAULT_GOAL_ID`, `encodeAppRefs`, `decodeAppRefs` (Task A1).
- Produces: on `DayPreferences` — `fun loadOnGoalApps(goalId: String): Set<AppRef>` and `fun saveOnGoalApps(goalId: String, apps: Set<AppRef>)`; `DayPreferencesSnapshot.onGoalApps: Set<AppRef>` (the `DEFAULT_GOAL_ID` set).

- [ ] **Step 1: Write the failing test** (append to `DesktopDayPreferencesTest.kt`)

```kotlin
    @Test
    fun onGoalAppsRoundTripAndSurviveANewInstance() {
        val store = java.util.prefs.Preferences.userRoot().node("dayview-test-ongoal")
        store.clear()
        val prefs = DesktopDayPreferences(store)
        val apps = setOf(AppRef("com.processone.draftline", "Draftline"))
        prefs.saveOnGoalApps(DEFAULT_GOAL_ID, apps)

        assertEquals(apps, DesktopDayPreferences(store).loadOnGoalApps(DEFAULT_GOAL_ID))
        assertEquals(apps, DesktopDayPreferences(store).snapshot().onGoalApps)
        assertEquals(emptySet(), DesktopDayPreferences(store).loadOnGoalApps("other"))
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DesktopDayPreferencesTest"`
Expected: FAIL — unresolved `saveOnGoalApps` / `onGoalApps`.

- [ ] **Step 3: Implement — interface**

In `DayPreferences.kt`, add the field to the snapshot (after `netTimeSettings`):

```kotlin
    val netTimeSettings: NetTimeSettings = NetTimeSettings(),
    val onGoalApps: Set<AppRef> = emptySet(),
```

Add to the `DayPreferences` interface (after `saveNetTimeSettings`):

```kotlin
    fun loadOnGoalApps(goalId: String): Set<AppRef> = emptySet()
    fun saveOnGoalApps(goalId: String, apps: Set<AppRef>) = Unit
```

Extend `snapshot()` (add the trailing argument):

```kotlin
        netTimeSettings = loadNetTimeSettings(),
        onGoalApps = loadOnGoalApps(DEFAULT_GOAL_ID),
```

(No change needed to `DefaultDayPreferences` — it inherits the empty defaults.)

- [ ] **Step 4: Implement — desktop**

In `DesktopDayPreferences.kt`, add overrides (after `saveNetTimeSettings`):

```kotlin
    override fun loadOnGoalApps(goalId: String): Set<AppRef> =
        decodeAppRefs(storage.get(KEY_ON_GOAL_APPS_PREFIX + goalId, ""))

    override fun saveOnGoalApps(goalId: String, apps: Set<AppRef>) {
        storage.put(KEY_ON_GOAL_APPS_PREFIX + goalId, encodeAppRefs(apps))
        preferencesChanged()
    }
```

Add to the companion:

```kotlin
        const val KEY_ON_GOAL_APPS_PREFIX = "on_goal_apps."
```

- [ ] **Step 5: Implement — Android**

In `AndroidDayPreferences.kt`, add overrides (after `saveNetTimeSettings`):

```kotlin
    override fun loadOnGoalApps(goalId: String): Set<AppRef> =
        decodeAppRefs(storage.getString(KEY_ON_GOAL_APPS_PREFIX + goalId, "").orEmpty())

    override fun saveOnGoalApps(goalId: String, apps: Set<AppRef>) {
        storage.edit().putString(KEY_ON_GOAL_APPS_PREFIX + goalId, encodeAppRefs(apps)).apply()
    }
```

Add to the companion:

```kotlin
        const val KEY_ON_GOAL_APPS_PREFIX = "on_goal_apps."
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DesktopDayPreferencesTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain composeApp/src/desktopMain composeApp/src/androidMain composeApp/src/desktopTest
git commit -m "feat: persist on-goal apps per goal in preferences"
```

### Task A3: Enumerate running apps — `MacRunningApplicationsProvider` (desktopMain)

**Files:**
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacRunningApplicationsProvider.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/MacRunningApplicationsProviderTest.kt`

**Interfaces:**
- Consumes: `AppRef` (Task A1).
- Produces: `class MacRunningApplicationsProvider { fun runningApps(): List<AppRef> }` — regular foreground apps (`activationPolicy == 0`) with a non-blank bundle id, sorted by display name; empty off macOS or on any native failure.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertTrue

class MacRunningApplicationsProviderTest {
    private val isMacOS = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

    @Test
    fun returnsRegularAppsOnMacOsAndDoesNotThrow() {
        val apps = MacRunningApplicationsProvider().runningApps()
        if (!isMacOS) return
        // The test runner itself is a regular app, so at least one entry with a bundle id.
        assertTrue(apps.all { it.bundleId.isNotBlank() })
        assertTrue(apps == apps.sortedBy { it.displayName.lowercase() })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.MacRunningApplicationsProviderTest"`
Expected: FAIL — unresolved `MacRunningApplicationsProvider`.

- [ ] **Step 3: Write minimal implementation**

Model this on `MacFrontmostApplicationProvider` in `FocusDriftDetector.kt` (same JNA `ObjectiveCRuntime` pattern), extended with the selectors needed to iterate `runningApplications` (an `NSArray`).

```kotlin
package fr.dayview.app

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Enumerates regular (foreground) macOS apps for the on-goal app picker. */
class MacRunningApplicationsProvider {
    fun runningApps(): List<AppRef> = runCatching {
        if (!isMacOS) return emptyList()
        val workspace = msg(cls("NSWorkspace"), "sharedWorkspace") ?: return emptyList()
        val apps = msg(workspace, "runningApplications") ?: return emptyList()
        val count = Pointer.nativeValue(msgL(apps, "count") ?: return emptyList())
        (0 until count).mapNotNull { index ->
            val app = msgIndex(apps, index) ?: return@mapNotNull null
            // NSApplicationActivationPolicyRegular == 0
            val policy = Pointer.nativeValue(msgL(app, "activationPolicy") ?: return@mapNotNull null)
            if (policy != 0L) return@mapNotNull null
            val bundleId = readString(app, "bundleIdentifier") ?: return@mapNotNull null
            if (bundleId.isBlank()) return@mapNotNull null
            val name = readString(app, "localizedName") ?: bundleId
            AppRef(bundleId, name)
        }.distinctBy { it.bundleId }.sortedBy { it.displayName.lowercase() }
    }.getOrDefault(emptyList())

    private fun cls(name: String): Pointer? = runtime.objc_getClass(name)

    private fun msg(receiver: Pointer?, selector: String): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector))
    }

    /** Same as [msg] but the return is a scalar (count / policy) carried in a Pointer. */
    private fun msgL(receiver: Pointer?, selector: String): Pointer? = msg(receiver, selector)

    private fun msgIndex(array: Pointer?, index: Long): Pointer? {
        if (array == null) return null
        return runtime.objc_msgSend_index(array, runtime.sel_registerName("objectAtIndex:"), index)
    }

    private fun readString(receiver: Pointer, selector: String): String? {
        val value = msg(receiver, selector) ?: return null
        val utf8 = msg(value, "UTF8String") ?: return null
        return utf8.getString(0, Charsets.UTF_8.name())
    }

    @Suppress("ktlint:standard:function-naming")
    private interface ObjectiveCRuntime : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend_index(receiver: Pointer, selector: Pointer, index: Long): Pointer?
    }

    private companion object {
        val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
        val runtime: ObjectiveCRuntime by lazy { Native.load("objc", ObjectiveCRuntime::class.java) }
    }
}
```

Note: `objc_msgSend_index` binds the same native `objc_msgSend` symbol with an integer argument (JNA maps by method name to the C symbol; the trailing `_index` is only a Kotlin name, so declare it with `@JvmName`-free overloading via a distinct Kotlin name mapped through `Native.load`). If JNA rejects the duplicate symbol mapping, add `fun objc_msgSend(receiver: Pointer, selector: Pointer, index: Long): Pointer?` as an overload instead and call `runtime.objc_msgSend(array, sel, index)`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.MacRunningApplicationsProviderTest"`
Expected: PASS (asserts are skipped off macOS; on macOS the list is sorted and every bundle id non-blank).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/MacRunningApplicationsProvider.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/MacRunningApplicationsProviderTest.kt
git commit -m "feat: enumerate regular running macOS apps for on-goal picker"
```

### Task A4: Settings section to pick on-goal apps

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (add setter + state field)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewSettingsScreen.kt` (new section)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (wire callback + provide running apps)
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (pass a running-apps supplier into `DayViewApp`)

**Interfaces:**
- Consumes: `AppRef`, `DEFAULT_GOAL_ID`, `DayPreferences.saveOnGoalApps`, `MacRunningApplicationsProvider`.
- Produces: `DayViewUiState.onGoalApps: Set<AppRef>`; `DayViewController.setOnGoalApps(apps: Set<AppRef>)`.

- [ ] **Step 1: Add controller state + setter**

In `DayViewController.kt`, add to `DayViewUiState` (after `busyIntervals`):

```kotlin
    val onGoalApps: Set<AppRef> = emptySet(),
```

In `DayViewUiState`'s construction from the snapshot (`toUiState` mapper, near where `netTimeSettings` is copied), add `onGoalApps = onGoalApps`. Then add the setter to `DayViewController`:

```kotlin
    fun setOnGoalApps(apps: Set<AppRef>) {
        state = state.copy(onGoalApps = apps)
        preferences.saveOnGoalApps(DEFAULT_GOAL_ID, apps)
    }
```

- [ ] **Step 2: Add the settings UI section**

In `DayViewSettingsScreen.kt`, following the existing net-time settings section pattern (a titled block with rows), add a macOS-only section that:
- receives `onGoalApps: Set<AppRef>`, `runningApps: () -> List<AppRef>`, `onOnGoalAppsChange: (Set<AppRef>) -> Unit`, and a `Boolean` `showOnGoalSection`;
- lists selected apps by `displayName`, each with a "Retirer" (remove) affordance calling `onOnGoalAppsChange(onGoalApps - app)`;
- an "Ajouter des applications" button that opens a picker (a simple selectable list built from `runningApps()`), and each pick calls `onOnGoalAppsChange(onGoalApps + app)`.

Concrete section (place beside the net-time section; reuse the local `colors`, `SectionTitle`, and row composables already in the file):

```kotlin
if (showOnGoalSection) {
    SectionTitle("Applications de l’objectif")
    Text(
        "Ces applications comptent comme du travail sur l’objectif pendant une session de focus.",
        color = colors.muted,
        fontSize = 12.sp,
    )
    onGoalApps.sortedBy { it.displayName.lowercase() }.forEach { app ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(app.displayName, color = colors.cloud, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { onOnGoalAppsChange(onGoalApps - app) }) { Text("Retirer") }
        }
    }
    var showPicker by remember { mutableStateOf(false) }
    TextButton(onClick = { showPicker = !showPicker }) {
        Text(if (showPicker) "Fermer" else "Ajouter des applications")
    }
    if (showPicker) {
        val candidates = remember(showPicker) { runningApps() }
        candidates.filter { it !in onGoalApps }.forEach { app ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onOnGoalAppsChange(onGoalApps + app) },
            ) {
                Text(app.displayName, color = colors.cloud, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("+", color = colors.mint, fontSize = 16.sp)
            }
        }
    }
}
```

Add any missing imports (`clickable`, `TextButton`, `remember`, `mutableStateOf`) consistent with the file's existing imports.

- [ ] **Step 3: Wire callbacks through `App.kt`**

In `DayViewApp` (in `App.kt`), thread through new parameters `runningApps: () -> List<AppRef> = { emptyList() }` and pass to the settings screen: `onGoalApps = controller.state.onGoalApps`, `runningApps = runningApps`, `onOnGoalAppsChange = { controller.setOnGoalApps(it) }`, `showOnGoalSection = runningApps().isNotEmpty()`.

To avoid calling `runningApps()` on every recomposition for the gate, compute it once: `val hasRunningApps = remember { runningApps().isNotEmpty() }` and pass `showOnGoalSection = hasRunningApps`.

- [ ] **Step 4: Provide the supplier from `Main.kt` (desktop)**

In `Main.kt`, add `val runningApplicationsProvider = remember { MacRunningApplicationsProvider() }` beside the other `remember`s, and pass `runningApps = { runningApplicationsProvider.runningApps() }` into the `DayViewApp(...)` call. Android's `DayViewApp` call omits it (defaults to empty → section hidden).

- [ ] **Step 5: Build & lint**

Run: `./gradlew ktlintFormat :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, existing tests green.

- [ ] **Step 6: Manual verification (macOS)**

Run: `./gradlew :composeApp:run` → open Settings → the "Applications de l’objectif" section lists running apps, add/remove works, and the selection is still present after restarting the app.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src
git commit -m "feat: settings section to pick on-goal apps for the goal"
```

---

## Increment B — Sustained-off-goal drift nudge

Ships: during a focus session, staying in a non-on-goal app for 2 minutes fires the existing nudge, in addition to the churn rule.

### Task B1: Shared frontmost classification (commonMain)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/OnGoalClassification.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/OnGoalClassificationTest.kt`

**Interfaces:**
- Produces: `enum class OnGoalState { ON_GOAL, OFF_GOAL, NEUTRAL }`; `fun classifyFrontmost(frontmostBundleId: String?, onGoalBundleIds: Set<String>, dayViewBundleId: String = "fr.dayview.app"): OnGoalState`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class OnGoalClassificationTest {
    private val onGoal = setOf("com.processone.draftline")

    @Test fun inSetIsOnGoal() =
        assertEquals(OnGoalState.ON_GOAL, classifyFrontmost("com.processone.draftline", onGoal))

    @Test fun notInSetIsOffGoal() =
        assertEquals(OnGoalState.OFF_GOAL, classifyFrontmost("com.apple.Safari", onGoal))

    @Test fun dayViewItselfIsNeutral() =
        assertEquals(OnGoalState.NEUTRAL, classifyFrontmost("fr.dayview.app", onGoal))

    @Test fun blankOrNullIsNeutral() {
        assertEquals(OnGoalState.NEUTRAL, classifyFrontmost(null, onGoal))
        assertEquals(OnGoalState.NEUTRAL, classifyFrontmost("  ", onGoal))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.OnGoalClassificationTest"`
Expected: FAIL — unresolved `classifyFrontmost`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app

enum class OnGoalState { ON_GOAL, OFF_GOAL, NEUTRAL }

/** Classify the frontmost app for a focus session. DayView itself / blank is neutral. */
fun classifyFrontmost(
    frontmostBundleId: String?,
    onGoalBundleIds: Set<String>,
    dayViewBundleId: String = "fr.dayview.app",
): OnGoalState = when {
    frontmostBundleId.isNullOrBlank() -> OnGoalState.NEUTRAL
    frontmostBundleId == dayViewBundleId -> OnGoalState.NEUTRAL
    frontmostBundleId in onGoalBundleIds -> OnGoalState.ON_GOAL
    else -> OnGoalState.OFF_GOAL
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.OnGoalClassificationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/OnGoalClassification.kt composeApp/src/commonTest/kotlin/fr/dayview/app/OnGoalClassificationTest.kt
git commit -m "feat: add shared on-goal frontmost classification"
```

### Task B2: Sustained-off-goal rule in `FocusDriftDetector`

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/FocusDriftDetector.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusDriftDetectorTest.kt` (add cases)

**Interfaces:**
- Consumes: `classifyFrontmost`, `OnGoalState` (Task B1).
- Produces: new `observe` signature `fun observe(isFocusActive: Boolean, nowMillis: Long, frontmostBundleId: String?, onGoalBundleIds: Set<String> = emptySet()): Boolean`; constructor gains `sustainedOffGoalMillis: Long = 120_000L`.

- [ ] **Step 1: Write the failing tests** (append to `FocusDriftDetectorTest.kt`)

```kotlin
    @Test
    fun sustainedOffGoalFiresAfterThresholdOnceGracePassed() {
        val detector = FocusDriftDetector()
        val onGoal = setOf("com.goal.app")
        // t=0 activate (on-goal), then move off-goal and stay.
        detector.observe(true, 0L, "com.goal.app", onGoal)
        // Past 30s grace; off-goal for 2 min ⇒ fires.
        assertFalse(detector.observe(true, 31_000L, "com.other.app", onGoal))
        assertTrue(detector.observe(true, 31_000L + 120_000L, "com.other.app", onGoal))
    }

    @Test
    fun returningOnGoalResetsSustainedTimer() {
        val detector = FocusDriftDetector()
        val onGoal = setOf("com.goal.app")
        detector.observe(true, 0L, "com.goal.app", onGoal)
        detector.observe(true, 31_000L, "com.other.app", onGoal)      // off-goal starts
        detector.observe(true, 61_000L, "com.goal.app", onGoal)       // back on-goal ⇒ reset
        assertFalse(detector.observe(true, 150_000L, "com.other.app", onGoal))  // only 89s off-goal
    }

    @Test
    fun dayViewFrontmostIsNeutralAndDoesNotResetSustainedTimer() {
        val detector = FocusDriftDetector()
        val onGoal = setOf("com.goal.app")
        detector.observe(true, 0L, "com.goal.app", onGoal)
        detector.observe(true, 31_000L, "com.other.app", onGoal)      // off-goal starts at 31s
        detector.observe(true, 60_000L, "fr.dayview.app", onGoal)     // neutral: timer keeps running
        assertTrue(detector.observe(true, 31_000L + 120_000L, "com.other.app", onGoal))
    }

    @Test
    fun emptyAllowlistDisablesSustainedRule() {
        val detector = FocusDriftDetector()
        detector.observe(true, 0L, "com.other.app", emptySet())
        assertFalse(detector.observe(true, 500_000L, "com.other.app", emptySet()))
    }
```

Ensure `assertFalse` / `assertTrue` are imported.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FocusDriftDetectorTest"`
Expected: FAIL — `observe` does not accept a 4th argument.

- [ ] **Step 3: Implement**

Edit `FocusDriftDetector` to add the constructor parameter, the `offGoalSinceMillis` field, and restructure `observe` so churn and sustained both run each tick and share the cooldown. Replace the class body's `observe`/state with:

```kotlin
internal class FocusDriftDetector(
    private val switchThreshold: Int = 4,
    private val observationWindowMillis: Long = 45_000L,
    private val initialGraceMillis: Long = 30_000L,
    private val reminderCooldownMillis: Long = 5 * 60_000L,
    private val sustainedOffGoalMillis: Long = 120_000L,
    private val dayViewBundleId: String = "fr.dayview.app",
) {
    private val switchTimes = ArrayDeque<Long>()
    private var wasActive = false
    private var activeSinceMillis = 0L
    private var lastBundleId: String? = null
    private var nextReminderAtMillis = 0L
    private var offGoalSinceMillis: Long? = null

    fun observe(
        isFocusActive: Boolean,
        nowMillis: Long,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String> = emptySet(),
    ): Boolean {
        if (!isFocusActive) {
            reset()
            return false
        }
        if (!wasActive) {
            wasActive = true
            activeSinceMillis = nowMillis
            lastBundleId = frontmostBundleId.takeUnless { it == dayViewBundleId }
            offGoalSinceMillis = null
            return false
        }
        val pastGrace = nowMillis - activeSinceMillis >= initialGraceMillis
        val fired = churnFired(nowMillis, frontmostBundleId, pastGrace) ||
            sustainedFired(nowMillis, frontmostBundleId, onGoalBundleIds, pastGrace)
        if (fired) {
            switchTimes.clear()
            offGoalSinceMillis = null
            nextReminderAtMillis = nowMillis + reminderCooldownMillis
        }
        return fired
    }

    private fun churnFired(nowMillis: Long, frontmostBundleId: String?, pastGrace: Boolean): Boolean {
        if (frontmostBundleId.isNullOrBlank() || frontmostBundleId == dayViewBundleId) return false
        val previousBundleId = lastBundleId
        lastBundleId = frontmostBundleId
        if (previousBundleId == null || previousBundleId == frontmostBundleId) return false
        if (!pastGrace) return false
        switchTimes.addLast(nowMillis)
        while (switchTimes.isNotEmpty() && nowMillis - switchTimes.first() > observationWindowMillis) {
            switchTimes.removeFirst()
        }
        return switchTimes.size >= switchThreshold && nowMillis >= nextReminderAtMillis
    }

    private fun sustainedFired(
        nowMillis: Long,
        frontmostBundleId: String?,
        onGoalBundleIds: Set<String>,
        pastGrace: Boolean,
    ): Boolean {
        if (onGoalBundleIds.isEmpty()) {
            offGoalSinceMillis = null
            return false
        }
        when (classifyFrontmost(frontmostBundleId, onGoalBundleIds, dayViewBundleId)) {
            OnGoalState.ON_GOAL -> { offGoalSinceMillis = null; return false }
            OnGoalState.NEUTRAL -> return false
            OnGoalState.OFF_GOAL -> {
                val since = offGoalSinceMillis ?: nowMillis.also { offGoalSinceMillis = it }
                return pastGrace &&
                    nowMillis - since >= sustainedOffGoalMillis &&
                    nowMillis >= nextReminderAtMillis
            }
        }
    }

    private fun reset() {
        wasActive = false
        activeSinceMillis = 0L
        lastBundleId = null
        switchTimes.clear()
        nextReminderAtMillis = 0L
        offGoalSinceMillis = null
    }
}
```

Leave `FocusResumeDetector`, `MacFrontmostApplicationProvider`, and the JNA interface in the file unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FocusDriftDetectorTest"`
Expected: PASS — new sustained cases and all pre-existing churn cases.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/FocusDriftDetector.kt composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusDriftDetectorTest.kt
git commit -m "feat: nudge on sustained off-goal drift beside churn"
```

### Task B3: Feed the on-goal set into the detector from `Main.kt`

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt`

**Interfaces:**
- Consumes: `DayPreferencesSnapshot.onGoalApps`, the new `observe(..., onGoalBundleIds)`.

- [ ] **Step 1: Implement wiring**

In the `LaunchedEffect` loop, replace the drift-detection branch so it passes the active goal's on-goal bundle ids:

```kotlin
            } else if (
                focusDriftDetector.observe(
                    focusIsActive,
                    currentNowMillis,
                    frontmostBundleId,
                    currentPreferences.onGoalApps.map { it.bundleId }.toSet(),
                )
            ) {
                focusDriftReminderId = currentNowMillis
                nudgeNotifier.notify(currentPreferences.focusIntention)
            } else if (!focusIsActive) {
```

- [ ] **Step 2: Build & lint**

Run: `./gradlew ktlintFormat :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual verification (macOS)**

Run: `./gradlew :composeApp:run`, set Draftline (or any app) as the only on-goal app, start a focus session, then stay in a different app for > 2 min → the nudge notification appears. Staying in the on-goal app does not nudge.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt
git commit -m "feat: pass on-goal apps to drift detector during focus"
```

---

## Increment C — Intense-focus arcs + "Focused today"

Ships: on-goal presence during focus sessions accumulates into intervals, persists per day, and draws accent arcs on the ring with a "Focus H h MM" total.

### Task C1: `PresenceAccumulator` + `FocusPresenceInterval` (commonMain)

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/PresenceAccumulator.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/PresenceAccumulatorTest.kt`

**Interfaces:**
- Consumes: `OnGoalState` (Task B1).
- Produces: `data class FocusPresenceInterval(val startMillis: Long, val endMillis: Long)`; `class PresenceAccumulator(bridgeMillis: Long = 30_000L, minIntervalMillis: Long = 120_000L)` with `fun restore(intervals: List<FocusPresenceInterval>, dayKey: Long)` and `fun observe(nowMillis: Long, state: OnGoalState, dayKey: Long): List<FocusPresenceInterval>`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PresenceAccumulatorTest {
    private val day = 20_000L
    private fun on(a: PresenceAccumulator, t: Long) = a.observe(t, OnGoalState.ON_GOAL, day)
    private fun off(a: PresenceAccumulator, t: Long) = a.observe(t, OnGoalState.OFF_GOAL, day)
    private fun neutral(a: PresenceAccumulator, t: Long) = a.observe(t, OnGoalState.NEUTRAL, day)

    @Test
    fun aContinuousOnGoalRunBecomesOneInterval() {
        val a = PresenceAccumulator()
        on(a, 0L)
        val result = on(a, 130_000L) // 2m10s on-goal, exceeds 2m minimum
        assertEquals(listOf(FocusPresenceInterval(0L, 130_000L)), result)
    }

    @Test
    fun shortOnGoalDipIsDiscardedBelowMinimum() {
        val a = PresenceAccumulator()
        on(a, 0L)
        on(a, 60_000L)                 // only 1 min on-goal
        val result = off(a, 120_000L)  // gap ≥ bridge closes it → discarded
        assertEquals(emptyList(), result)
    }

    @Test
    fun briefBlipUnderBridgeDoesNotSplitTheInterval() {
        val a = PresenceAccumulator()
        on(a, 0L)
        on(a, 120_000L)                // on-goal through 2 min (lastOnGoal = 120s)
        neutral(a, 140_000L)           // 20s blip (< 30s bridge), interval still open
        val result = on(a, 150_000L)   // back on-goal, one continuous interval
        assertEquals(listOf(FocusPresenceInterval(0L, 150_000L)), result)
    }

    @Test
    fun gapAtOrAboveBridgeClosesThenANewIntervalStarts() {
        val a = PresenceAccumulator()
        on(a, 0L); on(a, 130_000L)     // interval 1: [0, 130000]
        off(a, 170_000L)               // 40s gap ≥ bridge → closes interval 1
        on(a, 200_000L); on(a, 400_000L) // interval 2 grows
        val result = on(a, 400_000L)
        assertEquals(
            listOf(
                FocusPresenceInterval(0L, 130_000L),
                FocusPresenceInterval(200_000L, 400_000L),
            ),
            result,
        )
    }

    @Test
    fun dayRolloverClearsAccumulatedIntervals() {
        val a = PresenceAccumulator()
        on(a, 0L); on(a, 130_000L)
        val next = a.observe(500_000L, OnGoalState.ON_GOAL, day + 1) // new day
        assertEquals(emptyList(), next)
    }

    @Test
    fun restoreSeedsClosedIntervalsForTheDay() {
        val a = PresenceAccumulator()
        a.restore(listOf(FocusPresenceInterval(0L, 130_000L)), day)
        val result = neutral(a, 200_000L)
        assertEquals(listOf(FocusPresenceInterval(0L, 130_000L)), result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PresenceAccumulatorTest"`
Expected: FAIL — unresolved `PresenceAccumulator`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app

/** A stretch of continuous on-goal presence (local-day relative), for the ring arcs. */
data class FocusPresenceInterval(val startMillis: Long, val endMillis: Long)

/**
 * Builds today's intense-focus intervals from the per-tick on-goal classification.
 * On-goal ticks extend the open interval; off-goal/neutral gaps shorter than [bridgeMillis]
 * are tolerated; a longer gap closes it; intervals below [minIntervalMillis] are discarded.
 */
class PresenceAccumulator(
    private val bridgeMillis: Long = 30_000L,
    private val minIntervalMillis: Long = 120_000L,
) {
    private val closed = mutableListOf<FocusPresenceInterval>()
    private var openStart: Long? = null
    private var lastOnGoalMillis: Long = 0L
    private var currentDayKey: Long = Long.MIN_VALUE

    /** Seed closed intervals for [dayKey] at startup (persisted state). */
    fun restore(intervals: List<FocusPresenceInterval>, dayKey: Long) {
        currentDayKey = dayKey
        closed.clear()
        closed.addAll(intervals)
        openStart = null
    }

    fun observe(nowMillis: Long, state: OnGoalState, dayKey: Long): List<FocusPresenceInterval> {
        if (dayKey != currentDayKey) {
            currentDayKey = dayKey
            closed.clear()
            openStart = null
            lastOnGoalMillis = 0L
        }
        when (state) {
            OnGoalState.ON_GOAL -> {
                if (openStart == null) openStart = nowMillis
                lastOnGoalMillis = nowMillis
            }
            OnGoalState.OFF_GOAL, OnGoalState.NEUTRAL -> {
                val start = openStart
                if (start != null && nowMillis - lastOnGoalMillis >= bridgeMillis) {
                    if (lastOnGoalMillis - start >= minIntervalMillis) {
                        closed.add(FocusPresenceInterval(start, lastOnGoalMillis))
                    }
                    openStart = null
                }
            }
        }
        return snapshotIntervals()
    }

    private fun snapshotIntervals(): List<FocusPresenceInterval> {
        val start = openStart
        if (start != null && lastOnGoalMillis - start >= minIntervalMillis) {
            return closed + FocusPresenceInterval(start, lastOnGoalMillis)
        }
        return closed.toList()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.PresenceAccumulatorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/PresenceAccumulator.kt composeApp/src/commonTest/kotlin/fr/dayview/app/PresenceAccumulatorTest.kt
git commit -m "feat: accumulate on-goal presence into intense-focus intervals"
```

### Task C2: `focusArcs` projection + "Focused today" total (commonMain)

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt` (add `FocusArc`, `focusArcs`, `focusedMillis`)
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/PresenceAccumulatorTest.kt` (add projection cases) or a new `FocusArcsTest.kt`

**Interfaces:**
- Consumes: `FocusPresenceInterval` (Task C1); reuses window/angle conventions of `busyArcs`.
- Produces: `data class FocusArc(val startAngleDegrees: Float, val sweepDegrees: Float)`; `fun focusArcs(windowStartMillis: Long, windowEndMillis: Long, intervals: List<FocusPresenceInterval>): List<FocusArc>`; `fun focusedMillis(windowStartMillis: Long, windowEndMillis: Long, intervals: List<FocusPresenceInterval>): Long`.

- [ ] **Step 1: Write the failing test** (new file `FocusArcsTest.kt`)

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusArcsTest {
    // Window 0..360_000 ms maps linearly to 0..360°, starting at -90°.
    private val start = 0L
    private val end = 360_000L

    @Test
    fun intervalProjectsToExpectedAngleAndSweep() {
        val arcs = focusArcs(start, end, listOf(FocusPresenceInterval(0L, 90_000L)))
        assertEquals(1, arcs.size)
        assertEquals(-90f, arcs[0].startAngleDegrees)
        assertEquals(90f, arcs[0].sweepDegrees) // 25% of the ring
    }

    @Test
    fun focusedMillisClipsToTheWindowAndSums() {
        val intervals = listOf(
            FocusPresenceInterval(-10_000L, 60_000L), // clipped start → 60s in-window
            FocusPresenceInterval(300_000L, 400_000L), // clipped end → 60s in-window
        )
        assertEquals(120_000L, focusedMillis(start, end, intervals))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FocusArcsTest"`
Expected: FAIL — unresolved `focusArcs` / `focusedMillis`.

- [ ] **Step 3: Write minimal implementation** (append to `CalendarNetTime.kt`)

```kotlin
data class FocusArc(
    val startAngleDegrees: Float,
    val sweepDegrees: Float,
)

private fun clipToWindow(
    intervals: List<FocusPresenceInterval>,
    windowStartMillis: Long,
    windowEndMillis: Long,
): List<FocusPresenceInterval> =
    intervals.map {
        FocusPresenceInterval(
            startMillis = it.startMillis.coerceIn(windowStartMillis, windowEndMillis),
            endMillis = it.endMillis.coerceIn(windowStartMillis, windowEndMillis),
        )
    }.filter { it.endMillis > it.startMillis }

/** Project intense-focus intervals to ring arcs (same convention as [busyArcs]). */
fun focusArcs(
    windowStartMillis: Long,
    windowEndMillis: Long,
    intervals: List<FocusPresenceInterval>,
): List<FocusArc> {
    val duration = (windowEndMillis - windowStartMillis).toFloat()
    if (duration <= 0f) return emptyList()
    return clipToWindow(intervals, windowStartMillis, windowEndMillis).map {
        val fStart = (it.startMillis - windowStartMillis) / duration
        val fEnd = (it.endMillis - windowStartMillis) / duration
        FocusArc(
            startAngleDegrees = -90f + fStart * 360f,
            sweepDegrees = (fEnd - fStart) * 360f,
        )
    }
}

/** Total intense-focus time within the day window. */
fun focusedMillis(
    windowStartMillis: Long,
    windowEndMillis: Long,
    intervals: List<FocusPresenceInterval>,
): Long = clipToWindow(intervals, windowStartMillis, windowEndMillis)
    .sumOf { it.endMillis - it.startMillis }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FocusArcsTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/CalendarNetTime.kt composeApp/src/commonTest/kotlin/fr/dayview/app/FocusArcsTest.kt
git commit -m "feat: project intense-focus intervals to ring arcs and total"
```

### Task C3: Persist presence intervals per day in `DayPreferences`

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferences.kt`
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/DesktopDayPreferences.kt`
- Modify: `composeApp/src/androidMain/kotlin/fr/dayview/app/AndroidDayPreferences.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/DesktopDayPreferencesTest.kt` (add case)

**Interfaces:**
- Consumes: `FocusPresenceInterval` (Task C1).
- Produces: `DayPreferences.loadFocusPresence(): Pair<Long, List<FocusPresenceInterval>>` (day-key + intervals; default `-1L to emptyList()`); `DayPreferences.saveFocusPresence(dayKey: Long, intervals: List<FocusPresenceInterval>)`. Not added to the snapshot (Main reads it directly at startup).

- [ ] **Step 1: Write the failing test** (append to `DesktopDayPreferencesTest.kt`)

```kotlin
    @Test
    fun focusPresenceRoundTripsWithDayKey() {
        val store = java.util.prefs.Preferences.userRoot().node("dayview-test-presence")
        store.clear()
        val prefs = DesktopDayPreferences(store)
        val intervals = listOf(FocusPresenceInterval(1_000L, 2_000L), FocusPresenceInterval(5_000L, 9_000L))
        prefs.saveFocusPresence(19_000L, intervals)

        val (day, loaded) = DesktopDayPreferences(store).loadFocusPresence()
        assertEquals(19_000L, day)
        assertEquals(intervals, loaded)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DesktopDayPreferencesTest"`
Expected: FAIL — unresolved `saveFocusPresence`.

- [ ] **Step 3: Implement — interface** (`DayPreferences.kt`, in the interface)

```kotlin
    fun loadFocusPresence(): Pair<Long, List<FocusPresenceInterval>> = -1L to emptyList()
    fun saveFocusPresence(dayKey: Long, intervals: List<FocusPresenceInterval>) = Unit
```

- [ ] **Step 4: Implement — desktop** (`DesktopDayPreferences.kt`)

```kotlin
    override fun loadFocusPresence(): Pair<Long, List<FocusPresenceInterval>> {
        val day = storage.getLong(KEY_FOCUS_PRESENCE_DAY, -1L)
        val intervals = storage.get(KEY_FOCUS_PRESENCE, "")
            .split("\n")
            .mapNotNull { line ->
                val parts = line.split(",")
                val s = parts.getOrNull(0)?.toLongOrNull()
                val e = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size == 2 && s != null && e != null) FocusPresenceInterval(s, e) else null
            }
        return day to intervals
    }

    override fun saveFocusPresence(dayKey: Long, intervals: List<FocusPresenceInterval>) {
        storage.putLong(KEY_FOCUS_PRESENCE_DAY, dayKey)
        storage.put(KEY_FOCUS_PRESENCE, intervals.joinToString("\n") { "${it.startMillis},${it.endMillis}" })
        preferencesChanged()
    }
```

Companion keys:

```kotlin
        const val KEY_FOCUS_PRESENCE_DAY = "focus_presence_day"
        const val KEY_FOCUS_PRESENCE = "focus_presence"
```

- [ ] **Step 5: Implement — Android** (`AndroidDayPreferences.kt`, same body using `SharedPreferences`)

```kotlin
    override fun loadFocusPresence(): Pair<Long, List<FocusPresenceInterval>> {
        val day = storage.getLong(KEY_FOCUS_PRESENCE_DAY, -1L)
        val intervals = storage.getString(KEY_FOCUS_PRESENCE, "").orEmpty()
            .split("\n")
            .mapNotNull { line ->
                val parts = line.split(",")
                val s = parts.getOrNull(0)?.toLongOrNull()
                val e = parts.getOrNull(1)?.toLongOrNull()
                if (parts.size == 2 && s != null && e != null) FocusPresenceInterval(s, e) else null
            }
        return day to intervals
    }

    override fun saveFocusPresence(dayKey: Long, intervals: List<FocusPresenceInterval>) {
        storage.edit()
            .putLong(KEY_FOCUS_PRESENCE_DAY, dayKey)
            .putString(KEY_FOCUS_PRESENCE, intervals.joinToString("\n") { "${it.startMillis},${it.endMillis}" })
            .apply()
    }
```

Companion keys (Android): `KEY_FOCUS_PRESENCE_DAY = "focus_presence_day"`, `KEY_FOCUS_PRESENCE = "focus_presence"`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.DesktopDayPreferencesTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/commonMain composeApp/src/desktopMain composeApp/src/androidMain composeApp/src/desktopTest
git commit -m "feat: persist focus-presence intervals per day"
```

### Task C4: Accumulate presence in `Main.kt` and route intervals to the UI

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewController.kt` (state field + computed arcs/total + setter)

**Interfaces:**
- Consumes: `PresenceAccumulator`, `classifyFrontmost`, `DayPreferences.loadFocusPresence/saveFocusPresence`, `focusArcs`, `focusedMillis`.
- Produces: `DayViewUiState.focusPresenceIntervals: List<FocusPresenceInterval>` + computed `focusArcsState: List<FocusArc>` and `focusedTodayMillis: Long`; `DayViewController.setFocusPresenceIntervals(list)`.

- [ ] **Step 1: Controller state + computed values + setter** (`DayViewController.kt`)

Add to `DayViewUiState` (after `onGoalApps`):

```kotlin
    val focusPresenceIntervals: List<FocusPresenceInterval> = emptyList(),
```

Add computed properties near `busyArcsState`:

```kotlin
    val focusArcsState: List<FocusArc>
        get() {
            val (start, end) = dayWindow
            return focusArcs(start, end, focusPresenceIntervals)
        }

    val focusedTodayMillis: Long
        get() {
            val (start, end) = dayWindow
            return focusedMillis(start, end, focusPresenceIntervals)
        }
```

Add the setter to `DayViewController`:

```kotlin
    fun setFocusPresenceIntervals(intervals: List<FocusPresenceInterval>) {
        state = state.copy(focusPresenceIntervals = intervals)
    }
```

Map it in `toUiState` if the snapshot ever carries it (it does not here — Main pushes it), so no snapshot change is needed.

- [ ] **Step 2: Wire the accumulator in `Main.kt`**

Add imports for `kotlinx.datetime` (`TimeZone`, `toLocalDateTime`) consistent with the codebase, and near the other `remember`s:

```kotlin
    val presenceAccumulator = remember {
        PresenceAccumulator().also {
            val (day, intervals) = preferences.loadFocusPresence()
            if (day >= 0) it.restore(intervals, day)
        }
    }
    var focusPresenceIntervals by remember { mutableStateOf(preferences.loadFocusPresence().second) }
    var lastPresenceSaveMillis by remember { mutableLongStateOf(0L) }
```

Inside the loop, after `frontmostBundleId` is computed and the drift branch runs, add accumulation. The in-memory state updates every tick when it changes (cheap, drives the live arc at the same cadence as the clock), but **persistence is throttled** — the in-progress interval's `endMillis` grows every tick, so an unconditional write would hit the prefs store and fan out observers once per second for the whole session. Persist only on a structural change (an interval closed or a day rollover changed the list size) or at most once per 30 s:

```kotlin
            val onGoalBundleIds = currentPreferences.onGoalApps.map { it.bundleId }.toSet()
            val dayKey = kotlin.time.Instant.fromEpochMilliseconds(currentNowMillis)
                .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
                .date.toEpochDays()
            val classification = classifyFrontmost(frontmostBundleId, onGoalBundleIds)
            val updatedIntervals = if (focusIsActive) {
                presenceAccumulator.observe(currentNowMillis, classification, dayKey)
            } else {
                focusPresenceIntervals
            }
            if (updatedIntervals != focusPresenceIntervals) {
                val structuralChange = updatedIntervals.size != focusPresenceIntervals.size
                focusPresenceIntervals = updatedIntervals
                if (structuralChange || currentNowMillis - lastPresenceSaveMillis >= 30_000L) {
                    preferences.saveFocusPresence(dayKey, updatedIntervals)
                    lastPresenceSaveMillis = currentNowMillis
                }
            }
```

`onGoalBundleIds` is hoisted so the same set feeds both `focusDriftDetector.observe(...)` (Task B3) and `classifyFrontmost(...)`. When `!focusIsActive`, `frontmostBundleId` is already `null`, so `classification` is `NEUTRAL` and the accumulator is not called. Note `.date.toEpochDays()` returns `Long` in this kotlinx-datetime version — no `.toLong()` needed.

- [ ] **Step 3: Push intervals into the rendered state**

`Main.kt` renders `DayViewApp(preferences = preferences, ...)`, which builds its own `DayViewController` from the preferences snapshot. To get the intervals into that controller without a snapshot field, pass them as a parameter: add `focusPresenceIntervals: List<FocusPresenceInterval> = emptyList()` to `DayViewApp` and, inside it, `LaunchedEffect(focusPresenceIntervals) { controller.setFocusPresenceIntervals(focusPresenceIntervals) }`. Then in `Main.kt` pass `focusPresenceIntervals = focusPresenceIntervals`.

- [ ] **Step 4: Build & lint**

Run: `./gradlew ktlintFormat :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src
git commit -m "feat: accumulate on-goal presence and route intervals to the day view"
```

### Task C5: Draw intense-focus arcs and "Focused today" on the ring

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt`

**Interfaces:**
- Consumes: `DayViewUiState.focusArcsState`, `DayViewUiState.focusedTodayMillis`, `FocusArc`, `formatDurationHm`.

- [ ] **Step 1: Thread the values to `CountdownCircle`**

At the two `CountdownCircle(...)` call sites (around lines 142 and 182), add `focusArcs = state.focusArcsState` and `focusedTodayMillis = state.focusedTodayMillis`. Add matching parameters to `CountdownCircle`:

```kotlin
    focusArcs: List<FocusArc> = emptyList(),
    focusedTodayMillis: Long = 0L,
```

- [ ] **Step 2: Draw the focus arcs** — inside the `Canvas`, after the `busyArcs.forEach { ... }` block, add:

```kotlin
                    focusArcs.forEach { arc ->
                        drawArc(
                            color = colors.mint.copy(alpha = .55f),
                            startAngle = arc.startAngleDegrees,
                            sweepAngle = arc.sweepDegrees,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(strokeWidth * .5f, cap = StrokeCap.Round),
                        )
                    }
```

(Thinner, mint, rounded — visually distinct from the wider grey busy arcs.)

- [ ] **Step 3: Add the "Focused today" readout** — in the center `Column`, after the net-time block, add:

```kotlin
                        if (focusedTodayMillis > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Focus ${formatDurationHm(focusedTodayMillis)}",
                                color = colors.mint,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = .5.sp,
                            )
                        }
```

- [ ] **Step 4: Build & lint**

Run: `./gradlew ktlintFormat :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Step 5: Manual verification (macOS)**

Run: `./gradlew :composeApp:run`, add an on-goal app, start a focus session, work in that app for > 2 min → a mint arc appears on the ring over that span and the center shows "Focus 2 h MM". Restart the app during the same day → the arc and total are still there.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt
git commit -m "feat: draw intense-focus arcs and Focused today on the ring"
```

---

## Final verification

- [ ] Run the full gate: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` → all green, no stderr.
- [ ] Update `README.md` "Temps net" area with a short "Focus / objectif" paragraph describing the on-goal apps, the drift nudge, and the intense-focus arcs (French, developer-owned wording). Commit as `docs: document on-goal focus tracking`.

---

## Notes for the implementer

- **Increments are independently shippable and ordered A → B → C.** A adds config with no behavior change; B adds the nudge; C adds the arcs. Each ends green.
- **Shared classifier** (`classifyFrontmost`) is the single source of on/off/neutral truth — do not re-derive it in the accumulator or the detector.
- **Android** compiles against every new `DayPreferences` method but returns empty; no arcs, no detection there (by design).
- The `objc_msgSend` index-selector call in Task A3 is the one native subtlety — if JNA's symbol mapping complains, use the overload fallback noted in that task.
