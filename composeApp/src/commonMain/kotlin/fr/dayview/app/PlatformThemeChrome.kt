package fr.dayview.app

import androidx.compose.runtime.Composable

/**
 * Syncs platform window chrome (Android status/navigation bars) to the resolved
 * theme. Desktop is a no-op here — the desktop window appearance is handled in Main.
 */
@Composable
expect fun PlatformThemeChrome(isDark: Boolean)
