package pro.vinnikov.kawavpn

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pro.vinnikov.kawavpn.data.*
import pro.vinnikov.kawavpn.domain.*
import pro.vinnikov.kawavpn.vpn.*

/** Isolated QA: local VLESS server on the host plus optional real configs passed as arguments. */
@RunWith(AndroidJUnit4::class)
class ResilienceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(name: String): String? = args.getString(name)?.let { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }
    private fun state() = KawaVpnService.status.value.state
    private fun waitFor(ms: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return true; Thread.sleep(200) }
        return condition()
    }
    private fun connect(profile: VpnProfile) {
        PrivateStore(context).write(AppData(profiles = listOf(SavedProfile(profile, "manual")), selected = profile.id))
        context.startForegroundService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.CONNECT))
    }
    private fun stop() {
        context.startService(Intent(context, KawaVpnService::class.java).setAction(KawaVpnService.STOP))
        waitFor(15000) { !NativeEngineBridge.running() && state() == ConnectionState.DISCONNECTED }
    }

    @Test fun deadEngineIsRebuiltAutomaticallyInsteadOfShowingAnError() {
        val before = PrivateStore(context).read()
        val uuid = args.getString("testVlessUuid") ?: error("Isolated QA transport is required")
        val profile = ProfileParser.parse("""{"remarks":"Resilience QA","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"10.0.2.2","port":25080,"users":[{"id":"$uuid","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}]}""").single()
        try {
            connect(profile)
            assertTrue("first connect", waitFor(60000) { state() == ConnectionState.CONNECTED })
            val firstSince = KawaVpnService.status.value.since
            // Simulate the engine dying under the user (OOM kill of the Go runtime, crash).
            NativeEngineBridge.invoke("stopXray")
            assertTrue("reconnected by itself", waitFor(45000) { KawaVpnService.status.value.let { it.state == ConnectionState.CONNECTED && it.since > firstSince } })
            assertNull(KawaVpnService.status.value.error)
            assertTrue(NativeEngineBridge.running())
        } finally { stop(); PrivateStore(context).write(before) }
    }

    @Test fun expiredSubscriptionShowsRenewNoticeNotDeadServers() {
        val store = PrivateStore(context); val before = store.read()
        val profiles = ProfileParser.parse("""[
            {"remarks":"⌛ Подписка истекла ","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"0.0.0.0","port":1,"users":[{"id":"00000000-0000-0000-0000-000000000000"}]}]}}]},
            {"remarks":"Продлите подписку в боте","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"0.0.0.0","port":1,"users":[{"id":"00000000-0000-0000-0000-000000000000"}]}]}}]},
            {"remarks":"🇪🇺 Резерв · открытые сервера","outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"127.0.0.1","port":9,"users":[{"id":"x"}]}]}},{"protocol":"freedom","tag":"direct"}]}
        ]""")
        try {
            store.write(AppData(profiles = profiles.map { SavedProfile(it, "https://sub.example.invalid/x") }, selected = profiles[0].id, autoRefresh = false))
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(10000) { compose.onAllNodesWithText("Продлить подписку").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("⌛ Подписка истекла", substring = true).assertIsDisplayed()
                compose.onNodeWithText("Серверы").performClick()
                compose.waitUntil(5000) { compose.onAllNodesWithText("🇪🇺 Резерв · открытые сервера").fetchSemanticsNodes().isNotEmpty() }
                // The two placeholders are one message, not two rows with a connect button.
                assertEquals(1, compose.onAllNodesWithText("Продлите подписку в боте").fetchSemanticsNodes().size)
                compose.onNodeWithText("1 в списке").assertIsDisplayed()
            }
        } finally { store.write(before) }
    }

    /** Real configs (reserve over open servers, Hysteria2 + Salamander) through the Android engine. */
    @Test fun realReserveAndSalamanderConfigsConnectThroughTheAndroidEngine() {
        val cases = listOfNotNull(arg("reserveJson")?.let { "reserve" to it }, arg("salamanderJson")?.let { "salamander" to it })
        Assume.assumeTrue("real configs not supplied", cases.isNotEmpty())
        val before = PrivateStore(context).read()
        try {
            for ((name, json) in cases) {
                val profile = ProfileParser.parse(json).first { !ProfileNotice.of(it) }
                val tags = ProfileProbeTargets.tags(profile.configJson)
                val probes = (if (tags.isEmpty()) listOf(ProbeConfig(profile.configJson)) else tags.take(5).map { ProbeConfig(profile.configJson, it) })
                val delays = kotlinx.coroutines.runBlocking { NativeProbeClient.ping(context, probes) }
                assertTrue("$name: no candidate answered the real protocol probe: $delays", delays.any { it != null })
                connect(profile)
                assertTrue("$name: tunnel did not come up (${KawaVpnService.status.value.error})", waitFor(90000) { state() == ConnectionState.CONNECTED })
                assertNotNull("$name: health check latency", KawaVpnService.status.value.latency)
                stop()
            }
        } finally { stop(); PrivateStore(context).write(before) }
    }

    /** Server lists are pinged in batches of five: every batch must work, not only the first. */
    @Test fun consecutiveProbeBatchesAllWork() {
        val uuid = args.getString("testVlessUuid") ?: error("Isolated QA transport is required")
        val json = """{"outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"10.0.2.2","port":25080,"users":[{"id":"$uuid","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}]}"""
        val results = (1..3).map { kotlinx.coroutines.runBlocking { NativeProbeClient.ping(context, listOf(ProbeConfig(json))) }.single() }
        assertTrue("batch results: $results", results.all { it != null })
    }
}
