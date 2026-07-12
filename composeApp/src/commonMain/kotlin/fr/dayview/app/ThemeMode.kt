package fr.dayview.app

/** How the app chooses between the light and dark palettes. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Resolves the concrete dark flag, given the current OS dark-mode signal. */
fun ThemeMode.resolveIsDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
