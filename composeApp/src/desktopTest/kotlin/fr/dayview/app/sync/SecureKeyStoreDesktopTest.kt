package fr.dayview.app.sync

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecureKeyStoreDesktopTest {
    private val tempDir = createTempDirectory("dayview-secure-key-store-test").toFile()
    private val file = File(tempDir, "nested/sync.secret")

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun roundTripsKeyAndConfigThroughTempFile() {
        val store = desktopSecureKeyStore(file)
        assertNull(store.loadKey())
        assertNull(store.loadConfig())

        val key = RawSyncKey.generate()
        val config = SyncConfig("https://s", "u", "t")
        store.storeKey(key)
        store.storeConfig(config)

        val reloaded = desktopSecureKeyStore(file)
        assertEquals(key.bytes.toList(), reloaded.loadKey()!!.bytes.toList())
        assertEquals(config, reloaded.loadConfig())
    }

    @Test
    fun clearRemovesEverything() {
        val store = desktopSecureKeyStore(file)
        store.storeKey(RawSyncKey.generate())
        store.storeConfig(SyncConfig("https://s", "u", "t"))

        store.clear()

        assertNull(store.loadKey())
        assertNull(store.loadConfig())
    }
}
