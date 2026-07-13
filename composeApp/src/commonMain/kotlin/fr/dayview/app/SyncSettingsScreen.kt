package fr.dayview.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.sync_settings_clear
import fr.dayview.app.generated.resources.sync_settings_description
import fr.dayview.app.generated.resources.sync_settings_generate_key
import fr.dayview.app.generated.resources.sync_settings_generated_phrase_prompt
import fr.dayview.app.generated.resources.sync_settings_key_description
import fr.dayview.app.generated.resources.sync_settings_key_missing
import fr.dayview.app.generated.resources.sync_settings_key_present
import fr.dayview.app.generated.resources.sync_settings_key_section
import fr.dayview.app.generated.resources.sync_settings_phrase_invalid
import fr.dayview.app.generated.resources.sync_settings_phrase_label
import fr.dayview.app.generated.resources.sync_settings_phrase_placeholder
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

    Column(modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SyncSettingsScreen)) {
        Text(
            stringResource(Res.string.sync_settings_description),
            color = colors.muted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(12.dp))

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
            SettingsAccentButton(
                text = stringResource(Res.string.sync_settings_generate_key),
                onClick = { generatedKey = onGenerateKey() },
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsGenerateKey),
            )
            generatedKey?.let { phrase ->
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
            Spacer(Modifier.height(14.dp))
            SettingsDivider()
            Spacer(Modifier.height(14.dp))
            SyncFieldLabel(stringResource(Res.string.sync_settings_phrase_label))
            GoalTextField(
                value = pasteKeyDraft,
                semanticLabel = stringResource(Res.string.sync_settings_phrase_label),
                placeholder = stringResource(Res.string.sync_settings_phrase_placeholder),
                isError = phraseError,
                onValueChange = { draft ->
                    pasteKeyDraft = draft
                    phraseError = false
                },
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsPhraseInput),
            )
            Spacer(Modifier.height(10.dp))
            SettingsAccentButton(
                text = stringResource(Res.string.sync_settings_use_phrase),
                onClick = { phraseError = !onPasteKey(pasteKeyDraft) },
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsUsePhrase),
            )
            if (phraseError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.sync_settings_phrase_invalid),
                    color = colors.red,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag(DayViewTestTags.SyncSettingsPhraseError),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        SettingsPanelCard(contentPadding = PaddingValues(16.dp)) {
            Text(
                syncStatusLabel(status),
                color = syncStatusColor(status, colors),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag(DayViewTestTags.SyncSettingsStatus),
            )
            Spacer(Modifier.height(12.dp))
            Row {
                SettingsAccentButton(
                    text = stringResource(Res.string.sync_settings_sync_now),
                    onClick = onSyncNow,
                    modifier = Modifier.testTag(DayViewTestTags.SyncSettingsSyncNow),
                )
                Spacer(Modifier.width(10.dp))
                SettingsAccentButton(
                    text = stringResource(Res.string.sync_settings_clear),
                    onClick = onClear,
                    modifier = Modifier.testTag(DayViewTestTags.SyncSettingsClear),
                )
            }
        }
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
