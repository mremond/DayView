package fr.dayview.app.sync

/** Identity codec: keeps the loop logic under test independent of real crypto. */
internal object PlainCodec : PayloadCodec {
    override suspend fun encrypt(plaintext: String) = plaintext

    override suspend fun decrypt(ciphertext: String) = ciphertext
}

internal object FailKeyCodec : PayloadCodec {
    override suspend fun encrypt(plaintext: String) = plaintext

    override suspend fun decrypt(ciphertext: String): String = throw SyncKeyMismatchException(null)
}
