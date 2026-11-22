package pro.vinnikov.kawavpn
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pro.vinnikov.kawavpn.data.*
import pro.vinnikov.kawavpn.domain.*

/** Transport fixtures prove account ownership and partial sync; never contact Telegram or payment systems. */
class AccountContractTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val profile=ProfileParser.parse("""{"remarks":"Contract server","outbounds":[{"protocol":"vless","tag":"vpn"}]}""").single()
    @Test fun loginConsumesConfirmationAndLoadsServersWithoutPaymentServices() = runBlocking {
        val store=PrivateStore(context);val before=store.read();val calls=mutableListOf<String>();var polls=0
        try {
            store.write(AppData(profiles=listOf(SavedProfile(profile,"manual"))))
            val repo=KawaRepository(context,{ path,_,_ ->
                calls+=path
                val response=when(path) {
                    "/auth/deeplink/request" -> """{"token":"test-safe-token","bot_username":"your_bot","expires_in":30}"""
                    "/auth/deeplink/poll" -> if(polls++==0) """{"detail":"pending"}""" else """{"access_token":"fixture-access","refresh_token":"fixture-refresh","user":{"id":7}}"""
                    "/subscriptions" -> """{"subscriptions":[{"id":1,"status":"active","subscription_url":"https://example.test/good"}]}"""
                    "/auth/me" -> """{"id":7,"balance_kopeks":7500}"""
                    else -> error("Login must not depend on payment routes: $path")
                }
                (if(path=="/auth/deeplink/poll" && polls==1) 202 else 200) to JsonParser.parseString(response)
            },{listOf(profile.copy(id="managed"))})
            var opened="";repo.login({opened=it},{})
            assertTrue(opened.startsWith("https://t.me/your_bot?start=webauth_"))
            assertEquals(7L,store.read().session!!.userId)
            assertNull(store.read().pendingLogin)
            assertEquals(2,store.read().profiles.size)
            assertEquals(7500L,store.read().cabinet.balanceKopeks)
            assertFalse(calls.any { it.contains("balance/") || it.contains("purchase") })
        } finally { store.write(before) }
    }
    @Test fun oneBrokenSubscriptionKeepsCachedServersAndDoesNotHideWorkingOne() {
        val store=PrivateStore(context);val before=store.read()
        try {
            store.write(AppData(session=AccountSession("a","r",7),profiles=listOf(SavedProfile(profile.copy(id="cached"),"https://example.test/offline",true))))
            val repo=KawaRepository(context,{path,_,_->200 to JsonParser.parseString(if(path=="/auth/me") """{"id":7}""" else """{"subscriptions":[{"id":1,"status":"active","subscription_url":"https://example.test/good"},{"id":2,"status":"active","subscription_url":"https://example.test/offline"}]}""")},{url->if(url.endsWith("offline")) throw java.io.IOException() else listOf(profile.copy(id="fresh"))})
            repo.refreshAccount()
            assertEquals(setOf("cached","fresh"),store.read().profiles.map { it.profile.id }.toSet())
            assertNotNull(store.read().syncWarning)
        } finally { store.write(before) }
    }
}
