# Synchronisation Android et Desktop

## Résumé

DayView doit pouvoir conserver le même état sur Android et macOS sans perdre son fonctionnement hors ligne. Les deux applications restent utilisables sans connexion et synchronisent leurs modifications dès que le réseau revient.

La synchronisation concerne l'état métier et les préférences partagées. Les éléments propres à une plateforme, comme l'icône de barre des menus, les autorisations Android ou les notifications programmées, restent locaux.

L'implémentation actuelle ne réalise aucune synchronisation : Android stocke les données dans `SharedPreferences`, tandis que Desktop utilise `java.util.prefs.Preferences`. L'interface commune `DayPreferences` constitue néanmoins un bon point d'entrée pour introduire une couche synchronisée sans déplacer la logique métier hors de `commonMain`.

## Expérience cible

Après avoir associé ses deux appareils au même compte, l'utilisateur retrouve sur Android et Desktop :

- ses horaires de début et de fin de journée ;
- son objectif global et son échéance ;
- ses préférences d'affichage et de sons ;
- la durée et l'intention du Focus ;
- l'état de la session Focus en cours.

Une modification locale apparaît immédiatement à l'écran, puis sur l'autre appareil en quelques secondes lorsqu'ils sont connectés. Sans réseau, elle reste enregistrée et est envoyée ultérieurement.

Le lancement, la pause, la reprise ou l'arrêt d'un Focus sur un appareil est répercuté sur l'autre. Chaque appareil calcule localement le temps restant à partir d'une échéance commune ; aucun compte à rebours n'est envoyé chaque seconde.

## Périmètre de la première version

Inclus :

- association d'Android et de Desktop à une même identité ;
- synchronisation dans les deux sens ;
- fonctionnement hors ligne ;
- mise à jour en temps quasi réel lorsque les applications sont ouvertes ;
- rattrapage au lancement et au retour au premier plan ;
- résolution déterministe des modifications concurrentes ;
- reprogrammation locale des alarmes et notifications après une modification distante du Focus.

Hors périmètre initial :

- historique complet des Focus ;
- statistiques multi-appareils ;
- partage entre plusieurs utilisateurs ;
- édition collaborative ;
- sauvegarde des secrets ou réglages propres aux intégrations externes ;
- synchronisation continue lorsque les deux applications sont complètement arrêtées.

## Données partagées et données locales

| Domaine | Synchronisé | Reste local |
| --- | --- | --- |
| Journée | début, fin | fuseau courant de l'appareil |
| Affichage | affichage des secondes | taille et position des fenêtres |
| Sons | activation, repères, intervalle, volume | état audio du système |
| Objectif | titre, échéance | rendu du widget |
| Focus | identifiant, intention, durée, état, début et fin | alarmes, notifications et sons programmés |
| Desktop | — | icône monochrome, lancement à l'ouverture de session |
| Android | — | autorisations, configuration du widget et de la tuile |

Les échéances sont enregistrées en instants UTC (`epochMillis`). Les horaires quotidiens restent exprimés en minutes locales depuis minuit, car ils décrivent une routine locale et non un instant absolu.

## Modèle de données

La première version peut utiliser un document par utilisateur. Les préférences et le Focus possèdent des versions distinctes afin qu'une modification du volume ne remplace pas simultanément un Focus démarré depuis l'autre appareil.

```kotlin
data class SyncedDaySettings(
    val startMinutes: Int,
    val endMinutes: Int,
    val showSeconds: Boolean,
    val soundSettings: SoundSettings,
    val goalTitle: String,
    val goalDeadlineMillis: Long?,
    val pomodoroMinutes: Int,
    val updatedAtMillis: Long,
    val updatedByDeviceId: String,
)

enum class SyncedFocusStatus {
    IDLE,
    RUNNING,
    PAUSED,
    AWAITING_OUTCOME,
}

data class SyncedFocusSession(
    val sessionId: String,
    val status: SyncedFocusStatus,
    val intention: String,
    val durationMinutes: Int,
    val startedAtMillis: Long?,
    val endsAtMillis: Long?,
    val remainingMillisWhenPaused: Long?,
    val revision: Long,
    val updatedAtMillis: Long,
    val updatedByDeviceId: String,
)
```

`sessionId` distingue deux Focus successifs. `revision` impose un ordre aux transitions d'une même session. `updatedByDeviceId` sert à ignorer l'écho d'une modification et facilite le diagnostic sans exposer le nom personnel de l'appareil.

L'état `AWAITING_OUTCOME` représente un minuteur arrivé à son terme, mais pas encore clôturé avec « Terminé », « Avancé » ou « À reprendre ». La clôture remet ensuite l'état partagé à `IDLE`.

## Architecture proposée

La source immédiate de vérité reste le stockage local. Un coordinateur commun observe les changements locaux, les transmet au service distant et applique les changements distants au stockage local.

```text
Interface Compose / widget / barre des menus
                    |
                    v
             DayViewController
                    |
                    v
        DayPreferences local-first
          |                    |
          v                    v
 stockage local        SyncCoordinator
                               |
                               v
                       RemoteSyncStore
                               |
                               v
                       service de données
```

Contrats communs proposés :

```kotlin
interface RemoteSyncStore {
    suspend fun pull(): RemoteDayViewState?
    suspend fun pushSettings(settings: SyncedDaySettings): SyncResult
    suspend fun pushFocus(focus: SyncedFocusSession): SyncResult
    fun observe(onState: (RemoteDayViewState) -> Unit): () -> Unit
}

interface SyncCoordinator {
    suspend fun start()
    suspend fun syncNow()
    suspend fun stop()
}
```

Les appels réseau ne doivent pas être placés dans les composables. Les actions utilisateur écrivent d'abord dans le stockage local ; le coordinateur traite ensuite une file persistante d'opérations en attente. Une panne du service distant ne doit jamais bloquer le minuteur.

À terme, il sera préférable de séparer dans le stockage local les réglages, l'objectif et la session Focus. L'interface actuelle `DayPreferences` peut être conservée comme façade pendant cette migration.

## Cycle de synchronisation

### Au démarrage

1. Charger et afficher immédiatement l'état local.
2. Démarrer l'observation du service distant.
3. Envoyer les opérations locales encore en attente.
4. Récupérer l'état distant.
5. Résoudre les versions, appliquer les changements retenus et notifier l'interface.
6. Reprogrammer les effets locaux nécessaires, notamment l'alarme Android.

### Après une modification locale

1. Valider et sauvegarder la modification localement.
2. Notifier immédiatement l'interface, le widget ou la barre des menus.
3. Ajouter une opération idempotente à la file d'envoi.
4. Tenter l'envoi sans bloquer l'utilisateur.
5. Retirer l'opération de la file après confirmation du serveur.

### Après une modification distante

1. Comparer son domaine, sa session et sa version à l'état local.
2. Ignorer les doublons et les versions anciennes.
3. Enregistrer la version retenue localement sans la renvoyer comme une nouvelle action.
4. Notifier les observateurs.
5. Mettre à jour les effets de plateforme.

## Conflits et idempotence

Pour les préférences ordinaires, la première version utilise la règle « dernière modification validée par le serveur gagnante », indépendamment pour chaque domaine. L'horloge du serveur doit fournir `updatedAtMillis` afin d'éviter de dépendre d'horloges d'appareils désynchronisées.

Pour un Focus, les règles métier sont prioritaires :

- une révision inférieure ne peut jamais remplacer une révision supérieure de la même session ;
- une action répétée avec le même identifiant d'opération ne produit aucun nouvel effet ;
- une ancienne session ne peut pas remplacer une session plus récente ;
- un arrêt ou une clôture confirmé ne peut pas être annulé par une mise à jour tardive `RUNNING` ;
- deux démarrages réellement concurrents sont départagés par le serveur, et le client perdant adopte la session retenue.

Chaque écriture distante porte donc un `operationId` unique et le serveur applique la mutation de manière atomique.

## Focus, alarmes et notifications

Le service synchronise uniquement les instants et les transitions métier. Android et Desktop continuent à produire localement l'affichage, les sons et les notifications.

Lorsqu'un appareil reçoit un Focus distant :

- `RUNNING` avec une échéance future : programmer ou remplacer l'alarme locale ;
- `PAUSED` : annuler l'alarme et afficher le temps figé ;
- `IDLE` ou session clôturée : annuler l'alarme et les notifications associées ;
- échéance déjà passée : passer à `AWAITING_OUTCOME` sans rejouer plusieurs fois le signal de fin.

Un identifiant dérivé de `sessionId` empêche les anciennes alarmes de modifier une nouvelle session. Plusieurs appareils peuvent signaler localement la fin d'un même Focus ; cette duplication est acceptable au départ, mais la clôture métier ne doit être enregistrée qu'une fois.

## Identité, sécurité et confidentialité

L'utilisateur doit explicitement activer la synchronisation. Une connexion par lien magique ou code à usage unique évite d'introduire un mot de passe propre à DayView. Une association par QR code pourra simplifier l'ajout du second appareil, mais ne remplace pas une identité récupérable.

Exigences minimales :

- trafic HTTPS uniquement ;
- jetons stockés dans le Trousseau macOS et le stockage sécurisé Android ;
- contrôle d'accès côté serveur limitant chaque compte à ses propres données ;
- possibilité de se déconnecter sur un appareil sans effacer l'autre ;
- action explicite pour supprimer les données synchronisées du compte ;
- aucune intention, aucun jeton et aucun contenu utilisateur dans les journaux ;
- identifiants d'appareil aléatoires, non dérivés du matériel.

Les données locales restent disponibles après une déconnexion, sauf si l'utilisateur demande aussi leur suppression. L'interface doit expliquer clairement cette distinction.

## Choix du service distant

Un service fournissant authentification, base transactionnelle et abonnement aux changements réduit fortement le travail initial. Supabase est un candidat adapté grâce à PostgreSQL, aux politiques d'accès par ligne et aux flux temps réel. Firebase constitue également une option viable.

Le code métier ne doit toutefois dépendre que de `RemoteSyncStore`. Ce contrat permet de remplacer le fournisseur, d'utiliser un faux serveur dans les tests et de conserver une variante de DayView entièrement locale.

iCloud seul n'est pas une solution adaptée puisque le client Android doit accéder aux mêmes données.

## États visibles dans l'interface

Ajouter une section `Synchronisation` dans les réglages avec :

- activation et connexion au compte ;
- état `À jour`, `Synchronisation…`, `Hors ligne` ou `Action requise` ;
- date de la dernière synchronisation réussie ;
- action `Synchroniser maintenant` ;
- déconnexion de cet appareil ;
- suppression du compte et des données distantes.

Une erreur réseau reste discrète et ne bloque pas l'usage. Un conflit résolu automatiquement ne demande pas de dialogue modal. Une erreur d'authentification persistante affiche en revanche une action de reconnexion.

## Plan d'implémentation

### Étape 1 — Modèle et stockage local

- ajouter des identifiants de session Focus persistants ;
- séparer les révisions des réglages et du Focus ;
- introduire la file locale d'opérations idempotentes ;
- couvrir les migrations depuis les préférences actuelles ;
- tester le fonctionnement local sans service distant.

### Étape 2 — Contrats et faux serveur

- ajouter `RemoteSyncStore` et `SyncCoordinator` dans `commonMain` ;
- écrire une implémentation mémoire déterministe pour les tests ;
- tester les démarrages hors ligne, reprises, doublons et conflits ;
- vérifier qu'un événement distant ne crée pas de boucle de renvoi.

### Étape 3 — Authentification et service réel

- configurer le fournisseur retenu et ses règles d'accès ;
- implémenter la connexion sur Android et Desktop ;
- stocker les jetons de façon sécurisée ;
- brancher la récupération et l'envoi manuels.

### Étape 4 — Temps réel et effets de plateforme

- observer les changements distants lorsque l'application est active ;
- resynchroniser au retour au premier plan et après reconnexion ;
- mettre à jour widget, tuile, barre des menus, alarmes et notifications ;
- ajouter les états de synchronisation aux réglages.

### Étape 5 — Robustesse

- tester les horloges décalées, changements de fuseau et changements d'heure ;
- tester deux actions concurrentes et des livraisons dans le désordre ;
- tester le redémarrage pendant une opération en attente ;
- ajouter une stratégie de nouvelle tentative avec délai exponentiel ;
- documenter la suppression et l'export des données.

## Critères d'acceptation

- un Focus démarré sur Android apparaît sur Desktop avec la même intention et la même échéance ;
- arrêter ou mettre en pause ce Focus sur Desktop met Android à jour et annule son alarme obsolète ;
- une modification hors ligne est conservée après redémarrage puis synchronisée au retour du réseau ;
- une même opération livrée plusieurs fois ne crée pas plusieurs sessions ni plusieurs clôtures ;
- un ancien événement ne peut pas ressusciter un Focus terminé ;
- les préférences propres à une plateforme ne quittent jamais l'appareil ;
- la désactivation ou une panne de la synchronisation ne dégrade pas le fonctionnement local de DayView ;
- les jetons d'authentification ne sont jamais stockés dans les préférences ordinaires ni écrits dans les journaux.
