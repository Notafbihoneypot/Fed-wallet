package app.independentwallet.android

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Same bounded luminance decoder used by the camera and image regression tests. */
internal class LocalQrDecoder {
    private val reader=QRCodeReader()
    fun decode(pixels:ByteArray,width:Int,height:Int):String?{
        require(width in 1..1920&&height in 1..1920&&pixels.size==width*height)
        return try{
            val image=BinaryBitmap(HybridBinarizer(PlanarYUVLuminanceSource(pixels,width,height,0,0,width,height,false)))
            reader.decode(image,mapOf(DecodeHintType.TRY_HARDER to true)).text.takeIf{it.length<=9000}
        }catch(_:ReaderException){null}finally{reader.reset()}
    }
}
