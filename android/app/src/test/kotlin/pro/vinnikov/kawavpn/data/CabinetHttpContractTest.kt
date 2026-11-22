package pro.vinnikov.kawavpn.data
import org.junit.Assert.*
import org.junit.Test
class CabinetHttpContractTest {
    @Test fun pendingAndSuccessAreValidButExpiredRateLimitedAndOfflineAreTyped() {
        checkCabinetStatus(200);checkCabinetStatus(202)
        assertThrows(LoginExpired::class.java) { checkCabinetStatus(401) }
        for(status in listOf(410,429,503)) assertEquals(status,assertThrows(ApiUnavailable::class.java) { checkCabinetStatus(status) }.status)
    }
}
