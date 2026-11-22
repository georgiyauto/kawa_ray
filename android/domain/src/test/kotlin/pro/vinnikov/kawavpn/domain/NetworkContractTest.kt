package pro.vinnikov.kawavpn.domain
import org.junit.Assert.*
import org.junit.Test

class NetworkContractTest {
    @Test fun subscriptionRequiresHttpsWithoutCredentialsOrLocalHost() {
        assertEquals("https://sub.example.test/token", NetworkContract.subscriptionUrl("https://sub.example.test/token"))
        for (url in listOf("http://sub.example/token", "https://user:secret@example.test/x", "https://localhost/x", "https://127.0.0.1/x", "file:///data/private")) {
            assertThrows(IllegalArgumentException::class.java) { NetworkContract.subscriptionUrl(url) }
        }
    }
    @Test fun botRequestMustMatchKnownBotAndSafeToken() {
        assertEquals("https://t.me/your_bot?start=webauth_abc-123", NetworkContract.botUrl("your_bot", "abc-123"))
        assertThrows(IllegalArgumentException::class.java) { NetworkContract.botUrl("foreign_bot", "abc") }
        assertThrows(IllegalArgumentException::class.java) { NetworkContract.botUrl("your_bot", "a&other=x") }
    }
    @Test fun nativeResponseFailureDoesNotExposeServerSecrets() {
        assertThrows(IllegalStateException::class.java) { NativeContract.data("""{"success":false,"error":"password=secret"}""") }
        try { NativeContract.data("""{"success":false,"error":"password=secret"}""") } catch (e: Exception) { assertFalse(e.message.orEmpty().contains("secret")) }
        assertTrue(NativeContract.data("""{"success":true,"data":{"running":true}}""").asJsonObject["running"].asBoolean)
    }
}
