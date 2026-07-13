package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class Bip39WordlistTest {
    @Test
    fun wordlistHasTheExpectedShape() {
        assertEquals(2048, Bip39Wordlist.size)
        assertEquals(2048, Bip39Wordlist.toSet().size) // all unique
        assertEquals(Bip39Wordlist, Bip39Wordlist.map { it.lowercase() }) // all lowercase
        assertEquals("abandon", Bip39Wordlist.first())
        assertEquals("zoo", Bip39Wordlist.last())
        // Verified against the canonical bitcoin/bips english.txt: index 99 (0-based) is "arrest",
        // not "art" (which is at index 102). Two independent authoritative mirrors
        // (bitcoin/bips and trezor/python-mnemonic) agree byte-for-byte on this ordering.
        assertEquals("arrest", Bip39Wordlist[99])
    }
}
