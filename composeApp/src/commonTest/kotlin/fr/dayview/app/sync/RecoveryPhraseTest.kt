package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecoveryPhraseTest {
    @Test
    fun roundTripsRandomKeys() {
        repeat(20) {
            val key = RawSyncKey.generate()
            val restored = RecoveryPhrase.decode(RecoveryPhrase.encode(key))
            assertTrue(restored != null && restored.bytes.toList() == key.bytes.toList())
        }
    }

    @Test
    fun encodesTheAllZeroStandardVector() {
        val zeros = RawSyncKey(ByteArray(32))
        val words = RecoveryPhrase.encode(zeros)
        assertEquals(List(23) { "abandon" } + "art", words) // canonical BIP39 256-bit zero vector
    }

    @Test
    fun decodesTheAllZeroStandardVector() {
        val restored = RecoveryPhrase.decode(List(23) { "abandon" } + "art")
        assertTrue(restored != null && restored.bytes.all { it == 0.toByte() })
    }

    @Test
    fun rejectsTamperedChecksum() {
        val words = RecoveryPhrase.encode(RawSyncKey.generate()).toMutableList()
        words[23] = if (words[23] == "zoo") "abandon" else "zoo" // change last word → checksum breaks
        assertNull(RecoveryPhrase.decode(words))
    }

    @Test
    fun rejectsUnknownWordAndWrongCount() {
        val valid = RecoveryPhrase.encode(RawSyncKey.generate())
        assertNull(RecoveryPhrase.decode(valid.dropLast(1))) // 23 words
        assertNull(RecoveryPhrase.decode(valid.dropLast(1) + "notaword"))
    }

    @Test
    fun toleratesWhitespaceAndCase() {
        val key = RawSyncKey.generate()
        val phrase = RecoveryPhrase.encode(key).joinToString("  ") { it.uppercase() }
        val restored = RecoveryPhrase.decodePhrase("  \n$phrase\n ")
        assertTrue(restored != null && restored.bytes.toList() == key.bytes.toList())
    }

    @Test
    fun decodePhraseAcceptsNumberedDisplayForm() {
        val key = RawSyncKey.generate()
        val words = RecoveryPhrase.encode(key)
        val numbered = words.mapIndexed { i, w -> "${i + 1}. $w" }.joinToString("   ")
        val restored = RecoveryPhrase.decodePhrase(numbered)
        assertTrue(restored != null && restored.bytes.toList() == key.bytes.toList())
    }
}
