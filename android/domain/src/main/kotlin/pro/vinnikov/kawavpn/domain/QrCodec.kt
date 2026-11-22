package pro.vinnikov.kawavpn.domain
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
object QrCodec {
    fun decode(pixels:IntArray,width:Int,height:Int):String {
        require(width>0 && height>0 && width.toLong()*height==pixels.size.toLong() && pixels.size<=4096*4096)
        return runCatching { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width,height,pixels))),mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),DecodeHintType.TRY_HARDER to true)).text }
            .getOrElse { throw IllegalArgumentException("QR-код не распознан. Попробуйте более чёткое изображение.") }
    }
    fun encode(value:String,size:Int):IntArray {
        require(value.isNotBlank() && value.toByteArray().size<=2800 && size in 128..1024)
        val matrix=QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,size,size,mapOf(EncodeHintType.CHARACTER_SET to "UTF-8",EncodeHintType.MARGIN to 2))
        return IntArray(size*size) { index -> if(matrix[index%size,index/size]) 0xFF000000.toInt() else -1 }
    }
}
