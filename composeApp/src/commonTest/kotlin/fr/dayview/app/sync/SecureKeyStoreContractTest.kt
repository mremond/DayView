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
}
