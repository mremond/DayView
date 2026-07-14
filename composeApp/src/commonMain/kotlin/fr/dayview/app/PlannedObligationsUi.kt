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
import androidx.compose.ui.text.style.TextDecoration
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
import fr.dayview.app.generated.resources.planned_obligations_active_section
import fr.dayview.app.generated.resources.planned_obligations_cap_reached
import fr.dayview.app.generated.resources.planned_obligations_chip
import fr.dayview.app.generated.resources.planned_obligations_completed_section
import fr.dayview.app.generated.resources.planned_obligations_open_label
import fr.dayview.app.generated.resources.planned_obligations_title
import org.jetbrains.compose.resources.stringResource

/** Compact main-screen entry point that opens the obligations modal; always visible. */
@Composable
internal fun PlannedObligationsChip(
    activeCount: Int,
    completedCount: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Text(
        stringResource(Res.string.planned_obligations_chip, activeCount, completedCount),
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
    completedObligations: List<String>,
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        PlannedObligationsContent(obligations, completedObligations, onAdd, onComplete, onRemove, onDismiss)
    }
}

/**
 * The day's must-do obligations: at most [MAX_PLANNED_OBLIGATIONS], each completable via
 * FAIT or deletable via the ✕. Split out of the Dialog so Compose UI tests can drive it.
 */
@Composable
internal fun PlannedObligationsContent(
    obligations: List<String>,
    completedObligations: List<String> = emptyList(),
    onAdd: (String) -> Unit,
    onComplete: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    var draft by remember { mutableStateOf("") }
    val atCap = obligations.size + completedObligations.size >= MAX_PLANNED_OBLIGATIONS
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

        if (obligations.isNotEmpty()) {
            Text(
                stringResource(Res.string.planned_obligations_active_section),
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.testTag(DayViewTestTags.PlannedObligationsActiveSection),
            )
        }

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
        } else if (obligations.size < MAX_PLANNED_OBLIGATIONS) {
            Text(
                stringResource(Res.string.planned_obligations_cap_reached),
                color = colors.muted,
                fontSize = 11.sp,
                modifier = Modifier.testTag(DayViewTestTags.PlannedObligationsCapHint),
            )
        }

        if (completedObligations.isNotEmpty()) {
            Text(
                stringResource(Res.string.planned_obligations_completed_section),
                color = colors.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.testTag(DayViewTestTags.PlannedObligationsCompletedSection),
            )
            completedObligations.forEach { motif ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓", color = colors.mint, fontSize = 13.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        motif,
                        color = colors.muted,
                        fontSize = 13.sp,
                        textDecoration = TextDecoration.LineThrough,
                        modifier = Modifier.weight(1f),
                    )
                }
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
