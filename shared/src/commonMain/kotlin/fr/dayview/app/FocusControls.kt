package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.detour_category_label
import fr.dayview.app.generated.resources.detour_category_placeholder
import fr.dayview.app.generated.resources.detour_description_label
import fr.dayview.app.generated.resources.detour_description_placeholder
import fr.dayview.app.generated.resources.focus_close_section
import fr.dayview.app.generated.resources.focus_closure_intention_label
import fr.dayview.app.generated.resources.focus_exit_cancel
import fr.dayview.app.generated.resources.focus_exit_detour_confirm
import fr.dayview.app.generated.resources.focus_exit_detour_label
import fr.dayview.app.generated.resources.focus_intention_label
import fr.dayview.app.generated.resources.focus_intention_placeholder
import fr.dayview.app.generated.resources.focus_outcome_completed
import fr.dayview.app.generated.resources.focus_outcome_progressed
import fr.dayview.app.generated.resources.focus_outcome_to_resume
import fr.dayview.app.generated.resources.focus_relaunch_label
import fr.dayview.app.generated.resources.focus_stop_label
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Round focus controls shared by the main Focus panel and the mini window:
 * "»" relaunches the next session of the sequence, the red square asks to close
 * the running one through the closure ritual.
 */
@Composable
internal fun FocusRelaunchRoundButton(
    onRelaunch: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier.size(size)
            .background(colors.overlay.copy(alpha = .08f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.focus_relaunch_label), onClick = onRelaunch),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "»",
            color = colors.mint,
            fontSize = (size.value / 2).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** The `focus_outcome_*` label of a closure outcome. */
private fun FocusClosureOutcome.labelRes(): StringResource = when (this) {
    FocusClosureOutcome.COMPLETED -> Res.string.focus_outcome_completed
    FocusClosureOutcome.PROGRESSED -> Res.string.focus_outcome_progressed
    FocusClosureOutcome.TO_RESUME -> Res.string.focus_outcome_to_resume
}

/** Reaching the term reads mint, progress amber, an interruption stays neutral. */
private fun FocusClosureOutcome.chipColor(colors: DayViewColors): Color = when (this) {
    FocusClosureOutcome.COMPLETED -> colors.mint
    FocusClosureOutcome.PROGRESSED -> colors.amber
    FocusClosureOutcome.TO_RESUME -> colors.muted
}

/**
 * The closure ritual shared by both windows. Naming the work happens here, at the close,
 * rather than as a toll on starting. [requiresDetourFor] decides which outcomes cost a
 * name: when one does, the panel unfolds the detour capture instead of closing, and only
 * a named motif confirms the exit. A refused closure is silent — nothing blames the user.
 * [onCancel], when given, offers staying rather than leaving.
 */
@Composable
internal fun FocusClosureContent(
    intention: String,
    requiresDetourFor: (FocusClosureOutcome) -> Boolean,
    recentDetourCategories: List<String>,
    onClose: (FocusClosureOutcome, String, String, String) -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val colors = LocalDayViewColors.current
    var intentionText by remember(intention) { mutableStateOf(intention) }
    var pendingOutcome by remember { mutableStateOf<FocusClosureOutcome?>(null) }
    var category by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // The toll can lift under the user's feet: reaching the term while the detour capture is
    // open would leave them naming a pull they no longer owe, in front of a Confirm the
    // controller would silently refuse. The capture follows the toll, not the tap that opened it.
    val detourOutcome = pendingOutcome?.takeIf(requiresDetourFor)
    LaunchedEffect(detourOutcome) {
        if (detourOutcome == null) pendingOutcome = null
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.focus_close_section),
            color = colors.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(Res.string.focus_closure_intention_label),
            color = colors.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(4.dp))
        GoalTextField(
            value = intentionText,
            semanticLabel = stringResource(Res.string.focus_intention_label),
            placeholder = stringResource(Res.string.focus_intention_placeholder),
            onValueChange = { intentionText = it },
            modifier = Modifier.testTag(DayViewTestTags.FocusClosureIntention),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (outcome in FocusClosureOutcome.entries) {
                FocusActionButton(
                    stringResource(outcome.labelRes()),
                    outcome.chipColor(colors),
                    modifier = Modifier.weight(1f).testTag(DayViewTestTags.focusOutcome(outcome)),
                    filled = detourOutcome == outcome,
                    onClick = {
                        if (requiresDetourFor(outcome)) {
                            pendingOutcome = outcome
                        } else {
                            onClose(outcome, intentionText, "", "")
                        }
                    },
                )
            }
        }
        detourOutcome?.let { outcome ->
            Spacer(Modifier.height(10.dp))
            FocusExitDetourCapture(
                category = category,
                description = description,
                recentDetourCategories = recentDetourCategories,
                onCategoryChange = { category = it },
                onDescriptionChange = { description = it },
                onConfirm = { onClose(outcome, intentionText, category, description) },
                onCancel = onCancel?.let {
                    {
                        pendingOutcome = null
                        it()
                    }
                },
            )
        }
    }
}

/** The exit toll: name the pull that takes you out, optionally describe it, then leave. */
@Composable
private fun FocusExitDetourCapture(
    category: String,
    description: String,
    recentDetourCategories: List<String>,
    onCategoryChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val colors = LocalDayViewColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.focus_exit_detour_label),
            color = colors.amber,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(6.dp))
        GoalTextField(
            value = category,
            semanticLabel = stringResource(Res.string.detour_category_label),
            placeholder = stringResource(Res.string.detour_category_placeholder),
            onValueChange = onCategoryChange,
            modifier = Modifier.testTag(DayViewTestTags.FocusExitDetourCategory),
        )
        if (recentDetourCategories.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            RecentDetourCategoryRow(
                recentDetourCategories = recentDetourCategories,
                selected = category,
                onSelect = onCategoryChange,
            )
        }
        Spacer(Modifier.height(8.dp))
        GoalTextField(
            value = description,
            semanticLabel = stringResource(Res.string.detour_description_label),
            placeholder = stringResource(Res.string.detour_description_placeholder),
            onValueChange = onDescriptionChange,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FocusActionButton(
                stringResource(Res.string.focus_exit_detour_confirm),
                colors.amber,
                modifier = Modifier.weight(1f).testTag(DayViewTestTags.FocusExitDetourConfirm),
                // The controller's own gate, not a laxer restatement of it: it sanitizes the
                // category first, and that strips commas. A comma-only name would otherwise
                // enable a Confirm whose click silently does nothing — a refusal landing
                // exactly where the user has just named the pull and pressed the button.
                enabled = sanitizeDetourCategory(category).isNotEmpty(),
                filled = true,
                onClick = onConfirm,
            )
            onCancel?.let {
                FocusActionButton(
                    stringResource(Res.string.focus_exit_cancel),
                    colors.muted,
                    modifier = Modifier.testTag(DayViewTestTags.FocusExitCancel),
                    onClick = it,
                )
            }
        }
    }
}

/** One-tap motifs already used today, so naming the pull costs a tap rather than a sentence. */
@Composable
private fun RecentDetourCategoryRow(
    recentDetourCategories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        recentDetourCategories.take(6).forEachIndexed { index, recent ->
            if (index > 0) Spacer(Modifier.width(7.dp))
            DetourChip(recent, selected = recent == selected) { onSelect(recent) }
        }
    }
}

@Composable
internal fun FocusStopRoundButton(
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier.size(size)
            .background(colors.overlay.copy(alpha = .08f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.focus_stop_label), onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(size * 0.3f)
                .background(colors.red.copy(alpha = .85f), RoundedCornerShape(2.dp)),
        )
    }
}
