package fr.dayview.app.sync

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Desktop has no OS-level secret store wired in yet (unlike Android's Keystore-backed
// EncryptedSharedPreferences): the sync key and endpoint config are kept as plaintext
// JSON on disk, with the file restricted to owner-only access (600) where the
// filesystem supports POSIX permissions. This is weaker than a hardware-backed
// keystore — anyone with local read access to the user account (or a backup of the
// home directory) can read the key. Migrating to the macOS Keychain is deferred.

@Serializable
private data class StoredSecret(val keyB64: String? = null, val config: SyncConfig? = null)

fun desktopSecureKeyStore(
    file: File = File(System.getProperty("user.home"), ".dayview/sync.secret"),
): SecureKeyStore = DesktopSecureKeyStore(file)

@OptIn(ExperimentalEncodingApi::class)
private class DesktopSecureKeyStore(private val file: File) : SecureKeyStore {
    override fun loadKey(): RawSyncKey? = read().keyB64?.let { RawSyncKey(Base64.decode(it)) }

    override fun storeKey(key: RawSyncKey) {
        write(read().copy(keyB64 = Base64.encode(key.bytes)))
    }

    override fun loadConfig(): SyncConfig? = read().config

    override fun storeConfig(config: SyncConfig) {
        write(read().copy(config = config))
    }

    override fun clear() {
        write(StoredSecret())
    }

    private fun read(): StoredSecret {
        if (!file.isFile) return StoredSecret()
        return SyncJson.decodeFromString(file.readText())
    }

    private fun write(secret: StoredSecret) {
        file.parentFile?.mkdirs()
        file.writeText(SyncJson.encodeToString(secret))
        lockDownPermissions()
    }

    private fun lockDownPermissions() {
        try {
            Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. some Windows setups); nothing to do.
        }
    }
}
