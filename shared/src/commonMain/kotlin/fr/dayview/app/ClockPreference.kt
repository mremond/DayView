package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether time labels should render in 24-hour form. Defaults to `true` so
 * existing tests and previews keep their 24h rendering unless a provider
 * overrides it near the app root.
 */
val LocalUses24HourClock = staticCompositionLocalOf { true }

/** Reads the operating system's 12h/24h clock preference. */
@Composable
expect fun rememberUses24HourClock(): Boolean
