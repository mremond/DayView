package fr.dayview.app

import androidx.compose.runtime.Composable

internal actual fun platformAutoScaleEnabled(): Boolean = false

// Desktop opts out of auto-scaling, so the basis is irrelevant; keep the live constraint value.
@Composable
internal actual fun stableScaleMinDimensionDp(liveMinDimensionDp: Float): Float = liveMinDimensionDp
