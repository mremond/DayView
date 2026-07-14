package fr.dayview.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.detour_capture_open_label
import fr.dayview.app.generated.resources.planned_obligations_count_action
import fr.dayview.app.generated.resources.planned_obligations_empty_action
import fr.dayview.app.generated.resources.planned_obligations_one_action
import fr.dayview.app.generated.resources.planned_obligations_open_label
import org.jetbrains.compose.resources.stringResource

/** The two frequent actions shown directly below today's dial. */
@Composable
internal fun TodayQuickActions(
    activeObligationCount: Int,
    onAddDetour: () -> Unit,
    onOpenObligations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    val detourLabel = stringResource(Res.string.detour_capture_open_label)
    val obligationsLabel = when (activeObligationCount) {
        0 -> stringResource(Res.string.planned_obligations_empty_action)
        1 -> stringResource(Res.string.planned_obligations_one_action)
        else -> stringResource(Res.string.planned_obligations_count_action, activeObligationCount)
    }

    Row(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(16.dp))
            .border(1.dp, colors.overlay.copy(alpha = .08f), RoundedCornerShape(16.dp))
            .padding(4.dp)
            .testTag(DayViewTestTags.TodayQuickActions),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TodayQuickAction(
            label = detourLabel,
            accent = colors.amber,
            icon = TodayQuickActionIcon.ADD,
            onClickLabel = detourLabel,
            onClick = onAddDetour,
            modifier = Modifier.weight(1f).testTag(DayViewTestTags.AddDetourQuickAction),
        )
        Spacer(
            Modifier
                .width(1.dp)
                .height(32.dp)
                .background(colors.overlay.copy(alpha = .10f)),
        )
        TodayQuickAction(
            label = obligationsLabel,
            accent = if (activeObligationCount > 0) colors.mint else colors.muted,
            icon = TodayQuickActionIcon.MUST_DO,
            onClickLabel = stringResource(Res.string.planned_obligations_open_label),
            onClick = onOpenObligations,
            modifier = Modifier.weight(1f).testTag(DayViewTestTags.OpenObligationsQuickAction),
        )
    }
}

@Composable
private fun TodayQuickAction(
    label: String,
    accent: Color,
    icon: TodayQuickActionIcon,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(accent.copy(alpha = .14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            TodayQuickActionGlyph(icon = icon, color = accent)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = colors.cloud,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TodayQuickActionGlyph(
    icon: TodayQuickActionIcon,
    color: Color,
) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.8.dp.toPx()
        when (icon) {
            TodayQuickActionIcon.ADD -> {
                drawLine(
                    color = color,
                    start = Offset(size.width * .22f, size.height * .5f),
                    end = Offset(size.width * .78f, size.height * .5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * .5f, size.height * .22f),
                    end = Offset(size.width * .5f, size.height * .78f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }

            TodayQuickActionIcon.MUST_DO -> {
                listOf(.32f, .68f).forEach { y ->
                    drawLine(
                        color = color,
                        start = Offset(size.width * .12f, size.height * (y - .02f)),
                        end = Offset(size.width * .22f, size.height * (y + .08f)),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width * .22f, size.height * (y + .08f)),
                        end = Offset(size.width * .36f, size.height * (y - .10f)),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width * .48f, size.height * y),
                        end = Offset(size.width * .88f, size.height * y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private enum class TodayQuickActionIcon {
    ADD,
    MUST_DO,
}
