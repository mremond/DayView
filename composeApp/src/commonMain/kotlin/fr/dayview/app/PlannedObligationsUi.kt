package fr.dayview.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.planned_obligation_add_button
import fr.dayview.app.generated.resources.planned_obligation_done_button
import fr.dayview.app.generated.resources.planned_obligation_motif_label
import fr.dayview.app.generated.resources.planned_obligation_motif_placeholder
import fr.dayview.app.generated.resources.planned_obligations_title
import org.jetbrains.compose.resources.stringResource

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
