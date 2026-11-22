package pro.vinnikov.kawavpn.domain

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.net.URI
import java.util.UUID

/** Wire names are mapped explicitly, rather than relying on Gson's field naming. */
data class BillingPurchase(val tariffId: Long, val periodDays: Long, val subscriptionId: Long? = null)
data class BillingSubscription(val id: Long, val tariffId: Long?, val status: String, val endDate: String?)
enum class BillingPhase {
    PURCHASE_SUBMITTED, TOPUP_SUBMITTED, INVOICE_PENDING, PAYMENT_CONFIRMED,
    PURCHASE_ACCEPTED, REJECTED, PAYMENT_CANCELLED
}

/** Keep in encrypted storage, scoped to the authenticated cabinet owner. */
data class PendingCheckout(
    val checkoutId: String,
    val purchase: BillingPurchase,
    val phase: BillingPhase,
    val startedAtMillis: Long = 0,
    val paymentUrl: String? = null,
    val paymentId: String? = null,
    val localPaymentId: Long? = null,
    val amountKopeks: Long? = null,
    val paid: Boolean = false,
    val baseline: List<BillingSubscription> = emptyList()
)
data class BillingResponse(val status: Int, val body: JsonObject)

/** One HTTP attempt per call. Must not follow redirects, authenticate or retry automatically. */
fun interface BillingTransport {
    fun execute(method: String, path: String, body: JsonObject?): BillingResponse
}

interface BillingPersistence {
    fun loadPending(): PendingCheckout?
    /** Atomically commit to durable storage only if no pending checkout exists. */
    fun tryCreatePending(value: PendingCheckout): Boolean
    /** Return only after durable commit; throw on any persistence failure. */
    fun savePending(value: PendingCheckout?)
}
class BillingRejected : IllegalStateException("Заказ отклонён. Обновите кабинет.")
class BillingUncertain : IllegalStateException("Проверьте сохранённый заказ. Не создавайте оплату повторно.")
class BillingPendingExists : IllegalStateException("Сначала проверьте сохранённый заказ.")
class BillingAuthenticationRequired : IllegalStateException("Обновите сессию перед проверкой заказа.")

sealed interface BillingResult {
    data class PurchaseAccepted(val pending: PendingCheckout, val response: JsonObject) : BillingResult
    data class Invoice(val pending: PendingCheckout) : BillingResult
    data class Pending(val pending: PendingCheckout) : BillingResult
    /** A confirmed payment still needs subscription reconciliation, never another purchase. */
    data class Paid(val pending: PendingCheckout) : BillingResult
    data class Cancelled(val pending: PendingCheckout) : BillingResult
}

object BillingPaymentUrl {
    fun validate(value: String): String {
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Неверная ссылка оплаты") }
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme.equals("https", true) && host.isNotBlank() && uri.rawUserInfo == null) { "Нужна HTTPS-ссылка оплаты" }
        require(host != "localhost" && !host.endsWith(".localhost") && !host.startsWith("127.") && host != "[::1]" && host != "0.0.0.0") { "Неверный адрес оплаты" }
        return uri.toASCIIString()
    }
}

/** A serial account owner coordinates refresh/persistence before calling begin.
 * Mutations are never replayed, including after a 401, timeout, process death or 404 recovery.
 * Reconstruct this coordinator after restart and call recover(), not begin().
 */
class BillingCoordinator(
    private val transport: BillingTransport,
    private val persistence: BillingPersistence,
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "") },
    private val now: () -> Long = System::currentTimeMillis
) {
    @Synchronized fun begin(
        purchase: BillingPurchase,
        option: String = "hosted",
        minimumKopeks: Long = 1000,
        maximumKopeks: Long? = null,
        baseline: List<BillingSubscription> = emptyList()
    ): BillingResult {
        require(purchase.tariffId > 0 && purchase.periodDays > 0 && (purchase.subscriptionId == null || purchase.subscriptionId > 0))
        require(option.isNotBlank() && option.length <= 128 && minimumKopeks >= 0 && (maximumKopeks == null || maximumKopeks >= 0))
        val id = newId().also(::validateId)
        var pending = PendingCheckout(id, purchase, BillingPhase.PURCHASE_SUBMITTED, now(), baseline = baseline.toList())
        if (!persistence.tryCreatePending(pending)) throw BillingPendingExists()
        val response = request("POST", "/subscription/purchase-tariff", JsonObject().apply {
            addProperty("tariff_id", purchase.tariffId)
            addProperty("period_days", purchase.periodDays)
            purchase.subscriptionId?.let { addProperty("subscription_id", it) }
        })
        if (response.status == 200 || response.status == 201) {
            pending = pending.copy(phase = BillingPhase.PURCHASE_ACCEPTED)
            persist(pending)
            return BillingResult.PurchaseAccepted(pending, response.body.deepCopy())
        }
        val detail = response.body["detail"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        if (response.status != 402 || detail.text("code") != "insufficient_funds" || !detail.flag("cart_saved")) {
            if (response.status in setOf(400, 401, 403, 404, 409, 422) ||
                (response.status == 402 && !detail.flag("cart_saved")) || detail.text("code") == "cart_unavailable") reject(pending)
            throw BillingUncertain()
        }
        val missing = detail.number("missing_amount")?.takeIf { it > 0 } ?: throw BillingUncertain()
        val amount = maxOf(missing, minimumKopeks, 1000L)
        if (maximumKopeks != null && amount > maximumKopeks) reject(pending)
        pending = pending.copy(phase = BillingPhase.TOPUP_SUBMITTED, amountKopeks = amount)
        persist(pending)
        val invoice = request("POST", "/balance/topup", JsonObject().apply {
            addProperty("amount_kopeks", amount)
            addProperty("payment_method", "platega")
            addProperty("payment_option", option)
            addProperty("checkout_id", id)
        })
        if (invoice.status in setOf(400, 401, 403, 404, 422, 429)) reject(pending)
        if (invoice.status !in 200..299) throw BillingUncertain()
        val url = validatedUrl(invoice.body.text("payment_url") ?: throw BillingUncertain())
        pending = pending.copy(phase = BillingPhase.INVOICE_PENDING, paymentUrl = url, paymentId = invoice.body.identifier("payment_id"))
        persist(pending)
        return BillingResult.Invoice(pending)
    }

    @Synchronized fun recover(): BillingResult? {
        var pending = persistence.loadPending() ?: return null
        validateId(pending.checkoutId)
        if (pending.phase == BillingPhase.REJECTED) return BillingResult.Cancelled(pending)
        val order = request("GET", "/balance/app-checkout/${pending.checkoutId}")
        if (order.status == 404) return BillingResult.Pending(pending)
        requireReadSuccess(order)
        pending = mergePayment(pending, order.body)
        // Persist recovered identity before a provider check can fail or the process can die.
        persist(pending)
        if (!pending.paid && order.body.flag("is_checkable")) {
            val localId = pending.localPaymentId?.takeIf { it > 0 } ?: throw BillingUncertain()
            val checked = request("POST", "/balance/pending-payments/platega/$localId/check", JsonObject())
            requireReadSuccess(checked)
            checked.body["payment"]?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                pending = mergePayment(pending, it)
                persist(pending)
            }
        }
        return when {
            pending.paid -> BillingResult.Paid(pending)
            pending.phase == BillingPhase.PAYMENT_CANCELLED -> BillingResult.Cancelled(pending)
            pending.paymentUrl != null -> BillingResult.Invoice(pending)
            else -> BillingResult.Pending(pending)
        }
    }

    /** Payment confirmation alone is insufficient: wait for actual issue or renewal. */
    @Synchronized fun reconcile(subscriptions: List<BillingSubscription>): Boolean {
        val pending = persistence.loadPending() ?: return false
        if (pending.phase in setOf(BillingPhase.REJECTED, BillingPhase.PAYMENT_CANCELLED)) return false
        if ((pending.paymentId != null || pending.phase in setOf(BillingPhase.TOPUP_SUBMITTED, BillingPhase.INVOICE_PENDING)) && !pending.paid) return false
        val fulfilled = subscriptions.any { subscription ->
            subscription.tariffId == pending.purchase.tariffId &&
                (pending.purchase.subscriptionId == null || subscription.id == pending.purchase.subscriptionId) &&
                subscription.status.equals("active", true) &&
                pending.baseline.firstOrNull { it.id == subscription.id }.let { previous ->
                    previous == null || (subscription.endDate != null && (previous.endDate == null || subscription.endDate > previous.endDate))
                }
        }
        if (fulfilled) persistence.savePending(null)
        return fulfilled
    }

    private fun request(method: String, path: String, body: JsonObject? = null): BillingResponse =
        try { transport.execute(method, path, body) } catch (_: Exception) { throw BillingUncertain() }

    private fun persist(pending: PendingCheckout) {
        try { persistence.savePending(pending) } catch (_: Exception) { throw BillingUncertain() }
    }
    private fun reject(pending: PendingCheckout): Nothing {
        persist(pending.copy(phase = BillingPhase.REJECTED))
        throw BillingRejected()
    }
    private fun requireReadSuccess(response: BillingResponse) {
        if (response.status == 401) throw BillingAuthenticationRequired()
        if (response.status !in 200..299) throw BillingUncertain()
    }
    private fun validatedUrl(value: String): String =
        try { BillingPaymentUrl.validate(value) } catch (_: IllegalArgumentException) { throw BillingUncertain() }

    private fun mergePayment(pending: PendingCheckout, payment: JsonObject): PendingCheckout {
        val paid = pending.paid || payment.flag("is_paid")
        val cancelled = !paid && payment.text("status") in setOf("FAILED", "CANCELED", "EXPIRED")
        val url = payment.text("payment_url")?.let(::validatedUrl) ?: pending.paymentUrl
        return pending.copy(
            phase = when { paid -> BillingPhase.PAYMENT_CONFIRMED; cancelled -> BillingPhase.PAYMENT_CANCELLED; else -> BillingPhase.INVOICE_PENDING },
            paymentUrl = url,
            paymentId = payment.identifier("identifier") ?: pending.paymentId,
            localPaymentId = payment.number("id")?.takeIf { it > 0 } ?: pending.localPaymentId,
            paid = paid
        )
    }
    private fun validateId(value: String) { require(value.matches(Regex("[a-f0-9]{32}"))) { "Неверный ID заказа" } }
    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    private fun JsonObject.flag(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
    private fun JsonObject.number(key: String): Long? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let {
        runCatching { it.asBigDecimal.toBigIntegerExact().longValueExact() }.getOrNull()
    }
    private fun JsonObject.identifier(key: String): String? = text(key) ?: number(key)?.toString()
}
