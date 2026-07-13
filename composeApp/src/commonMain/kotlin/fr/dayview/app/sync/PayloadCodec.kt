package fr.dayview.app.sync

import dev.whyoleg.cryptography.random.CryptographyRandom

interface PayloadCodec {
    suspend fun encrypt(plaintext: String): String

    suspend fun decrypt(ciphertext: String): String
}

class SyncKeyMismatchException(cause: Throwable?) : Exception("Sync key missing or invalid", cause)

class RawSyncKey(val bytes: ByteArray) {
    init {
        require(bytes.size == SIZE_BYTES) { "SyncKey must be $SIZE_BYTES bytes" }
    }

    companion object {
        const val SIZE_BYTES = 32

        fun generate(): RawSyncKey = RawSyncKey(CryptographyRandom.nextBytes(SIZE_BYTES))
    }
}
