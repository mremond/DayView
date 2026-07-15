package fr.dayview.app

import androidx.compose.runtime.Composable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

internal actual fun createSyncQrCode(content: String): QrCodeMatrix {
    val bits = QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 3,
        ),
    )
    return QrCodeMatrix(
        width = bits.width,
        height = bits.height,
        pixels = BooleanArray(bits.width * bits.height) { index -> bits[index % bits.width, index / bits.width] },
    )
}

@Composable
internal actual fun rememberSyncPairingScanner(
    onResult: (String) -> Unit,
    onFailure: () -> Unit,
): (() -> Unit)? = null
