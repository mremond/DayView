package fr.dayview.app.sync

import kotlinx.serialization.Serializable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Desktop has no OS-level secret store wired in yet (unlike Android's Keystore-backed
// EncryptedSharedPreferences): the sync key and endpoint config are kept as plaintext
// JSON on disk, with the file restricted to owner-only access (600) where the
// filesystem supports POSIX permissions. This is weaker than a hardware-backed
// keystore — anyone with local read access to the user account (or a backup of the
// home directory) can read the key. Migrating to the macOS Keychain is deferred.

@Serializable
private data class StoredSecret(val keyB64: String? = null, val config: SyncConfig? = null, val deviceId: String? = null)

fun desktopSecureKeyStore(
    file: File = File(System.getProperty("user.home"), ".dayview/sync.secret"),
): SecureKeyStore = DesktopSecureKeyStore(file)

@OptIn(ExperimentalEncodingApi::class)
private class DesktopSecureKeyStore(private val file: File) : SecureKeyStore {
    override fun loadKey(): RawSyncKey? = read().keyB64?.let { b64 ->
        runCatching { RawSyncKey(Base64.decode(b64)) }.getOrNull()
    }

    override fun storeKey(key: RawSyncKey) {
        write(read().copy(keyB64 = Base64.encode(key.bytes)))
    }

    override fun loadConfig(): SyncConfig? = read().config

    override fun storeConfig(config: SyncConfig) {
        write(read().copy(config = config))
    }

    override fun loadDeviceId(): String? = read().deviceId

    override fun storeDeviceId(id: String) {
        write(read().copy(deviceId = id))
    }

    override fun clear() {
        write(StoredSecret())
    }

    // A corrupted, truncated, or hand-edited file must degrade to "nothing stored"
    // rather than crash callers that load the secret at startup.
    private fun read(): StoredSecret {
        if (!file.isFile) return StoredSecret()
        return runCatching { SyncJson.decodeFromString<StoredSecret>(file.readText()) }
            .getOrDefault(StoredSecret())
    }

    private fun write(secret: StoredSecret) {
        file.parentFile?.mkdirs()
        val content = SyncJson.encodeToString(secret)
        try {
            writeAtomicPosix(content)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. some Windows setups): fall back to a plain
            // write followed by best-effort permission narrowing.
            file.writeText(content)
            lockDownPermissions()
        }
    }

    // Writes to a same-directory temp file created with owner-only (600) permissions
    // from the moment it exists, then atomically renames it over the target. This
    // avoids both the exposure window and the corruption risk of write-then-chmod.
    private fun writeAtomicPosix(content: String) {
        val dir = (file.parentFile ?: file.absoluteFile.parentFile).toPath()
        val attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        val tmp = Files.createTempFile(dir, file.name, ".tmp", attrs)
        try {
            Files.write(tmp, content.toByteArray())
            Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
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
