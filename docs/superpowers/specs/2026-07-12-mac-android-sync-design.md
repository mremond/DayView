# Design — Synchronisation d'état Mac ↔ Android

Date : 2026-07-12
Statut : proposé (en attente de relecture)

## 1. Objectif

Synchroniser **tout l'état partagé** de DayView entre les appareils d'un même
utilisateur (macOS + Android, Linux à terme), avec une vraie gestion des conflits,
sans dépendre d'un service tiers, et **chiffré de bout en bout** : le serveur de
synchronisation ne voit jamais le contenu en clair.

### Buts

- Retrouver, sur n'importe quel appareil, l'objectif en cours, les réglages durables
  **et** la progression du jour (détours, clean sessions, streak).
- Fusion **par champ**, jamais « dernier fichier gagne » : deux appareils qui touchent
  la même journée ne s'écrasent pas mutuellement.
- Cœur de sync **transport-agnostique** dans `commonMain` : un premier transport HTTP
  auto-hébergé, remplaçable (Firestore, fichier cloud) sans toucher au cœur.
- **Chiffrement de bout en bout** : le serveur stocke des blobs opaques.

### Non-buts (v1)

- Pas de multi-utilisateur ni de partage entre personnes.
- Pas de temps-réel/push : synchronisation au premier plan / au réveil, plus déclenchée
  après une écriture locale (débounce). `observe()` push viendra plus tard en extension.
- Pas d'historique / versions antérieures récupérables.

## 2. Portée : ce qui se synchronise

L'état vit aujourd'hui dans un unique `DayPreferencesSnapshot`
([`DayPreferencesStore.kt`](../../../composeApp/src/commonMain/kotlin/fr/dayview/app/DayPreferencesStore.kt)).
Tout ne traverse pas.

| Champ | Sync ? | Stratégie de merge |
|---|---|---|
| `startMinutes`, `endMinutes` | ✅ | LWW horodaté |
| `showSeconds` | ✅ | LWW horodaté |
| `soundSettings.*` | ✅ | LWW horodaté (groupe) |
| `goalTitle`, `goalDeadline`, `goalStart` | ✅ | LWW horodaté (groupe objectif) |
| `pomodoroMinutes`, `pomodoroEnd` | ✅ | LWW horodaté |
| `focusIntention` | ✅ | LWW horodaté |
| `themeMode` | ✅ | LWW horodaté |
| `detours` (+ `detoursDayKey`) | ✅ | Groupe daté : dayKey récent gagne ; à dayKey égal, **union par id** |
| `plannedObligations` (+ `…DayKey`) | ✅ | Idem détours |
| `recentDetourMotifs` | ✅ | Union puis N plus récents |
| `cleanSessions` | ✅ | `streakDays`/`streakLastDayKey` : max ; `cleanToday` : max à dayKey égal |
| `onGoalApps` | ❌ local | Packages Android — sans objet ailleurs |
| `netTimeSettings.includedCalendarIds` | ❌ local | IDs de calendriers par appareil |
| `netTimeSettings.enabled` | ✅ | LWW horodaté (le toggle est partageable) |
| `fontScale` | ❌ local | Spécifique à l'affichage de l'appareil |
| `focusPresence`, icône monochrome (desktop) | ❌ local | Déjà hors snapshot, macOS-only |

Règle : le sous-ensemble **local** ne quitte jamais l'appareil et n'est jamais écrasé
par une sync. Le `SyncDocument` ne contient que la colonne « ✅ ».

## 3. Architecture

```
        commonMain (partagé, testable, sans I/O plateforme)
   ┌────────────────────────────────────────────────────────┐
   │  DayPreferences (existant)                              │
   │        ▲            │                                    │
   │        │ applyLocally│ buildLocalDocument                │
   │        │            ▼                                    │
   │  ┌──────────────────────────┐   règles pures            │
   │  │      SyncEngine          │──▶ SyncDocument.merge()    │
   │  │  boucle pull→merge→push  │   (LWW + union par id)     │
   │  └──────────┬───────────────┘                            │
   │             │ payload clair (JSON)                       │
   │             ▼                                            │
   │  ┌──────────────────────────┐                            │
   │  │  PayloadCodec (crypto)   │  chiffre/déchiffre         │
   │  │  AES-256-GCM + SyncKey   │                            │
   │  └──────────┬───────────────┘                            │
   │             │ String opaque (chiffré, base64)            │
   │             ▼                                            │
   │  ┌──────────────────────────┐                            │
   │  │  SyncTransport (interface)│                           │
   │  │  HttpSyncTransport (Ktor) │                           │
   │  └──────────────────────────┘                            │
   └──────────────┬─────────────────────────┬────────────────┘
                  │ expect/actual            │ config
                  ▼                          ▼
          SecureKeyStore            SyncConfig (URL, token, deviceId)
       (Keystore / Keychain /       persistés hors DataStore synchronisé
        fichier chiffré desktop)
```

Unités, chacune avec une responsabilité unique et testable en isolation :

- **`SyncEngine`** — orchestration : quand synchroniser, boucle pull→merge→push avec
  retry sur conflit, application locale. Ne connaît ni crypto ni HTTP (injectés).
- **`SyncDocument`** + **`SyncDocument.merge()`** — schéma versionné par champ et
  **fonctions de merge pures** (aucune I/O). Cœur testable ligne à ligne.
- **`PayloadCodec`** — sérialise le `SyncDocument` en JSON puis chiffre (AES-256-GCM).
  Frontière E2EE : au-dessus c'est clair, en dessous c'est opaque.
- **`SyncTransport`** — get/put opaque avec concurrence optimiste (déjà validé).
  `HttpSyncTransport` = implémentation Ktor.
- **`SecureKeyStore`** (`expect`/`actual`) — range la clé de sync dans le coffre de
  l'OS.
- **`SyncConfig`** — URL du endpoint, token d'appareil, `deviceId`. Persisté **à part**
  du DataStore synchronisé (sinon boucle : la config voyagerait avec l'état).

## 4. `SyncDocument` : schéma versionné

Chaque champ (ou groupe logique) porte sa propre estampille logique. Le merge compare
champ à champ, jamais le document entier.

```kotlin
/** Estampille logique d'une valeur : gagne la plus récente, égalité tranchée par deviceId. */
data class Stamp(val at: Long, val by: String)   // at = ms epoch ; by = deviceId

data class Versioned<T>(val value: T, val stamp: Stamp)

/** Élément de liste adressable, avec tombstone pour propager les suppressions. */
data class SyncItem<T>(
    val id: String,
    val value: T,
    val deleted: Boolean,
    val stamp: Stamp,
)

/** Groupe de données daté (détours, obligations) : rattaché à un jour. */
data class DayScoped<T>(val dayKey: Long, val items: List<SyncItem<T>>)

data class SyncDocument(
    val schemaVersion: Int,
    // scalaires / groupes LWW
    val dayWindow: Versioned<DayWindow>,           // start + end
    val showSeconds: Versioned<Boolean>,
    val sound: Versioned<SoundSettings>,
    val goal: Versioned<GoalState>,                // title + deadline + start
    val pomodoro: Versioned<PomodoroState>,
    val focusIntention: Versioned<String>,
    val themeMode: Versioned<String>,
    val netTimeEnabled: Versioned<Boolean>,
    // groupes datés
    val detours: DayScoped<Detour>,
    val plannedObligations: DayScoped<PlannedObligation>,
    val recentDetourMotifs: List<SyncItem<String>>,
    // compteurs
    val cleanSessions: Versioned<CleanSessionLedger>,
    // métadonnées locales, PAS sérialisées dans le payload distant
    val baseRevision: String?,                     // dernière révision serveur connue
)
```

Sérialisation : `kotlinx.serialization` JSON. `schemaVersion` permet les migrations
futures. `baseRevision` est un champ local (non émis) qui alimente `expectedRevision`.

## 5. Règles de merge (pures)

`SyncDocument.merge(local, remote): SyncDocument`. `remote` peut être `null` (première
sync). Règles par type :

- **`Versioned<T>` (LWW)** — on garde la valeur dont le `Stamp` est le plus grand
  (`at`, puis `by` en tiebreak lexicographique). Déterministe et commutatif.
- **`DayScoped<T>` (détours, obligations)** :
  1. Si les `dayKey` diffèrent, le **jour le plus récent gagne en bloc** (les données
     d'un jour révolu ne ressuscitent pas).
  2. À `dayKey` égal, **union par `id`** : pour chaque id, on garde le `SyncItem` au
     `Stamp` le plus récent ; un `deleted=true` récent l'emporte (tombstone).
- **`recentDetourMotifs`** — union par id, puis on tronque aux N plus récents par
  `Stamp`.
- **`cleanSessions`** — `streakDays` et `streakLastDayKey` : on prend l'entrée au plus
  grand `streakLastDayKey` (le streak le plus à jour). `cleanToday` : **max** à `dayKey`
  égal, 0 sinon. Choix pragmatique : `max` évite la double-comptabilisation lors d'un
  re-merge ; le compromis (sessions faites en parallèle sur deux appareils le même jour
  non additionnées) est acceptable pour un usage mono-personne.

Toutes ces règles sont **commutatives et idempotentes** : `merge(a, b) == merge(b, a)`,
et remerger un résultat déjà fusionné ne le change pas. C'est ce qui rend la boucle de
retry sûre.

### Horloge et dérive

v1 : LWW sur horloge murale (`at` en ms) avec tiebreak `deviceId`. Simple, suffisant
pour deux appareils bien réglés. **Risque** : une horloge d'appareil faussée peut faire
« gagner » une vieille valeur. Durcissement prévu si le problème se manifeste : passer
`Stamp.at` à une **horloge logique hybride (HLC)** — `max(horloge_murale, at_distant) + 1`
— qui garde des timestamps lisibles tout en interdisant les retours en arrière. Non
implémenté en v1 (YAGNI), mais le type `Stamp` est déjà prêt à l'accueillir.

## 6. Chiffrement de bout en bout

Frontière : `PayloadCodec` chiffre le JSON du `SyncDocument` avant qu'il n'atteigne le
transport. Le serveur ne stocke que du chiffré + une révision. Même les timestamps par
champ sont **à l'intérieur** du chiffré : le serveur n'apprend que la taille du blob et
la fréquence des écritures.

### Primitive

- **AES-256-GCM**, nonce aléatoire de 96 bits par message (jamais réutilisé), tag
  d'authentification 128 bits. AAD = `schemaVersion` (lie le chiffré à sa version de
  schéma). Format du blob : `base64(nonce ‖ ciphertext ‖ tag)`.
- Bibliothèque : **cryptography-kotlin** (whyoleg) — couvre JDK, Android et Apple/Native,
  donc les 3 cibles sans trou (contrairement aux bindings libsodium via cinterop).
  Fournit AES-GCM et un `SecureRandom` multiplateforme.

### Clé de sync et provisioning

Une seule **clé symétrique de 256 bits** (`SyncKey`) partagée entre les appareils du même
utilisateur. Approche recommandée : **clé aléatoire + phrase de récupération**.

1. Le premier appareil génère une `SyncKey` aléatoire (haute entropie).
2. Il l'affiche sous forme de **phrase de récupération** (BIP39-like, ~24 mots) et/ou
   d'un **QR code**.
3. Le second appareil l'importe (scan QR ou saisie de la phrase). Aucune clé ne transite
   par le serveur.

Alternative écartée : clé dérivée d'une passphrase via PBKDF2/Argon2id. Plus simple à
saisir mais entropie tributaire du mot de passe, et portabilité KDF inégale sur Native.
La clé aléatoire est plus robuste et évite la dépendance à un KDF. (On pourra offrir la
passphrase en option secondaire plus tard.)

### Stockage de la clé — `SecureKeyStore` (`expect`/`actual`)

- **Android** : Android Keystore (`MasterKey` + `EncryptedSharedPreferences` /
  `EncryptedFile`), clé non exportable, adossée au matériel si dispo.
- **macOS natif (à venir)** : Keychain via le framework Security.
- **JVM desktop (aujourd'hui)** : pas de coffre OS portable → fichier sous `~/.dayview`
  en permissions `600`, **enveloppé** par une clé dérivée d'une passphrase locale
  optionnelle. **Plus faible** que le matériel ; assumé et documenté pour la v1. La
  migration macOS→SwiftUI natif fera passer le desktop macOS sur Keychain.

### Conséquence sur le merge

Aucune : le merge reste **client-side** (déjà le cas). Boucle E2EE : `pull` (chiffré) →
`PayloadCodec.decrypt` → `merge` → `PayloadCodec.encrypt` → `push` (chiffré). La
concurrence optimiste fonctionne sur la révision du blob chiffré, sans lecture du clair.

### Perte de clé

Perdre la `SyncKey` (tous les appareils réinitialisés, phrase perdue) = état distant
irrécupérable **par design**. On documente la phrase de récupération comme critique, et
un appareil encore appairé peut toujours ré-émettre la clé vers un nouvel appareil.

## 7. Transport HTTP (endpoint auto-hébergé)

Interface `SyncTransport` (déjà validée) implémentée par `HttpSyncTransport` (client
Ktor, `commonMain`). Un seul document par utilisateur.

| Opération | Requête | Réponse |
|---|---|---|
| `pull()` | `GET /sync/{userId}` `Authorization: Bearer <token>` | `200` `{ revision, payload }` ‖ `204` si vide |
| `push(payload, expected)` | `PUT /sync/{userId}` `If-Match: <expected>` (ou `If-None-Match: *` si `expected == null`) corps `{ payload }` | `200` `{ revision }` ‖ `412` `{ revision, payload }` si la révision a bougé |

- **Révision** = etag opaque assigné par le serveur (compteur monotone ou hash). Un
  `412 Precondition Failed` mappe sur `PushOutcome.Rejected(current)`.
- **Auth** = token d'appareil (Bearer) provisionné à la config. Indépendant de la
  `SyncKey` E2EE : le token protège l'accès au blob, la clé protège son contenu.
- **Serveur** : minimal — stocke `{userId → (revision, payload)}` avec compare-and-set
  sur la révision. Peut être un petit service HTTP dédié ou greffé sur l'infra existante.
  Ne déchiffre rien, ne fusionne rien.
- **Erreurs** : réseau/5xx → exceptions remontées au `SyncEngine`, qui abandonne le
  cycle et réessaiera au prochain déclencheur. Seul `412` est un résultat de contrôle,
  pas une erreur.

## 8. Boucle et déclencheurs

```kotlin
suspend fun sync() {
    val local = buildLocalDocument()          // snapshot + versions par champ
    var expected = local.baseRevision
    repeat(MAX_RETRIES) {                      // p.ex. 3
        val remote = transport.pull()
        val merged = SyncDocument.merge(local, remote?.let { codec.decrypt(it.payload) })
        val outcome = transport.push(codec.encrypt(merged), remote?.revision ?: expected)
        when (outcome) {
            is PushOutcome.Applied  -> { applyLocally(merged, outcome.revision); return }
            is PushOutcome.Rejected -> expected = outcome.current.revision   // re-merge, retry
        }
    }
    // échec après MAX_RETRIES : on laisse tel quel, prochain déclencheur réessaiera
}
```

Déclencheurs :

- **Au premier plan / au réveil** (foreground, resume) — réutilise les hooks de cycle de
  vie existants.
- **Après une écriture locale**, avec **débounce** (~2–5 s) pour regrouper les rafales.
- Jamais en boucle serrée : pas de polling permanent en v1.

Concurrence : un seul cycle de sync à la fois (mutex). Un déclencheur pendant un cycle
en cours est coalescé en « resync à la fin ».

## 9. Cas limites

- **Première sync (distant vide)** : `pull()` → `null`/`204` ; `push` avec
  `If-None-Match: *`. Si un autre appareil a créé le doc entre-temps → `412` → merge.
- **Bascule de jour** : géré par la règle `DayScoped` (dayKey récent gagne). Un appareil
  resté sur hier ne réinjecte pas les détours d'hier dans aujourd'hui.
- **Appareil hors-ligne longtemps** : au retour, son `dayKey` est ancien → ses données du
  jour périmées perdent proprement ; ses champs LWW ne gagnent que s'ils sont réellement
  plus récents.
- **Clé absente/incorrecte sur un appareil** : `codec.decrypt` échoue (tag GCM invalide)
  → la sync est **suspendue** avec un état d'erreur clair « clé de sync manquante /
  invalide », l'UI propose de ré-importer la phrase. On n'écrase jamais le distant avec
  du clair.
- **Champs locaux** : `buildLocalDocument` ne les lit pas ; `applyLocally` ne les touche
  pas. Garanti par construction (ils ne sont pas dans `SyncDocument`).

## 10. Tests

- **Merge (le cœur)** — `commonTest`, table-driven sur `SyncDocument.merge` : LWW dans
  les deux sens, commutativité (`merge(a,b) == merge(b,a)`), idempotence
  (`merge(merge(a,b),b) == merge(a,b)`), union de détours, tombstones, bascule de jour,
  `cleanToday` max. Aucune I/O, pas de plateforme.
- **Codec E2EE** — round-trip chiffrer→déchiffrer ; échec sur clé fausse ; nonce distinct
  à chaque chiffrement ; incompatibilité de `schemaVersion` via l'AAD.
- **Boucle SyncEngine** — `SyncTransport` factice : succès, `Rejected` puis succès au
  retry, épuisement des retries, coalescing des déclencheurs. Transport et codec mockés.
- **`HttpSyncTransport`** — contre le moteur HTTP de test Ktor : mapping etag/If-Match,
  412→Rejected, 204→null, propagation des 5xx en exception.
- Respecter les garde-fous de test du repo (pas d'assertion sur `stringResource`, tags /
  données seedées).

## 11. Suites possibles (hors v1)

- Extension `observe(): Flow<Unit>` sur le transport pour du push temps-réel (SSE /
  WebSocket / XMPP PubSub sur l'infra existante).
- HLC pour durcir l'ordonnancement en cas de dérive d'horloge.
- Passphrase comme méthode de clé secondaire.
- Rotation de clé (ré-chiffrer le doc sous une nouvelle `SyncKey`).

## Décision ouverte

- **UX de provisioning de clé** : phrase de récupération (~24 mots), QR code, ou les
  deux ? Recommandation : QR pour Mac→Android (scan facile) **et** phrase de secours
  affichée à écrire. À trancher avant le plan d'implémentation.
