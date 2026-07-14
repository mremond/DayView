package fr.dayview.app.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.AES
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class HistoryBlobCodec(key: RawSyncKey) {
    private val gcm = CryptographyProvider.Default.get(AES.GCM)
    private val cipher = gcm.keyDecoder().decodeFromByteArrayBlocking(AES.Key.Format.RAW, key.bytes).cipher()

    private fun aad(dayKey: Long) = "dayview-history-v$HISTORY_SCHEMA_VERSION:$dayKey".encodeToByteArray()

    suspend fun encrypt(dayKey: Long, plaintext: String): String = Base64.encode(cipher.encrypt(plaintext.encodeToByteArray(), aad(dayKey)))

    suspend fun decrypt(dayKey: Long, ciphertext: String): String = try {
        cipher.decrypt(Base64.decode(ciphertext), aad(dayKey)).decodeToString()
    } catch (e: Exception) {
        throw SyncKeyMismatchException(e)
    }

    private fun focusAad(dayKey: Long, deviceId: String) = "dayview-focus-v$HISTORY_SCHEMA_VERSION:$dayKey:$deviceId".encodeToByteArray()

    suspend fun encryptFocus(dayKey: Long, deviceId: String, plaintext: String): String = Base64.encode(cipher.encrypt(plaintext.encodeToByteArray(), focusAad(dayKey, deviceId)))

    suspend fun decryptFocus(dayKey: Long, deviceId: String, ciphertext: String): String = try {
        cipher.decrypt(Base64.decode(ciphertext), focusAad(dayKey, deviceId)).decodeToString()
    } catch (e: Exception) {
        throw SyncKeyMismatchException(e)
    }
}
