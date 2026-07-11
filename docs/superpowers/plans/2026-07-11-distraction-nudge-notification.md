# Notification système sur détection de dispersion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Émettre une notification système macOS quand DayView détecte une dispersion, sans voler le focus à la détection ; le clic sur la notification ramène DayView au premier plan (comportement macOS natif).

**Architecture:** La notification est postée **in-process depuis la JVM** via le runtime Objective-C (`objc_msgSend`, JNA), sur le modèle de `MacFrontmostApplicationProvider` déjà présent dans `FocusDriftDetector.kt`. Aucun process séparé, aucun helper Swift, aucune nouvelle dépendance ni tâche de build. On câble l'appel dans la branche « dispersion détectée » de la boucle d'observation de `Main.kt`, et on retire le vol de focus sur ce chemin (conservé pour le rituel de reprise).

**Tech Stack:** Kotlin Multiplatform (cible `jvm("desktop")`), Compose Desktop, JNA 5.17 (déjà en dépendance de `desktopMain`), API AppKit `NSUserNotification` / `NSUserNotificationCenter`. Tests avec `kotlin.test`.

## Global Constraints

- **macOS desktop uniquement.** Aucune modification Android, iOS ni common. La détection de dispersion n'existe que dans `desktopMain`.
- **In-process, pas de helper.** Notification postée par le process JVM via `objc_msgSend` (JNA). Réutiliser la dépendance `libs.jna` déjà déclarée dans `desktopMain` — ne pas en ajouter d'autre.
- **API :** `NSUserNotification` + `NSUserNotificationCenter.defaultUserNotificationCenter` → `deliverNotification:` (dépréciée mais fonctionnelle ; bien plus simple en JNA que `UNUserNotificationCenter`).
- **Robustesse :** tout appel natif enveloppé dans `runCatching` ; un échec de notification ne doit jamais casser la boucle d'observation ni empêcher l'affichage de la carte. No-op silencieux hors macOS.
- **Pas de throttling dans le notifieur.** Le rythme est déjà borné par `FocusDriftDetector` (`reminderCooldownMillis = 5 min`).
- **Libellés exacts :** titre = `Reviens à l'essentiel` ; corps = l'intention de focus si non vide, sinon `Une seule chose à la fois.`
- **ktlint doit passer.** L'interface JNA porte `@Suppress("ktlint:standard:function-naming")` (méthodes en `snake_case` du runtime C), comme l'existant.
- **Détection inchangée :** `FocusDriftDetector` et `FocusResumeDetector` ne sont pas touchés (déjà testés unitairement).

---

## File Structure

- **Create** `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusNudgeNotifier.kt`
  - `FocusNudgeCopy` : objet pur portant titre + corps (logique de repli testable, sans natif).
  - `MacFocusNudgeNotifier` : wrapper mince autour du runtime Objective-C qui poste la notification.
- **Create** `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusNudgeCopyTest.kt`
  - Tests unitaires déterministes de `FocusNudgeCopy.body(...)` (tournent sur toute plateforme).
- **Create** `composeApp/src/desktopTest/kotlin/fr/dayview/app/MacFocusNudgeNotifierTest.kt`
  - Test de fumée gardé par macOS (modèle `MacFocusStatusItemTest`).
- **Modify** `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt`
  - Instancier le notifieur, l'appeler à la détection de dispersion, retirer le vol de focus sur ce chemin.

---

### Task 1: Copie de la notification (`FocusNudgeCopy`, pur, TDD)

**Files:**
- Create: `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusNudgeNotifier.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusNudgeCopyTest.kt`

**Interfaces:**
- Consumes: rien.
- Produces:
  - `internal object FocusNudgeCopy`
  - `const val FocusNudgeCopy.TITLE: String = "Reviens à l'essentiel"`
  - `const val FocusNudgeCopy.DEFAULT_BODY: String = "Une seule chose à la fois."`
  - `fun FocusNudgeCopy.body(intention: String): String` — renvoie `intention` si non-blanc, sinon `DEFAULT_BODY`.

- [ ] **Step 1: Écrire le test qui échoue**

Créer `composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusNudgeCopyTest.kt` :

```kotlin
package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FocusNudgeCopyTest {
    @Test
    fun bodyUsesIntentionWhenPresent() {
        assertEquals("Terminer le rapport", FocusNudgeCopy.body("Terminer le rapport"))
    }

    @Test
    fun bodyFallsBackWhenBlank() {
        assertEquals("Une seule chose à la fois.", FocusNudgeCopy.body("   "))
    }

    @Test
    fun titleIsStable() {
        assertEquals("Reviens à l'essentiel", FocusNudgeCopy.TITLE)
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue à la compilation**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FocusNudgeCopyTest"`
Expected: ÉCHEC — compilation impossible, `FocusNudgeCopy` non résolu.

- [ ] **Step 3: Écrire l'implémentation minimale**

Créer `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusNudgeNotifier.kt` :

```kotlin
package fr.dayview.app

/** Textes de la notification de dispersion (logique de repli isolée pour être testable). */
internal object FocusNudgeCopy {
    const val TITLE = "Reviens à l'essentiel"
    const val DEFAULT_BODY = "Une seule chose à la fois."

    fun body(intention: String): String = intention.ifBlank { DEFAULT_BODY }
}
```

- [ ] **Step 4: Lancer le test et vérifier qu'il passe**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.FocusNudgeCopyTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusNudgeNotifier.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/FocusNudgeCopyTest.kt
git commit -m "feat: add focus-drift notification copy helper"
```

---

### Task 2: Wrapper natif `MacFocusNudgeNotifier`

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusNudgeNotifier.kt`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/MacFocusNudgeNotifierTest.kt`

**Interfaces:**
- Consumes: `FocusNudgeCopy.TITLE`, `FocusNudgeCopy.body(intention)` (Task 1).
- Produces:
  - `internal class MacFocusNudgeNotifier()`
  - `fun MacFocusNudgeNotifier.notify(intention: String)` — poste une notification native macOS ; no-op silencieux hors macOS ; toute erreur native avalée.

**Note TDD :** la partie native ne peut pas être vérifiée par un test unitaire (pas de banner assertable, erreurs avalées). Comme `MacFocusStatusItemTest`, on écrit un **test de fumée** gardé par macOS qui garantit chargement de classe + absence de crash synchrone. La vérification réelle est manuelle (Task 3, `.app` packagé).

- [ ] **Step 1: Écrire le test de fumée**

Créer `composeApp/src/desktopTest/kotlin/fr/dayview/app/MacFocusNudgeNotifierTest.kt` :

```kotlin
package fr.dayview.app

import kotlin.test.Test

class MacFocusNudgeNotifierTest {
    @Test
    fun deliversNudgeNotificationWithoutError() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return

        val notifier = MacFocusNudgeNotifier()
        notifier.notify("Terminer le rapport")
        notifier.notify("")
    }
}
```

- [ ] **Step 2: Lancer le test et vérifier qu'il échoue à la compilation**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.MacFocusNudgeNotifierTest"`
Expected: ÉCHEC — `MacFocusNudgeNotifier` non résolu.

- [ ] **Step 3: Ajouter le wrapper natif au fichier `MacFocusNudgeNotifier.kt`**

Ajouter, sous l'objet `FocusNudgeCopy`, dans `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusNudgeNotifier.kt` :

```kotlin
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Poste une notification macOS « dispersion » depuis le process JVM via le runtime Objective-C.
 *
 * Comme la notification est émise par la JVM elle-même, cliquer dessus ramène DayView au premier
 * plan sans code supplémentaire (comportement macOS par défaut). Miroir de
 * [MacFrontmostApplicationProvider] : appels natifs enveloppés dans runCatching, no-op hors macOS.
 */
internal class MacFocusNudgeNotifier {
    fun notify(intention: String) {
        if (!isMacOS) return
        runCatching { deliver(FocusNudgeCopy.TITLE, FocusNudgeCopy.body(intention)) }
    }

    private fun deliver(title: String, body: String) {
        val notificationClass = runtime.objc_getClass("NSUserNotification") ?: return
        val notification = message(message(notificationClass, "alloc"), "init") ?: return
        setString(notification, "setTitle:", title)
        setString(notification, "setInformativeText:", body)
        val centerClass = runtime.objc_getClass("NSUserNotificationCenter") ?: return
        val center = message(centerClass, "defaultUserNotificationCenter") ?: return
        messageWithObject(center, "deliverNotification:", notification)
    }

    private fun setString(receiver: Pointer, selector: String, value: String) {
        val nsString = makeNSString(value) ?: return
        messageWithObject(receiver, selector, nsString)
    }

    private fun makeNSString(value: String): Pointer? {
        val stringClass = runtime.objc_getClass("NSString") ?: return null
        // stringWithUTF8String: attend un C string UTF-8 NUL-terminé ; JNA ne l'ajoute pas.
        val utf8 = value.toByteArray(Charsets.UTF_8) + 0.toByte()
        return runtime.objc_msgSend(stringClass, runtime.sel_registerName("stringWithUTF8String:"), utf8)
    }

    private fun message(receiver: Pointer?, selector: String): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector))
    }

    private fun messageWithObject(receiver: Pointer?, selector: String, arg: Pointer): Pointer? {
        if (receiver == null || Pointer.nativeValue(receiver) == 0L) return null
        return runtime.objc_msgSend(receiver, runtime.sel_registerName(selector), arg)
    }

    // JNA mappe les méthodes vers les symboles natifs par nom ; ces signatures doivent coller au runtime C.
    @Suppress("ktlint:standard:function-naming")
    private interface ObjectiveCRuntime : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: ByteArray): Pointer?
    }

    private companion object {
        val isMacOS: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
        val runtime: ObjectiveCRuntime by lazy { Native.load("objc", ObjectiveCRuntime::class.java) }
    }
}
```

Note : placer les trois `import com.sun.jna.*` en tête de fichier (après `package fr.dayview.app`), pas au milieu.

- [ ] **Step 4: Lancer le test et vérifier qu'il passe**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.MacFocusNudgeNotifierTest"`
Expected: PASS (sur macOS, la notification est délivrée sans exception ; hors macOS, `return` immédiat).

- [ ] **Step 5: ktlint**

Run: `./gradlew :composeApp:ktlintCheck`
Expected: PASS. En cas d'écart de formatage : `./gradlew :composeApp:ktlintFormat` puis relancer `ktlintCheck`.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusNudgeNotifier.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/MacFocusNudgeNotifierTest.kt
git commit -m "feat: post native macOS notification on focus drift"
```

---

### Task 3: Câblage dans `Main.kt` (poster la notif, retirer le vol de focus à la détection)

**Files:**
- Modify: `composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt` (3 emplacements)

**Interfaces:**
- Consumes: `MacFocusNudgeNotifier()` et `.notify(intention)` (Task 2) ; `currentPreferences.focusIntention` (déjà en scope dans la boucle, cf. `Main.kt:51`).
- Produces: comportement final — à la dispersion, la notif est émise et la carte préparée, sans que la fenêtre passe au premier plan.

- [ ] **Step 1: Instancier le notifieur**

Dans `Main.kt`, après la ligne `val focusResumeDetector = remember { FocusResumeDetector() }` (`Main.kt:29`), ajouter :

```kotlin
    val nudgeNotifier = remember { MacFocusNudgeNotifier() }
```

- [ ] **Step 2: Émettre la notif et retirer le vol de focus dans la branche dispersion**

Dans la boucle d'observation (`Main.kt:81-84`), remplacer ce bloc :

```kotlin
            } else if (focusDriftDetector.observe(focusIsActive, currentNowMillis, frontmostBundleId)) {
                focusDriftReminderId = currentNowMillis
                isMiniWindowVisible = false
                isWindowVisible = true
            } else if (!focusIsActive) {
```

par :

```kotlin
            } else if (focusDriftDetector.observe(focusIsActive, currentNowMillis, frontmostBundleId)) {
                focusDriftReminderId = currentNowMillis
                nudgeNotifier.notify(currentPreferences.focusIntention)
            } else if (!focusIsActive) {
```

(On retire `isMiniWindowVisible = false` et `isWindowVisible = true` : plus de changement de visibilité de fenêtre à la détection.)

- [ ] **Step 3: Restreindre le passage au premier plan au seul rituel de reprise**

Dans le bloc `Window { ... }` (`Main.kt:177-182`), remplacer :

```kotlin
            LaunchedEffect(focusDriftReminderId, focusResumeRitualId) {
                if (focusDriftReminderId != null || focusResumeRitualId != null) {
                    window.toFront()
                    window.requestFocus()
                }
            }
```

par :

```kotlin
            LaunchedEffect(focusResumeRitualId) {
                if (focusResumeRitualId != null) {
                    window.toFront()
                    window.requestFocus()
                }
            }
```

- [ ] **Step 4: Compiler et vérifier ktlint**

Run: `./gradlew :composeApp:compileKotlinDesktop :composeApp:ktlintCheck`
Expected: BUILD SUCCESSFUL. (En cas d'écart ktlint : `./gradlew :composeApp:ktlintFormat` puis relancer.)

- [ ] **Step 5: Lancer toute la suite de tests desktop (non-régression)**

Run: `./gradlew :composeApp:desktopTest`
Expected: PASS — tous les tests existants (dont `FocusDriftDetectorTest`) + les nouveaux.

- [ ] **Step 6: Vérification manuelle sur le `.app` packagé (macOS)**

> Étape manuelle indispensable : `NSUserNotification` ne s'affiche de façon fiable que si le process a un bundle id, ce qui est le cas du `.app` packagé (`fr.dayview.app`), pas forcément sous `./gradlew run`.

1. Packager puis lancer l'app : `./gradlew :composeApp:packageDmg` (ou `:composeApp:createDistributable`), installer/ouvrir le `.app`.
2. Démarrer une session de focus, renseigner une intention.
3. Provoquer une dispersion : basculer ≥ 4 fois entre deux autres applications sur ~45 s (après la grâce initiale de 30 s).
4. **Attendu :**
   - une notification macOS apparaît (titre « Reviens à l'essentiel », corps = l'intention) ;
   - DayView **ne** passe **pas** au premier plan à ce moment-là ;
   - **cliquer** la notification ramène DayView au premier plan, la carte « C'EST REPARTI » visible si la fenêtre était ouverte.
5. Vérifier que le **rituel de reprise** (revenir après une longue interruption) ramène toujours, lui, la fenêtre au premier plan comme avant.

> Si la notification ne s'affiche pas même sur le `.app` packagé : appliquer le repli documenté dans le spec (`osascript -e 'display notification …'`, bannière fiable mais non cliquable) et rouvrir la discussion de design.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt
git commit -m "feat: notify on focus drift instead of stealing focus"
```

---

## Self-Review

**Spec coverage :**
- Notification système à la détection → Task 2 (`notify`) + Task 3 step 2. ✅
- In-process JNA / `NSUserNotification`, pas de helper ni dépendance → Task 2. ✅
- Remplace le vol de focus ; clic ramène DayView devant → Task 3 steps 2-3 + vérif step 6. ✅
- Rituel de reprise inchangé → Task 3 step 3 (LaunchedEffect ne réagit qu'à `focusResumeRitualId`) + vérif step 6.5. ✅
- Libellés exacts → `FocusNudgeCopy` (Task 1), testés. ✅
- macOS-only, no-op ailleurs, erreurs avalées → Task 2. ✅
- Cas fenêtre fermée (dégradé accepté) → couvert par le retrait de `isWindowVisible = true` (Task 3 step 2) ; documenté dans le spec.
- Risque bundle id → Task 3 step 6 (vérif sur `.app` packagé) + repli osascript documenté.

**Placeholder scan :** aucun TBD/TODO ; chaque step de code contient le code complet et les commandes exactes. ✅

**Type consistency :** `FocusNudgeCopy.TITLE` / `.body(intention)` définis en Task 1 et consommés à l'identique en Task 2 ; `MacFocusNudgeNotifier().notify(intention: String)` défini en Task 2 et appelé en Task 3 avec `currentPreferences.focusIntention: String`. ✅
