package fr.dayview.app.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

class HistoryKey(rawKey: RawSyncKey) {
    private val hmac = CryptographyProvider.Default.get(HMAC)

    private val indexKey = run {
        val root = hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, rawKey.bytes)
        val derived =
            root.signatureGenerator().generateSignatureBlocking("dayview-history-index-v1".encodeToByteArray())
        hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, derived)
    }

    fun opaqueKey(dayKey: Long): String = indexKey.signatureGenerator().generateSignatureBlocking(dayKey.toString().encodeToByteArray()).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
