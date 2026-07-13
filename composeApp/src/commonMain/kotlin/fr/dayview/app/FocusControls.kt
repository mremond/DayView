package fr.dayview.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dayview.app.generated.resources.Res
import fr.dayview.app.generated.resources.focus_relaunch_label
import fr.dayview.app.generated.resources.focus_stop_label
import org.jetbrains.compose.resources.stringResource

/**
 * Round focus controls shared by the main Focus panel and the mini window:
 * "»" relaunches the next session of the sequence, the red square stops the
 * sequence without going through the closure ritual.
 */
@Composable
internal fun FocusRelaunchRoundButton(onRelaunch: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier.size(40.dp)
            .background(colors.overlay.copy(alpha = .08f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.focus_relaunch_label), onClick = onRelaunch),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "»",
            color = colors.mint,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun FocusStopRoundButton(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalDayViewColors.current
    Box(
        modifier = modifier.size(40.dp)
            .background(colors.overlay.copy(alpha = .08f), CircleShape)
            .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.focus_stop_label), onClick = onStop),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(12.dp)
                .background(colors.red.copy(alpha = .85f), RoundedCornerShape(2.dp)),
        )
    }
}
