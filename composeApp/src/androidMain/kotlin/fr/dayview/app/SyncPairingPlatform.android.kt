package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
): (() -> Unit)? {
    val context = LocalContext.current
    val currentResult by rememberUpdatedState(onResult)
    val currentFailure by rememberUpdatedState(onFailure)
    val scanner = remember(context) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        GmsBarcodeScanning.getClient(context, options)
    }
    return remember(scanner) {
        {
            scanner.startScan()
                .addOnSuccessListener { barcode -> barcode.rawValue?.let(currentResult) ?: currentFailure() }
                .addOnFailureListener { currentFailure() }
        }
    }
}
