package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HistoryKeyTest {
    private fun key() = RawSyncKey(ByteArray(32) { it.toByte() })

    @Test
    fun opaqueKeyIsDeterministicAcrossInstances() {
        assertEquals(HistoryKey(key()).opaqueKey(20100), HistoryKey(key()).opaqueKey(20100))
    }

    @Test
    fun differentDaysGiveDifferentKeys() {
        val k = HistoryKey(key())
        assertNotEquals(k.opaqueKey(20100), k.opaqueKey(20101))
    }

    @Test
    fun differentSyncKeysGiveDifferentKeys() {
        val other = RawSyncKey(ByteArray(32) { (it + 7).toByte() })
        assertNotEquals(HistoryKey(key()).opaqueKey(20100), HistoryKey(other).opaqueKey(20100))
    }
}
