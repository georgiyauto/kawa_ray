package pro.vinnikov.kawavpn.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import pro.vinnikov.kawavpn.domain.BillingResponse
import pro.vinnikov.kawavpn.domain.BillingTransport
import pro.vinnikov.kawavpn.domain.BillingUncertain
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The account owner refreshes and durably stores tokens BEFORE begin(). This transport
 * deliberately never refreshes/replays a billing request in response to a 401.
 */
class BillingClient(private val accessToken: () -> String) : BillingTransport {
    private class AttemptBudget(val consumed: AtomicBoolean = AtomicBoolean(false))
    private val http = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            // OkHttp may follow 503 Retry-After:0 even with connection retries off.
            // Retried exchanges preserve the request tag; reject them before any HTTP write.
            val budget = chain.request().tag(AttemptBudget::class.java) ?: throw IOException("Missing attempt budget")
            if (!budget.consumed.compareAndSet(false, true)) throw IOException("Billing retry forbidden")
            chain.proceed(chain.request())
        }
        .build()

    override fun execute(method: String, path: String, body: JsonObject?): BillingResponse {
        require((method == "POST" && (path == "/subscription/purchase-tariff" || path == "/balance/topup" || path.matches(Regex("/balance/pending-payments/platega/[1-9][0-9]*/check")))) ||
            (method == "GET" && path.matches(Regex("/balance/app-checkout/[a-f0-9]{32}")))) { "Unsupported billing endpoint" }
        try {
            val token = accessToken()
            check(token.isNotBlank())
            val request = Request.Builder()
                .url("https://cabinet.example.com/api/cabinet$path")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .tag(AttemptBudget::class.java, AttemptBudget())
            if (method == "POST") {
                val bytes = (body ?: JsonObject()).toString().toByteArray(Charsets.UTF_8)
                request.post(object : RequestBody() {
                    override fun contentType() = "application/json".toMediaType()
                    override fun contentLength() = bytes.size.toLong()
                    override fun isOneShot() = true
                    override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
                })
            }
            return http.newCall(request.build()).execute().use { response ->
                val output = ByteArrayOutputStream()
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val size = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES + 1 - output.size()))
                        if (size < 0) break
                        output.write(buffer, 0, size)
                        check(output.size() <= MAX_RESPONSE_BYTES)
                    }
                }
                val text = output.toString(Charsets.UTF_8.name())
                val value = if (text.isBlank()) JsonObject() else JsonParser.parseString(text).asJsonObject
                BillingResponse(response.code, value)
            }
        } catch (_: Exception) {
            // Native, HTTP and JSON exception messages can contain authorization or payment URLs.
            throw BillingUncertain()
        }
    }

    private companion object { const val MAX_RESPONSE_BYTES = 256 * 1024 }
}
