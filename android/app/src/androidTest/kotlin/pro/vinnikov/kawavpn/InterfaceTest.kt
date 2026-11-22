package pro.vinnikov.kawavpn

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import java.io.File

class InterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun mainNavigationImportAndSettingsHaveUsableStates() {
        compose.onNodeWithContentDescription("Мой Kawa VPN").assertIsDisplayed()
        compose.onNodeWithText("Подключиться").assertIsNotEnabled()
        screenshot("home")
        val time = compose.onNodeWithText("ВРЕМЯ").fetchSemanticsNode().boundsInRoot
        val speed = compose.onNodeWithText("СКОРОСТЬ").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals("Metric headings must share a baseline", time.top, speed.top, 1f)
        compose.onNodeWithText("Проверить соединение").assertDoesNotExist()
        val timeCard=compose.onNodeWithTag("session-time").fetchSemanticsNode().boundsInRoot
        val speedCard=compose.onNodeWithTag("session-speed").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertEquals(timeCard.height,speedCard.height,1f)
        compose.onNodeWithContentDescription("Проверить соединение").assertIsDisplayed()
        compose.onNodeWithText("Для AI").performClick()
        compose.onNodeWithText("AI").assertExists()
        compose.onNodeWithText("Главная").performClick()
        compose.onNodeWithTag("session-time").performScrollTo()
        screenshot("home-metrics")
        compose.onNodeWithText("Серверы").performClick()
        compose.onNodeWithText("Добавить").assertIsDisplayed()
        screenshot("servers")
        compose.onNodeWithText("Добавить").performClick()
        compose.onNodeWithText("Добавить подписку").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Войти через бота").assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithText("Статистика").performClick()
        compose.onNodeWithText("Получено").assertIsDisplayed()
        screenshot("statistics")
    }
    @Test fun importConnectReconnectStatisticsAndRoutingUseRealRuntime() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val store=pro.vinnikov.kawavpn.data.PrivateStore(context);val before=store.read()
        val uuid=InstrumentationRegistry.getArguments().getString("testVlessUuid") ?: error("Isolated QA transport is required")
        try {
            compose.onNodeWithText("Серверы").performClick()
            compose.onNodeWithText("Добавить").performClick()
            val json="""{"remarks":"Germany QA","outbounds":[{"tag":"vpn","protocol":"vless","settings":{"vnext":[{"address":"10.0.2.2","port":25080,"users":[{"id":"$uuid","encryption":"none"}]}]},"streamSettings":{"network":"tcp","security":"none"}}]}"""
            compose.onNodeWithText("Ссылка подписки, ключ или JSON").performTextInput(json)
            compose.onAllNodesWithText("Добавить").onLast().performClick()
            compose.waitUntil(10000) { store.read().profiles.any { it.profile.name=="Germany QA" } }
            compose.onNodeWithText("Germany QA").assertIsDisplayed()
            screenshot("servers-imported")
            compose.onNodeWithText("Подключиться").performClick()
            compose.waitUntil(60000) { pro.vinnikov.kawavpn.vpn.KawaVpnService.status.value.state==pro.vinnikov.kawavpn.domain.ConnectionState.CONNECTED }
            compose.waitUntil(12000) { pro.vinnikov.kawavpn.vpn.KawaVpnService.status.value.received>0 }
            compose.onNodeWithText("ЗАЩИЩЕНО",substring=true).assertIsDisplayed()
            screenshot("home-connected")
            compose.onNodeWithTag("session-time").performScrollTo()
            screenshot("home-connected-metrics")
            compose.onNodeWithText("Статистика").performClick()
            compose.onNodeWithText("Получено").assertIsDisplayed()
            compose.onNodeWithText("Отправлено").assertIsDisplayed()
            screenshot("statistics-connected")
            val oldSince=pro.vinnikov.kawavpn.vpn.KawaVpnService.status.value.since
            compose.onNodeWithText("Серверы").performClick()
            compose.onNodeWithText("Подключиться").performClick()
            compose.waitUntil(60000) { pro.vinnikov.kawavpn.vpn.KawaVpnService.status.value.let { it.state==pro.vinnikov.kawavpn.domain.ConnectionState.CONNECTED && it.since>oldSince } }
            compose.onNodeWithText("Отключиться").performScrollTo().performClick()
            compose.waitUntil(15000) { pro.vinnikov.kawavpn.vpn.KawaVpnService.status.value.state==pro.vinnikov.kawavpn.domain.ConnectionState.DISCONNECTED }
            compose.onNodeWithText("Настройки").performClick()
            compose.onNodeWithText("Правила доменов и IP",substring=true).performScrollTo().performClick()
            compose.onNodeWithText("example.com или geosite:…").performTextInput("example.test")
            compose.onNodeWithText("Напрямую").performClick()
            compose.onNodeWithText("Добавить правило").performClick()
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(10000) { store.read().routing.rules.any { it.value=="example.test" && it.target=="direct" } }
            compose.onNodeWithText("Правила доменов и IP",substring=true).performScrollTo().performClick()
            compose.onNodeWithText("example.test").assertIsDisplayed()
            screenshot("routing-editor")
            compose.onNodeWithText("Отмена").performClick()
        } finally {
            context.startService(android.content.Intent(context,pro.vinnikov.kawavpn.vpn.KawaVpnService::class.java).setAction(pro.vinnikov.kawavpn.vpn.KawaVpnService.STOP))
            val end=android.os.SystemClock.elapsedRealtime()+15000
            while(android.os.SystemClock.elapsedRealtime()<end && pro.vinnikov.kawavpn.vpn.NativeEngineBridge.running()) Thread.sleep(100)
            store.write(before)
        }
    }
}
