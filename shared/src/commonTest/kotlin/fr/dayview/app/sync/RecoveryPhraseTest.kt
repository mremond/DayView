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
    fun rejectsWrongChecksum() {
        // 24×"abandon" is all-zero entropy carrying an all-zero checksum, but the real
        // checksum for zero entropy is non-zero (the canonical 24th word is "art", not
        // "abandon"), so this phrase must be rejected. Deterministic — unlike tampering a
        // random phrase, which lands on another valid checksum ~1 time in 256 (BIP39's
        // checksum is only 8 bits for a 256-bit key), so that form was flaky.
        assertNull(RecoveryPhrase.decode(List(24) { "abandon" }))
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
