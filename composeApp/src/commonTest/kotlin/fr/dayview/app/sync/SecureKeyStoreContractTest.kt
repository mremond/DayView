package fr.dayview.app.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecureKeyStoreContractTest {
    @Test
    fun storesAndLoadsKeyAndConfig() {
        val store = InMemorySecureKeyStore()
        assertNull(store.loadKey())
        val key = RawSyncKey.generate()
        store.storeKey(key)
        store.storeConfig(SyncConfig("https://s", "u", "t"))
        assertEquals(key.bytes.toList(), store.loadKey()!!.bytes.toList())
        assertEquals(SyncConfig("https://s", "u", "t"), store.loadConfig())
    }

    @Test
    fun clearRemovesEverything() {
        val store = InMemorySecureKeyStore()
        store.storeKey(RawSyncKey.generate())
        store.clear()
        assertNull(store.loadKey())
        assertNull(store.loadConfig())
    }

    @Test
    fun deviceIdOrCreateGeneratesOnceAndReusesTheStoredId() {
        val store = InMemorySecureKeyStore()
        var generateCalls = 0
        fun generate(): String {
            generateCalls++
            return "generated-id"
        }

        val first = store.deviceIdOrCreate(::generate)
        val second = store.deviceIdOrCreate(::generate)

        assertEquals("generated-id", first)
        assertEquals("generated-id", second)
        assertEquals(1, generateCalls)
        assertEquals("generated-id", store.loadDeviceId())
    }

    @Test
    fun clearRemovesTheDeviceId() {
        val store = InMemorySecureKeyStore()
        store.storeDeviceId("some-id")
        store.clear()
        assertNull(store.loadDeviceId())
    }
}
