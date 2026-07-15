package fr.dayview.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MacHelpersTest {
    @Test
    fun `same bytes yield the same file name`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertEquals(
            helperFileName("/macos-eventkit-helper", bytes),
            helperFileName("/macos-eventkit-helper", bytes),
        )
    }

    @Test
    fun `different bytes yield different file names`() {
        assertNotEquals(
            helperFileName("/macos-eventkit-helper", byteArrayOf(1, 2, 3)),
            helperFileName("/macos-eventkit-helper", byteArrayOf(4, 5, 6)),
        )
    }

    @Test
    fun `leading slash is stripped and hash is appended`() {
        val name = helperFileName("/macos-eventkit-helper", byteArrayOf(0))
        assertTrue(name.startsWith("macos-eventkit-helper-"), "was: $name")
        // basename + '-' + 16 hex chars (8 bytes)
        assertEquals("macos-eventkit-helper-".length + 16, name.length)
    }
}
