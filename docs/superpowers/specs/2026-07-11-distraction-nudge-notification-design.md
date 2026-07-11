# Notification système sur détection de dispersion (desktop macOS)

**Date :** 2026-07-11
**Statut :** Design validé, prêt pour plan d'implémentation

## Contexte

Le nudge « dispersion » de DayView est une fonctionnalité **desktop (macOS uniquement)**.
Pendant une session de focus, `FocusDriftDetector`
([`FocusDriftDetector.kt`](../../../composeApp/src/desktopMain/kotlin/fr/dayview/app/FocusDriftDetector.kt))
observe l'app au premier plan chaque seconde depuis la boucle de
[`Main.kt`](../../../composeApp/src/desktopMain/kotlin/fr/dayview/app/Main.kt). Quand l'utilisateur
change d'application trop souvent (≥ 4 bascules dans une fenêtre de 45 s, après une grâce initiale et
avec un cooldown de 5 min), `observe(...)` renvoie `true`.

Aujourd'hui, à cette détection, `Main.kt` :

1. pose `focusDriftReminderId = currentNowMillis` → affiche la carte « REVENIR À L'ESSENTIEL »
   avec le bouton **« C'EST REPARTI »** (dans
   [`DayViewTodayScreen.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTodayScreen.kt)) ;
2. force la fenêtre DayView au premier plan (`isWindowVisible = true`, puis un
   `LaunchedEffect` appelle `window.toFront()` / `window.requestFocus()`).

## Objectif

Quand la dispersion est détectée, **envoyer une notification système macOS** en plus de préparer la
carte de rappel. La notification remplace le vol de focus actuel : DayView ne s'impose plus
brutalement au premier plan ; l'utilisateur reçoit une notification discrète et, **en cliquant
dessus, ramène DayView devant** (révélant la carte « C'EST REPARTI »).

## Décisions produit

- **La notification remplace le vol de focus.** À la détection, on ne force plus la fenêtre au
  premier plan. On pose toujours `focusDriftReminderId` pour que la carte soit présente dès que
  l'utilisateur revient sur DayView.
- **La notification est cliquable et ramène DayView au premier plan.**
- **Périmètre : macOS desktop uniquement.** La détection de dispersion n'existe que là. Aucun
  changement Android/iOS/commun côté détection.

## Approche technique : notification in-process via JNA

Le desktop tourne sur la **JVM** (Compose Desktop). On poste la notification **depuis le process JVM
lui-même**, via `objc_msgSend` (JNA) — le même mécanisme que
`MacFrontmostApplicationProvider` dans `FocusDriftDetector.kt`, qui appelle déjà le runtime
Objective-C par l'interface JNA `ObjectiveCRuntime`.

**Pourquoi in-process plutôt qu'un helper séparé (Swift ou Kotlin/Native) :**

- Kotlin/Native ne peut pas tourner dans le process JVM ; il produirait un binaire séparé, donc un
  helper piloté par stdin — même architecture que le helper Swift existant, mais avec une cible
  Kotlin/Native supplémentaire à configurer. Aucun gain « bas niveau in-process ».
- En postant depuis la JVM, **le clic sur la notification est géré gratuitement par macOS** : l'OS
  ramène au premier plan l'application qui a posté la notification (la JVM). Pas de délégué, pas de
  canal retour (stdin/stdout), pas d'astuce de PID, pas de process séparé, pas de nouvelle tâche de
  build, pas de binaire à embarquer/signer.

**API native.** On utilise `NSUserNotification` + `NSUserNotificationCenter`
(`deliverNotification:`). API dépréciée depuis macOS 11 mais toujours fonctionnelle, et beaucoup plus
simple à piloter en JNA que `UNUserNotificationCenter` (qui impose une autorisation asynchrone et un
bundle). Le comportement de clic par défaut (`NSUserNotificationActivationTypeContentsClicked`)
foregrounde l'app émettrice — ce qui suffit à notre besoin.

**Contrainte connue.** Une notification ne s'affiche de façon fiable que si le process a un
**bundle identifier**, ce qui est le cas du `.app` packagé (`fr.dayview.app`). Sous
`./gradlew run` (non bundlé), elle peut ne pas apparaître : à **vérifier sur le `.app` packagé**
pendant l'implémentation. Si le rendu s'avère non fiable, repli documenté : `osascript -e 'display
notification …'` (bannière fiable mais non cliquable).

## Composants

### 1. `MacFocusNudgeNotifier` (nouveau, desktopMain)

Fichier : `composeApp/src/desktopMain/kotlin/fr/dayview/app/MacFocusNudgeNotifier.kt`

Wrapper mince autour du runtime Objective-C, sur le modèle de `MacFrontmostApplicationProvider` /
`MacFocusStatusItem`.

- `fun notify(intention: String)` : poste une notification native.
  - Titre : `"Reviens à l'essentiel"`.
  - Corps : `intention` si non vide, sinon `"Une seule chose à la fois."` (mêmes libellés que la
    carte).
  - No-op si l'OS n'est pas macOS.
  - Toute erreur native est avalée (`runCatching`), comme `MacFrontmostApplicationProvider`.
- Étend l'interface JNA `ObjectiveCRuntime` (aujourd'hui limitée à `objc_msgSend(receiver,
  selector)`) avec les variantes à arguments nécessaires (envoi d'objets/messages avec paramètres) et
  la création de `NSString` (`stringWithUTF8String:`). L'`ObjectiveCRuntime` peut être extraite dans
  un fichier partagé ou dupliquée localement ; le plan tranchera pour éviter un couplage inutile
  entre le détecteur et le notifieur.
- La livraison de la notification doit se faire de manière compatible avec le main thread AppKit ;
  détail d'implémentation à valider (l'app Compose Desktop a déjà une boucle AppKit active).

**Throttling :** aucun dans le notifieur. Le rythme est déjà borné par `FocusDriftDetector`
(`reminderCooldownMillis = 5 min`) : `notify(...)` n'est appelé qu'au moment exact où `observe(...)`
renvoie `true`.

### 2. Câblage dans `Main.kt`

Dans la boucle d'observation, branche « dispersion détectée »
(`else if (focusDriftDetector.observe(...))`) :

- **Conserver** `focusDriftReminderId = currentNowMillis` (la carte reste disponible).
- **Ajouter** `nudgeNotifier.notify(focusIntention)`.
- **Retirer le vol de focus pour la dispersion :** ne plus forcer la fenêtre au premier plan sur ce
  chemin. Concrètement, deux changements :
  - le `LaunchedEffect(focusDriftReminderId, focusResumeRitualId)` qui appelle `window.toFront()` /
    `requestFocus()` doit ne réagir **qu'au rituel de reprise** (`focusResumeRitualId`), pas à la
    dispersion ;
  - la branche dispersion ne modifie **plus** l'état de visibilité de la fenêtre (aujourd'hui elle
    fait `isWindowVisible = true` et `isMiniWindowVisible = false`) — on retire ces lignes. On ne
    laisse que la pose de `focusDriftReminderId` et l'appel `notify(...)`.
  Le `focusResumeDetector` (rituel de reprise) garde son comportement actuel, inchangé, fenêtre au
  premier plan comprise.
- **Conséquence assumée :** la carte s'affiche dans la fenêtre DayView telle qu'elle est. Si la
  fenêtre est déjà ouverte (au premier plan ou en arrière-plan), la carte y apparaît ; le clic sur la
  notification foregrounde DayView et la révèle. Si la fenêtre principale est fermée au moment de la
  détection, cliquer la notification foregrounde le process sans recréer de fenêtre : l'utilisateur
  rouvre DayView depuis la barre de menu (comportement dégradé accepté, cas rare car DayView est un
  tableau de bord de journée généralement ouvert). La notification reste, elle, toujours émise.

Instancier `val nudgeNotifier = remember { MacFocusNudgeNotifier() }` à côté des autres `remember`
de `main()`.

## Ce qui ne change pas

- `FocusDriftDetector` et `FocusResumeDetector` : logique de détection inchangée (déjà testée
  unitairement).
- La carte « REVENIR À L'ESSENTIEL » et le bouton « C'EST REPARTI » : inchangés.
- Le rituel de reprise (`focusResumeRitualId`) : garde son comportement actuel, fenêtre au premier
  plan comprise.
- Android et le code commun : aucun changement.

## Tests

- **`MacFocusNudgeNotifier`** : test dans `desktopTest` sur le modèle de `MacFocusStatusItemTest` —
  `return` immédiat hors macOS, sinon appel de fumée `notify("…")` vérifiant l'absence d'exception.
- **Détection** : couverte par les tests existants de `FocusDriftDetectorTest` ; inchangée.
- **Vérification manuelle (macOS, `.app` packagé)** : déclencher une dispersion → observer
  l'apparition de la notification, l'absence de vol de focus à la détection, et le retour de DayView
  au premier plan au clic avec la carte affichée.

## Gestion des erreurs

- Hors macOS : `notify(...)` est un no-op silencieux.
- Échec d'un appel natif : avalé via `runCatching`, cohérent avec `MacFrontmostApplicationProvider`.
  Un échec de notification ne doit jamais casser la boucle d'observation ni empêcher l'affichage de
  la carte.

## Risques / points à valider pendant l'implémentation

1. Rendu de la notification sous `.app` packagé vs `./gradlew run` (bundle id). Repli `osascript`
   documenté si nécessaire.
2. Livraison de la notification sur le bon thread AppKit.
3. Vérifier qu'aucun chemin de la branche dispersion ne ramène plus la fenêtre au premier plan
   (ni `toFront`, ni changement de `isWindowVisible`), tout en gardant ce comportement pour le
   rituel de reprise.
