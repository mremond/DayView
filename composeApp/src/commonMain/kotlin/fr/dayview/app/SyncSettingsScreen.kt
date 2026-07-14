package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.dialog_cancel
import fr.dayview.app.generated.resources.sync_settings_clear
import fr.dayview.app.generated.resources.sync_settings_description
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_button
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_message
import fr.dayview.app.generated.resources.sync_settings_erase_confirm_title
import fr.dayview.app.generated.resources.sync_settings_erase_note
import fr.dayview.app.generated.resources.sync_settings_generate_key
import fr.dayview.app.generated.resources.sync_settings_generated_phrase_prompt
import fr.dayview.app.generated.resources.sync_settings_key_description
import fr.dayview.app.generated.resources.sync_settings_key_missing
import fr.dayview.app.generated.resources.sync_settings_key_present
import fr.dayview.app.generated.resources.sync_settings_key_section
import fr.dayview.app.generated.resources.sync_settings_phrase_invalid
import fr.dayview.app.generated.resources.sync_settings_phrase_label
import fr.dayview.app.generated.resources.sync_settings_phrase_placeholder
import fr.dayview.app.generated.resources.sync_settings_regenerate_confirm_button
import fr.dayview.app.generated.resources.sync_settings_regenerate_confirm_message
import fr.dayview.app.generated.resources.sync_settings_regenerate_confirm_title
import fr.dayview.app.generated.resources.sync_settings_regenerate_key
import fr.dayview.app.generated.resources.sync_settings_replace_confirm_button
import fr.dayview.app.generated.resources.sync_settings_replace_confirm_message
import fr.dayview.app.generated.resources.sync_settings_replace_confirm_title
import fr.dayview.app.generated.resources.sync_settings_replace_key
import fr.dayview.app.generated.resources.sync_settings_sync_now
import fr.dayview.app.generated.resources.sync_settings_token_label
import fr.dayview.app.generated.resources.sync_settings_token_placeholder
import fr.dayview.app.generated.resources.sync_settings_url_label
import fr.dayview.app.generated.resources.sync_settings_url_placeholder
import fr.dayview.app.generated.resources.sync_settings_use_phrase
import fr.dayview.app.generated.resources.sync_settings_user_label
import fr.dayview.app.generated.resources.sync_settings_user_placeholder
import fr.dayview.app.generated.resources.sync_status_failed
import fr.dayview.app.generated.resources.sync_status_idle
import fr.dayview.app.generated.resources.sync_status_key_error
import fr.dayview.app.generated.resources.sync_status_not_configured
import fr.dayview.app.generated.resources.sync_status_ok
import fr.dayview.app.generated.resources.sync_status_syncing
import fr.dayview.app.sync.SyncConfig
import fr.dayview.app.sync.SyncStatus
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Stateless sync settings screen: endpoint/credentials fields, encryption-key generation and
 * pasting, a manual "sync now" trigger, and a status line. All state is hoisted through
 * [config]/[status]/[hasKey] and the callbacks — mirrors [NetTimeSettingsScreen].
 */
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
                    onDraftChange = {
                        pasteKeyDraft = it
                        phraseError = false
                    },
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
                        onDraftChange = {
                            pasteKeyDraft = it
                            phraseError = false
                        },
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
                            generatedKey = null
                        }
                    }
                    SyncConfirmAction.Erase -> {
                        onClear()
                        generatedKey = null
                        pasteKeyDraft = ""
                        replacing = false
                    }
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

@Composable
private fun SyncFieldLabel(text: String) {
    val colors = LocalDayViewColors.current
    Text(text, color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun syncStatusLabel(status: SyncStatus): String = when (status) {
    SyncStatus.Idle -> stringResource(Res.string.sync_status_idle)
    SyncStatus.Syncing -> stringResource(Res.string.sync_status_syncing)
    SyncStatus.Ok -> stringResource(Res.string.sync_status_ok)
    SyncStatus.KeyError -> stringResource(Res.string.sync_status_key_error)
    SyncStatus.Failed -> stringResource(Res.string.sync_status_failed)
    SyncStatus.NotConfigured -> stringResource(Res.string.sync_status_not_configured)
}

private fun syncStatusColor(status: SyncStatus, colors: DayViewColors) = when (status) {
    SyncStatus.Ok -> colors.mint
    SyncStatus.Syncing -> colors.amber
    SyncStatus.KeyError, SyncStatus.Failed -> colors.red
    SyncStatus.Idle, SyncStatus.NotConfigured -> colors.muted
}

@Composable
private fun SyncConfirmDialog(
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
