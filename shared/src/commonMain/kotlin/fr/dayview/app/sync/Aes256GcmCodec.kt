package fr.dayview.app.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class Aes256GcmCodec(key: RawSyncKey) : PayloadCodec {
    private val aad = "dayview-sync-v$SYNC_SCHEMA_VERSION".encodeToByteArray()
    private val gcm = CryptographyProvider.Default.get(AES.GCM)
    private val cipherKey = gcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key.bytes)

    // The cipher prepends a fresh random nonce to the returned ciphertext and verifies
    // it (plus the GCM tag) on decrypt.
    private val cipher = cipherKey.cipher()

    override suspend fun encrypt(plaintext: String): String {
        val out = cipher.encrypt(plaintext.encodeToByteArray(), aad)
        return Base64.encode(out)
    }

    override suspend fun decrypt(ciphertext: String): String = try {
        cipher.decrypt(Base64.decode(ciphertext), aad).decodeToString()
    } catch (e: Exception) {
        throw SyncKeyMismatchException(e)
    }
}
