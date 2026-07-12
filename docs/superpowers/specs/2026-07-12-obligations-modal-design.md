# Obligations du jour — passage en modale

## Problème

La section « Obligations du jour » est rendue inline sur l'écran principal
(`PlannedObligationsSection`), dans les deux layouts (large et compact). Elle empile un
titre, la liste des obligations et un champ d'ajout entre la rangée des détours et le
panneau d'objectif, ce qui alourdit et défigure l'écran principal.

Deux problèmes secondaires liés, visibles sur l'écran actuel :

1. **Impossible de supprimer une obligation saisie par erreur.** La logique existe
   (`removePlannedObligation`) mais n'est pas câblée à l'UI ; seul « FAIT » (complétion)
   est exposé.
2. **Le motif de l'obligation est illisible en thème sombre.** Il est rendu avec
   `colors.ink` (`0xFF0B0D12`, quasi noir — la couleur *de fond*) au lieu d'une couleur
   de texte. Voir `PlannedObligationsUi.kt:42`.

## Objectif

Sortir les obligations de l'écran principal vers une modale dédiée, ouverte depuis une
puce compteur discrète près de la rangée des détours ; permettre la suppression d'une
obligation ; corriger le contraste du motif au passage. Aucun changement de modèle de
données ni de persistance.

## Décisions de conception

- **Point d'entrée : puce compteur.** Une puce compacte « Obligations n/3 » placée dans
  le cluster visuel de la rangée des détours (`DetourRow`). Style muted/arrondi cohérent
  avec l'idiome existant. **Toujours visible, même à 0** (« Obligations 0/3 »), pour
  permettre d'ouvrir et d'ajouter. Un clic ouvre la modale.
- **Suppression : icône ✕ par ligne, directe.** Chaque obligation porte un ✕ discret à
  côté de « FAIT ». Un clic supprime immédiatement, sans confirmation (enjeu faible :
  cap à 3, re-saisie triviale).
- **Contraste corrigé.** Dans la modale, le motif est rendu avec `colors.cloud`
  (taille 13, poids Medium), comme les lignes de `DetourListDialog`. Plus aucune ligne
  d'obligation sur `colors.ink`.

## Architecture

### Composants UI

- **`PlannedObligationsChip(count: Int, cap: Int, onOpen: () -> Unit)`** — nouveau
  composable compact rendant « Obligations n/3 », cliquable, placé près de `DetourRow`
  dans les deux layouts. Remplace les appels actuels à `PlannedObligationsSection` aux
  sites de rendu.
- **`PlannedObligationsDialog(...)`** — wrapper `Dialog(onDismissRequest = …)` suivant
  l'idiome de `DetourListDialog`.
- **`PlannedObligationsContent(...)`** — le contenu, extrait du Dialog pour être
  pilotable par les tests Compose sans fenêtre Dialog (comme le couple
  `DetourCaptureDialog` / `DetourCaptureContent`). Contient :
  - un titre « OBLIGATIONS DU JOUR » ;
  - la liste : chaque ligne = `motif` (`colors.cloud`) + bouton **FAIT** + icône **✕** ;
  - sous le cap (< `MAX_PLANNED_OBLIGATIONS`) : la rangée d'ajout (champ texte +
    **AJOUTER**), déplacée depuis l'ancienne section ;
  - un bouton **Fermer** (réutilise `detour_close_button`).

L'ancien `PlannedObligationsSection` est remplacé : son corps (liste + ajout) migre dans
`PlannedObligationsContent`.

### Flux

- **Ouvrir** : clic sur la puce → `showObligations = true` dans `DayViewScreen`.
- **Ajouter** : champ + AJOUTER → `actions.addPlannedObligation` (inchangé).
- **FAIT** : ferme la modale des obligations et déclenche le flux de complétion existant
  (`obligationToComplete = motif`, qui ouvre `DetourCaptureDialog` pré-rempli). Aucun
  changement de comportement de complétion.
- **✕** : `actions.removePlannedObligation(motif)` → `controller::removePlannedObligation`
  (déjà existant). La modale reste ouverte, la liste se met à jour.
- **Fermer** : `showObligations = false`.

### Câblage d'état et d'actions

- `DayViewScreen` : nouvel état `var showObligations by remember { mutableStateOf(false) }`.
- `DayViewScreenActions` : nouveau champ `removePlannedObligation: (String) -> Unit`.
- `App.kt` : câbler `removePlannedObligation = controller::removePlannedObligation`.
- `controller::removePlannedObligation` et `removePlannedObligation(current, motif)`
  existent déjà — aucune logique métier nouvelle.

### Chaînes i18n (fr + en)

- Libellé de la puce avec compteur (ex. clé `planned_obligations_chip` formatée
  « Obligations %1$d/%2$d »).
- Libellé d'accessibilité du ✕ (ex. `planned_obligation_remove_label`).
- Libellé d'accessibilité d'ouverture de la puce (ex. `planned_obligations_open_label`).
- Réutilise `detour_close_button` pour « Fermer ».
- Les clés `planned_obligations_title`, `planned_obligation_done_button`,
  `planned_obligation_add_button`, `planned_obligation_motif_label`,
  `planned_obligation_motif_placeholder` restent utilisées dans la modale.

### Test tags

- `DayViewTestTags` : ajouter un tag d'ouverture de la puce et un tag pour le ✕ de
  suppression (ex. `PlannedObligationsChip`, `PlannedObligationRemove`).

## Tests

- Adapter `PlannedObligationsUiTest` : piloter `PlannedObligationsContent` (au lieu de
  `PlannedObligationsSection`) pour les cas ajout / FAIT / cap.
- Nouveau cas : le ✕ retire l'obligation ciblée (via le callback `onRemove`).
- Vérifier via tags/données seedées, pas via `stringResource` (contrainte du harnais
  desktop de test).
- `PlannedObligationsTest` (logique pure) inchangé — `removePlannedObligation` déjà
  couvert.

## Hors périmètre

- Pas de confirmation sur la suppression.
- Pas de balayage pour supprimer.
- Pas de changement de persistance, de cap, ni du flux de complétion en détour.
- Pas de rappel/mise en évidence de la puce quand des obligations restent à faire (peut
  faire l'objet d'une itération ultérieure).
