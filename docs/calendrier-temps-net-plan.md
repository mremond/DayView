# Plan d'intégration calendrier pour le temps net

## Résumé

DayView pourra afficher, en plus du temps brut de la journée, un **temps net** :
le temps restant duquel on soustrait les périodes marquées comme **occupé** dans
le calendrier de l'appareil. Ces périodes sont **grisées** sur le cercle afin de
montrer d'un coup d'œil ce qui reste réellement disponible.

La fonction est **optionnelle, désactivée par défaut et entièrement locale**.
DayView lit les calendriers déjà présents sur l'appareil en **lecture seule**,
sans compte à configurer ni accès réseau. Un refus d'autorisation, l'absence de
calendrier ou une erreur de lecture ne doit jamais modifier le décompte de base :
le temps brut reste toujours la référence.

## Expérience cible

### Affichage

Le décompte brut de la journée — l'anneau et le grand chiffre — reste
**inchangé**. Il continue de se consumer entre les heures de début et de fin
choisies, exactement comme aujourd'hui.

Le **temps net** s'affiche comme information **secondaire**, sous le décompte
principal. Il correspond au temps brut restant moins les plages occupées encore
à venir dans la fenêtre de la journée.

```text
04 h 12 restantes
Net : 02 h 45  ·  1 h 27 en réunion
```

Lorsque la fonction est désactivée, ou qu'aucune plage occupée n'est présente, la
ligne de temps net disparaît et l'écran retrouve son apparence actuelle.

### Sur le cercle

Chaque plage occupée est projetée en **arc grisé** sur le cercle, à sa position
réelle dans la fenêtre début → fin de la journée. Trois états restent visuellement
distincts, en thème clair comme en thème sombre :

- l'arc **déjà consumé** (temps écoulé) ;
- l'arc **restant disponible** ;
- les **arcs occupés** grisés, qu'ils soient passés ou à venir.

Le repère du moment présent conserve son rôle actuel. Les arcs occupés sont un
calque décoratif : ils n'altèrent ni l'angle du repère, ni le décompte brut.

### Nom de l'événement au survol

Sur les plateformes dotées d'un pointeur (macOS, et Android en mode fenêtré avec
souris), **survoler un arc occupé** affiche un **overlay** rappelant le **nom de
l'événement** correspondant, avec ses horaires :

```text
Réunion produit
14 h 00 – 15 h 00
```

L'overlay suit la position du pointeur et disparaît dès que celui-ci quitte l'arc.
Si plusieurs événements ont été fusionnés en une seule plage, l'overlay liste
leurs noms. Le titre n'est utilisé **que** pour cet affichage à la demande : il
n'entre pas dans le calcul du temps net, qui ne repose que sur les bornes
horaires. Sur un appareil purement tactile, l'équivalent (appui) est renvoyé à un
second temps.

## Définition du temps net

### Ce qui compte comme « occupé »

Seuls les événements au statut **occupé / opaque** sont pris en compte. Sont
**exclus par défaut** :

- les événements marqués **disponible** (transparents) ;
- les événements **tentatifs** ;
- les événements **journée entière**, souvent des rappels, voyages ou
  anniversaires qui ne bloquent pas réellement une plage de travail.

### Calcul

1. Récupérer les événements du jour recoupant la fenêtre `[début, fin]` de DayView.
2. Ne conserver que les événements **occupé** non journée entière.
3. **Rogner** chaque événement aux bornes de la journée DayView (un événement qui
   déborde avant le début ou après la fin n'est compté que sur sa partie interne).
4. **Fusionner** les plages qui se chevauchent ou se touchent, afin de ne jamais
   compter deux fois une même minute occupée.
5. Le **temps net brut de la journée** = durée de la fenêtre − somme des plages
   occupées fusionnées.
6. Le **temps net restant** = temps brut restant − plages occupées **encore à
   venir** (partie postérieure au moment présent).

Le calcul vit dans une fonction **pure**, sans dépendance de plateforme, donc
directement testable.

## Architecture

DayView est une application Kotlin Multiplatform (Android + macOS) dont la logique
métier est partagée. L'intégration suit ce découpage.

### Modèle et calcul partagés (`commonMain`)

- `data class BusyInterval(startMillis: Long, endMillis: Long, title: String?)` :
  une plage occupée déjà rognée et normalisée. Le `title` sert uniquement à
  l'overlay de survol et reste facultatif ; il n'intervient jamais dans le calcul.
  Après fusion, une plage peut porter la liste des titres qu'elle recouvre.
- `fun mergeBusyIntervals(intervals: List<BusyInterval>): List<BusyInterval>` :
  tri et fusion des chevauchements.
- `fun calculateNetTime(progress: DayProgress, nowMillis: Long, busy: List<BusyInterval>): NetTime`
  où `NetTime` porte le temps net de la journée, le temps net restant et le total
  occupé restant. Cette fonction s'appuie sur `DayProgress` déjà existant.
- La projection en arcs réutilise la logique d'angle de
  `currentMomentAngleDegrees` : chaque `BusyInterval` devient un couple d'angles
  `(début, fin)` sur le cercle.

### Source de données par plateforme (`expect` / `actual`)

- `expect class CalendarSource` exposant une lecture **seule** des événements
  occupés du jour, retournant une `List<BusyInterval>` déjà filtrée.
- **Android** (`androidMain`) : lecture via le *Calendar Provider*
  (`CalendarContract`). Permission `READ_CALENDAR`. Sélection des calendriers à
  inclure.
- **macOS / desktop** (`desktopMain`) : lecture via **EventKit**, au travers d'une
  passerelle native. Autorisation d'accès au calendrier demandée à la première
  activation.

Aucune écriture n'est jamais effectuée dans les calendriers de l'utilisateur.

### Rafraîchissement

Les plages occupées sont relues au démarrage, à la reprise au premier plan et à
intervalle régulier tant que la fonction est active. La lecture est asynchrone et
n'entre jamais dans le chemin du rendu du décompte : en cas d'échec, la dernière
liste connue (ou une liste vide) est utilisée, et le temps brut reste affiché.

## Réglages

Un nouveau bloc dans l'écran Réglages, **désactivé par défaut** :

- interrupteur d'activation du temps net ;
- demande et rappel de l'état de l'autorisation calendrier ;
- **sélection des calendriers** à prendre en compte ;
- rappel que seuls les événements « occupé » hors journée entière sont comptés.

## Confidentialité

- Lecture **strictement locale**, aucun envoi réseau, aucun stockage sur disque du
  contenu des événements.
- Le calcul du temps net ne repose que sur les **bornes horaires** (début/fin).
- Le **titre** est lu uniquement pour l'**overlay de survol**, gardé en mémoire le
  temps de l'affichage et jamais persisté. Les **participants** et le **lieu** ne
  sont ni lus ni conservés.
- La fonction ne s'active qu'après un **consentement explicite** et l'octroi de
  l'autorisation système.

## Périmètre de la première version

Inclus :

- Android et macOS ;
- activation optionnelle et sélection des calendriers ;
- lecture des événements « occupé » du jour, hors journée entière ;
- rognage aux bornes de la journée et fusion des chevauchements ;
- affichage du temps net en information secondaire ;
- arcs occupés grisés sur le cercle ;
- overlay au survol affichant le nom de l'événement (plateformes à pointeur) ;
- rafraîchissement au démarrage, à la reprise et à intervalle régulier.

Hors périmètre pour la première version :

- l'écriture ou la création d'événements ;
- la prise en compte de plusieurs jours (cap à long terme, agenda hebdomadaire) ;
- les événements tentatifs, « disponible » et journée entière ;
- l'affichage des participants et du lieu des événements ;
- l'équivalent tactile de l'overlay de survol (appui) ;
- le widget Android et la mini-fenêtre, traités dans un second temps une fois le
  cœur validé.

## Tests

- Fonctions pures `mergeBusyIntervals` et `calculateNetTime` couvertes dans
  `commonTest` : chevauchements, plages adjacentes, débordements avant le début et
  après la fin, plage couvrant toute la journée, aucune plage, plages entièrement
  passées.
- Projection des angles d'arcs vérifiée aux bornes (début, fin, milieu de journée).
- Comportement de repli vérifié : autorisation refusée et lecture en échec
  laissent le temps brut intact.
