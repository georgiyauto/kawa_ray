package pro.vinnikov.kawavpn

import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pro.vinnikov.kawavpn.data.*
import pro.vinnikov.kawavpn.domain.*
import pro.vinnikov.kawavpn.vpn.*
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Runs only on an isolated emulator/test device. No production accounts or payments. */
@RunWith(AndroidJUnit4::class)
class PlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @Test fun encryptedStoreSurvivesReopenWithoutPlaintextSecrets() {
        val store = PrivateStore(context)
        val before = store.read()
        try {
            val profile = ProfileParser.parse("""{"remarks":"Private test","outbounds":[{"protocol":"freedom"}]}""").single()
            store.write(AppData(profiles = listOf(SavedProfile(profile, "manual")), session = AccountSession("test-access-sensitive", "test-refresh-sensitive")))
            val raw = File(context.noBackupFilesDir, "kawa-private.v1").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("test-access-sensitive")); assertFalse(raw.contains("test-refresh-sensitive")); assertFalse(raw.contains("Private test"))
            assertEquals("test-refresh-sensitive", PrivateStore(context).read().session!!.refresh_token)
            store.update { it.copy(session = null) }
            assertNull(PrivateStore(context).read().session)
        } finally { store.write(before) }
    }
    @Test fun actualNativeEngineLoadsAndReturnsVersionAndNoRunningTunnel() {
        val result = NativeEngineBridge.invoke("xrayVersion").asJsonObject
        assertTrue(result["version"].asString.isNotBlank())
        assertFalse(NativeEngineBridge.running())
    }
    @Test fun concurrentStoreInstancesNeverLoseRotatedSession() {
        val before = PrivateStore(context).read()
        try {
            PrivateStore(context).write(AppData(session = AccountSession("test", "0")))
            val start = java.util.concurrent.CountDownLatch(1)
            val workers = List(2) {
                Thread { start.await(); repeat(10) { PrivateStore(context).update { old -> old.copy(session = old.session!!.copy(refresh_token = (old.session.refresh_token.toInt() + 1).toString())) } } }.apply { start() }
            }
            start.countDown(); workers.forEach { it.join() }
            assertEquals("20", PrivateStore(context).read().session!!.refresh_token)
        } finally { PrivateStore(context).write(before) }
    }
    @Test fun fullTunConnectHttpDnsAndStopCleanup() {
        assertNull("Grant ACTIVATE_VPN app-op to this isolated test app before instrumentation", VpnService.prepare(context))
        val store = PrivateStore(context)
        val before = store.read()
        val uuid = InstrumentationRegistry.getArguments().getString("testVlessUuid")
        assertNotNull("An owned local VLESS test server is required", uuid)
        val outbound = """{"protocol":"vless","settings":{"vnext":[{"address":"10.0.2.2","port":25080,"users":[{"id":"$uuid","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}"""
        val profile = ProfileParser.parse("""{"remarks":"Isolated transport test","outbounds":[$outbound]}""").single()
        try {
            store.write(AppData(profiles = listOf(SavedProfile(profile, "manual")), selected = profile.id))
            context.startForegroundService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.CONNECT))
            val end = SystemClock.elapsedRealtime() + 60000
            while (SystemClock.elapsedRealtime() < end && KawaVpnService.status.value.state != ConnectionState.CONNECTED && KawaVpnService.status.value.state != ConnectionState.ERROR) Thread.sleep(250)
            assertEquals(KawaVpnService.status.value.error, ConnectionState.CONNECTED, KawaVpnService.status.value.state)
            assertTrue(NativeEngineBridge.running())
            assertNotNull(KawaVpnService.status.value.latency)
            val meterDeadline=SystemClock.elapsedRealtime()+10000
            while(SystemClock.elapsedRealtime()<meterDeadline && KawaVpnService.status.value.received==0L) Thread.sleep(250)
            assertTrue("TUN downlink counter must report real bytes",KawaVpnService.status.value.received>0)
            assertTrue("TUN uplink counter must report real bytes",KawaVpnService.status.value.sent>0)
            val realPing=kotlinx.coroutines.runBlocking { NativeProbeClient.ping(context,listOf(ProbeConfig(profile.configJson))) }
            assertNotNull("Real protocol probe must work while the managed VPN is active",realPing.single())
            assertTrue("Probe must not stop the managed Xray",NativeEngineBridge.running())
            assertEquals(ConnectionState.CONNECTED,KawaVpnService.status.value.state)

            // The disposable emulator is owned by QA. A failed manual ping during
            // physical-network loss must not destroy the TUN/native session.
            val connectedSince = KawaVpnService.status.value.since
            fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command)
            ).use { it.readBytes() }
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            fun hasPhysicalNetwork() = connectivity.allNetworks.any { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } == true
            }
            try {
                shell("svc wifi disable"); shell("svc data disable")
                val offlineDeadline = SystemClock.elapsedRealtime() + 10000
                while (SystemClock.elapsedRealtime() < offlineDeadline && hasPhysicalNetwork()) Thread.sleep(100)
                assertFalse("QA network controls must actually remove physical internet", hasPhysicalNetwork())
                Thread.sleep(500)
                val previousPing = KawaVpnService.status.value.lastPingAt
                context.startService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.PING))
                val pingDeadline = SystemClock.elapsedRealtime() + 20000
                while (SystemClock.elapsedRealtime() < pingDeadline && KawaVpnService.status.value.lastPingAt == previousPing) Thread.sleep(100)
                assertTrue("Manual offline ping must finish", KawaVpnService.status.value.lastPingAt > previousPing)
                assertNull("The offline probe must actually fail", KawaVpnService.status.value.latency)
                assertEquals("Failed manual ping must retain the session", ConnectionState.CONNECTED, KawaVpnService.status.value.state)
                assertTrue(NativeEngineBridge.running())
                assertEquals(connectedSince, KawaVpnService.status.value.since)
            } finally { shell("svc wifi enable"); shell("svc data enable") }
            val onlineDeadline = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < onlineDeadline && !hasPhysicalNetwork()) Thread.sleep(100)
            assertTrue("QA physical network must return", hasPhysicalNetwork())
            Thread.sleep(1000)
            val failedPingAt = KawaVpnService.status.value.lastPingAt
            context.startService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.PING))
            val recoveryDeadline = SystemClock.elapsedRealtime() + 20000
            while (SystemClock.elapsedRealtime() < recoveryDeadline && (KawaVpnService.status.value.lastPingAt <= failedPingAt || KawaVpnService.status.value.latency == null)) Thread.sleep(100)
            assertTrue("A fresh recovery probe must finish", KawaVpnService.status.value.lastPingAt > failedPingAt)
            assertNotNull("Connectivity should recover after the network returns", KawaVpnService.status.value.latency)
            assertEquals(connectedSince, KawaVpnService.status.value.since)

        } finally {
            context.startService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.STOP))
            val end = SystemClock.elapsedRealtime() + 15000
            while (SystemClock.elapsedRealtime() < end && NativeEngineBridge.running()) Thread.sleep(100)
            assertFalse("Native engine must stop", NativeEngineBridge.running())
            assertEquals(ConnectionState.DISCONNECTED, KawaVpnService.status.value.state)
            store.write(before)
        }
    }
    @Test fun deadProxyWithDirectProbeBypassNeverBecomesProtected() {
        assertNull(VpnService.prepare(context))
        val store = PrivateStore(context); val before = store.read()
        val profile = ProfileParser.parse("""{"remarks":"Dead proxy test","outbounds":[{"tag":"dead","protocol":"vless","settings":{"vnext":[{"address":"127.0.0.1","port":25099,"users":[{"id":"11111111-1111-4111-8111-111111111111","encryption":"none"}]}]}},{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"domain":["www.gstatic.com"],"outboundTag":"direct"}]}}""").single()
        try {
            store.write(AppData(profiles = listOf(SavedProfile(profile, "manual")), selected = profile.id))
            context.startForegroundService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.CONNECT))
            val end = SystemClock.elapsedRealtime() + 45000
            do {
                assertNotEquals(ConnectionState.CONNECTED, KawaVpnService.status.value.state)
                Thread.sleep(100)
            } while (SystemClock.elapsedRealtime() < end && KawaVpnService.status.value.state != ConnectionState.ERROR)
            assertEquals(ConnectionState.ERROR, KawaVpnService.status.value.state)
            assertFalse(NativeEngineBridge.running())
        } finally {
            context.startService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.STOP))
            Thread.sleep(500)
            store.write(before)
        }
    }
    @Test fun queuedConnectIsCancelledByStopWithoutLateProtection() {
        assertNull(VpnService.prepare(context))
        val store = PrivateStore(context); val before = store.read()
        val uuid = InstrumentationRegistry.getArguments().getString("testVlessUuid")
        assertNotNull(uuid)
        val profile = ProfileParser.parse("""{"remarks":"Cancellation test","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"10.0.2.2","port":25080,"users":[{"id":"$uuid","encryption":"none"}]}]}}]}""").single()
        val violated = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
        val observer = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            KawaVpnService.status.collect {
                if (stopRequested.get() && it.state == ConnectionState.DISCONNECTED) stopped.set(true)
                if (stopped.get() && it.state == ConnectionState.CONNECTED) violated.set(true)
            }
        }
        try {
            store.write(AppData(profiles = listOf(SavedProfile(profile, "manual")), selected = profile.id))
            repeat(3) { context.startForegroundService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.CONNECT)) }
            val end = SystemClock.elapsedRealtime() + 10000
            while (SystemClock.elapsedRealtime() < end && KawaVpnService.status.value.state == ConnectionState.DISCONNECTED) Thread.sleep(25)
            stopRequested.set(true)
            context.startService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.STOP))
            Thread.sleep(20000)
            assertFalse("A cancelled queued start must never publish Protected", violated.get())
            assertFalse(NativeEngineBridge.running())
            assertEquals(ConnectionState.DISCONNECTED, KawaVpnService.status.value.state)
        } finally { observer.cancel(); store.write(before) }
    }
}
