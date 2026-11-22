package pro.vinnikov.kawavpn.domain
import org.junit.Assert.*
import org.junit.Test
class QrCodecTest {
    @Test fun roundTripsSubscriptionWithoutNetworkOrExternalScanner() {
        val value="https://example.test/subscription"
        val pixels=QrCodec.encode(value,320)
        assertEquals(value,QrCodec.decode(pixels,320,320))
    }
    @Test fun unreadableImageCannotBeImportedAsProfile() {
        assertThrows(IllegalArgumentException::class.java) { QrCodec.decode(IntArray(256*256) { -1 },256,256) }
    }
}
