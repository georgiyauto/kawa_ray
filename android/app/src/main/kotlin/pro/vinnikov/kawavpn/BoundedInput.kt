package pro.vinnikov.kawavpn

import java.io.InputStream
import java.io.ByteArrayOutputStream
internal fun InputStream.readBounded(limit:Int):ByteArray {
    val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true) { val size=read(buffer,0,minOf(buffer.size,limit+1-output.size()));if(size<0) break;output.write(buffer,0,size);require(output.size()<=limit) { "Слишком большой ответ" } }
    return output.toByteArray()
}
