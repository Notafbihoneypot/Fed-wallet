package app.independentwallet.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Local encoding only. No remote QR service or camera dependency. */
internal fun receiveQrPixels(address: String): IntArray {
    require(address.length in 95..106 && address.all { it.code in 33..126 })
    val matrix = QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    return IntArray(QR_SIZE * QR_SIZE) { index ->
        if (matrix[index % QR_SIZE, index / QR_SIZE]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
}
internal const val QR_SIZE = 512
