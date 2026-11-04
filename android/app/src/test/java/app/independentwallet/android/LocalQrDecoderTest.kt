package app.independentwallet.android

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class LocalQrDecoderTest {
    @Test fun cameraDecoderReadsMoneroUriInAllFourRotations(){
        val text="monero:"+"4"+"1".repeat(94)+"?tx_amount=0.025"
        val size=384;val qr=QRCodeWriter().encode(text,BarcodeFormat.QR_CODE,size,size)
        var pixels=ByteArray(size*size){i->if(qr[i%size,i/size])0 else 255.toByte()}
        val decoder=LocalQrDecoder()
        repeat(4){
            assertEquals(text,decoder.decode(pixels,size,size))
            val rotated=ByteArray(pixels.size)
            for(y in 0 until size)for(x in 0 until size)rotated[x*size+size-1-y]=pixels[y*size+x]
            pixels=rotated
        }
        assertNull(decoder.decode(ByteArray(size*size){255.toByte()},size,size))
    }
}
