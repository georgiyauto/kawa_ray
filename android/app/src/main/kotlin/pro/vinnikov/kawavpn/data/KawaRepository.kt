package pro.vinnikov.kawavpn.data

import android.content.Context
import com.google.gson.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import pro.vinnikov.kawavpn.domain.*
import pro.vinnikov.kawavpn.vpn.NativeEngineBridge
import pro.vinnikov.kawavpn.account.BillingClient
import java.util.concurrent.TimeUnit
import java.io.InputStream
import java.io.ByteArrayOutputStream

class ApiUnavailable(val status: Int) : IllegalStateException()
class LoginExpired : IllegalStateException("Войдите через бота повторно")
class KawaRepository(context: Context,
    private val exchange: ((String,String?,JsonObject?) -> Pair<Int,JsonElement>)? = null,
    private val fetchProfiles: ((String) -> List<VpnProfile>)? = null) {
    companion object { private val accountLock = Any() }
    val store = PrivateStore(context)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).build()
    private val base = "https://cabinet.example.com/api/cabinet"
    private val connectivity = context.applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
    /** While the tunnel is still coming up (or has failed) every app socket goes into it and dies with a DNS error, so cabinet
     *  and subscription requests use the phone's own network until the VPN is really connected. */
    private fun client(): OkHttpClient {
        val state = pro.vinnikov.kawavpn.vpn.KawaVpnService.status.value.state
        if (state == ConnectionState.CONNECTED || state == ConnectionState.DISCONNECTED) return http
        val network = connectivity?.let { pro.vinnikov.kawavpn.vpn.underlyingNetwork(it) } ?: return http
        return http.newBuilder().socketFactory(network.socketFactory).dns(Dns { name -> network.getAllByName(name).toList() }).build()
    }
    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= limit) { "Слишком большой ответ сервера" }
        }
        return output.toByteArray()
    }
    private fun request(path: String, token: String? = null, body: JsonObject? = null): Pair<Int, JsonElement> {
        exchange?.let { val result=it(path,token,body);checkCabinetStatus(result.first);return result }
        val builder = Request.Builder().url(base + path).header("Accept", "application/json").header("User-Agent","KawaVPN/${pro.vinnikov.kawavpn.BuildConfig.VERSION_NAME}")
        token?.let { builder.header("Authorization", "Bearer $it") }
        body?.let {
            val delegate = it.toString().toRequestBody("application/json".toMediaType())
            builder.post(object : RequestBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
            })
        }
        return client().newCall(builder.build()).execute().use { response ->
            checkCabinetStatus(response.code)
            val raw = response.body?.byteStream()?.let { bounded(it, 256 * 1024) } ?: byteArrayOf()
            require(raw.size <= 256 * 1024) { "Слишком большой ответ кабинета" }
            response.code to if (raw.isEmpty()) JsonObject() else JsonParser.parseString(String(raw, Charsets.UTF_8))
        }
    }
    private fun json(key: String, value: String) = JsonObject().apply { addProperty(key, value) }
    suspend fun login(openBot: (String) -> Unit, waiting: (String) -> Unit) {
        val stored = store.read().pendingLogin
        val pending = stored?.takeIf { it.expiresAt > System.currentTimeMillis() } ?: run {
            val login = request("/auth/deeplink/request", body = JsonObject()).second.asJsonObject
            currentCoroutineContext().ensureActive()
            val token = login["token"].asString
            val link = NetworkContract.botUrl(login["bot_username"].asString, token)
            val seconds = login["expires_in"]?.asInt?.coerceIn(1, 300) ?: 180
            val job=currentCoroutineContext()[kotlinx.coroutines.Job]
            PendingLogin(token, link, System.currentTimeMillis() + seconds * 1000L).also { challenge -> store.update { job?.ensureActive();it.copy(pendingLogin = challenge) } }
        }
        openBot(pending.url)
        while (System.currentTimeMillis() < pending.expiresAt) {
            waiting("Подтвердите вход в @your_bot, затем вернитесь в Kawa VPN.")
            delay(2500)
            val result = try { request("/auth/deeplink/poll", body = json("token", pending.token)) }
            catch (_: java.io.IOException) { waiting("Не удалось проверить подтверждение. Повторяем при восстановлении сети…"); delay(2500); continue }
            catch (e: ApiUnavailable) {
                if(e.status == 410) break
                if(e.status == 429 || e.status >= 500) { waiting("Кабинет временно недоступен. Повторяем проверку…"); delay(5000); continue }
                throw e
            }
            currentCoroutineContext().ensureActive()
            if (result.first == 200) {
                val job=currentCoroutineContext()[kotlinx.coroutines.Job]
                synchronized(accountLock) { job?.ensureActive();saveSession(result.second.asJsonObject,loginToken=pending.token,active={job?.isActive!=false}) }
                waiting("")
                refreshAccount()
                return
            }
        }
        store.update { it.copy(pendingLogin = null) }
        throw LoginExpired()
    }
    private fun saveSession(body: JsonObject, expectedOwner: Long? = null, loginToken:String?=null, active:()->Boolean={true}): AccountSession {
        val access = body["access_token"]?.asString.orEmpty()
        val refresh = body["refresh_token"]?.asString.orEmpty()
        check(access.isNotBlank() && refresh.isNotBlank()) { "Неверный ответ кабинета" }
        val user = body["user"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("id")?.asLong ?: expectedOwner
        check(user != null && user > 0 && (expectedOwner == null || user == expectedOwner))
        return AccountSession(access, refresh, user).also { session -> store.update { old ->
            if(loginToken!=null) check(active() && old.pendingLogin?.token==loginToken) { "Запрос входа отменён" }
            val sameOwner = old.session?.userId == user
            val profiles = if (sameOwner) old.profiles else old.profiles.filterNot { it.managed }
            old.copy(session = session, pendingLogin=if(loginToken!=null) null else old.pendingLogin, cabinet = if (sameOwner) old.cabinet else CabinetData(), profiles = profiles,
                selected = old.selected?.takeIf { id -> profiles.any { it.profile.id == id && !ProfileNotice.of(it.profile) } } ?: profiles.firstOrNull { !ProfileNotice.of(it.profile) }?.profile?.id)
        } }
    }
    fun refreshAccount(includeBilling: Boolean = false) = synchronized(accountLock) {
        var session = store.read().session ?: throw LoginExpired()
        val body = try { request("/subscriptions", session.access_token).second.asJsonObject } catch (_: LoginExpired) {
            session = saveSession(request("/auth/refresh", body = json("refresh_token", session.refresh_token)).second.asJsonObject, session.userId)
            request("/subscriptions", session.access_token).second.asJsonObject
        }
        val user = request("/auth/me", session.access_token).second.asJsonObject
        val owner = user["id"].asLong
        check(session.userId == owner) { "Неверный владелец сессии" }
        val previous=store.read().cabinet
        val offers = if(includeBilling) request("/subscription/purchase-options", session.access_token).second.asJsonObject else JsonObject()
        val methods = if(includeBilling) request("/balance/payment-methods", session.access_token).second.asJsonArray else JsonArray()
        val payment = methods.firstOrNull { it.asJsonObject["id"]?.asString == "platega" }?.asJsonObject
        val gson = Gson()
        val cabinet = CabinetData(
            subscriptions = gson.fromJson(body["subscriptions"], Array<CabinetSubscription>::class.java)?.toList().orEmpty(),
            tariffs = if(includeBilling) gson.fromJson(offers["tariffs"], Array<CabinetTariff>::class.java)?.toList().orEmpty() else previous.tariffs,
            paymentOptions = if(!includeBilling) previous.paymentOptions else payment?.get("options")?.takeIf { it.isJsonArray }?.let { gson.fromJson(it, Array<CabinetPaymentOption>::class.java).toList() }.orEmpty(),
            minPayment = payment?.get("min_amount_kopeks")?.asLong ?: previous.minPayment,
            maxPayment = payment?.get("max_amount_kopeks")?.takeIf { !it.isJsonNull }?.asLong ?: previous.maxPayment,
            balanceKopeks = user["balance_kopeks"]?.asLong ?: 0
        )
        val urls = body["subscriptions"]?.asJsonArray?.toList().orEmpty().mapNotNull { element ->
            element.asJsonObject["subscription_url"]?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        }.distinct()
        // One expired/unreachable subscription must not hide other active subscriptions.
        val cached=store.read().profiles.filter { it.managed }
        var failures=0
        val profiles = urls.flatMap { url ->
            runCatching { download(url).map { SavedProfile(it,url,true) } }.getOrElse { failures++;cached.filter { it.source==url } }
        }.distinctBy { it.profile.id }
        pro.vinnikov.kawavpn.diag.Diagnostics.log("cabinet: ${cabinet.subscriptions.size} subscriptions (${cabinet.subscriptions.joinToString { it.status }}), ${urls.size} links, ${profiles.size} servers, failed $failures")
        store.update { old ->
            val merged = old.profiles.filterNot { it.managed } + profiles
            old.copy(syncWarning=if(failures>0) "Не удалось обновить подписок: $failures. Последние рабочие серверы сохранены." else null, profiles = merged.distinctBy { it.profile.id }, cabinet = cabinet, selected = old.selected?.takeIf { id -> merged.any { it.profile.id == id && !ProfileNotice.of(it.profile) } } ?: merged.firstOrNull { !ProfileNotice.of(it.profile) }?.profile?.id)
        }
        billing(owner).reconcile(cabinet.subscriptions.map { BillingSubscription(it.id, it.tariff_id, it.status, it.end_date) })
        Unit
    }
    private fun download(url: String): List<VpnProfile> {
        val safeUrl = NetworkContract.subscriptionUrl(url)
        fetchProfiles?.let { return it(safeUrl) }
        val identity = store.read()
        var info = identity.subscriptionInfo[safeUrl] ?: SubscriptionInfo()
        val profiles = SubscriptionContract.fetch(safeUrl, { requested ->
            var current = requested
            var payload: String? = null
            repeat(4) { hop ->
                if(payload != null) return@repeat
                val happ = identity.happ?.takeIf { it.enabled }
                val builder = Request.Builder().url(current).header("User-Agent", happ?.userAgent() ?: "KawaVPN/${pro.vinnikov.kawavpn.BuildConfig.VERSION_NAME}").header("Accept", "application/json, text/plain;q=0.9, */*;q=0.8")
                if (happ != null && java.net.URI(current).host == java.net.URI(safeUrl).host) {
                    builder.header("x-hwid", happ.hwid).header("x-device-os", happ.os).header("x-ver-os", happ.osVersion)
                        .header("x-device-model", happ.model).header("x-device-locale", happ.locale)
                } else if (identity.sendDeviceInfo && java.net.URI(current).host == java.net.URI(safeUrl).host) {
                    builder.header("x-hwid", identity.deviceId).header("x-device-os", "Android")
                        .header("x-ver-os", android.os.Build.VERSION.RELEASE).header("x-device-model", android.os.Build.MODEL)
                }
                client().newCall(builder.build()).execute().use { response ->
                    if(response.code in setOf(301,302,303,307,308)) {
                        require(hop < 3) { "Слишком много перенаправлений" }
                        current = NetworkContract.subscriptionUrl(response.request.url.resolve(response.header("Location") ?: error("Нет адреса перенаправления"))?.toString() ?: error("Неверный адрес"))
                    } else {
                        if(!response.isSuccessful) throw ApiUnavailable(response.code)
                        val bytes = bounded(response.body?.byteStream() ?: error("Подписка пустая"), ProfileParser.MAX_BYTES)
                        payload = String(bytes, Charsets.UTF_8)
                        val stats = response.header("subscription-userinfo").orEmpty().split(';').mapNotNull { item -> val pair=item.trim().split('=',limit=2); if(pair.size==2) pair[1].toLongOrNull()?.let { pair[0] to it } else null }.toMap()
                        val title = response.header("profile-title")?.let { if(it.startsWith("base64:")) runCatching { String(java.util.Base64.getDecoder().decode(it.removePrefix("base64:")),Charsets.UTF_8) }.getOrNull() else it }
                        info = info.copy(title=title ?: info.title, download=stats["download"] ?: info.download, upload=stats["upload"] ?: info.upload, total=stats["total"] ?: info.total, expire=stats["expire"] ?: info.expire, support=response.header("support-url") ?: info.support)
                    }
                }
            }
            payload ?: error("Подписка не получена")
        }, NativeEngineBridge::convert)
        store.update { it.copy(subscriptionInfo = it.subscriptionInfo + (safeUrl to info)) }
        return profiles
    }
    fun import(text: String) {
        val trimmed = text.trim()
        val url = trimmed.startsWith("https://")
        val profiles = if (url) download(trimmed) else ProfileParser.parse(trimmed, NativeEngineBridge::convert)
        val source = if (url) NetworkContract.subscriptionUrl(trimmed) else "manual"
        store.update { old ->
            val managed=old.profiles.any { it.source==source && it.managed }
            val merged = (old.profiles.filterNot { source != "manual" && it.source == source } + profiles.map { SavedProfile(it, source,managed) }).distinctBy { it.profile.id }
            old.copy(profiles = merged, selected = old.selected?.takeIf { id -> merged.any { it.profile.id == id && !ProfileNotice.of(it.profile) } } ?: merged.firstOrNull { !ProfileNotice.of(it.profile) }?.profile?.id,
                subscriptionUrls = if (url && !managed) (old.subscriptionUrls + source).distinct() else old.subscriptionUrls)
        }
    }
    fun updateSubscriptions() {
        val data=store.read()
        val replacements=mutableMapOf<String,List<SavedProfile>>()
        var failure:Exception?=null;var failures=0
        for(url in data.subscriptionUrls) {
            try { replacements[url]=download(url).map { SavedProfile(it,url) } }
            catch(e:Exception) { failures++;if(failure==null) failure=e }
        }
        store.update { old ->
            val merged=(old.profiles.filterNot { !it.managed && it.source in replacements.keys } + replacements.values.flatten()).distinctBy { it.profile.id }
            old.copy(profiles=merged,syncWarning=if(failures>0) "Не удалось обновить подписок: $failures. Последние рабочие серверы сохранены." else null,
                selected=old.selected?.takeIf { id->merged.any { it.profile.id==id } } ?: merged.firstOrNull { !ProfileNotice.of(it.profile) }?.profile?.id)
        }
        var accountUpdated=false
        if(data.session!=null) try { refreshAccount();accountUpdated=true } catch(e:Exception) { if(failure==null) failure=e }
        if(replacements.isEmpty() && !accountUpdated && failure!=null) throw failure
    }
    fun logout() = synchronized(accountLock) {
        val session = store.read().session
        // Local credentials and managed server secrets are removed even if revocation is offline.
        store.update { old ->
            val manual = old.profiles.filterNot { it.managed }
            old.copy(session = null, pendingLogin = null, cabinet = CabinetData(), profiles = manual, selected = old.selected?.takeIf { id -> manual.any { it.profile.id == id } } ?: manual.firstOrNull { !ProfileNotice.of(it.profile) }?.profile?.id)
        }
        session?.let { runCatching { request("/auth/logout", it.access_token, json("refresh_token", it.refresh_token)) } }
        Unit
    }
    private fun billing(owner: Long): BillingCoordinator = BillingCoordinator(BillingClient {
        val session = store.read().session ?: throw BillingAuthenticationRequired()
        check(session.userId == owner); session.access_token
    }, object : BillingPersistence {
        override fun loadPending() = store.read().pendingOrders[owner.toString()]
        override fun tryCreatePending(value: PendingCheckout): Boolean {
            var created = false
            store.update { old ->
                check(old.session?.userId == owner)
                if (old.pendingOrders.containsKey(owner.toString())) old else { created = true; old.copy(pendingOrders = old.pendingOrders + (owner.toString() to value)) }
            }
            return created
        }
        override fun savePending(value: PendingCheckout?) {
            store.update { old -> old.copy(pendingOrders = if (value == null) old.pendingOrders - owner.toString() else old.pendingOrders + (owner.toString() to value)) }
        }
    })
    fun purchase(tariffId: Long, days: Long, subscriptionId: Long?, option: String): PendingCheckout? = synchronized(accountLock) {
        refreshAccount(includeBilling=true) // Refresh/persist credentials and authoritative baseline before any mutation.
        val data = store.read(); val owner = data.session?.userId ?: throw LoginExpired()
        if (subscriptionId != null) require(data.cabinet.subscriptions.any { it.id == subscriptionId })
        val offered = if (subscriptionId == null) data.cabinet.tariffs else Gson().fromJson(request("/subscription/purchase-options?subscription_id=$subscriptionId", data.session!!.access_token).second.asJsonObject["tariffs"], Array<CabinetTariff>::class.java).toList()
        val tariff = offered.firstOrNull { it.id == tariffId } ?: error("Тариф недоступен")
        require(tariff.periods.any { it.days == days })
        require(data.cabinet.paymentOptions.any { it.id == option } || data.cabinet.paymentOptions.isEmpty() && option == "hosted")
        billing(owner).begin(BillingPurchase(tariffId, days, subscriptionId), option, data.cabinet.minPayment, data.cabinet.maxPayment,
            data.cabinet.subscriptions.map { BillingSubscription(it.id, it.tariff_id, it.status, it.end_date) })
        refreshAccount()
        store.read().pendingOrders[owner.toString()]
    }
    fun recoverPayment(): PendingCheckout? = synchronized(accountLock) {
        refreshAccount()
        val owner = store.read().session?.userId ?: throw LoginExpired()
        billing(owner).recover()
        refreshAccount()
        store.read().pendingOrders[owner.toString()]
    }
    fun clearCancelledPayment() = synchronized(accountLock) {
        val owner = store.read().session?.userId ?: throw LoginExpired()
        store.update { old ->
            val pending = old.pendingOrders[owner.toString()] ?: return@update old
            check(pending.phase in setOf(BillingPhase.REJECTED, BillingPhase.PAYMENT_CANCELLED))
            old.copy(pendingOrders = old.pendingOrders - owner.toString())
        }
        Unit
    }
    fun offers(subscriptionId: Long?): List<CabinetTariff> = synchronized(accountLock) {
        refreshAccount(includeBilling=true)
        val data = store.read()
        if (subscriptionId == null) data.cabinet.tariffs else {
            require(data.cabinet.subscriptions.any { it.id == subscriptionId })
            Gson().fromJson(request("/subscription/purchase-options?subscription_id=$subscriptionId", data.session!!.access_token).second.asJsonObject["tariffs"], Array<CabinetTariff>::class.java).toList()
        }
    }
}
