package fr.dayview.app.sync

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256

// BIP39 encode/decode for the 32-byte sync key, expressed as a 24-word recovery phrase.
// See https://github.com/bitcoin/bips/blob/master/bip-0039.mediawiki
object RecoveryPhrase {
    private const val WORD_COUNT = 24
    private const val ENTROPY_BYTES = 32 // 256 bits
    private const val CHECKSUM_BITS = 8 // ENT / 32
    private const val TOTAL_BITS = ENTROPY_BYTES * 8 + CHECKSUM_BITS // 264 = 24 * 11

    fun encode(key: RawSyncKey): List<String> {
        val full = key.bytes + sha256(key.bytes)[0] // 32 entropy bytes + 1 checksum byte
        return (0 until WORD_COUNT).map { group ->
            var index = 0
            for (b in 0 until 11) {
                val bitPos = group * 11 + b
                val bit = (full[bitPos / 8].toInt() ushr (7 - bitPos % 8)) and 1
                index = (index shl 1) or bit
            }
            Bip39Wordlist[index]
        }
    }

    fun decode(words: List<String>): RawSyncKey? {
        val normalized = words.map { it.trim().lowercase() }
        if (normalized.size != WORD_COUNT) return null
        val indices = normalized.map { w -> Bip39Wordlist.indexOf(w).also { if (it < 0) return null } }

        val bits = BooleanArray(TOTAL_BITS)
        for (i in 0 until WORD_COUNT) {
            for (b in 0 until 11) bits[i * 11 + b] = (indices[i] ushr (10 - b)) and 1 == 1
        }
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BYTES * 8) {
            if (bits[i]) entropy[i / 8] = (entropy[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
        }
        val checksum = sha256(entropy)[0].toInt()
        for (b in 0 until CHECKSUM_BITS) {
            val expected = (checksum ushr (7 - b)) and 1 == 1
            if (bits[ENTROPY_BYTES * 8 + b] != expected) return null
        }
        return RawSyncKey(entropy)
    }

    fun decodePhrase(text: String): RawSyncKey? = decode(text.trim().split(Regex("\\s+")).filter { it.isNotBlank() })

    private val sha256Hasher = CryptographyProvider.Default.get(SHA256).hasher()

    private fun sha256(data: ByteArray): ByteArray = sha256Hasher.hashBlocking(data)
}
