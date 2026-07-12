package fr.dayview.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Small window-mode glyphs, hand-drawn with Canvas to match the app's other
// inline glyphs (the focus-start "+", the mini stop square) rather than pulling
// in a Material icon dependency. Shared by the main-window header and the mini
// window. Coordinates map the approved 20-unit design onto the drawn size.

/**
 * Picture-in-picture: an outer window frame with a small filled pane in the
 * bottom-right corner. Reads "shrink to mini window".
 */
@Composable
fun MiniWindowGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.075f
        val inset = stroke / 2f
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.11f + inset, s * 0.175f + inset),
            size = Size(s * 0.78f - stroke, s * 0.65f - stroke),
            cornerRadius = CornerRadius(s * 0.11f, s * 0.11f),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.50f, s * 0.48f),
            size = Size(s * 0.30f, s * 0.22f),
            cornerRadius = CornerRadius(s * 0.05f, s * 0.05f),
        )
    }
}

/**
 * Two diagonal corner arrows pushing outward (top-right and bottom-left).
 * Reads "grow back to the full window".
 */
@Composable
fun ExpandWindowGlyph(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.width
        val stroke = s * 0.075f
        val line = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val topRight = Path().apply {
            moveTo(s * 0.55f, s * 0.15f)
            lineTo(s * 0.85f, s * 0.15f)
            lineTo(s * 0.85f, s * 0.45f)
        }
        drawPath(topRight, color, style = line)
        drawLine(
            color = color,
            start = Offset(s * 0.85f, s * 0.15f),
            end = Offset(s * 0.55f, s * 0.45f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        val bottomLeft = Path().apply {
            moveTo(s * 0.45f, s * 0.85f)
            lineTo(s * 0.15f, s * 0.85f)
            lineTo(s * 0.15f, s * 0.55f)
        }
        drawPath(bottomLeft, color, style = line)
        drawLine(
            color = color,
            start = Offset(s * 0.15f, s * 0.85f),
            end = Offset(s * 0.45f, s * 0.55f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
