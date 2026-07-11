# Plan d'intégration Discord pour les sessions Focus

## Résumé

DayView proposera deux fonctions Discord indépendantes et désactivées par défaut :

1. une **Rich Presence personnelle** pendant une session Focus, visible sur le profil Discord de l'utilisateur ;
2. la **publication facultative du résultat** de la session dans un salon Discord, au moyen d'un webhook entrant.

L'intégration doit rester strictement optionnelle, ne jamais empêcher le fonctionnement local d'un Focus et limiter par défaut les informations partagées. Une panne, une déconnexion ou une limitation de débit Discord ne doit modifier ni le minuteur ni l'historique local.

## Expérience cible

### Pendant un Focus

Si la Rich Presence est activée et le compte Discord connecté, le profil affiche une activité de ce type :

```text
DayView
Focus en cours
Préparer la présentation
18 min restantes
```

Le compte à rebours est produit par Discord à partir de l'échéance de la session. DayView ne publie donc pas une mise à jour chaque seconde.

Par défaut, l'intention n'est **pas** incluse. L'utilisateur peut explicitement activer son partage. Sans intention, la présence affiche uniquement `Focus en cours` et le temps restant.

La présence est supprimée quand la session est arrêtée, clôturée ou expirée. Lors d'une relance de DayView, une session encore active restaure la présence après reconnexion à Discord.

### À la clôture d'un Focus

Si la publication dans un salon est activée, DayView publie un seul message lorsque l'utilisateur choisit un résultat :

```text
✅ Focus terminé — 25 min
Résultat : Terminé
```

Les trois résultats DayView sont représentés ainsi :

| Résultat | Libellé Discord | Icône |
| --- | --- | --- |
| `COMPLETED` | Terminé | ✅ |
| `PROGRESSED` | Avancé | ➡️ |
| `TO_RESUME` | À reprendre | 🔁 |

L'intention peut être ajoutée au message uniquement si l'option dédiée est activée. Un arrêt sans clôture ne publie rien. Une expiration du minuteur ne publie rien tant que l'utilisateur n'a pas choisi son résultat.

## Périmètre de la première version

Inclus :

- macOS et Android ;
- association explicite d'un compte Discord pour la Rich Presence ;
- présence créée au démarrage d'un Focus et supprimée à sa fin ;
- reprise après redémarrage pour une session encore active ;
- configuration d'un webhook lié à un salon ;
- test du webhook depuis les réglages ;
- publication d'un message lors de la clôture ;
- options séparées pour partager l'intention dans la présence et dans le salon ;
- retour d'état non bloquant dans les réglages.

Hors périmètre initial :

- commandes slash et bot Discord permanent ;
- démarrage ou pilotage d'un Focus depuis Discord ;
- statistiques d'équipe ou classement ;
- messages à chaque démarrage, interruption ou minute écoulée ;
- synchronisation cloud de la configuration Discord ;
- publication de l'objectif global ou des horaires de travail.

## Réglages et confidentialité

Ajouter une section `Discord` dans l'écran Réglages avec deux sous-sections indépendantes.

### Présence personnelle

- interrupteur `Afficher mes Focus sur Discord`, désactivé par défaut ;
- action `Connecter Discord` / `Déconnecter` ;
- état de connexion compréhensible : connecté, Discord indisponible, autorisation requise ;
- interrupteur `Afficher mon intention`, désactivé par défaut ;
- aperçu du contenu qui sera rendu public.

### Publication dans un salon

- interrupteur `Publier le résultat dans un salon`, désactivé par défaut ;
- champ masqué pour l'URL du webhook ;
- action `Tester la connexion`, qui envoie un message de test explicite ;
- interrupteur `Inclure mon intention`, désactivé par défaut ;
- action `Supprimer la configuration du salon`.

L'interface doit expliquer que :

- une Rich Presence est visible selon les réglages de partage d'activité du compte Discord ;
- une intention peut contenir des données personnelles ou professionnelles ;
- l'URL d'un webhook permet de publier dans le salon associé et doit être traitée comme un secret.

Le jeton Discord et l'URL complète du webhook ne doivent jamais apparaître dans les journaux, messages d'erreur ou rapports de crash.

## Architecture proposée

La logique métier reste dans `commonMain`. Les intégrations externes sont exposées par des interfaces et implémentées par plateforme.

```text
Actions Focus dans DayViewApp
        |
        v
FocusSharingCoordinator (commonMain)
        |                         |
        v                         v
DiscordPresenceClient       FocusResultPublisher
(implémentation native)     (client HTTP webhook)
        |                         |
        v                         v
Discord Social SDK          API Webhook Discord
```

### Événements métier

Introduire des événements explicites afin de ne pas déduire les transitions depuis la boucle d'affichage :

```kotlin
sealed interface FocusSharingEvent {
    data class Started(
        val sessionId: String,
        val intention: String,
        val durationMinutes: Int,
        val startedAtMillis: Long,
        val endsAtMillis: Long,
    ) : FocusSharingEvent

    data class Stopped(val sessionId: String) : FocusSharingEvent

    data class Closed(
        val sessionId: String,
        val intention: String,
        val durationMinutes: Int,
        val outcome: FocusClosureOutcome,
        val closedAtMillis: Long,
    ) : FocusSharingEvent
}
```

`Started` met à jour la Rich Presence. `Stopped` la supprime sans publier de résultat. `Closed` supprime la présence puis programme au plus une publication de résultat.

Le `sessionId` persistant sert de clé d'idempotence. Il empêche un double message après un double clic, une recomposition Compose, une reprise réseau ou un redémarrage de l'application.

### Contrats communs

```kotlin
interface DiscordPresenceClient {
    suspend fun connect(): PresenceConnectionResult
    suspend fun updateFocus(activity: FocusPresence): Result<Unit>
    suspend fun clear(): Result<Unit>
    suspend fun disconnect()
}

interface FocusResultPublisher {
    suspend fun test(webhook: DiscordWebhook): Result<Unit>
    suspend fun publish(result: SharedFocusResult): Result<Unit>
}
```

Un `FocusSharingCoordinator` reçoit les événements, applique les réglages de confidentialité, construit les contenus et appelle ces deux contrats. Il ne doit jamais lever d'erreur vers le flux principal du minuteur.

### Branchement dans DayView

Les transitions existent déjà dans `DayViewApp` :

- `onPomodoroStart` démarre et persiste le Focus ;
- `onPomodoroStop` l'interrompt ;
- `onPomodoroClose` reçoit le résultat de clôture.

La première étape de refactorisation consiste à ajouter un callback métier unique, par exemple `onFocusSharingEvent`, à côté de `onFocusAlarmChange`. Les points d'entrée desktop et Android créent ensuite le coordinateur approprié. Ce découplage évite de placer des appels réseau dans les composables et rend les transitions testables.

La boucle desktop de `Main.kt` reste chargée de détecter une session restaurée. Elle déclenche une restauration de présence uniquement lorsque l'identifiant et l'échéance de la session diffèrent du dernier état envoyé.

## Rich Presence personnelle

### Technologie

Utiliser le **Discord Social SDK**, solution officielle recommandée par Discord pour les nouveaux projets. Le SDK prend en charge macOS x64/ARM64 et Android 7 ou version ultérieure, ce qui correspond aux cibles actuelles de DayView.

Le SDK expose une API C++. L'intégration Kotlin Multiplatform demandera donc une couche native minimale :

- macOS desktop : bibliothèque native universelle ou deux variantes x64/ARM64, chargée depuis la JVM via JNA ;
- Android : bibliothèque `.so` par ABI et pont JNI ;
- interface Kotlin identique sur les deux plateformes ;
- empaquetage et signature vérifiés dans le DMG et l'APK.

Le SDK Discord archivé (« Game SDK ») et les bibliothèques RPC non officielles ne doivent pas être utilisés pour une nouvelle intégration.

### Cycle de vie

1. L'utilisateur active la fonctionnalité et autorise son compte Discord.
2. DayView initialise le client Discord sans bloquer l'interface.
3. Au démarrage d'un Focus, DayView envoie une activité contenant l'échéance.
4. Une modification de l'intention pendant la session met à jour la présence seulement si son partage est activé, avec un anti-rebond.
5. À l'arrêt, à la clôture ou à l'expiration, DayView supprime l'activité.
6. À la fermeture de l'application, DayView libère proprement le client.

Si Discord n'est pas installé, n'est pas lancé ou n'est pas joignable, DayView conserve le Focus et affiche seulement un état discret dans les réglages. Une reconnexion ultérieure restaure la présence si la session est toujours active.

### Contenu transmis

Le payload doit rester minimal :

- nom de l'application configuré dans le Developer Portal : `DayView` ;
- détail : `Focus en cours` ;
- état : intention tronquée et nettoyée, uniquement avec consentement ;
- timestamp de fin : `endsAtMillis` ;
- visuel DayView enregistré comme asset Discord.

Ne pas transmettre l'objectif global, le nom des applications au premier plan, le signal de dérive d'attention ou un identifiant utilisateur DayView.

## Publication facultative dans un salon

### Technologie

Utiliser un webhook entrant Discord. Il permet de publier dans un salon sans bot, connexion Gateway ni serveur DayView. Le client HTTP peut vivre dans `commonMain` avec une implémentation Kotlin Multiplatform, sous réserve d'ajouter la permission Internet Android.

Le webhook reçoit un message ou un embed comportant :

- le résultat de clôture ;
- la durée prévue du Focus ;
- éventuellement l'intention ;
- un pied de message `DayView`.

La première version publie un message autonome. Elle n'essaie pas de maintenir un message vivant pendant la session.

### Sécurité du webhook

- macOS : stocker l'URL dans le Trousseau via une petite abstraction de stockage sécurisé ;
- Android : stocker l'URL chiffrée avec le stockage sécurisé de la plateforme ;
- ne conserver dans les préférences ordinaires qu'un booléen indiquant qu'un webhook est configuré ;
- valider le schéma HTTPS et l'hôte Discord avant l'enregistrement ;
- masquer l'URL après saisie et offrir un remplacement explicite ;
- ne jamais renvoyer l'URL dans une télémétrie.

Un webhook compromis peut être supprimé depuis Discord puis remplacé dans DayView.

### Fiabilité et idempotence

La publication est une conséquence secondaire de la clôture locale :

- enregistrer d'abord le résultat local ;
- créer ensuite une publication en attente associée au `sessionId` ;
- tenter l'envoi avec un délai court ;
- en cas d'échec réseau ou `5xx`, réessayer avec un backoff borné ;
- respecter `429` et son délai de reprise ;
- ne pas réessayer les erreurs permanentes `401`, `403` ou `404` ;
- marquer la publication comme envoyée avant de la retirer de la file ;
- limiter la file locale et sa durée de rétention.

L'utilisateur doit pouvoir voir `Dernière publication réussie` ou une erreur actionnable dans les réglages. L'échec ne donne pas lieu à une fenêtre modale lors de la clôture.

## Modèle de préférences

Étendre `DayPreferences` avec une structure versionnée plutôt qu'une succession de paramètres indépendants :

```kotlin
data class DiscordSharingSettings(
    val presenceEnabled: Boolean = false,
    val presenceIncludesIntention: Boolean = false,
    val resultPublishingEnabled: Boolean = false,
    val resultIncludesIntention: Boolean = false,
    val hasWebhook: Boolean = false,
)
```

Les secrets et jetons restent hors de `DayPreferences`. Créer un contrat distinct :

```kotlin
interface DiscordSecretStore {
    suspend fun loadWebhookUrl(): String?
    suspend fun saveWebhookUrl(value: String)
    suspend fun deleteWebhookUrl()
}
```

La session Focus devra aussi recevoir un identifiant stable, conservé avec son heure de fin, afin d'assurer l'idempotence des événements et des publications.

## Stratégie de mise en œuvre

### Phase 1 — Événements et confidentialité

- extraire les transitions Focus en événements métier ;
- ajouter un `sessionId` persistant ;
- ajouter les réglages Discord désactivés par défaut ;
- créer les interfaces et implémentations inertes ;
- tester démarrage, arrêt, clôture et restauration.

Cette phase ne contacte pas encore Discord et réduit le risque des phases suivantes.

### Phase 2 — Webhook de résultat

- implémenter le stockage sécurisé sur macOS et Android ;
- ajouter le client HTTP et la validation de l'URL ;
- créer l'action de test ;
- publier les trois résultats ;
- ajouter idempotence, gestion de `429` et reprise bornée ;
- vérifier qu'aucun secret n'apparaît dans les logs.

Cette phase livre la première valeur utilisateur avec une complexité limitée.

### Phase 3 — Rich Presence macOS

- créer l'application DayView dans le Discord Developer Portal ;
- configurer nom, icône, asset Rich Presence et OAuth ;
- intégrer le Social SDK et le pont JNA ;
- gérer connexion, mise à jour, suppression et reconnexion ;
- empaqueter les architectures Intel et Apple Silicon ;
- tester avec Discord fermé, ouvert, hors ligne et avec un compte de test.

### Phase 4 — Rich Presence Android

- intégrer les bibliothèques natives par ABI et JNI ;
- ajouter le flux d'autorisation mobile et le deep link Discord ;
- restaurer la présence après recréation d'activité ou relance ;
- vérifier le comportement en arrière-plan et les restrictions d'Android ;
- tester au minimum Android 7 et une version Android récente.

### Phase 5 — Durcissement et lancement

- audit confidentialité et suppression des secrets ;
- tests de migration depuis une installation existante ;
- documentation utilisateur et procédure de révocation ;
- vérification des limites de débit ;
- test avec un compte Discord réservé au développement avant diffusion.

## Tests attendus

### Tests unitaires communs

- les fonctions sont inertes avec les réglages par défaut ;
- une intention n'est jamais incluse sans consentement explicite ;
- `Started` produit une présence avec le bon timestamp ;
- `Stopped` supprime la présence et ne publie rien ;
- `Closed` supprime la présence et publie exactement une fois ;
- deux événements `Closed` du même `sessionId` ne créent qu'un message ;
- une restauration expirée supprime la présence ;
- une erreur Discord ne modifie pas l'état du Focus.

### Tests d'intégration webhook

- payload conforme pour les trois résultats ;
- intention absente ou présente selon le réglage ;
- gestion de `204`, `400`, `401`, `404`, `429` et `5xx` ;
- timeout, absence de réseau et reprise ;
- URL et jeton expurgés des erreurs.

### Tests manuels Rich Presence

- connexion et révocation OAuth ;
- affichage public avec partage d'activité activé ;
- absence d'affichage lorsque Discord ou le partage d'activité est désactivé ;
- compte à rebours correct ;
- suppression immédiate après arrêt ou clôture ;
- restauration après relance ;
- absence d'intention avec le réglage par défaut.

## Critères d'acceptation

La fonctionnalité est prête lorsque :

1. aucune donnée ne quitte DayView sans activation explicite ;
2. une session Focus active produit au plus une Rich Presence correcte ;
3. la présence disparaît à la fin de la session ;
4. une clôture produit au plus un message dans le salon configuré ;
5. aucun message n'est envoyé lors d'un simple arrêt ou d'une expiration non clôturée ;
6. l'intention reste privée par défaut pour les deux canaux ;
7. Discord peut être indisponible sans dégrader le minuteur ;
8. les secrets sont stockés dans les mécanismes sécurisés des plateformes et absents des logs ;
9. le comportement est couvert par des tests communs et des tests d'intégration ;
10. la déconnexion Discord et la suppression du webhook sont accessibles depuis les réglages.

## Références Discord

- [Rich Presence](https://docs.discord.com/developers/platform/rich-presence)
- [Discord Social SDK](https://docs.discord.com/developers/discord-social-sdk/overview)
- [Compatibilité des plateformes](https://docs.discord.com/developers/discord-social-sdk/core-concepts/platform-compatibility)
- [Webhooks entrants](https://docs.discord.com/developers/resources/webhook)

