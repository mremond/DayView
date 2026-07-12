package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModeTest {
    @Test
    fun systemFollowsSystemFlag() {
        assertEquals(true, ThemeMode.SYSTEM.resolveIsDark(systemDark = true))
        assertEquals(false, ThemeMode.SYSTEM.resolveIsDark(systemDark = false))
    }

    @Test
    fun lightIsAlwaysLight() {
        assertEquals(false, ThemeMode.LIGHT.resolveIsDark(systemDark = true))
        assertEquals(false, ThemeMode.LIGHT.resolveIsDark(systemDark = false))
    }

    @Test
    fun darkIsAlwaysDark() {
        assertEquals(true, ThemeMode.DARK.resolveIsDark(systemDark = true))
        assertEquals(true, ThemeMode.DARK.resolveIsDark(systemDark = false))
    }
}
