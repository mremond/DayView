package fr.dayview.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.detour_add_button
import fr.dayview.app.generated.resources.detour_cancel_button
import fr.dayview.app.generated.resources.detour_capture_open_label
import fr.dayview.app.generated.resources.detour_capture_prompt
import fr.dayview.app.generated.resources.detour_category_label
import fr.dayview.app.generated.resources.detour_category_placeholder
import fr.dayview.app.generated.resources.detour_close_button
import fr.dayview.app.generated.resources.detour_confirm_button
import fr.dayview.app.generated.resources.detour_delete_button
import fr.dayview.app.generated.resources.detour_description_label
import fr.dayview.app.generated.resources.detour_description_placeholder
import fr.dayview.app.generated.resources.detour_duration_decrease
import fr.dayview.app.generated.resources.detour_duration_increase
import fr.dayview.app.generated.resources.detour_duration_label
import fr.dayview.app.generated.resources.detour_duration_more
import fr.dayview.app.generated.resources.detour_duration_section
import fr.dayview.app.generated.resources.detour_duration_value
import fr.dayview.app.generated.resources.detour_edit_row_label
import fr.dayview.app.generated.resources.detour_forget_confirm
import fr.dayview.app.generated.resources.detour_forget_prompt
import fr.dayview.app.generated.resources.detour_forget_row_label
import fr.dayview.app.generated.resources.detour_list_add_button
import fr.dayview.app.generated.resources.detour_list_empty
import fr.dayview.app.generated.resources.detour_list_open_label
import fr.dayview.app.generated.resources.detour_list_title
import fr.dayview.app.generated.resources.detour_minutes_chip
import fr.dayview.app.generated.resources.detour_off_window_tag
import fr.dayview.app.generated.resources.detour_overflow
import fr.dayview.app.generated.resources.detour_save_button
import fr.dayview.app.generated.resources.detour_section
import fr.dayview.app.generated.resources.detour_source_total
import fr.dayview.app.generated.resources.detour_start_adjust
import fr.dayview.app.generated.resources.detour_start_decrease
import fr.dayview.app.generated.resources.detour_start_increase
import fr.dayview.app.generated.resources.detour_start_section
import fr.dayview.app.generated.resources.detour_start_value
import fr.dayview.app.generated.resources.detour_time_range
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Per-source tally under the dial plus the capture affordance. */
@Composable
internal fun DetourRow(
    sources: List<DetourSource>,
    onOpenList: () -> Unit,
    onCapture: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (sources.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.detour_list_open_label), onClick = onOpenList)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                sources.take(3).forEachIndexed { index, source ->
                    if (index > 0) Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(7.dp)
                            .background(colors.detours[source.colorIndex % colors.detours.size], CircleShape),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        stringResource(Res.string.detour_source_total, source.label, formatDurationHm(source.total)),
                        color = colors.muted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
                if (sources.size > 3) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.detour_overflow, (sources.size - 3).toString()),
                        color = colors.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Text(
            stringResource(Res.string.detour_add_button),
            color = colors.muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.detour_capture_open_label), onClick = onCapture)
                .padding(vertical = 8.dp, horizontal = 6.dp),
        )
    }
}

/**
 * Small selectable pill used for suggestions and duration picks. A non-null [onLongClick]
 * makes the pill long-pressable (the recent-category suggestions use it to offer removal).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DetourChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            role = Role.Button,
            onLongClickLabel = onLongClickLabel,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Text(
        label,
        color = if (selected) colors.ink else colors.cloud,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        textAlign = textAlign,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) colors.amber else colors.overlay.copy(alpha = .07f),
                RoundedCornerShape(9.dp),
            )
            .then(clickModifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private val DETOUR_DURATION_CHOICES = listOf(5, 15, 30, 45, 60)
private val DETOUR_LONG_DURATION_CHOICES = listOf(90, 120, 180)

/** Quick capture: required category, recent-category suggestions, quick duration picks. */
@Composable
internal fun DetourCaptureDialog(
    recentCategories: List<String>,
    now: Instant,
    onConfirm: (category: String, description: String, durationMinutes: Int, startMinutesOfDay: Int?) -> Unit,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
    initialCategory: String = "",
    initialDescription: String = "",
) {
    Dialog(onDismissRequest = onDismiss) {
        DetourCaptureContent(recentCategories, now, onConfirm, onForget, onDismiss, initialCategory, initialDescription)
    }
}

/**
 * The quick-capture form, split out of [DetourCaptureDialog] so Compose UI tests can
 * drive it without a desktop Dialog window (which `runComposeUiTest` cannot reach).
 */
@Composable
internal fun DetourCaptureContent(
    recentCategories: List<String>,
    now: Instant,
    onConfirm: (category: String, description: String, durationMinutes: Int, startMinutesOfDay: Int?) -> Unit,
    onForget: (String) -> Unit,
    onDismiss: () -> Unit,
    initialCategory: String = "",
    initialDescription: String = "",
) {
    val colors = LocalDayViewColors.current
    val uses24Hour = LocalUses24HourClock.current
    val timeZone = TimeZone.currentSystemDefault()
    val forgetRowLabel = stringResource(Res.string.detour_forget_row_label)
    var category by remember { mutableStateOf(initialCategory) }
    var description by remember { mutableStateOf(initialDescription) }
    var durationMinutes by remember { mutableIntStateOf(15) }
    var showStart by remember { mutableStateOf(false) }
    var startPinned by remember { mutableStateOf(false) }
    var pinnedStartMinutes by remember { mutableIntStateOf(0) }
    var categoryPendingForget by remember { mutableStateOf<String?>(null) }
    var showLongDurations by remember { mutableStateOf(false) }
    // "Ends now" default: the start tracks the duration until the user pins it by nudging.
    val startMinutes = if (startPinned) pinnedStartMinutes else detourDefaultStartMinutes(now, durationMinutes, timeZone)
    Column(
        modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Text(stringResource(Res.string.detour_section), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(Res.string.detour_capture_prompt),
            color = colors.cloud,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(10.dp))
        GoalTextField(
            value = category,
            semanticLabel = stringResource(Res.string.detour_category_label),
            placeholder = stringResource(Res.string.detour_category_placeholder),
            onValueChange = { category = it },
            modifier = Modifier.testTag(DayViewTestTags.DetourCategoryField),
        )
        if (recentCategories.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                recentCategories.take(6).forEachIndexed { index, recent ->
                    if (index > 0) Spacer(Modifier.width(7.dp))
                    DetourChip(
                        recent,
                        selected = recent == category,
                        onLongClick = { categoryPendingForget = recent },
                        onLongClickLabel = forgetRowLabel,
                    ) { category = recent }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        GoalTextField(
            value = description,
            semanticLabel = stringResource(Res.string.detour_description_label),
            placeholder = stringResource(Res.string.detour_description_placeholder),
            onValueChange = { description = it },
            modifier = Modifier.testTag(DayViewTestTags.DetourDescriptionField),
        )
        Spacer(Modifier.height(14.dp))
        Text(stringResource(Res.string.detour_duration_section), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            DETOUR_DURATION_CHOICES.forEach { minutes ->
                DetourChip(
                    stringResource(Res.string.detour_minutes_chip, minutes.toString()),
                    selected = minutes == durationMinutes,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                ) { durationMinutes = minutes }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!showLongDurations) {
            Text(
                stringResource(Res.string.detour_duration_more),
                color = colors.amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { showLongDurations = true }
                    .testTag(DayViewTestTags.DetourLongToggle)
                    .padding(vertical = 6.dp, horizontal = 6.dp),
            )
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                DETOUR_LONG_DURATION_CHOICES.forEach { minutes ->
                    DetourChip(
                        formatDurationHm(minutes.minutes),
                        selected = minutes == durationMinutes,
                        modifier = Modifier.weight(1f).testTag(DayViewTestTags.detourDurationChip(minutes)),
                        textAlign = TextAlign.Center,
                    ) { durationMinutes = minutes }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (!showStart) {
            Text(
                stringResource(Res.string.detour_start_adjust),
                color = colors.amber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.minimumInteractiveComponentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { showStart = true }
                    .testTag(DayViewTestTags.DetourStartAdjust)
                    .padding(vertical = 6.dp, horizontal = 6.dp),
            )
        } else {
            Text(stringResource(Res.string.detour_start_section), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = "−",
                    enabled = startMinutes >= 5,
                    onClickLabel = stringResource(Res.string.detour_start_decrease),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes, uses24Hour)),
                ) {
                    pinnedStartMinutes = (startMinutes - 5).coerceAtLeast(0)
                    startPinned = true
                }
                Spacer(Modifier.width(10.dp))
                Text(formatMinutesOfDay(startMinutes, uses24Hour), color = colors.cloud, fontSize = 17.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(10.dp))
                TimeButton(
                    label = "+",
                    enabled = startMinutes <= 23 * 60 + 54,
                    onClickLabel = stringResource(Res.string.detour_start_increase),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes, uses24Hour)),
                    modifier = Modifier.testTag(DayViewTestTags.DetourStartIncrease),
                ) {
                    pinnedStartMinutes = (startMinutes + 5).coerceAtMost(23 * 60 + 59)
                    startPinned = true
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            FocusActionButton(stringResource(Res.string.detour_cancel_button), colors.muted, modifier = Modifier.weight(1f), onClick = onDismiss)
            FocusActionButton(
                stringResource(Res.string.detour_confirm_button),
                colors.amber,
                modifier = Modifier.weight(1f).testTag(DayViewTestTags.DetourConfirm),
                enabled = category.isNotBlank(),
                filled = true,
                onClick = { onConfirm(category, description, durationMinutes, if (startPinned) startMinutes else null) },
            )
        }
    }
    val pending = categoryPendingForget
    if (pending != null) {
        DetourForgetConfirmDialog(
            category = pending,
            onConfirm = {
                onForget(pending)
                categoryPendingForget = null
            },
            onDismiss = { categoryPendingForget = null },
        )
    }
}

/** Confirmation for dropping a suggestion from the recent-category list. */
@Composable
private fun DetourForgetConfirmDialog(
    category: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth()
                .background(colors.panel, RoundedCornerShape(18.dp))
                .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
                .padding(20.dp),
        ) {
            Text(
                stringResource(Res.string.detour_forget_prompt),
                color = colors.cloud,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            Text(category, color = colors.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                FocusActionButton(stringResource(Res.string.detour_cancel_button), colors.muted, modifier = Modifier.weight(1f), onClick = onDismiss)
                FocusActionButton(
                    stringResource(Res.string.detour_forget_confirm),
                    colors.red,
                    modifier = Modifier.weight(1f),
                    filled = true,
                    onClick = onConfirm,
                )
            }
        }
    }
}

/** Editing target inside the list dialog: an existing row or a new episode. */
private sealed interface DetourEdit {
    data class Existing(val index: Int, val episode: DetourEpisode) : DetourEdit

    data object New : DetourEdit
}

/** The day's detours: chronological rows, tap to edit, retroactive add. */
@Composable
internal fun DetourListDialog(
    episodes: List<DetourEpisode>,
    now: Instant,
    windowStart: Instant,
    windowEnd: Instant,
    onUpdate: (Int, DetourEpisode) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (DetourEpisode) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        DetourListContent(episodes, now, windowStart, windowEnd, onUpdate, onRemove, onAdd, onDismiss)
    }
}

/**
 * The list dialog's body, split out of [DetourListDialog] so Compose UI tests can
 * drive it without a desktop Dialog window (which `runComposeUiTest` cannot reach).
 */
@Composable
internal fun DetourListContent(
    episodes: List<DetourEpisode>,
    now: Instant,
    windowStart: Instant,
    windowEnd: Instant,
    onUpdate: (Int, DetourEpisode) -> Unit,
    onRemove: (Int) -> Unit,
    onAdd: (DetourEpisode) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalDayViewColors.current
    val uses24Hour = LocalUses24HourClock.current
    var edit by remember { mutableStateOf<DetourEdit?>(null) }
    val sources = detourSources(episodes)
    val colorOf: (DetourEpisode) -> androidx.compose.ui.graphics.Color = { episode ->
        val label = sanitizeDetourCategory(episode.category).lowercase()
        val index = sources.firstOrNull { it.label.lowercase() == label }?.colorIndex ?: 0
        colors.detours[index % colors.detours.size]
    }
    Column(
        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth()
            .background(colors.panel, RoundedCornerShape(18.dp))
            .border(1.dp, colors.overlay.copy(alpha = .06f), RoundedCornerShape(18.dp))
            .padding(20.dp),
    ) {
        Text(stringResource(Res.string.detour_list_title), color = colors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(12.dp))
        when (val current = edit) {
            null -> {
                if (episodes.isEmpty()) {
                    Text(stringResource(Res.string.detour_list_empty), color = colors.muted, fontSize = 13.sp)
                } else {
                    Column(
                        Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    ) {
                        episodes.forEachIndexed { index, episode ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.detour_edit_row_label)) {
                                        edit = DetourEdit.Existing(index, episode)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 9.dp),
                            ) {
                                Box(Modifier.size(8.dp).background(colorOf(episode), CircleShape))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(episode.category, color = colors.cloud, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        stringResource(
                                            Res.string.detour_time_range,
                                            formatClockHm(episode.start, use24Hour = uses24Hour),
                                            formatClockHm(episode.end, use24Hour = uses24Hour),
                                            formatDurationHm(episode.duration),
                                        ),
                                        color = colors.muted,
                                        fontSize = 11.sp,
                                    )
                                    if (detourMidpointOutsideWindow(episode, windowStart, windowEnd)) {
                                        Text(
                                            stringResource(Res.string.detour_off_window_tag),
                                            color = colors.muted,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            letterSpacing = .5.sp,
                                        )
                                    }
                                    if (episode.description.isNotEmpty()) {
                                        Text(
                                            episode.description,
                                            color = colors.muted,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.testTag(DayViewTestTags.DetourDescriptionText),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    FocusActionButton(stringResource(Res.string.detour_close_button), colors.muted, modifier = Modifier.weight(1f), onClick = onDismiss)
                    FocusActionButton(
                        stringResource(Res.string.detour_list_add_button),
                        colors.amber,
                        modifier = Modifier.weight(1f),
                        filled = true,
                        onClick = { edit = DetourEdit.New },
                    )
                }
            }
            else -> DetourEditForm(
                initial = (current as? DetourEdit.Existing)?.episode,
                now = now,
                onDelete = (current as? DetourEdit.Existing)?.let { existing ->
                    {
                        onRemove(existing.index)
                        edit = null
                    }
                },
                onCancel = { edit = null },
                onSave = { episode ->
                    when (current) {
                        is DetourEdit.Existing -> onUpdate(current.index, episode)
                        DetourEdit.New -> onAdd(episode)
                    }
                    edit = null
                },
            )
        }
    }
}

/** Category + start time + duration form shared by edit and retroactive add. */
@Composable
private fun DetourEditForm(
    initial: DetourEpisode?,
    now: Instant,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
    onSave: (DetourEpisode) -> Unit,
) {
    val colors = LocalDayViewColors.current
    val uses24Hour = LocalUses24HourClock.current
    val timeZone = TimeZone.currentSystemDefault()
    val initialStart = (initial?.start ?: now).toLocalDateTime(timeZone)
    var category by remember { mutableStateOf(initial?.category.orEmpty()) }
    var description by remember { mutableStateOf(initial?.description.orEmpty()) }
    var startMinutes by remember {
        mutableIntStateOf(
            (initialStart.hour * 60 + initialStart.minute - if (initial == null) 15 else 0).coerceAtLeast(0),
        )
    }
    var durationMinutes by remember {
        mutableIntStateOf(initial?.duration?.inWholeMinutes?.toInt() ?: 15)
    }
    GoalTextField(
        value = category,
        semanticLabel = stringResource(Res.string.detour_category_label),
        placeholder = stringResource(Res.string.detour_category_placeholder),
        onValueChange = { category = it },
    )
    Spacer(Modifier.height(12.dp))
    GoalTextField(
        value = description,
        semanticLabel = stringResource(Res.string.detour_description_label),
        placeholder = stringResource(Res.string.detour_description_placeholder),
        onValueChange = { description = it },
        modifier = Modifier.testTag(DayViewTestTags.DetourDescriptionField),
    )
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.detour_start_section), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = "−",
                    enabled = startMinutes >= 5,
                    onClickLabel = stringResource(Res.string.detour_start_decrease),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes, uses24Hour)),
                ) { startMinutes = (startMinutes - 5).coerceAtLeast(0) }
                Spacer(Modifier.width(10.dp))
                Text(formatMinutesOfDay(startMinutes, uses24Hour), color = colors.cloud, fontSize = 17.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(10.dp))
                TimeButton(
                    label = "+",
                    enabled = startMinutes <= 23 * 60 + 54,
                    onClickLabel = stringResource(Res.string.detour_start_increase),
                    valueDescription = stringResource(Res.string.detour_start_value, formatMinutesOfDay(startMinutes, uses24Hour)),
                ) { startMinutes = (startMinutes + 5).coerceAtMost(23 * 60 + 59) }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.detour_duration_label), color = colors.muted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeButton(
                    label = "−",
                    enabled = durationMinutes > 5,
                    onClickLabel = stringResource(Res.string.detour_duration_decrease),
                    valueDescription = stringResource(Res.string.detour_duration_value, durationMinutes.toString()),
                ) { durationMinutes = (durationMinutes - 5).coerceAtLeast(5) }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(Res.string.detour_minutes_chip, durationMinutes.toString()),
                    color = colors.cloud,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Light,
                )
                Spacer(Modifier.width(10.dp))
                TimeButton(
                    label = "+",
                    enabled = durationMinutes < 12 * 60,
                    onClickLabel = stringResource(Res.string.detour_duration_increase),
                    valueDescription = stringResource(Res.string.detour_duration_value, durationMinutes.toString()),
                ) { durationMinutes = (durationMinutes + 5).coerceAtMost(12 * 60) }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (onDelete != null) {
            FocusActionButton(stringResource(Res.string.detour_delete_button), colors.red, modifier = Modifier.weight(1f), onClick = onDelete)
        }
        FocusActionButton(stringResource(Res.string.detour_cancel_button), colors.muted, modifier = Modifier.weight(1f), onClick = onCancel)
        FocusActionButton(
            stringResource(Res.string.detour_save_button),
            colors.amber,
            modifier = Modifier.weight(1f),
            enabled = category.isNotBlank(),
            filled = true,
            onClick = { onSave(detourEpisodeAt(now, startMinutes, durationMinutes, category, description)) },
        )
    }
}

private fun formatMinutesOfDay(minutes: Int, use24Hour: Boolean): String {
    val safe = minutes.coerceIn(0, 23 * 60 + 59)
    return formatWallClock(safe / 60, safe % 60, use24Hour)
}
