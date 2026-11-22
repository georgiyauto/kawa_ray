package pro.vinnikov.kawavpn.domain

import org.junit.Assert.*
import org.junit.Test

class TunnelHealthTest {
    @Test fun independentEndpointFallbackAcceptsLaterSuccess() {
        val attempted = mutableListOf<String>()
        assertTrue(TunnelHealthChecks.verify({ url -> attempted.add(url); attempted.size == 2 }))
        assertEquals(2, attempted.size)
        assertNotEquals(java.net.URI(attempted[0]).host, java.net.URI(attempted[1]).host)
    }
    @Test fun allFailedEndpointsDoNotProduceConnectivity() {
        assertFalse(TunnelHealthChecks.verify({ false }))
    }
    @Test fun cancelledProbeDoesNotContinueFallbacks() {
        var attempts = 0
        assertFalse(TunnelHealthChecks.verify({ attempts++; false }, { attempts == 0 }))
        assertEquals(1, attempts)
    }
    @Test fun oneAutomaticFailureDoesNotDisconnectAndSuccessResetsBudget() {
        val policy = TunnelHealthPolicy()
        assertFalse(policy.record(false, 100_000, null))
        assertFalse(policy.record(true, 101_000, null))
        assertFalse(policy.record(false, 102_000, null))
        assertTrue(policy.record(false, 103_000, null))
    }
    @Test fun handoverGetsGraceWithoutAccumulatingFailures() {
        val policy = TunnelHealthPolicy()
        assertFalse(policy.record(false, 100_000, null))
        assertFalse(policy.record(false, 105_000, 104_000))
        assertFalse(policy.record(false, 160_000, 104_000))
        assertFalse(policy.record(false, 165_000, 104_000))
        assertTrue(policy.record(false, 195_000, 104_000))
    }
    @Test fun laterHandoverRestartsTheGracePeriod() {
        val policy = TunnelHealthPolicy()
        assertFalse(policy.record(false, 200_000, 195_000))
        assertFalse(policy.record(false, 270_000, 195_000))
        assertFalse(policy.record(false, 280_000, 279_000))
        assertFalse(policy.record(false, 330_000, 279_000))
        assertFalse(policy.record(true, 340_000, 279_000))
    }
}
