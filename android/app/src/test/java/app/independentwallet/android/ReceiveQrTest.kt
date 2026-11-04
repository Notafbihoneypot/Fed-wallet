package app.independentwallet.android

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class ReceiveQrTest {
    @Test fun generatedQrDecodesToExactlyTheDisplayedAddress() {
        for (address in listOf("5" + "aB39".repeat(23) + "xy", "5" + "aB39".repeat(26) + "x")) {
            val pixels = receiveQrPixels(address)
            val source = RGBLuminanceSource(QR_SIZE, QR_SIZE, pixels)
            assertEquals(address, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        }
    }
    @Test fun receiveEncodingRejectsUrisAndUnboundedInput() {
        for (input in listOf("", "https://example.invalid/", "a".repeat(4096), "\u202e".repeat(95)))
            assertThrows(IllegalArgumentException::class.java) { receiveQrPixels(input) }
    }
}
