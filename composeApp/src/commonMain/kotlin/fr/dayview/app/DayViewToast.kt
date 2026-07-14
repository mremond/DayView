package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ToastSeverity { Success, Error, Info }

/**
 * Carries our severity + resolved text through Material's Snackbar queue. Built by
 * [toastVisualsFor] (Task 5) and handed to `SnackbarHostState.showSnackbar`.
 */
class ToastVisuals(
    override val message: String,
    val severity: ToastSeverity,
    val actionLabelText: String? = null,
    val onAction: (() -> Unit)? = null,
    override val duration: SnackbarDuration =
        if (actionLabelText != null) SnackbarDuration.Long else SnackbarDuration.Short,
) : SnackbarVisuals {
    override val actionLabel: String? get() = actionLabelText
    override val withDismissAction: Boolean get() = false
}

/** On-brand transient toast, matching the TodayNotices banner language. */
@Composable
fun DayViewToast(
    visuals: ToastVisuals,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalDayViewColors.current
    val accent: Color = when (visuals.severity) {
        ToastSeverity.Success -> colors.mint
        ToastSeverity.Error -> colors.red
        ToastSeverity.Info -> colors.cloud
    }
    Row(
        modifier = modifier
            .testTag(DayViewTestTags.Toast)
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.panel)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = visuals.message
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        BasicText(
            text = visuals.message,
            style = TextStyle(color = colors.cloud, fontSize = 13.sp),
            modifier = Modifier.weight(1f),
        )
        val label = visuals.actionLabelText
        if (label != null) {
            BasicText(
                text = label,
                style = TextStyle(color = colors.mint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
                modifier = Modifier
                    .testTag(DayViewTestTags.ToastAction)
                    .minimumInteractiveComponentSize()
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onAction)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}
