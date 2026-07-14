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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.toast_detour_removed
import fr.dayview.app.generated.resources.toast_obligation_removed
import fr.dayview.app.generated.resources.toast_save_failed
import fr.dayview.app.generated.resources.toast_sound_failed
import fr.dayview.app.generated.resources.toast_synced
import fr.dayview.app.generated.resources.toast_undo
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.getString

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

/**
 * Maps a semantic [AppEvent.Toast] to a localized [ToastVisuals]. Suspend (not @Composable)
 * so it can run inside the event-collector coroutine; uses `getString`, not `stringResource`.
 * Undo callbacks are supplied by the caller (which holds the controller).
 */
suspend fun toastVisualsFor(
    event: AppEvent.Toast,
    onUndoDetour: () -> Unit,
    onUndoObligation: () -> Unit,
): ToastVisuals = when (event.kind) {
    ToastKind.DetourRemoved -> ToastVisuals(
        message = getString(Res.string.toast_detour_removed, event.arg ?: ""),
        severity = ToastSeverity.Success,
        actionLabelText = getString(Res.string.toast_undo),
        onAction = onUndoDetour,
    )
    ToastKind.ObligationRemoved -> ToastVisuals(
        message = getString(Res.string.toast_obligation_removed, event.arg ?: ""),
        severity = ToastSeverity.Success,
        actionLabelText = getString(Res.string.toast_undo),
        onAction = onUndoObligation,
    )
    ToastKind.SyncSucceeded -> ToastVisuals(
        message = getString(Res.string.toast_synced),
        severity = ToastSeverity.Success,
    )
    ToastKind.SoundPreviewFailed -> ToastVisuals(
        message = getString(Res.string.toast_sound_failed),
        severity = ToastSeverity.Error,
    )
    ToastKind.SaveFailed -> ToastVisuals(
        message = getString(Res.string.toast_save_failed),
        severity = ToastSeverity.Error,
    )
}

/** Overlay host: Material's queue/timing, our [DayViewToast] rendering. */
@Composable
fun DayViewToastHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState, modifier) { data ->
        (data.visuals as? ToastVisuals)?.let { visuals ->
            DayViewToast(visuals = visuals, onAction = { data.performAction() })
        }
    }
}

/**
 * Collects [AppEvent.Toast]s, maps each to a localized [ToastVisuals], shows it through
 * [hostState], and — when the user taps the action — invokes the visuals' undo callback.
 * Extracted from DayViewApp so the event→toast→undo seam is testable.
 */
@Composable
fun ToastEventHost(
    events: Flow<AppEvent>,
    hostState: SnackbarHostState,
    onUndoDetour: () -> Unit,
    onUndoObligation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(events, hostState) {
        events.collect { event ->
            if (event is AppEvent.Toast) {
                val visuals = toastVisualsFor(event, onUndoDetour, onUndoObligation)
                if (hostState.showSnackbar(visuals) == SnackbarResult.ActionPerformed) {
                    visuals.onAction?.invoke()
                }
            }
        }
    }
    DayViewToastHost(hostState, modifier)
}
