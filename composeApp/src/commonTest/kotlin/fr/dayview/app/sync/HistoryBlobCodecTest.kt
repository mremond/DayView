package fr.dayview.app.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HistoryBlobCodecTest {
    private fun key() = RawSyncKey(ByteArray(32) { it.toByte() })

    @Test
    fun roundTripsForSameDay() = runTest {
        val codec = HistoryBlobCodec(key())
        val blob = codec.encrypt(20100, "payload")
        assertEquals("payload", codec.decrypt(20100, blob))
    }

    @Test
    fun rejectsBlobDecryptedUnderADifferentDay() = runTest {
        val codec = HistoryBlobCodec(key())
        val blob = codec.encrypt(20100, "payload")
        assertFailsWith<SyncKeyMismatchException> { codec.decrypt(20101, blob) }
    }

    @Test
    fun rejectsBlobUnderADifferentKey() = runTest {
        val blob = HistoryBlobCodec(key()).encrypt(20100, "payload")
        val other = HistoryBlobCodec(RawSyncKey(ByteArray(32) { (it + 1).toByte() }))
        assertFailsWith<SyncKeyMismatchException> { other.decrypt(20100, blob) }
    }
}
