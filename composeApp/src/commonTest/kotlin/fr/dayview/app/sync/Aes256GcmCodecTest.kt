package fr.dayview.app.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class Aes256GcmCodecTest {
    @Test
    fun roundTripsPlaintext() = runTest {
        val codec = Aes256GcmCodec(RawSyncKey.generate())
        val text = sampleDocument().encodeToString()
        assertEquals(text, codec.decrypt(codec.encrypt(text)))
    }

    @Test
    fun encryptUsesFreshNonceEachTime() = runTest {
        val codec = Aes256GcmCodec(RawSyncKey.generate())
        assertNotEquals(codec.encrypt("hello"), codec.encrypt("hello"))
    }

    @Test
    fun wrongKeyFailsAuthentication() = runTest {
        val cipher = Aes256GcmCodec(RawSyncKey.generate()).encrypt("secret")
        val other = Aes256GcmCodec(RawSyncKey.generate())
        assertFailsWith<SyncKeyMismatchException> { other.decrypt(cipher) }
    }
}
