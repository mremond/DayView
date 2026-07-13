package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.focus_close_section
import fr.dayview.app.generated.resources.focus_outcome_completed
import fr.dayview.app.generated.resources.focus_outcome_progressed
import fr.dayview.app.generated.resources.focus_outcome_to_resume
import fr.dayview.app.generated.resources.focus_relaunch_label
import fr.dayview.app.generated.resources.focus_stop_label
import org.jetbrains.compose.resources.stringResource

/**
 * Round focus controls shared by the main Focus panel and the mini window:
 * "»" relaunches the next session of the sequence, the red square stops the
 * sequence without going through the closure ritual.
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

/**
 * The closure ritual shared by both windows: name how the sequence ends
 * (done / progressed / to resume) so the clean-session ledger stays honest.
 */
@Composable
internal fun FocusClosureSection(onClose: (FocusClosureOutcome) -> Unit) {
    val colors = LocalDayViewColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.focus_close_section),
            color = colors.muted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FocusActionButton(
                stringResource(Res.string.focus_outcome_completed),
                colors.mint,
                modifier = Modifier.weight(1f).testTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.COMPLETED)),
                onClick = { onClose(FocusClosureOutcome.COMPLETED) },
            )
            FocusActionButton(
                stringResource(Res.string.focus_outcome_progressed),
                colors.amber,
                modifier = Modifier.weight(1f).testTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.PROGRESSED)),
                onClick = { onClose(FocusClosureOutcome.PROGRESSED) },
            )
            FocusActionButton(
                stringResource(Res.string.focus_outcome_to_resume),
                colors.muted,
                modifier = Modifier.weight(1f).testTag(DayViewTestTags.focusOutcome(FocusClosureOutcome.TO_RESUME)),
                onClick = { onClose(FocusClosureOutcome.TO_RESUME) },
            )
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
