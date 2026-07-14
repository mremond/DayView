# Design — Recovery-phrase key provisioning (Sync, Plan 3)

Date: 2026-07-13
Status: proposed (pending review)

## Goal

Replace the base64 sync-key entry in the Sync settings screen with a **BIP39
recovery phrase**: 24 human-readable words that encode the 256-bit `RawSyncKey`,
with a checksum that catches typos. This makes moving the key between a user's
devices legible and error-resistant, across macOS ↔ Android ↔ Linux, with no
camera and no heavy new dependencies.

### Non-goals (deferred to a future Plan 4)

QR-code display and camera scanning. Those add an Android camera stack (CameraX
+ a barcode library), a runtime camera permission, and a scanner screen, and
only ever flow one way (desktop has no camera). The recovery phrase works
everywhere and is the cross-platform core; QR is a later convenience layered on
top of the same key.

Implementation update (2026-07-14): the later guided-sync work has now added
QR generation on desktop and Android plus scanning on Android. It uses Google
Code Scanner rather than CameraX, so DayView itself does not request camera
permission. The QR also carries the server URL and account identifier, together
with a short-lived single-use enrollment code; it never carries a long-lived
access token.

## Scope

Replace, in `SyncSettingsScreen` + its `App.kt` wiring:
- **Generate** — today: `RawSyncKey.generate()` → base64 shown in a
  `SelectionContainer`. New: the same key rendered as 24 numbered words.
- **Enter** — today: a paste field decoding base64. New: a phrase field where the
  user types the 24 words (space/newline-separated), validated by checksum.

Everything else stays: the key still lands in `SecureKeyStore` unchanged; the
transport, engine, codec, config, and triggers are untouched.

## Architecture

One pure, testable unit plus a bundled asset and a UI swap.

```
  commonMain (fr.dayview.app.sync)
  ┌───────────────────────────────────────────────┐
  │  RecoveryPhrase                                │
  │    encode(key: RawSyncKey): List<String>       │  ← 24 words
  │    decode(words: List<String>): RawSyncKey?    │  ← null on any error
  │        ▲                                        │
  │        │ uses                                   │
  │  Bip39Wordlist (2048 words, bundled)           │
  │  SHA-256 (cryptography-kotlin, already a dep)  │
  └───────────────────────────────────────────────┘
              ▲                         ▲
              │ generate                │ enter
   SyncSettingsScreen (numbered words) │ (phrase field + inline error)
              ▲                         ▲
              └──────── App.kt callbacks (swap Base64 → RecoveryPhrase)
```

### Components

- **`RecoveryPhrase`** (`sync/RecoveryPhrase.kt`) — pure object, no I/O.
  - `encode(key: RawSyncKey): List<String>` — the standard BIP39 encoding of
    256-bit entropy: append an 8-bit checksum (first byte of `SHA-256(entropy)`),
    split the resulting 264 bits into 24 groups of 11 bits, map each group
    (0–2047) to a wordlist entry.
  - `decode(words: List<String>): RawSyncKey?` — inverse; returns `null` when
    the word count is not 24, any word is not in the list, or the checksum does
    not match. Case-insensitive, trims/collapses whitespace before matching.
- **`Bip39Wordlist`** — the official 2048-word English list, bundled (a generated
  Kotlin `List<String>` or a resource read once). Chosen for low visual/spelling
  ambiguity; using the standard list avoids bikeshedding and gives interop.
- **`SyncSettingsScreen`** — replace the base64 display with a numbered 24-word
  block (still inside a `SelectionContainer` so it can be copied), and the paste
  field with a phrase input showing an inline "invalid recovery phrase" message
  when `decode` returns `null`.
- **`App.kt`** — `generateSyncKey` returns the phrase text (`encode(...)` joined
  by spaces); `pasteSyncKey`/enter decodes via `RecoveryPhrase.decode`, stores
  the key on success, signals the error on `null`.

## Data flow

1. **Provision on device A:** user taps Generate → `RawSyncKey.generate()` →
   `RecoveryPhrase.encode` → 24 numbered words shown → stored in
   `SecureKeyStore` (as today).
2. **Enter on device B:** user types the 24 words → `RecoveryPhrase.decode` →
   on success, the resulting `RawSyncKey` is stored in `SecureKeyStore`; sync can
   now run. On failure, nothing is stored.

## Error handling

- `decode` is total: any malformed input (wrong count, unknown word, bad
  checksum) yields `null` — never throws. The screen shows an inline error and
  does not store a key; sync status is untouched.
- A wrong-but-valid phrase (correct checksum, wrong key) is indistinguishable at
  entry time — it surfaces later as `SyncStatus.KeyError` on the first sync
  (decrypt fails), which the existing UI already reports. No new handling needed.

## Testing

`RecoveryPhrase` is pure and gets the bulk of the tests (`commonTest`):
- **Round-trip:** `decode(encode(key)) == key` for several random keys.
- **Standard vector:** all-zero 32-byte entropy encodes to the canonical BIP39
  phrase (`"abandon" × 23 + "art"`), proving the encoding matches the standard.
- **Checksum:** swapping one word for another valid word makes `decode` return
  `null`.
- **Unknown word / wrong count:** both return `null`.
- **Whitespace/case tolerance:** extra spaces, newlines, and mixed case decode
  correctly.
- **Wordlist integrity:** exactly 2048 words, unique, lowercase.

UI: a tag-based `SyncSettingsScreen` test (per the repo guardrail — no
`stringResource` assertions) that entering a valid phrase invokes the store
callback and an invalid one surfaces the error tag without storing.

## Open decision

None. Word count (24), scheme (BIP39), and directionality (phrase typed on the
receiving device) are all settled by the 256-bit key size and the phrase-first
scope.
