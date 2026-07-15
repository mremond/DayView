package fr.dayview.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.mlkit.common.MlKitException
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
    onFailure: (SyncPairingScanError) -> Unit,
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
            // The Google code scanner relies on Play Services to provision a barcode module and
            // present its own camera UI. On devices without a working Play Services (e.g. some
            // e-ink readers) startScan() fails instantly with no camera prompt, so report the
            // scanner as unavailable and let the user fall back to manual setup.
            val availability = GoogleApiAvailabilityLight.getInstance()
                .isGooglePlayServicesAvailable(context)
            if (availability != ConnectionResult.SUCCESS) {
                logError("SyncPairingScanner", "Play Services unavailable for QR scan (code $availability)", null)
                currentFailure(SyncPairingScanError.Unavailable)
            } else {
                scanner.startScan()
                    .addOnSuccessListener { barcode ->
                        barcode.rawValue?.let(currentResult) ?: currentFailure(SyncPairingScanError.Failed)
                    }
                    .addOnFailureListener { error ->
                        // Backing out of the scanner is not a failure, so stay silent on cancel.
                        if ((error as? MlKitException)?.errorCode == MlKitException.CODE_SCANNER_CANCELLED) {
                            return@addOnFailureListener
                        }
                        logError("SyncPairingScanner", "QR scan failed", error)
                        currentFailure(scanErrorFor(error))
                    }
            }
        }
    }
}

/**
 * Maps a code-scanner failure to a [SyncPairingScanError]. Errors that mean the scanner cannot run
 * on this device (module unavailable, Play Services too old, pipeline init failed) are reported as
 * [SyncPairingScanError.Unavailable] so the user is steered to manual setup; everything else is a
 * transient [SyncPairingScanError.Failed].
 */
private fun scanErrorFor(error: Exception): SyncPairingScanError {
    val code = (error as? MlKitException)?.errorCode ?: return SyncPairingScanError.Failed
    return when (code) {
        MlKitException.CODE_SCANNER_UNAVAILABLE,
        MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD,
        MlKitException.CODE_SCANNER_PIPELINE_INITIALIZATION_ERROR,
        MlKitException.UNAVAILABLE,
        -> SyncPairingScanError.Unavailable

        else -> SyncPairingScanError.Failed
    }
}
