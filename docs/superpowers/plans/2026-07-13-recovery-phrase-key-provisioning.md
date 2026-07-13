# Recovery-phrase Key Provisioning Implementation Plan (Plan 3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the base64 sync-key entry in the Sync settings screen with a BIP39 recovery phrase — 24 words that encode the 256-bit `RawSyncKey`, with a checksum that catches typos.

**Architecture:** One pure, heavily-tested `RecoveryPhrase` object in `commonMain` converts between `RawSyncKey` and 24 words using the standard BIP39 encoding (256-bit entropy + 8-bit SHA-256 checksum → 24 × 11-bit word indices), backed by the bundled 2048-word English wordlist. The Sync settings screen and its `App.kt` callbacks swap `Base64` for `RecoveryPhrase`; everything downstream (`SecureKeyStore`, transport, engine, codec) is untouched.

**Tech Stack:** Kotlin Multiplatform, cryptography-kotlin (SHA-256, already a dependency), Compose Multiplatform (settings screen).

## Global Constraints

- JDK 21 toolchain; run before every commit: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`; `./gradlew ktlintFormat` to auto-fix.
- Commit messages in English; describe the change only; **never** reference Claude/Anthropic/AI, and **never** reference `docs/superpowers/` docs.
- New code in `fr.dayview.app.sync` (`composeApp/src/commonMain/kotlin/fr/dayview/app/sync/`); tests mirror under `commonTest`.
- Compose UI test guardrail: NEVER assert `stringResource` text in `runComposeUiTest`; use `DayViewTestTags` + callbacks. `assertExists` is a member (no import). Test the pure screen, not `DayViewApp`.
- `RawSyncKey` is 32 bytes (`RawSyncKey(bytes: ByteArray)`, `RawSyncKey.generate()`, `SIZE_BYTES = 32`) in `sync/PayloadCodec.kt` — do not change it.
- The key must stay the only thing that reaches `SecureKeyStore`; `RecoveryPhrase` only translates it to/from words.
- Scope: recovery phrase only. QR/camera scanning is explicitly out (a future Plan 4).

## File Structure

- `sync/Bip39Wordlist.kt` — `internal val Bip39Wordlist: List<String>` (the 2048 official English words).
- `sync/RecoveryPhrase.kt` — `object RecoveryPhrase { encode(key): List<String>; decode(words): RawSyncKey?; decodePhrase(text): RawSyncKey? }` + a private `sha256` helper.
- `SyncSettingsScreen.kt` (modify) — numbered 24-word display + phrase input with inline error.
- `App.kt` (modify) — `generateSyncKey`/`pasteSyncKey` callbacks use `RecoveryPhrase`.
- `DayViewTestTags.kt` (modify) — new tags for the phrase input + error.
- `composeResources/values*/strings.xml` (modify) — new strings.

---

## Task 1: Bundle the BIP39 English wordlist

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/Bip39Wordlist.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/Bip39WordlistTest.kt`

**Interfaces:**
- Produces: `internal val Bip39Wordlist: List<String>` — exactly 2048 words, all lowercase, unique, index 0 = `"abandon"`, index 2047 = `"zoo"`, index 99 = `"art"`.

- [ ] **Step 1: Write the failing integrity test**

```kotlin
package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class Bip39WordlistTest {
    @Test
    fun wordlistHasTheExpectedShape() {
        assertEquals(2048, Bip39Wordlist.size)
        assertEquals(2048, Bip39Wordlist.toSet().size) // all unique
        assertEquals(Bip39Wordlist, Bip39Wordlist.map { it.lowercase() }) // all lowercase
        assertEquals("abandon", Bip39Wordlist.first())
        assertEquals("zoo", Bip39Wordlist.last())
        assertEquals("art", Bip39Wordlist[99]) // fixed BIP39 index, used by the all-zero test vector
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.Bip39WordlistTest"`
Expected: FAIL — `Bip39Wordlist` unresolved.

- [ ] **Step 3: Generate the wordlist file**

Obtain the canonical BIP39 English wordlist (2048 words, one per line) — the standard `english.txt` from the `bitcoin/bips` repository (`bip-0039/english.txt`). Verify it has 2048 lines, starts with `abandon`, ends with `zoo`. Generate the Kotlin file:

```kotlin
package fr.dayview.app.sync

// The official BIP39 English wordlist (2048 words). Do not reorder — indices are load-bearing.
internal val Bip39Wordlist: List<String> = listOf(
    "abandon", "ability", "able", /* … all 2048 words in order … */ "zoo",
)
```

Produce the `listOf(...)` body programmatically from `english.txt` (e.g. quote-and-comma each line) so no word is transcribed by hand. Do not hand-edit individual words.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.Bip39WordlistTest"`
Expected: PASS. Then `./gradlew ktlintCheck` (a 2048-element list may need `ktlintFormat`; if ktlint objects to the long file, add a targeted `@Suppress`/`.editorconfig` exception consistent with the repo, or format as one word per line).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/Bip39Wordlist.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/Bip39WordlistTest.kt
git commit -m "Bundle the BIP39 English wordlist for recovery phrases"
```

---

## Task 2: RecoveryPhrase encode/decode

**Files:**
- Create: `composeApp/src/commonMain/kotlin/fr/dayview/app/sync/RecoveryPhrase.kt`
- Test: `composeApp/src/commonTest/kotlin/fr/dayview/app/sync/RecoveryPhraseTest.kt`

**Interfaces:**
- Consumes: `Bip39Wordlist` (Task 1); `RawSyncKey` (`sync/PayloadCodec.kt`); cryptography-kotlin SHA-256.
- Produces:
  - `object RecoveryPhrase`
  - `fun encode(key: RawSyncKey): List<String>` — 24 words.
  - `fun decode(words: List<String>): RawSyncKey?` — `null` on wrong count, unknown word, or checksum mismatch; tolerant of case and surrounding whitespace on each word.
  - `fun decodePhrase(text: String): RawSyncKey?` — splits `text` on whitespace and calls `decode`.

- [ ] **Step 1: Write the failing test**

```kotlin
package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryPhraseTest {
    @Test
    fun roundTripsRandomKeys() {
        repeat(20) {
            val key = RawSyncKey.generate()
            val restored = RecoveryPhrase.decode(RecoveryPhrase.encode(key))
            assertTrue(restored != null && restored.bytes.toList() == key.bytes.toList())
        }
    }

    @Test
    fun encodesTheAllZeroStandardVector() {
        val zeros = RawSyncKey(ByteArray(32))
        val words = RecoveryPhrase.encode(zeros)
        assertEquals(List(23) { "abandon" } + "art", words) // canonical BIP39 256-bit zero vector
    }

    @Test
    fun decodesTheAllZeroStandardVector() {
        val restored = RecoveryPhrase.decode(List(23) { "abandon" } + "art")
        assertTrue(restored != null && restored.bytes.all { it == 0.toByte() })
    }

    @Test
    fun rejectsTamperedChecksum() {
        val words = RecoveryPhrase.encode(RawSyncKey.generate()).toMutableList()
        words[23] = if (words[23] == "zoo") "abandon" else "zoo" // change last word → checksum breaks
        assertNull(RecoveryPhrase.decode(words))
    }

    @Test
    fun rejectsUnknownWordAndWrongCount() {
        val valid = RecoveryPhrase.encode(RawSyncKey.generate())
        assertNull(RecoveryPhrase.decode(valid.dropLast(1))) // 23 words
        assertNull(RecoveryPhrase.decode(valid.dropLast(1) + "notaword"))
    }

    @Test
    fun toleratesWhitespaceAndCase() {
        val key = RawSyncKey.generate()
        val phrase = RecoveryPhrase.encode(key).joinToString("  ") { it.uppercase() }
        val restored = RecoveryPhrase.decodePhrase("  \n$phrase\n ")
        assertTrue(restored != null && restored.bytes.toList() == key.bytes.toList())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.RecoveryPhraseTest"`
Expected: FAIL — `RecoveryPhrase` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package fr.dayview.app.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

object RecoveryPhrase {
    private const val WORD_COUNT = 24
    private const val ENTROPY_BYTES = 32 // 256 bits
    private const val CHECKSUM_BITS = 8 // ENT / 32
    private const val TOTAL_BITS = ENTROPY_BYTES * 8 + CHECKSUM_BITS // 264 = 24 * 11

    fun encode(key: RawSyncKey): List<String> {
        val full = key.bytes + sha256(key.bytes)[0] // 32 entropy bytes + 1 checksum byte
        return (0 until WORD_COUNT).map { group ->
            var index = 0
            for (b in 0 until 11) {
                val bitPos = group * 11 + b
                val bit = (full[bitPos / 8].toInt() ushr (7 - bitPos % 8)) and 1
                index = (index shl 1) or bit
            }
            Bip39Wordlist[index]
        }
    }

    fun decode(words: List<String>): RawSyncKey? {
        val normalized = words.map { it.trim().lowercase() }
        if (normalized.size != WORD_COUNT) return null
        val indices = normalized.map { w -> Bip39Wordlist.indexOf(w).also { if (it < 0) return null } }

        val bits = BooleanArray(TOTAL_BITS)
        for (i in 0 until WORD_COUNT) {
            for (b in 0 until 11) bits[i * 11 + b] = (indices[i] ushr (10 - b)) and 1 == 1
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BYTES * 8) {
            if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        }
        val checksum = sha256(entropy)[0].toInt()
        for (b in 0 until CHECKSUM_BITS) {
            val expected = (checksum ushr (7 - b)) and 1 == 1
            if (bits[ENTROPY_BYTES * 8 + b] != expected) return null
        }
        return RawSyncKey(entropy)
    }

    fun decodePhrase(text: String): RawSyncKey? =
        decode(text.trim().split(Regex("\\s+")).filter { it.isNotBlank() })

    private val sha256Hasher = CryptographyProvider.Default.get(SHA256).hasher()

    private fun sha256(data: ByteArray): ByteArray = sha256Hasher.hashBlocking(data)
}
```

Note: confirm the cryptography-kotlin 0.5.0 SHA-256 API (`CryptographyProvider.Default.get(SHA256).hasher().hashBlocking(data)`) at implementation time; the tests (the all-zero standard vector especially) are the contract — adapt only the `sha256` helper if the API differs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.sync.RecoveryPhraseTest"`
Expected: PASS (all six tests, including the standard vector — proof the encoding matches BIP39).

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/sync/RecoveryPhrase.kt composeApp/src/commonTest/kotlin/fr/dayview/app/sync/RecoveryPhraseTest.kt
git commit -m "Add BIP39 recovery-phrase encode/decode for the sync key"
```

---

## Task 3: Swap the settings UI and callbacks to the recovery phrase

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/SyncSettingsScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/App.kt` (the `generateSyncKey`/`pasteSyncKey` callbacks, ~line 321)
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml`, `values-fr/strings.xml`
- Test: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncSettingsScreenTest.kt` (extend)

**Interfaces:**
- Consumes: `RecoveryPhrase` (Task 2).
- Produces: `SyncSettingsScreen`'s `onGenerateKey: () -> String` now returns a space-joined 24-word phrase; `onPasteKey` becomes `onPasteKey: (String) -> Boolean` (returns `true` when the phrase was valid and stored, `false` when invalid). The screen shows an inline error when it returns `false`.

- [ ] **Step 1: Add test tags and extend the failing screen test**

Add to `DayViewTestTags.kt`: `SyncSettingsPhraseInput`, `SyncSettingsPhraseError`, `SyncSettingsGeneratedPhrase` (follow the existing tag naming/const style in that file). In `SyncSettingsScreenTest.kt`, add tests:

```kotlin
@Test
fun validPhraseInvokesPasteCallback() = runComposeUiTest {
    var pasted: String? = null
    setContent {
        SyncSettingsScreen(
            config = null, status = SyncStatus.Idle, hasKey = false,
            onConfigChange = {}, onGenerateKey = { "abandon" }, onPasteKey = { pasted = it; true },
            onSyncNow = {}, onClear = {},
        )
    }
    onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).performTextInput("some phrase")
    assertEquals("some phrase", pasted)
}

@Test
fun invalidPhraseShowsErrorTag() = runComposeUiTest {
    setContent {
        SyncSettingsScreen(
            config = null, status = SyncStatus.Idle, hasKey = false,
            onConfigChange = {}, onGenerateKey = { "" }, onPasteKey = { false }, // always invalid
            onSyncNow = {}, onClear = {},
        )
    }
    onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).performTextInput("bad")
    onNodeWithTag(DayViewTestTags.SyncSettingsPhraseError).assertExists()
}
```

- [ ] **Step 2: Run — fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncSettingsScreenTest"`
Expected: FAIL — new tags unresolved / `onPasteKey` signature mismatch.

- [ ] **Step 3: Update the screen**

In `SyncSettingsScreen.kt`: change the parameter to `onPasteKey: (String) -> Boolean`. Replace the base64 generate-display with a numbered word block, and the paste field with a phrase input tracking an error flag:

```kotlin
// generate: show the returned phrase as numbered words
generatedKey?.let { phrase ->
    SelectionContainer {
        Text(
            phrase.split(Regex("\\s+")).filter { it.isNotBlank() }
                .mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("   "),
            modifier = Modifier.testTag(DayViewTestTags.SyncSettingsGeneratedPhrase),
        )
    }
}

// enter: phrase field with inline error
var phraseError by remember { mutableStateOf(false) }
OutlinedTextField(
    value = pasteKeyDraft,
    onValueChange = { draft ->
        pasteKeyDraft = draft
        phraseError = false // clear the error while editing
    },
    modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SyncSettingsPhraseInput),
    // … keep the existing label/style used by the other fields …
)
SettingsAccentButton(
    text = stringResource(Res.string.sync_settings_use_phrase),
    onClick = { phraseError = !onPasteKey(pasteKeyDraft) },
)
if (phraseError) {
    Text(
        stringResource(Res.string.sync_settings_phrase_invalid),
        color = colors.danger, // use the screen's existing error/danger color
        modifier = Modifier.testTag(DayViewTestTags.SyncSettingsPhraseError),
    )
}
```

(Match the surrounding screen's components — reuse `SettingsAccentButton`, the field style used for URL/token, and whatever the existing error/danger color token is. Keep the generate button; it now calls `onGenerateKey()` which returns the phrase.)

- [ ] **Step 4: Update the App.kt callbacks**

Around `App.kt:321`, replace `Base64` with `RecoveryPhrase`:

```kotlin
generateSyncKey = {
    val key = RawSyncKey.generate()
    syncHasKey = true
    scope.launch(Dispatchers.IO) { secureKeyStore?.storeKey(key) }
    RecoveryPhrase.encode(key).joinToString(" ")
},
pasteSyncKey = { phrase ->
    val key = RecoveryPhrase.decodePhrase(phrase)
    if (key != null) {
        syncHasKey = true
        scope.launch(Dispatchers.IO) { secureKeyStore?.storeKey(key) }
        true
    } else {
        false
    }
},
```

Add `import fr.dayview.app.sync.RecoveryPhrase`. Remove the now-unused `Base64`/`ExperimentalEncodingApi` imports **only if** nothing else in `App.kt` uses them (check first). Update the `pasteSyncKey`/`onPasteKey` type to `(String) -> Boolean` at the call site and wherever the actions are declared.

- [ ] **Step 5: Add strings**

Add to both `values/strings.xml` and `values-fr/strings.xml` (single `%` if any format specifier; follow existing key naming): `sync_settings_use_phrase` ("Use phrase" / "Utiliser la phrase") and `sync_settings_phrase_invalid` ("Invalid recovery phrase" / "Phrase de récupération invalide"). Reuse or rename the existing key-entry strings as needed so no dangling references remain.

- [ ] **Step 6: Run tests and the full gate**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncSettingsScreenTest"`
Expected: PASS. Then `./gradlew ktlintFormat && ./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest` and `./gradlew :composeApp:compileDebugKotlinAndroid` — all green.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Provision the sync key with a recovery phrase instead of base64"
```

---

## Self-Review (completed)

- **Spec coverage:** `RecoveryPhrase.encode/decode/decodePhrase` → Task 2; BIP39 wordlist → Task 1; UI swap (numbered words + phrase input + inline error) and `App.kt` callback swap → Task 3; error handling (total `decode`, wrong-key surfaces later as `KeyError`) → Task 2 tests + existing status UI. Testing (round-trip, standard vector, checksum, unknown word/count, whitespace/case, wordlist integrity, tag-based screen test) → Tasks 1–3.
- **Placeholder scan:** the only non-verbatim content is Task 1's wordlist body (2048 words can't be inlined) — the task gives an exact source, generation method, and an integrity + standard-vector test that gate correctness. Task 2/3 note two "confirm the exact API / reuse the existing style" points, both with the tests as the contract, not deferred work.
- **Type consistency:** `RecoveryPhrase.encode(RawSyncKey): List<String>`, `decode(List<String>): RawSyncKey?`, `decodePhrase(String): RawSyncKey?`, and `onPasteKey: (String) -> Boolean` are used identically across Tasks 2 and 3.

## Out of scope (future Plan 4)

QR-code display and Android camera scanning (CameraX + barcode library + camera permission + scanner screen), layered on the same key. The recovery phrase remains the cross-platform baseline.
