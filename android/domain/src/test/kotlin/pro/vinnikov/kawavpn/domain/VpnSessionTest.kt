package pro.vinnikov.kawavpn.domain
import org.junit.Assert.*
import org.junit.Test

class VpnSessionTest {
    class Driver : EngineDriver {
        var stopCount = 0; var fail = false; var live = true
        override fun start() { if (fail) error("secret config") }
        override fun stop() { stopCount++ }
        override fun running() = live
    }
    @Test fun engineFailureAlwaysCleansUpAndDoesNotExposeSecrets() {
        val driver = Driver().also { it.fail = true }; val session = VpnSession(driver) { true }
        assertFalse(session.connect()); assertEquals(ConnectionState.ERROR, session.state)
        assertEquals(1, driver.stopCount); assertFalse(session.error.orEmpty().contains("secret config"))
    }
    @Test fun failedConnectivityNeverReportsProtected() {
        val driver = Driver(); val session = VpnSession(driver) { false }
        assertFalse(session.connect()); assertEquals(ConnectionState.ERROR, session.state); assertEquals(1, driver.stopCount)
    }
    @Test fun successfulSessionStopsCleanly() {
        val driver = Driver(); val session = VpnSession(driver) { true }
        assertTrue(session.connect()); assertEquals(ConnectionState.CONNECTED, session.state)
        session.disconnect(); assertEquals(ConnectionState.DISCONNECTED, session.state); assertEquals(1, driver.stopCount)
    }
}
