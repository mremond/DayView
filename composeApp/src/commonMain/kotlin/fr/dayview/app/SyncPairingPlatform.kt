package fr.dayview.app

import androidx.compose.runtime.Composable

internal class QrCodeMatrix(
    val width: Int,
    val height: Int,
    private val pixels: BooleanArray,
) {
    operator fun get(x: Int, y: Int): Boolean = pixels[y * width + x]
}

internal expect fun createSyncQrCode(content: String): QrCodeMatrix

/** Why a QR pairing scan could not complete. */
internal enum class SyncPairingScanError {
    /** The device has no working code scanner (e.g. Google Play Services unavailable). */
    Unavailable,

    /** The scanner launched but the scan could not be completed. */
    Failed,
}

/** Returns null on platforms without a camera scanner (desktop). */
@Composable
internal expect fun rememberSyncPairingScanner(
    onResult: (String) -> Unit,
    onFailure: (SyncPairingScanError) -> Unit,
): (() -> Unit)?
