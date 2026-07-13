package fr.dayview.app.sync

import java.io.File
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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

    @Test
    fun loadReturnsNullOnCorruptedFile() {
        file.parentFile.mkdirs()
        file.writeText("not json {{{")

        val store = desktopSecureKeyStore(file)

        assertNull(store.loadKey())
        assertNull(store.loadConfig())
    }

    @Test
    fun loadReturnsNullOnWrongLengthKey() {
        file.parentFile.mkdirs()
        // "AAAA" base64-decodes to 3 bytes, well short of the required 32-byte key.
        file.writeText("""{"keyB64":"AAAA"}""")

        val store = desktopSecureKeyStore(file)

        assertNull(store.loadKey())
    }

    @Test
    fun secretFileHasOwnerOnlyPermissions() {
        val fileStore = try {
            Files.getFileStore(tempDir.toPath())
        } catch (_: Exception) {
            null
        }
        if (fileStore == null || !fileStore.supportsFileAttributeView("posix")) {
            return
        }

        val store = desktopSecureKeyStore(file)
        store.storeKey(RawSyncKey.generate())

        try {
            val permissions = Files.getPosixFilePermissions(file.toPath())
            assertEquals(PosixFilePermissions.fromString("rw-------"), permissions)
        } catch (_: FileSystemException) {
            // Non-POSIX filesystem despite the FileStore check above; nothing to assert.
        }
    }
}
