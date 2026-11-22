package pro.vinnikov.kawavpn.domain

import org.junit.Assert.*
import org.junit.Test

class SubscriptionContractTest {
    private val valid = """[{"remarks":"Germany","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"example.test","port":443}]}}]}]"""
    @Test fun triesFullJsonBeforeAcceptingProviderWarningLinks() {
        val calls = mutableListOf<String>()
        val result = SubscriptionContract.fetch("https://example.test/token?key=value", { url ->
            calls += url
            if (url.contains("/json")) valid else "vless://notice"
        }, { listOf(com.google.gson.JsonParser.parseString("""{"remarks":"Не поддерживается","outbounds":[{"protocol":"freedom"}]}""").asJsonObject) })
        assertEquals("Germany", result.single().name)
        assertEquals(listOf("https://example.test/token?key=value", "https://example.test/token/json?key=value"), calls)
    }
    @Test fun keepsBaseFullJsonWithoutAdditionalRequest() {
        var calls = 0
        assertEquals(1, SubscriptionContract.fetch("https://example.test/token", { calls++; valid }).size)
        assertEquals(1, calls)
    }
    @Test fun jsonFailureRetainsUsableBaseProfiles() {
        val result = SubscriptionContract.fetch("https://example.test/token", { url -> if (url.endsWith("/json")) error("offline") else "vless://working" },
            { listOf(com.google.gson.JsonParser.parseString(valid).asJsonArray[0].asJsonObject) })
        assertEquals("Germany", result.single().name)
    }
    @Test fun rejectsUnsupportedProviderNoticeInsteadOfExposingConnect() {
        assertThrows(IllegalArgumentException::class.java) { ProfileParser.parse("""[{"remarks":"Не поддерживается","outbounds":[{"protocol":"freedom"}]},{"remarks":"Перейдите в другое приложение","outbounds":[{"protocol":"blackhole"}]}]""") }
    }
}
