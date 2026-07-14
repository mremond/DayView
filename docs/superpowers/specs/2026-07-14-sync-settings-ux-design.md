# Sync settings UX: reorganized screen, guarded key actions, first-sync choice

**Date:** 2026-07-14
**Status:** Approved design, pending implementation planning

## Problem

The sync settings screen (`SyncSettingsScreen`) has grown ergonomically muddled:

1. **Destructive key actions are silent.** When a key is already stored, the
   always-visible *Générer une clé* button and the *Utiliser la phrase* button both
   overwrite the existing key with no warning. Overwriting the key breaks the sync link
   with the user's other devices.

2. **Two views of one thing look like two mechanisms.** The 24-word recovery phrase *is*
   the key (`RecoveryPhrase.encode/decode` is a reversible representation of the same
   32-byte key). The current UI presents "generate a key" and "recovery phrase" as
   separate, unrelated controls.

3. **Layout ordering buries the everyday action.** The status line (*À jour*) and the
   *Synchroniser* button sit at the bottom, sharing a row with the destructive *Effacer*
   button. The common action (sync now) should be at the top; the destructive one should
   be isolated at the bottom.

4. **First sync can clobber server data silently.** On a device's first sync, local
   fields are freshly stamped with *now*, so a merge makes local scalar settings win over
   whatever is already on the server, and lists are unioned — all without asking the user.

## Scope

Two related changes, sharing the same screen. They may be implemented as two separate
plans: **Part A** is UI + string resources only and can ship independently; **Part B**
reaches into the sync engine and coordinator.

Non-goals:
- No CRDT / merge-algorithm rewrite.
- No server-side deletion (the *Effacer* button stays a local wipe).
- No change to the recovery-phrase encoding or the key format.

---

## Part A — Reorganized screen and guarded key actions (UI + strings only)

### Layout

`SyncSettingsScreen` is reordered into four `SettingsPanelCard`s, top → bottom:

1. **Statut + Synchroniser** *(moved up from the old bottom row)*
   - Status label (`syncStatusLabel` / `syncStatusColor`, unchanged).
   - *Synchroniser* button (`onSyncNow`).
   - The *Effacer* button is removed from this row (it moves to card 4).

2. **Serveur** — URL / utilisateur / jeton fields. Unchanged from today.

3. **Clé de chiffrement** — state-aware on the existing `hasKey` parameter (see below).

4. **Effacer** *(new bottom danger-zone card)*
   - A short muted line clarifying the action disables sync on this device.
   - *Effacer* button → confirmation dialog → existing `onClear` (`clearSyncKey`). Behaviour
     of `clearSyncKey` is unchanged: it forgets the endpoint config, clears the key from
     the keystore, and resets local sync state. The server blob is not touched.

### Encryption-key card: state-aware

**No key stored (`hasKey == false`)** — the setup case, essentially today's layout:
- Section header + description.
- Status: *Aucune clé enregistrée pour l'instant* (`sync_settings_key_missing`).
- *Générer une clé* button → on click generates and shows the numbered 24-word phrase
  block with the "note it down…" prompt (existing behaviour, `onGenerateKey`).
- Divider.
- *Phrase de récupération* label + field + *Utiliser la phrase* button (`onPasteKey`).
  Invalid phrase shows the existing inline red error.

**Key stored (`hasKey == true`)** — no silent overwrite:
- Section header + description.
- Status: *Une clé est enregistrée sur cet appareil* (`sync_settings_key_present`).
- **Régénérer la clé** button → confirmation dialog → `onGenerateKey`, then the new 24-word
  phrase block is shown so it can be copied to other devices.
- **Remplacer la clé** button → reveals the *Phrase de récupération* field + *Utiliser la
  phrase* button. Submitting a **valid** phrase opens a confirmation dialog, then
  `onPasteKey` replaces the key. An **invalid** phrase shows the existing inline red error
  and does **not** open a dialog.
- No delete action here — deletion lives only in card 4 (single destructive entry point).

### Confirmation dialog

One reusable composable modelled on the existing `DetourForgetConfirmDialog`
(`DetoursUi.kt`). It takes per-action title, message, and confirm-label; a shared cancel
label. Used three times:

- **Régénérer:** "L'ancienne clé sera définitivement perdue ; la synchronisation cessera
  jusqu'à ce que vous copiiez la nouvelle clé sur vos autres appareils."
- **Remplacer:** "La clé actuelle sera remplacée. Assurez-vous que cette phrase provient
  bien de votre autre appareil."
- **Effacer:** "Cet appareil oubliera la configuration du serveur et la clé, et cessera de
  se synchroniser. Les données déjà sur le serveur ne sont pas supprimées."

Dialog local UI state (which action is pending) lives in `SyncSettingsScreen` alongside the
existing `generatedKey` / `pasteKeyDraft` / `phraseError` state.

### String resources (FR + EN, both `values/strings.xml` and `values-fr/strings.xml`)

New keys:
- `sync_settings_regenerate_key` — RÉGÉNÉRER LA CLÉ / REGENERATE KEY
- `sync_settings_replace_key` — REMPLACER LA CLÉ / REPLACE KEY
- `sync_settings_erase_note` — one-line description under the Effacer card
- Dialog strings for the three actions: title + message + confirm label
  (`sync_settings_regenerate_confirm_title`, `…_message`, and analogous for replace/erase)
- A shared cancel label (reuse an existing dialog cancel string if one exists; otherwise
  add `sync_settings_dialog_cancel`)

Reused keys: `sync_settings_generate_key`, `sync_settings_use_phrase`,
`sync_settings_phrase_label`, `sync_settings_phrase_placeholder`,
`sync_settings_phrase_invalid`, `sync_settings_generated_phrase_prompt`,
`sync_settings_key_present`, `sync_settings_key_missing`, `sync_settings_sync_now`,
`sync_settings_clear`.

### Test tags and tests

New tags for the *Régénérer* / *Remplacer* buttons and the dialog confirm/cancel buttons.
Compose UI tests (tag-based, per the project's testing conventions — never assert on
`stringResource` text):
- No-key state shows generate + phrase field; régénérer/remplacer absent.
- Key-present state shows régénérer + remplacer; the phrase field is hidden until
  *Remplacer* is pressed.
- Régénérer opens the dialog; confirming invokes `onGenerateKey`; cancelling does not.
- Remplacer reveals the field; a valid phrase + confirm invokes `onPasteKey`; an invalid
  phrase shows the inline error and opens no dialog.
- Effacer (bottom card) opens the dialog; confirming invokes `onClear`.
- Card ordering: status/synchroniser card first, effacer card last.

---

## Part B — First-sync merge/replace choice (engine + coordinator + UI)

### Trigger

Exactly one condition, evaluated inside `SyncEngine.sync` right after the pull
(`SyncEngine.kt` ~line 32): `state.baseDocument == null` (first sync on this device) **and**
`remoteDoc != null` (the server already holds a document) **and** no strategy has been
chosen yet. This is the only moment where both facts are known together.

### Strategies

A `FirstSyncStrategy` enum, threaded as a parameter into `SyncEngine.sync()`:

- **Merge** → current behaviour: `localDoc.merge(remoteDoc)` then push. Lists are unioned;
  scalar settings resolve by last-write-wins, which on a first sync means local scalars win
  (fresh *now* stamps). This local-scalars-win quirk is accepted and documented, not fixed
  here.
- **AdoptServer** (force-pull) → `applyDocument(remoteDoc, local)`, persist
  `SyncState(remote.revision, remoteDoc)`, skip the merge and the push. No new transport
  method needed (`pull()` is already GET-only).
- **PushLocal** (force-push) → push `localDoc` with `expectedRevision = remote.revision`
  (satisfies the compare-and-swap), persist `SyncState(newRevision, localDoc)`. No
  unconditional PUT needed — the freshly pulled revision is passed as the expected one.

### Flow

1. Engine `sync()` is called with no strategy. After pull, if the trigger condition holds,
   it returns a new `SyncResult.FirstSyncChoiceNeeded` (carrying nothing the UI needs beyond
   the fact itself; the re-run re-pulls).
2. `SyncCoordinator.runOnce` maps this to a new `SyncStatus.NeedsChoice` and exposes a
   pending flag (e.g. a `StateFlow<Boolean>` or reuse the status) plus a
   `resolveFirstSync(strategy)` method.
3. The UI observes the pending state and shows a **3-option dialog** in the sync settings
   screen: *Fusionner* / *Prendre le serveur* / *Envoyer cet appareil*, plus cancel.
4. On choice, the coordinator re-runs `sync(strategy)` with the chosen strategy, which
   re-pulls and applies the corresponding branch.

**Safety for auto-sync:** an automatic sync (e.g. on app resume) that hits the trigger also
stops and enters `NeedsChoice` rather than silently merging. The pending choice persists
until the user resolves it; the dialog is shown when the user is on the sync settings
screen. (A global prompt outside settings is out of scope for this change; the status
indicator reflects `NeedsChoice` meanwhile.)

### Plumbing

- `SyncEngine.sync(..., strategy: FirstSyncStrategy? = null)` + `SyncResult.FirstSyncChoiceNeeded`.
- `SyncCoordinator`: `SyncStatus.NeedsChoice`, a pending flag, and `resolveFirstSync(FirstSyncStrategy)`.
- `SettingsUiModels`: state `firstSyncChoicePending: Boolean` and action
  `resolveFirstSync: (FirstSyncStrategy) -> Unit`.
- `App.kt`: wire the coordinator's pending flag and resolve method into the actions/state.
- `DayViewSettingsScreen.kt` / `SyncSettingsScreen`: observe the flag, render the dialog.

### String resources (FR + EN)

- `sync_first_sync_title` — e.g. "Des données existent déjà sur le serveur"
- `sync_first_sync_message` — explains the three choices
- `sync_first_sync_merge` — Fusionner
- `sync_first_sync_adopt_server` — Prendre le serveur
- `sync_first_sync_push_local` — Envoyer cet appareil
- `sync_status_needs_choice` — status label for `SyncStatus.NeedsChoice`

### Tests

- Engine: with `baseDocument == null` and a non-null remote, `sync(null)` returns
  `FirstSyncChoiceNeeded`; `sync(Merge)` merges; `sync(AdoptServer)` yields the remote doc
  and its revision; `sync(PushLocal)` pushes local and persists local as base.
- Coordinator: `FirstSyncChoiceNeeded` → `NeedsChoice` status + pending flag set;
  `resolveFirstSync(x)` re-runs with strategy `x` and clears the flag on success.
- UI: pending flag shows the 3-option dialog; each option invokes `resolveFirstSync` with
  the matching strategy; cancel dismisses without resolving.

---

## Verification

- `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` green.
- Manual: on desktop, exercise the key-present state (régénérer/remplacer dialogs), the
  bottom Effacer dialog, and a first-sync against a server that already holds a document
  (all three strategies).
