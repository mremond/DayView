# Sync Settings Screen Rework (Part A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the sync settings screen (sync status + action on top, erase isolated at the bottom) and make encryption-key actions state-aware and guarded, so a stored key is never silently overwritten.

**Architecture:** Pure UI + string-resource change to the stateless `SyncSettingsScreen`. No business logic changes — the existing `onGenerateKey` / `onPasteKey` / `onSyncNow` / `onClear` callbacks are reused. A single reusable confirmation dialog (modelled on `DetourForgetConfirmDialog`) gates the three destructive actions (regenerate / replace / erase). The dialog's *content* is split into a separately-testable composable because desktop `Dialog` windows are unreachable from `runComposeUiTest`.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, `org.jetbrains.compose.resources` string resources, `runComposeUiTest` (desktopTest, tag-based).

## Global Constraints

- ktlint is enforced — run `./gradlew ktlintCheck` before each commit (or `ktlintFormat` to auto-fix).
- Full gate before finishing: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`.
- Compose UI tests: **never** assert on `stringResource` text (unresolved under `runComposeUiTest` on CI) — drive by test tags only. `assertExists` / `assertDoesNotExist` are members (no import).
- Strings live in **both** `composeApp/src/commonMain/composeResources/values/strings.xml` (English) and `.../values-fr/strings.xml` (French). Every new key must be added to both.
- Commit messages: English, describe the change only, no AI/Claude references, no test-plan section.
- Reuse the existing `dialog_cancel` string (`Cancel` / `Annuler`) — do not add a new cancel string.

---

## File Structure

- **Modify** `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt` — add four test tags.
- **Modify** `composeApp/src/commonMain/composeResources/values/strings.xml` and `.../values-fr/strings.xml` — add new key/dialog strings.
- **Modify** `composeApp/src/commonMain/kotlin/fr/dayview/app/SyncSettingsScreen.kt` — reorder cards, state-aware key card, gated actions, and the new `SyncConfirmDialog` / `SyncConfirmDialogContent` composables + `SyncConfirmAction` enum.
- **Modify** `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncSettingsScreenTest.kt` — update the clear test, add state/gating tests.
- **Create** `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncConfirmDialogContentTest.kt` — dialog content wiring tests.

---

## Task 1: Test tags and string resources

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt:73`
- Modify: `composeApp/src/commonMain/composeResources/values/strings.xml:311`
- Modify: `composeApp/src/commonMain/composeResources/values-fr/strings.xml:310`

**Interfaces:**
- Produces (test tags on `DayViewTestTags`): `SyncSettingsRegenerateKey`, `SyncSettingsReplaceKey`, `SyncConfirmDialogConfirm`, `SyncConfirmDialogCancel` (all `const val String`).
- Produces (Res string keys): `sync_settings_regenerate_key`, `sync_settings_replace_key`, `sync_settings_erase_note`, `sync_settings_regenerate_confirm_title`, `sync_settings_regenerate_confirm_message`, `sync_settings_regenerate_confirm_button`, `sync_settings_replace_confirm_title`, `sync_settings_replace_confirm_message`, `sync_settings_replace_confirm_button`, `sync_settings_erase_confirm_title`, `sync_settings_erase_confirm_message`, `sync_settings_erase_confirm_button`.

- [ ] **Step 1: Add the four test tags**

In `DayViewTestTags.kt`, immediately after the line `const val SyncSettingsClear = "syncSettingsClear"` (line 73), add:

```kotlin
    const val SyncSettingsRegenerateKey = "syncSettingsRegenerateKey"
    const val SyncSettingsReplaceKey = "syncSettingsReplaceKey"
    const val SyncConfirmDialogConfirm = "syncConfirmDialogConfirm"
    const val SyncConfirmDialogCancel = "syncConfirmDialogCancel"
```

- [ ] **Step 2: Add English strings**

In `values/strings.xml`, immediately after `<string name="sync_settings_clear">CLEAR</string>` (line 311), add:

```xml
    <string name="sync_settings_regenerate_key">REGENERATE KEY</string>
    <string name="sync_settings_replace_key">REPLACE KEY</string>
    <string name="sync_settings_erase_note">Disables sync on this device. Data already on the server is not deleted.</string>
    <string name="sync_settings_regenerate_confirm_title">Regenerate key?</string>
    <string name="sync_settings_regenerate_confirm_message">The old key will be lost. Sync will stop until you copy the new key to your other devices.</string>
    <string name="sync_settings_regenerate_confirm_button">REGENERATE</string>
    <string name="sync_settings_replace_confirm_title">Replace key?</string>
    <string name="sync_settings_replace_confirm_message">The current key will be replaced. Make sure this phrase comes from your other device.</string>
    <string name="sync_settings_replace_confirm_button">REPLACE</string>
    <string name="sync_settings_erase_confirm_title">Erase sync?</string>
    <string name="sync_settings_erase_confirm_message">This device will forget the server settings and key and stop syncing. Data already on the server is not deleted.</string>
    <string name="sync_settings_erase_confirm_button">ERASE</string>
```

- [ ] **Step 3: Add French strings**

In `values-fr/strings.xml`, immediately after `<string name="sync_settings_clear">EFFACER</string>` (line 310), add:

```xml
    <string name="sync_settings_regenerate_key">RÉGÉNÉRER LA CLÉ</string>
    <string name="sync_settings_replace_key">REMPLACER LA CLÉ</string>
    <string name="sync_settings_erase_note">Désactive la synchronisation sur cet appareil. Les données déjà sur le serveur ne sont pas supprimées.</string>
    <string name="sync_settings_regenerate_confirm_title">Régénérer la clé ?</string>
    <string name="sync_settings_regenerate_confirm_message">L\'ancienne clé sera perdue. La synchronisation cessera jusqu\'à ce que vous copiiez la nouvelle clé sur vos autres appareils.</string>
    <string name="sync_settings_regenerate_confirm_button">RÉGÉNÉRER</string>
    <string name="sync_settings_replace_confirm_title">Remplacer la clé ?</string>
    <string name="sync_settings_replace_confirm_message">La clé actuelle sera remplacée. Assurez-vous que cette phrase provient bien de votre autre appareil.</string>
    <string name="sync_settings_replace_confirm_button">REMPLACER</string>
    <string name="sync_settings_erase_confirm_title">Effacer la synchronisation ?</string>
    <string name="sync_settings_erase_confirm_message">Cet appareil oubliera la configuration du serveur et la clé, et cessera de se synchroniser. Les données déjà sur le serveur ne sont pas supprimées.</string>
    <string name="sync_settings_erase_confirm_button">EFFACER</string>
```

Note: apostrophes in French XML values are escaped as `\'` (matching the existing file's convention — verify against a neighbouring French string that contains an apostrophe and match its escaping exactly).

- [ ] **Step 4: Verify it compiles and generates the Res accessors**

Run: `./gradlew :composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL. The generated `Res.string.sync_settings_regenerate_key` (and the other new keys) now exist for later tasks.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/fr/dayview/app/DayViewTestTags.kt \
        composeApp/src/commonMain/composeResources/values/strings.xml \
        composeApp/src/commonMain/composeResources/values-fr/strings.xml
git commit -m "Add strings and test tags for sync key actions"
```

---

## Task 2: Reusable confirmation dialog

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/SyncSettingsScreen.kt` (add composables at end of file)
- Create: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncConfirmDialogContentTest.kt`

**Interfaces:**
- Consumes: `DayViewTestTags.SyncConfirmDialogConfirm`, `DayViewTestTags.SyncConfirmDialogCancel` (Task 1); `Res.string.dialog_cancel`; `FocusActionButton(label: String, color: Color, modifier: Modifier, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit)` from `DayViewTodayScreen.kt`; `LocalDayViewColors`.
- Produces: `SyncConfirmDialogContent(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit)` and `SyncConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit)`, both `internal @Composable`.

- [ ] **Step 1: Write the failing content test**

Create `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncConfirmDialogContentTest.kt`:

```kotlin
package fr.dayview.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class SyncConfirmDialogContentTest {
    @Test
    fun confirmButtonInvokesOnConfirm() = runComposeUiTest {
        var confirmed = false
        setContent {
            DayViewTheme {
                SyncConfirmDialogContent(
                    title = "Regenerate key?",
                    message = "The old key will be lost.",
                    confirmLabel = "REGENERATE",
                    onConfirm = { confirmed = true },
                    onDismiss = {},
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SyncConfirmDialogConfirm).performClick()
        assertTrue(confirmed)
    }

    @Test
    fun cancelButtonInvokesOnDismiss() = runComposeUiTest {
        var dismissed = false
        setContent {
            DayViewTheme {
                SyncConfirmDialogContent(
                    title = "Regenerate key?",
                    message = "The old key will be lost.",
                    confirmLabel = "REGENERATE",
                    onConfirm = {},
                    onDismiss = { dismissed = true },
                )
            }
        }
        onNodeWithTag(DayViewTestTags.SyncConfirmDialogCancel).performClick()
        assertTrue(dismissed)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncConfirmDialogContentTest"`
Expected: FAIL — `SyncConfirmDialogContent` is unresolved.

- [ ] **Step 3: Add the dialog composables**

Append to `SyncSettingsScreen.kt` (after the `syncStatusColor` function at the end of the file):

```kotlin
@Composable
internal fun SyncConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        SyncConfirmDialogContent(title, message, confirmLabel, onConfirm, onDismiss)
    }
}

@Composable
internal fun SyncConfirmDialogContent(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Column(
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Text(title, color = colors.cloud, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Text(message, color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FocusActionButton(
                stringResource(Res.string.dialog_cancel),
                colors.muted,
                modifier = Modifier.weight(1f).testTag(DayViewTestTags.SyncConfirmDialogCancel),
                onClick = onDismiss,
            )
            FocusActionButton(
                confirmLabel,
                colors.red,
                modifier = Modifier.weight(1f).testTag(DayViewTestTags.SyncConfirmDialogConfirm),
                filled = true,
                onClick = onConfirm,
            )
        }
    }
}
```

Add these imports to the top of `SyncSettingsScreen.kt` (merge alphabetically with existing imports):

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import fr.dayview.app.generated.resources.dialog_cancel
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncConfirmDialogContentTest"`
Expected: PASS (both tests).

- [ ] **Step 5: Lint and commit**

```bash
./gradlew ktlintCheck
git add composeApp/src/commonMain/kotlin/fr/dayview/app/SyncSettingsScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncConfirmDialogContentTest.kt
git commit -m "Add reusable sync confirmation dialog"
```

---

## Task 3: State-aware key card, reordered layout, gated actions

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/fr/dayview/app/SyncSettingsScreen.kt:60-216` (rewrite the `SyncSettingsScreen` body)
- Modify: `composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncSettingsScreenTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–2; existing callbacks `onGenerateKey`, `onPasteKey`, `onSyncNow`, `onClear`; existing helpers `SyncFieldLabel`, `syncStatusLabel`, `syncStatusColor`, `SettingsPanelCard`, `SettingsSectionHeader`, `SettingsAccentButton`, `SettingsDivider`, `GoalTextField`.
- Produces: no new public surface (the composable signature is unchanged).

- [ ] **Step 1: Update the existing clear test to expect gating (write the failing test)**

In `SyncSettingsScreenTest.kt`, replace the `clickingClearInvokesCallback` test with:

```kotlin
    @Test
    fun clickingEraseDoesNotImmediatelyClear() = runComposeUiTest {
        var cleared = false
        setContent { setSyncSettingsScreen(onClear = { cleared = true }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsClear).performClick()
        assertFalse(cleared)
    }
```

Add the import `import kotlin.test.assertFalse` to the test file.

- [ ] **Step 2: Add the new state/gating tests (also failing)**

Append these tests to `SyncSettingsScreenTest.kt`:

```kotlin
    @Test
    fun keyPresentShowsRegenerateAndReplaceNotGenerate() = runComposeUiTest {
        setContent { setSyncSettingsScreen(hasKey = true) }

        onNodeWithTag(DayViewTestTags.SyncSettingsRegenerateKey).assertExists()
        onNodeWithTag(DayViewTestTags.SyncSettingsReplaceKey).assertExists()
        onNodeWithTag(DayViewTestTags.SyncSettingsGenerateKey).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).assertDoesNotExist()
    }

    @Test
    fun noKeyShowsGenerateAndPhraseNotRegenerate() = runComposeUiTest {
        setContent { setSyncSettingsScreen(hasKey = false) }

        onNodeWithTag(DayViewTestTags.SyncSettingsGenerateKey).assertExists()
        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).assertExists()
        onNodeWithTag(DayViewTestTags.SyncSettingsRegenerateKey).assertDoesNotExist()
    }

    @Test
    fun clickingRegenerateDoesNotImmediatelyGenerate() = runComposeUiTest {
        var generated = false
        setContent { setSyncSettingsScreen(hasKey = true, onGenerateKey = { generated = true; "" }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsRegenerateKey).performClick()
        assertFalse(generated)
    }

    @Test
    fun clickingReplaceRevealsPhraseField() = runComposeUiTest {
        setContent { setSyncSettingsScreen(hasKey = true) }

        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).assertDoesNotExist()
        onNodeWithTag(DayViewTestTags.SyncSettingsReplaceKey).performClick()
        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).assertExists()
    }

    @Test
    fun clickingUsePhraseWhileReplacingDoesNotImmediatelyPaste() = runComposeUiTest {
        var pasted = false
        setContent { setSyncSettingsScreen(hasKey = true, onPasteKey = { pasted = true; true }) }

        onNodeWithTag(DayViewTestTags.SyncSettingsReplaceKey).performClick()
        onNodeWithTag(DayViewTestTags.SyncSettingsPhraseInput).performTextInput("some phrase")
        onNodeWithTag(DayViewTestTags.SyncSettingsUsePhrase).performClick()
        assertFalse(pasted)
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncSettingsScreenTest"`
Expected: FAIL — the new tags don't render yet (`assertExists` fails) and clicking still fires callbacks.

- [ ] **Step 4: Rewrite the `SyncSettingsScreen` body**

Replace the whole `SyncSettingsScreen` function body (lines 60–216) with the following. Keep the `@Composable internal fun SyncSettingsScreen(...)` signature and its parameters exactly as they are today.

```kotlin
@Composable
internal fun SyncSettingsScreen(
    config: SyncConfig?,
    status: SyncStatus,
    hasKey: Boolean,
    onConfigChange: (SyncConfig) -> Unit,
    onGenerateKey: () -> String,
    onPasteKey: (String) -> Boolean,
    onSyncNow: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val effectiveConfig = config ?: SyncConfig(baseUrl = "", userId = "", token = "")
    var generatedKey by remember { mutableStateOf<String?>(null) }
    var pasteKeyDraft by remember { mutableStateOf("") }
    var phraseError by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<SyncConfirmAction?>(null) }

    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SyncSettingsScreen)) {
        Text(
            stringResource(Res.string.sync_settings_description),
            color = colors.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))

        // Card 1 — status + sync now (top).
        SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
            Text(
                syncStatusLabel(status),
                color = syncStatusColor(status, colors),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsStatus),
            )
            Spacer(Modifier.height(12.dp))
            SettingsAccentButton(
                text = stringResource(Res.string.sync_settings_sync_now),
                onClick = onSyncNow,
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsSyncNow),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Card 2 — server endpoint/credentials.
        SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
            SyncFieldLabel(stringResource(Res.string.sync_settings_url_label))
            GoalTextField(
                value = effectiveConfig.baseUrl,
                semanticLabel = stringResource(Res.string.sync_settings_url_label),
                placeholder = stringResource(Res.string.sync_settings_url_placeholder),
                onValueChange = { onConfigChange(effectiveConfig.copy(baseUrl = it)) },
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsUrl),
            )
            Spacer(Modifier.height(12.dp))
            SyncFieldLabel(stringResource(Res.string.sync_settings_user_label))
            GoalTextField(
                value = effectiveConfig.userId,
                semanticLabel = stringResource(Res.string.sync_settings_user_label),
                placeholder = stringResource(Res.string.sync_settings_user_placeholder),
                onValueChange = { onConfigChange(effectiveConfig.copy(userId = it)) },
                imeAction = ImeAction.Next,
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsUser),
            )
            Spacer(Modifier.height(12.dp))
            SyncFieldLabel(stringResource(Res.string.sync_settings_token_label))
            GoalTextField(
                value = effectiveConfig.token,
                semanticLabel = stringResource(Res.string.sync_settings_token_label),
                placeholder = stringResource(Res.string.sync_settings_token_placeholder),
                onValueChange = { onConfigChange(effectiveConfig.copy(token = it)) },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsToken),
            )
        }

        Spacer(Modifier.height(14.dp))

        // Card 3 — encryption key (state-aware).
        SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
            SettingsSectionHeader(
                title = stringResource(Res.string.sync_settings_key_section),
                description = stringResource(Res.string.sync_settings_key_description),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(if (hasKey) Res.string.sync_settings_key_present else Res.string.sync_settings_key_missing),
                color = if (hasKey) colors.mint else colors.muted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))

            if (!hasKey) {
                SettingsAccentButton(
                    text = stringResource(Res.string.sync_settings_generate_key),
                    onClick = { generatedKey = onGenerateKey() },
                    modifier = Modifier.testTag(DayViewTestTags.SyncSettingsGenerateKey),
                )
                GeneratedPhraseBlock(generatedKey)
                Spacer(Modifier.height(14.dp))
                SettingsDivider()
                Spacer(Modifier.height(14.dp))
                PhraseEntry(
                    draft = pasteKeyDraft,
                    isError = phraseError,
                    onDraftChange = { pasteKeyDraft = it; phraseError = false },
                    onUse = { phraseError = !onPasteKey(pasteKeyDraft) },
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsAccentButton(
                        text = stringResource(Res.string.sync_settings_regenerate_key),
                        onClick = { pendingAction = SyncConfirmAction.Regenerate },
                        modifier = Modifier.testTag(DayViewTestTags.SyncSettingsRegenerateKey),
                    )
                    SettingsAccentButton(
                        text = stringResource(Res.string.sync_settings_replace_key),
                        onClick = { replacing = true },
                        modifier = Modifier.testTag(DayViewTestTags.SyncSettingsReplaceKey),
                    )
                }
                GeneratedPhraseBlock(generatedKey)
                if (replacing) {
                    Spacer(Modifier.height(14.dp))
                    SettingsDivider()
                    Spacer(Modifier.height(14.dp))
                    PhraseEntry(
                        draft = pasteKeyDraft,
                        isError = phraseError,
                        onDraftChange = { pasteKeyDraft = it; phraseError = false },
                        onUse = { pendingAction = SyncConfirmAction.Replace },
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Card 4 — erase (bottom danger zone).
        SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
            Text(
                stringResource(Res.string.sync_settings_erase_note),
                color = colors.muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(10.dp))
            SettingsAccentButton(
                text = stringResource(Res.string.sync_settings_clear),
                onClick = { pendingAction = SyncConfirmAction.Erase },
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsClear),
            )
        }
    }

    pendingAction?.let { action ->
        SyncConfirmDialog(
            title = stringResource(action.titleRes),
            message = stringResource(action.messageRes),
            confirmLabel = stringResource(action.confirmRes),
            onConfirm = {
                when (action) {
                    SyncConfirmAction.Regenerate -> generatedKey = onGenerateKey()
                    SyncConfirmAction.Replace -> {
                        phraseError = !onPasteKey(pasteKeyDraft)
                        if (!phraseError) {
                            pasteKeyDraft = ""
                            replacing = false
                        }
                    }
                    SyncConfirmAction.Erase -> onClear()
                }
                pendingAction = null
            },
            onDismiss = { pendingAction = null },
        )
    }
}

/** The three guarded destructive actions, each carrying its confirmation copy. */
private enum class SyncConfirmAction(
    val titleRes: StringResource,
    val messageRes: StringResource,
    val confirmRes: StringResource,
) {
    Regenerate(
        Res.string.sync_settings_regenerate_confirm_title,
        Res.string.sync_settings_regenerate_confirm_message,
        Res.string.sync_settings_regenerate_confirm_button,
    ),
    Replace(
        Res.string.sync_settings_replace_confirm_title,
        Res.string.sync_settings_replace_confirm_message,
        Res.string.sync_settings_replace_confirm_button,
    ),
    Erase(
        Res.string.sync_settings_erase_confirm_title,
        Res.string.sync_settings_erase_confirm_message,
        Res.string.sync_settings_erase_confirm_button,
    ),
}

/** Numbered recovery-phrase display shown after generating/regenerating a key. */
@Composable
private fun GeneratedPhraseBlock(phrase: String?) {
    val colors = LocalDayViewColors.current
    phrase ?: return
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(Res.string.sync_settings_generated_phrase_prompt),
        color = colors.muted,
        fontSize = 11.sp,
    )
    Spacer(Modifier.height(4.dp))
    SelectionContainer {
        Text(
            phrase.split(Regex("\\s+")).filter { it.isNotBlank() }
                .mapIndexed { i, word -> "${i + 1}. $word" }
                .joinToString("   "),
            color = colors.cloud,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(DayViewTestTags.SyncSettingsGeneratedPhrase),
        )
    }
}

/** Recovery-phrase text field + "use phrase" button + inline invalid-phrase error. */
@Composable
private fun PhraseEntry(
    draft: String,
    isError: Boolean,
    onDraftChange: (String) -> Unit,
    onUse: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    SyncFieldLabel(stringResource(Res.string.sync_settings_phrase_label))
    GoalTextField(
        value = draft,
        semanticLabel = stringResource(Res.string.sync_settings_phrase_label),
        placeholder = stringResource(Res.string.sync_settings_phrase_placeholder),
        isError = isError,
        onValueChange = onDraftChange,
        modifier = Modifier.testTag(DayViewTestTags.SyncSettingsPhraseInput),
    )
    Spacer(Modifier.height(10.dp))
    SettingsAccentButton(
        text = stringResource(Res.string.sync_settings_use_phrase),
        onClick = onUse,
        modifier = Modifier.testTag(DayViewTestTags.SyncSettingsUsePhrase),
    )
    if (isError) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.sync_settings_phrase_invalid),
            color = colors.red,
            fontSize = 11.sp,
            modifier = Modifier.testTag(DayViewTestTags.SyncSettingsPhraseError),
        )
    }
}
```

- [ ] **Step 5: Add the newly-referenced imports**

Add to the top of `SyncSettingsScreen.kt` (merge with existing imports; several may already be present from Task 2):

```kotlin
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_button
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_message
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_title
import fr.dayview.app.generated.resources.sync_settings_erase_note
import fr.dayview.app.generated.resources.sync_settings_regenerate_confirm_button
import fr.dayview.app.generated.resources.sync_settings_regenerate_confirm_message
import fr.dayview.app.generated.resources.sync_settings_regenerate_confirm_title
import fr.dayview.app.generated.resources.sync_settings_regenerate_key
import fr.dayview.app.generated.resources.sync_settings_replace_confirm_button
import fr.dayview.app.generated.resources.sync_settings_replace_confirm_message
import fr.dayview.app.generated.resources.sync_settings_replace_confirm_title
import fr.dayview.app.generated.resources.sync_settings_replace_key
import org.jetbrains.compose.resources.StringResource
```

- [ ] **Step 6: Run the full sync test suite to verify it passes**

Run: `./gradlew :composeApp:desktopTest --tests "fr.dayview.app.SyncSettingsScreenTest" --tests "fr.dayview.app.SyncConfirmDialogContentTest"`
Expected: PASS. All existing tests (`clickingSyncNowInvokesCallback`, `generatingAKeyDisplaysItForCopying`, `validPhraseInvokesPasteCallback`, `invalidPhraseShowsErrorTag`, config-field tests, `statusLineIsAlwaysPresent`) still pass because the no-key path and all tags are unchanged; the new state/gating tests pass.

- [ ] **Step 7: Lint and commit**

```bash
./gradlew ktlintCheck
git add composeApp/src/commonMain/kotlin/fr/dayview/app/SyncSettingsScreen.kt \
        composeApp/src/desktopTest/kotlin/fr/dayview/app/SyncSettingsScreenTest.kt
git commit -m "Make sync key actions state-aware and confirmed, reorder screen"
```

---

## Task 4: Full verification gate

**Files:** none (verification only).

- [ ] **Step 1: Run the full gate**

Run: `./gradlew ktlintCheck :composeApp:testDebugUnitTest :composeApp:desktopTest`
Expected: BUILD SUCCESSFUL, no ktlint violations, no test failures, no stderr.

- [ ] **Step 2: Manual smoke check on desktop (optional but recommended)**

Run: `./gradlew :composeApp:run`
Verify, in sync settings: status + Synchroniser is the top card; Effacer is the bottom card and opens a confirmation; with a key stored the key card shows Régénérer + Remplacer (Remplacer reveals the phrase field), and each destructive action prompts before acting; with no key stored the card still shows Générer + the phrase field directly.

---

## Self-Review

- **Spec coverage (Part A):** Layout reorder → Task 3 (cards 1 & 4 repositioned). State-aware key card (generate/enter vs regenerate/replace) → Task 3. Confirmation dialogs → Tasks 2–3. Effacer stays local wipe (`onClear` unchanged) → Task 3 card 4. Strings + tags → Task 1. Tests for state switching, dialog gates, ordering → Tasks 2–3.
- **Placeholder scan:** none — all code and strings are concrete.
- **Type consistency:** `SyncConfirmAction` (Regenerate/Replace/Erase) with `titleRes`/`messageRes`/`confirmRes: StringResource` is defined in Task 3 and consumed only there; `SyncConfirmDialog`/`SyncConfirmDialogContent` signatures defined in Task 2 match their call site in Task 3; test tags defined in Task 1 match usages in Tasks 2–3.

**Part B (first-sync merge/replace choice) is intentionally out of this plan** — it touches `SyncEngine`, `SyncCoordinator`, `SyncStatus`, `SettingsUiModels`, and `App.kt`, and will get its own plan once Part A lands.
