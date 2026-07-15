package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.dialog_cancel
import fr.dayview.app.generated.resources.sync_first_sync_adopt_server
import fr.dayview.app.generated.resources.sync_first_sync_merge
import fr.dayview.app.generated.resources.sync_first_sync_message
import fr.dayview.app.generated.resources.sync_first_sync_push_local
import fr.dayview.app.generated.resources.sync_first_sync_title
import fr.dayview.app.generated.resources.sync_settings_generated_phrase_prompt
import fr.dayview.app.generated.resources.sync_settings_phrase_accepted
import fr.dayview.app.generated.resources.sync_settings_phrase_invalid
import fr.dayview.app.generated.resources.sync_settings_phrase_label
import fr.dayview.app.generated.resources.sync_settings_phrase_placeholder
import fr.dayview.app.generated.resources.sync_settings_use_phrase
import org.jetbrains.compose.resources.stringResource

/** Numbered recovery-phrase display shown after generating a key. */
@Composable
internal fun GeneratedPhraseBlock(phrase: String?) {
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

/** Recovery-phrase text field, action, and inline validation state. */
@Composable
internal fun PhraseEntry(
    draft: String,
    isError: Boolean,
    isAccepted: Boolean,
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
    } else if (isAccepted) {
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.sync_settings_phrase_accepted),
            color = colors.mint,
            fontSize = 11.sp,
            modifier = Modifier.testTag(DayViewTestTags.SyncSettingsPhraseAccepted),
        )
    }
}

/**
 * First-sync reconciliation dialog: this device has never synced and the server already
 * holds a document. Offers the three [FirstSyncStrategy] choices plus cancel.
 */
@Composable
internal fun FirstSyncChoiceDialog(
    onMerge: () -> Unit,
    onAdoptServer: () -> Unit,
    onPushLocal: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(max = 340.dp).fillMaxWidth()
                .testTag(DayViewTestTags.SyncFirstChoiceDialog)
                .dismissOnEscape(onDismiss)
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                .padding(20.dp),
        ) {
            Text(
                stringResource(Res.string.sync_first_sync_title),
                color = colors.cloud,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(Res.string.sync_first_sync_message),
                color = colors.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
            SettingsAccentButton(
                text = stringResource(Res.string.sync_first_sync_merge),
                onClick = onMerge,
                modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SyncFirstChoiceMerge),
            )
            Spacer(Modifier.height(8.dp))
            SettingsAccentButton(
                text = stringResource(Res.string.sync_first_sync_adopt_server),
                onClick = onAdoptServer,
                modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SyncFirstChoiceAdoptServer),
            )
            Spacer(Modifier.height(8.dp))
            SettingsAccentButton(
                text = stringResource(Res.string.sync_first_sync_push_local),
                onClick = onPushLocal,
                modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SyncFirstChoicePushLocal),
            )
            Spacer(Modifier.height(12.dp))
            FocusActionButton(
                stringResource(Res.string.dialog_cancel),
                colors.muted,
                modifier = Modifier.fillMaxWidth().testTag(DayViewTestTags.SyncConfirmDialogCancel),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
internal fun SyncFieldLabel(text: String) {
    val colors = LocalDayViewColors.current
    Text(text, color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(4.dp))
}

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
            .dismissOnEscape(onDismiss)
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
