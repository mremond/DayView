package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.detour_close_button
import fr.dayview.app.generated.resources.planned_obligation_add_button
import fr.dayview.app.generated.resources.planned_obligation_done_button
import fr.dayview.app.generated.resources.planned_obligation_motif_label
import fr.dayview.app.generated.resources.planned_obligation_motif_placeholder
import fr.dayview.app.generated.resources.planned_obligation_remove_label
import fr.dayview.app.generated.resources.planned_obligations_chip
import fr.dayview.app.generated.resources.planned_obligations_open_label
import fr.dayview.app.generated.resources.planned_obligations_title
import org.jetbrains.compose.resources.stringResource

/** Compact main-screen entry point that opens the obligations modal; always visible. */
@Composable
internal fun PlannedObligationsChip(
    count: Int,
    cap: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Text(
        stringResource(Res.string.planned_obligations_chip, count, cap),
        color = colors.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.planned_obligations_open_label), onClick = onOpen)
            .testTag(DayViewTestTags.PlannedObligationsChip)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Modal wrapper around [PlannedObligationsContent] (untestable Dialog window; test the content). */
@Composable
internal fun PlannedObligationsDialog(
    obligations: List<String>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PlannedObligationsContent(obligations, onAdd, onComplete, onRemove, onDismiss)
    }
}

/**
 * The day's must-do obligations: at most [MAX_PLANNED_OBLIGATIONS], each completable via
 * FAIT or deletable via the ✕. Split out of the Dialog so Compose UI tests can drive it.
 */
@Composable
internal fun PlannedObligationsContent(
    obligations: List<String>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    var draft by remember { mutableStateOf("") }
    val atCap = obligations.size >= MAX_PLANNED_OBLIGATIONS
    val removeLabel = stringResource(Res.string.planned_obligation_remove_label)

    Column(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(Res.string.planned_obligations_title),
            color = colors.amber,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp,
        )

        obligations.forEach { motif ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    motif,
                    color = colors.cloud,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "✕",
                    color = colors.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = removeLabel) { onRemove(motif) }
                        .testTag(DayViewTestTags.PlannedObligationRemove)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(6.dp))
                FocusActionButton(
                    label = stringResource(Res.string.planned_obligation_done_button),
                    color = colors.mint,
                    modifier = Modifier.testTag(DayViewTestTags.PlannedObligationDone),
                    onClick = { onComplete(motif) },
                )
            }
        }

        if (!atCap) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalTextField(
                    value = draft,
                    semanticLabel = stringResource(Res.string.planned_obligation_motif_label),
                    placeholder = stringResource(Res.string.planned_obligation_motif_placeholder),
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).testTag(DayViewTestTags.PlannedObligationInput),
                )
                Spacer(Modifier.width(6.dp))
                FocusActionButton(
                    label = stringResource(Res.string.planned_obligation_add_button),
                    color = colors.muted,
                    modifier = Modifier.testTag(DayViewTestTags.PlannedObligationAdd),
                    enabled = draft.isNotBlank(),
                    onClick = {
                        onAdd(draft)
                        draft = ""
                    },
                )
            }
        }

        FocusActionButton(
            stringResource(Res.string.detour_close_button),
            colors.muted,
            modifier = Modifier.fillMaxWidth(),
            onClick = onDismiss,
        )
    }
}

/** The day's must-do obligations: at most [MAX_PLANNED_OBLIGATIONS], each completable. */
@Composable
internal fun PlannedObligationsSection(
    obligations: List<String>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    var draft by remember { mutableStateOf("") }
    val atCap = obligations.size >= MAX_PLANNED_OBLIGATIONS

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.planned_obligations_title), color = colors.muted)

        obligations.forEach { motif ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(motif, color = colors.ink, modifier = Modifier.weight(1f))
                FocusActionButton(
                    label = stringResource(Res.string.planned_obligation_done_button),
                    color = colors.mint,
                    modifier = Modifier.testTag(DayViewTestTags.PlannedObligationDone),
                    onClick = { onComplete(motif) },
                )
            }
        }

        if (!atCap) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GoalTextField(
                    value = draft,
                    semanticLabel = stringResource(Res.string.planned_obligation_motif_label),
                    placeholder = stringResource(Res.string.planned_obligation_motif_placeholder),
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).testTag(DayViewTestTags.PlannedObligationInput),
                )
                FocusActionButton(
                    label = stringResource(Res.string.planned_obligation_add_button),
                    color = colors.muted,
                    modifier = Modifier.testTag(DayViewTestTags.PlannedObligationAdd),
                    enabled = draft.isNotBlank(),
                    onClick = {
                        onAdd(draft)
                        draft = ""
                    },
                )
            }
        }
    }
}
