package pro.vinnikov.kawavpn.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BillingTest {
    private val purchase = BillingPurchase(1, 30, 399)
    private val id = "a".repeat(32)
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private class Memory : BillingPersistence {
        var pending: PendingCheckout? = null
        var fail = false
        @Synchronized override fun tryCreatePending(value: PendingCheckout): Boolean {
            check(!fail) { "disk unavailable" }
            if (pending != null) return false
            pending = value
            return true
        }
        override fun loadPending() = pending
        override fun savePending(value: PendingCheckout?) { check(!fail); pending = value }
    }
    private data class Call(val method: String, val path: String, val body: JsonObject?)
    private class Transport(private val memory: Memory, vararg val responses: BillingResponse) : BillingTransport {
        val calls = mutableListOf<Call>()
        var failureAt = -1
        override fun execute(method: String, path: String, body: JsonObject?): BillingResponse {
            check(memory.pending != null) { "mutation or recovery ran before durable identity" }
            calls += Call(method, path, body?.deepCopy())
            if (calls.size == failureAt) throw IOException("secret-token-private-url")
            return responses[calls.size - 1]
        }
    }
    private fun coordinator(memory: Memory, transport: BillingTransport) = BillingCoordinator(transport, memory, { id }, { 1000L })
    private fun missing(amount: Long = 500) = BillingResponse(402, json("""{"detail":{"code":"insufficient_funds","missing_amount":$amount,"cart_saved":true}}"""))
    @Test fun fundedPurchaseIsDurableBeforeMutationAndNeverCreatesInvoice() {
        val memory = Memory(); val transport = Transport(memory, BillingResponse(200, json("""{"id":399}""")))
        val result = coordinator(memory, transport).begin(purchase)
        assertTrue(result is BillingResult.PurchaseAccepted)
        assertEquals(1, transport.calls.size)
        assertEquals("POST", transport.calls.single().method)
        assertEquals("/subscription/purchase-tariff", transport.calls.single().path)
        assertEquals(399L, transport.calls.single().body!!["subscription_id"].asLong)
        assertEquals(BillingPhase.PURCHASE_ACCEPTED, memory.pending!!.phase)
    }
    @Test fun noRequestRunsWhenPendingIdentityCannotBeSaved() {
        val memory = Memory().also { it.fail = true }; val transport = Transport(memory)
        assertThrows(Exception::class.java) { coordinator(memory, transport).begin(purchase) }
        assertTrue(transport.calls.isEmpty())
    }
    @Test fun renewalUsesMissingBalanceFloorHostedOptionAndSameCheckoutId() {
        val memory = Memory(); val transport = Transport(memory, missing(), BillingResponse(200, json("""{"payment_url":"https://pay.platega.io/?id=hosted","payment_id":"invoice"}""")))
        val result = coordinator(memory, transport).begin(purchase, "hosted", 1000, 500000) as BillingResult.Invoice
        val body = transport.calls[1].body!!
        assertEquals(1000L, body["amount_kopeks"].asLong)
        assertEquals("platega", body["payment_method"].asString)
        assertEquals("hosted", body["payment_option"].asString)
        assertEquals(id, body["checkout_id"].asString)
        assertEquals("invoice", result.pending.paymentId)
        assertEquals(BillingPhase.INVOICE_PENDING, memory.pending!!.phase)
    }
    @Test fun unsavedCartAndLimitsNeverCreateAnInvoice() {
        for (response in listOf(BillingResponse(402, json("""{"detail":{"code":"insufficient_funds","missing_amount":9000,"cart_saved":false}}""")), missing(9000))) {
            val memory = Memory(); val transport = Transport(memory, response)
            assertThrows(BillingRejected::class.java) { coordinator(memory, transport).begin(purchase, maximumKopeks = 8000) }
            assertEquals(1, transport.calls.size)
            assertEquals(BillingPhase.REJECTED, memory.pending!!.phase)
        }
    }
    @Test fun lostPurchaseResponseIsNeverResentAfterRestartOrMissingOrder() {
        val memory = Memory(); val first = Transport(memory).also { it.failureAt = 1 }
        val error = assertThrows(BillingUncertain::class.java) { coordinator(memory, first).begin(purchase) }
        assertFalse(error.toString().contains("secret"))
        assertEquals(1, first.calls.size)
        val restarted = Transport(memory, BillingResponse(404, JsonObject()))
        assertTrue(coordinator(memory, restarted).recover() is BillingResult.Pending)
        assertEquals("GET", restarted.calls.single().method)
        assertEquals("/balance/app-checkout/$id", restarted.calls.single().path)
        assertThrows(BillingPendingExists::class.java) { coordinator(memory, restarted).begin(purchase) }
        assertEquals(1, restarted.calls.size)
    }
    @Test fun lostTopupResponseRecoversInvoiceAndChecksProviderWithoutNewPurchase() {
        val memory = Memory(); val first = Transport(memory, missing()).also { it.failureAt = 2 }
        assertThrows(BillingUncertain::class.java) { coordinator(memory, first).begin(purchase) }
        assertEquals(BillingPhase.TOPUP_SUBMITTED, memory.pending!!.phase)
        val recovery = Transport(memory,
            BillingResponse(200, json("""{"id":17,"identifier":"invoice","payment_url":"https://pay.platega.io/test","is_paid":false,"is_checkable":true,"status":"PENDING"}""")),
            BillingResponse(200, json("""{"success":true,"payment":{"id":17,"is_paid":true,"status":"CONFIRMED"}}""")))
        val result = coordinator(memory, recovery).recover() as BillingResult.Paid
        assertTrue(result.pending.paid)
        assertEquals("https://pay.platega.io/test", memory.pending!!.paymentUrl)
        assertEquals(listOf("/balance/app-checkout/$id", "/balance/pending-payments/platega/17/check"), recovery.calls.map { it.path })
        assertEquals("POST", recovery.calls[1].method)
    }
    @Test fun paidInvoicesAreNeverCheckedAgain() {
        val memory = Memory().also { it.pending = PendingCheckout(id, purchase, BillingPhase.INVOICE_PENDING) }
        val transport = Transport(memory, BillingResponse(200, json("""{"id":17,"is_paid":true,"is_checkable":true,"status":"CONFIRMED"}""")))
        assertTrue(coordinator(memory, transport).recover() is BillingResult.Paid)
        assertEquals(1, transport.calls.size)
    }
    @Test fun unauthorizedMutationIsRejectedWithoutRefreshOrRetry() {
        val memory = Memory(); val transport = Transport(memory, BillingResponse(401, json("""{"detail":"secret"}""")))
        val error = assertThrows(BillingRejected::class.java) { coordinator(memory, transport).begin(purchase) }
        assertEquals(1, transport.calls.size)
        assertFalse(error.toString().contains("secret"))
    }
    @Test fun unsafePaymentUrlNeverLeavesCoordinator() {
        for (url in listOf("http://pay.platega.io/a", "https://user:pass@pay.platega.io/a", "javascript:alert(1)", "https:///missing-host", "https://localhost/a", "https://127.0.0.1/a")) {
            val memory = Memory(); val transport = Transport(memory, missing(), BillingResponse(200, JsonObject().apply { addProperty("payment_url", url) }))
            assertThrows(BillingUncertain::class.java) { coordinator(memory, transport).begin(purchase) }
            assertNull(memory.pending!!.paymentUrl)
        }
    }
    @Test fun invalidIdentityOrPurchaseNeverReachesNetwork() {
        val memory = Memory(); val transport = Transport(memory)
        for (bad in listOf("../bad", "A".repeat(32), "a".repeat(31))) {
            assertThrows(IllegalArgumentException::class.java) { BillingCoordinator(transport, memory, { bad }).begin(purchase) }
        }
        assertThrows(IllegalArgumentException::class.java) { coordinator(memory, transport).begin(BillingPurchase(0, 30)) }
        assertTrue(transport.calls.isEmpty()); assertNull(memory.pending)
    }
    @Test fun providerCheckFailureIsNotAutomaticallyRetriedAndKeepsRecoveredInvoice() {
        val memory = Memory().also { it.pending = PendingCheckout(id, purchase, BillingPhase.TOPUP_SUBMITTED) }
        val transport = Transport(memory, BillingResponse(200, json("""{"id":17,"identifier":"invoice","is_paid":false,"is_checkable":true}"""))).also { it.failureAt = 2 }
        assertThrows(BillingUncertain::class.java) { coordinator(memory, transport).recover() }
        assertEquals(2, transport.calls.size)
        assertEquals("invoice", memory.pending!!.paymentId)
    }
    @Test fun paidMeansAwaitingFulfillmentUntilSubscriptionActuallyChanges() {
        val baseline = listOf(BillingSubscription(399, 1, "active", "2026-11-01"))
        val memory = Memory().also { it.pending = PendingCheckout(id, purchase, BillingPhase.PAYMENT_CONFIRMED, paid = true, baseline = baseline) }
        val billing = coordinator(memory, Transport(memory))
        assertFalse(billing.reconcile(baseline))
        assertNotNull(memory.pending)
        assertTrue(billing.reconcile(listOf(BillingSubscription(399, 1, "active", "2026-12-01"))))
        assertNull(memory.pending)
    }
}
