package pro.vinnikov.kawavpn

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pro.vinnikov.kawavpn.data.*
import pro.vinnikov.kawavpn.vpn.KawaVpnService
import pro.vinnikov.kawavpn.diag.Diagnostics
import pro.vinnikov.kawavpn.domain.*

data class ScreenState(val data: AppData = AppData(), val busy: Boolean = false, val error: String? = null, val botUrl: String? = null,
    val offers: List<CabinetTariff>? = null, val offerTarget: Long? = null, val loginWaiting: String? = null,
    val notice: String? = null, val latencies: Map<String, Long?> = emptyMap(), val pinging: Boolean = false, val reportSending: Boolean = false, val loaded: Boolean = false)

class KawaViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = KawaRepository(application)
    val screen = MutableStateFlow(ScreenState())
    val tunnel = KawaVpnService.status.asStateFlow()
    private val loginEpoch = java.util.concurrent.atomic.AtomicLong()
    private var loginJob: Job? = null
    private val pingEpoch=java.util.concurrent.atomic.AtomicLong()
    private var pingJob: Job? = null
    init {
        Diagnostics.init(application)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.store.read().let { Diagnostics.configure(it.telemetry, it.reportInstallId.orEmpty()) }
                // A placeholder ("⌛ Подписка истекла") selected by an older version is not a server.
                repo.store.read().let { old -> if(old.profiles.any { it.profile.id==old.selected && ProfileNotice.of(it.profile) })
                    repo.store.update { d -> d.copy(selected=d.profiles.firstOrNull { !ProfileNotice.of(it.profile) }?.profile?.id) } }
                screen.update { it.copy(data=repo.store.read(), loaded=true) }
                val data=repo.store.read()
                withContext(Dispatchers.Main) {
                    if(data.pendingLogin!=null) login()
                    else if(data.autoRefresh && (data.session!=null || data.subscriptionUrls.isNotEmpty()) && !screen.value.busy) refresh()
                }
            }.onFailure { screen.update { it.copy(error="Не удалось прочитать защищённое хранилище.") } }
        }
    }
    private fun local(change:(AppData)->AppData,ready:(()->Unit)?=null) {
        viewModelScope.launch(Dispatchers.IO) {
            try { repo.store.update(change);screen.update { it.copy(data=repo.store.read()) };if(ready!=null) withContext(Dispatchers.Main) { ready() } }
            catch(_:Exception) { screen.update { it.copy(error="Изменение не сохранено. Проверьте введённые данные.") } }
        }
    }
    /** The first-start answer about automatic error reports (as on the desktop). */
    fun telemetry(value: Boolean) = local({ old -> old.copy(telemetry = value, reportInstallId = if (value) old.reportInstallId ?: Diagnostics.newInstallId() else null) }) {
        repo.store.read().let { Diagnostics.configure(it.telemetry, it.reportInstallId.orEmpty()) }
    }
    /** What "Сообщить о проблеме" sends: shown to the user before sending. */
    fun reportPreview(note: String): String = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(Diagnostics.manualReport(note))
    /** Sends a user report; the code (KW-…) goes to the notice line. */
    fun sendReport(note: String, done: () -> Unit) {
        screen.update { it.copy(reportSending = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { Diagnostics.sendNow(Diagnostics.manualReport(note)) }
            screen.update { state -> result.fold(
                { (id, queued) -> state.copy(reportSending = false, notice = if (queued) "Нет связи с сервером поддержки. Отчёт $id сохранён и уйдёт автоматически." else "Отчёт $id отправлен. Назовите этот код в поддержке.") },
                { state.copy(reportSending = false, error = "Отчёт не отправлен. Попробуйте позже.") }) }
            if (result.isSuccess) withContext(Dispatchers.Main) { done() }
        }
    }
    fun notice(message: String) { screen.update { it.copy(notice = message, error = null) } }
    fun showError(message:String) { screen.update { it.copy(error=message) } }
    fun dismissMessage() { screen.value = screen.value.copy(error = null, notice = null) }
    fun resumeBot() { repo.store.read().pendingLogin?.let { screen.value = screen.value.copy(botUrl = it.url) } }
    fun cancelLogin() { loginEpoch.incrementAndGet(); loginJob?.cancel(); loginJob = null; repo.store.update { it.copy(pendingLogin = null) }; screen.value = screen.value.copy(loginWaiting = null, data = repo.store.read()) }

    private fun launch(work: suspend () -> Unit) {
        if (screen.value.busy) return
        screen.value = screen.value.copy(busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try { work(); screen.value = screen.value.copy(data = repo.store.read()) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Never display native/network raw exceptions (they may contain credentials/URLs).
                val safe = when (e) {
                    is LoginExpired -> "Сессия истекла. Войдите через бота повторно."
                    is BillingUncertain -> "Заказ сохранён. Нажмите «Проверить оплату»; новую оплату создавать не нужно."
                    is BillingPendingExists -> "Сначала проверьте сохранённый заказ."
                    is BillingRejected -> "Заказ отклонён. Обновите кабинет."
                    is BillingAuthenticationRequired -> "Обновите аккаунт перед проверкой заказа."
                    is java.net.UnknownHostException -> "DNS не смог найти сервер. Проверьте доступ к интернету и настройки DNS."
                    is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Повторите запрос или смените сеть."
                    is java.io.IOException -> "Нет соединения с сервером. Проверьте Wi-Fi или мобильную сеть."
                    is ApiUnavailable -> when(e.status) { 410 -> "Ссылка или запрос входа истекли. Запросите новый."; 429 -> "Слишком много запросов. Подождите минуту."; 403 -> "Сервер отклонил запрос. Проверьте подписку и доступ устройства."; else -> "Сервис временно недоступен (${e.status})." }
                    is IllegalArgumentException -> "Проверьте формат подписки: нужна HTTPS-ссылка, VLESS или полный JSON."
                    else -> "Операция не завершена. Проверьте сеть и повторите попытку."
                }
                screen.value = screen.value.copy(error = safe, data = runCatching { repo.store.read() }.getOrDefault(screen.value.data))
            } finally { screen.value = screen.value.copy(busy = false) }
        }
    }
    fun import(text: String, onImported:(()->Unit)?=null) = launch {
        repo.import(text)
        screen.value = screen.value.copy(notice = "Серверы добавлены. Выберите сервер и подключитесь.")
        if(onImported!=null) withContext(Dispatchers.Main) { onImported() }
    }
    fun importQr(uri: android.net.Uri) = launch {
        val context=getApplication<Application>()
        try {
            val data=context.contentResolver.openInputStream(uri)?.use { it.readBounded(16*1024*1024) } ?: error("Нет изображения")
            val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
            android.graphics.BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth<=32768 && bounds.outHeight<=32768)
            var sample=1;while(bounds.outWidth/sample>2048 || bounds.outHeight/sample>2048) sample*=2
            val bitmap=android.graphics.BitmapFactory.decodeByteArray(data,0,data.size,android.graphics.BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Нет изображения")
            val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            val text=try { QrCodec.decode(pixels,bitmap.width,bitmap.height) } finally { bitmap.recycle() }
            repo.import(text)
            screen.value=screen.value.copy(notice="QR-код импортирован.")
        } finally { if(uri.authority==context.packageName+".files") java.io.File(context.cacheDir,"qr/capture.jpg").delete() }
    }
    fun importFile(uri: android.net.Uri) = launch {
        val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: error("Файл не открыт")
        val bytes = input.use { it.readBounded(ProfileParser.MAX_BYTES) }
        require(bytes.size <= ProfileParser.MAX_BYTES)
        repo.import(String(bytes,Charsets.UTF_8))
        screen.value = screen.value.copy(notice = "Файл импортирован.")
    }
    fun refresh() = launch { repo.updateSubscriptions() }
    fun login() {
        if(loginJob?.isActive == true) { resumeBot(); return }
        val epoch=loginEpoch.incrementAndGet()
        screen.value = screen.value.copy(error = null, loginWaiting = "Запрашиваем вход…")
        loginJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.login({ url -> if(loginEpoch.get()==epoch) screen.value = screen.value.copy(botUrl = url) }, { status -> if(loginEpoch.get()==epoch) screen.value = screen.value.copy(loginWaiting = status.takeIf { it.isNotBlank() }, data=if(status.isBlank()) repo.store.read() else screen.value.data) })
                screen.value = screen.value.copy(notice = "Вы вошли. Подписки загружены.")
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(loginEpoch.get()!=epoch) return@launch
                screen.value = screen.value.copy(error = when(e) {
                    is LoginExpired -> "Время подтверждения истекло. Нажмите «Войти через бота» ещё раз."
                    is java.net.UnknownHostException -> "Не удалось найти кабинет через DNS. Проверьте сеть."
                    is java.io.IOException -> "Кабинет недоступен по сети. Вход можно повторить; подписку также можно добавить по ссылке."
                    else -> if(repo.store.read().session != null) "Вход выполнен, но подписки пока не загрузились. Нажмите «Обновить»." else "Вход не завершён. Повторите подтверждение через бота."
                })
            } finally { if(loginEpoch.get()==epoch) screen.value = screen.value.copy(data = repo.store.read(), loginWaiting = null) }
        }
    }
    fun botOpened() { screen.value = screen.value.copy(botUrl = null) }
    fun openPayment(url: String) { screen.value = screen.value.copy(botUrl = BillingPaymentUrl.validate(url)) }
    fun purchase(tariff: Long, days: Long, subscription: Long?, option: String) = launch {
        repo.purchase(tariff, days, subscription, option)?.paymentUrl?.let { screen.value = screen.value.copy(botUrl = BillingPaymentUrl.validate(it)) }
    }
    fun recoverPayment() = launch { repo.recoverPayment() }
    fun clearCancelledPayment() = launch { repo.clearCancelledPayment() }
    fun showOffers(subscription: Long? = null) = launch { val offers = repo.offers(subscription); screen.value = screen.value.copy(offers = offers, offerTarget = subscription) }
    fun dismissOffers() { screen.value = screen.value.copy(offers = null) }
    fun logout() = launch {
        cancelLogin()
        getApplication<Application>().startService(Intent(getApplication(), KawaVpnService::class.java).setAction(KawaVpnService.STOP))
        repo.logout()
    }
    fun select(id:String) = local({ old -> require(old.profiles.any { it.profile.id==id && !ProfileNotice.of(it.profile) });old.copy(selected=id) })
    fun selectAndConnect(id:String,ready:()->Unit) = local({ old -> require(old.profiles.any { it.profile.id==id });old.copy(selected=id) },ready)
    fun routing(value:RoutingOptions) = local({ it.copy(routing=value.validated()) })
    fun autoRefresh(value:Boolean)=local({it.copy(autoRefresh=value)})
    fun privacy(value:Boolean) = local({ it.copy(sendDeviceInfo=value) })
    fun textScale(value:Float) = local({ it.copy(textScale=value.coerceIn(0.9f,1.3f)) })
    fun removeSource(source: String) = launch { repo.store.update { old ->
        val profiles = old.profiles.filterNot { it.source == source }
        old.copy(profiles = profiles, subscriptionUrls = old.subscriptionUrls - source, subscriptionInfo = old.subscriptionInfo - source,
            favorites = old.favorites.intersect(profiles.map { it.profile.id }.toSet()), selected = old.selected?.takeIf { id -> profiles.any { it.profile.id == id } } ?: profiles.firstOrNull()?.profile?.id)
    } }
    fun edit(id: String, json: String, reset: Boolean = false, onSaved:(()->Unit)?=null) = launch { repo.store.update { old ->
        old.copy(profiles = old.profiles.map { saved -> if(saved.profile.id != id) saved else {
            val content = if(reset) saved.originalJson ?: saved.profile.configJson else json
            val parsed = ProfileParser.parse(content).single()
            saved.copy(profile = parsed.copy(id = id), originalJson = saved.originalJson ?: saved.profile.configJson)
        } })
    };if(onSaved!=null) withContext(Dispatchers.Main) { onSaved() } }
    fun pingMode(real:Boolean) { pingEpoch.incrementAndGet();pingJob?.cancel();screen.update { it.copy(pinging=false,latencies=emptyMap()) };local({it.copy(realPing=real)}) }
    fun pingServers(ids: Set<String>? = null) {
        if(pingJob?.isActive == true) { pingEpoch.incrementAndGet();pingJob?.cancel(); screen.value=screen.value.copy(pinging=false); return }
        val epoch=pingEpoch.incrementAndGet()
        screen.value=screen.value.copy(pinging=true)
        pingJob=viewModelScope.launch(Dispatchers.IO) {
            try {
                val data=repo.store.read()
                val entries=data.profiles.filter { (ids==null || it.profile.id in ids) && !ProfileNotice.of(it.profile) }
                if(data.realPing) {
                    val probes=entries.flatMap { saved ->
                        val tags=runCatching { ProfileProbeTargets.tags(saved.profile.configJson) }.getOrDefault(emptyList())
                        if(tags.isEmpty()) listOf(saved.profile.id to pro.vinnikov.kawavpn.vpn.ProbeConfig(saved.profile.configJson))
                        else tags.map { saved.profile.id to pro.vinnikov.kawavpn.vpn.ProbeConfig(saved.profile.configJson,it) }
                    }
                    val values=mutableMapOf<String,Long?>()
                    for(chunk in probes.chunked(5)) {
                        currentCoroutineContext().ensureActive()
                        val delays=pro.vinnikov.kawavpn.vpn.NativeProbeClient.ping(getApplication(),chunk.map { it.second })
                        chunk.forEachIndexed { index,item ->
                            val delay=delays.getOrNull(index)
                            val previous=values[item.first]
                            values[item.first]=if(previous!=null && delay!=null) minOf(previous,delay) else previous ?: delay
                        }
                        if(pingEpoch.get()==epoch) screen.update { it.copy(latencies=it.latencies+values) }
                    }
                    return@launch
                }
                coroutineScope {
                val semaphore=kotlinx.coroutines.sync.Semaphore(4)
                repo.store.read().profiles.filter { (ids == null || it.profile.id in ids) && !ProfileNotice.of(it.profile) }.map { saved -> async {
                    semaphore.acquire()
                    try {
                        val latency=runCatching {
                            val root=com.google.gson.JsonParser.parseString(saved.profile.configJson).asJsonObject
                            val outbound=root["outbounds"].asJsonArray.first { it.asJsonObject["protocol"]?.asString !in setOf("freedom","blackhole","dns","loopback") }.asJsonObject
                            val settings=outbound["settings"].asJsonObject
                            val server=(settings["vnext"] ?: settings["servers"])?.asJsonArray?.get(0)?.asJsonObject ?: settings
                            val address=server["address"].asString
                            val udp=outbound["protocol"]?.asString?.lowercase() in setOf("hysteria","hysteria2","wireguard","tuic")
                            val balanced=root["routing"]?.asJsonObject?.get("balancers")?.asJsonArray?.size()?.let { it>0 } == true
                            val placeholder=runCatching { java.net.InetAddress.getByName(address).let { it.isLoopbackAddress || it.isAnyLocalAddress } }.getOrDefault(false)
                            if(udp || balanced || placeholder) {
                                // TCP says nothing about UDP (Hysteria) or a balancer whose
                                // first outbound is a stand-in: ask the protocol itself.
                                val tags=ProfileProbeTargets.tags(saved.profile.configJson)
                                val configs=(if(tags.isEmpty()) listOf(pro.vinnikov.kawavpn.vpn.ProbeConfig(saved.profile.configJson)) else tags.take(5).map { pro.vinnikov.kawavpn.vpn.ProbeConfig(saved.profile.configJson,it) })
                                pro.vinnikov.kawavpn.vpn.NativeProbeClient.ping(getApplication(),configs).filterNotNull().minOrNull()
                            } else {
                                val start=android.os.SystemClock.elapsedRealtime()
                                java.net.Socket().use { socket -> socket.connect(java.net.InetSocketAddress(address,server["port"].asInt),3000) }
                                android.os.SystemClock.elapsedRealtime()-start
                            }
                        }.getOrNull()
                        if(pingEpoch.get()==epoch) screen.update { it.copy(latencies=it.latencies+(saved.profile.id to latency)) }
                    } finally { semaphore.release() }
                } }.awaitAll()
            } } finally { if(pingEpoch.get()==epoch) screen.update { it.copy(pinging=false) } }
        }
    }
    fun toggleFavorite(id: String) = local({ it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id) })
    fun routeAll(value: Boolean) = local({ it.copy(allTraffic=value) })
    fun autoConnect(value: Boolean) = local({ it.copy(autoConnect=value) })
    fun happ(value: HappSpoof) = local({ it.copy(happ=value) })
    fun theme(value: String) = local({ it.copy(theme=value) })
    fun hysteriaRestart(enabled: Boolean, minutes: Int) = local({ it.copy(hysteriaRestart=enabled, hysteriaMinutes=minutes.coerceIn(10,720)) })
    fun remove(id: String) = launch { repo.store.update { old ->
        val profiles = old.profiles.filterNot { it.profile.id == id }
        old.copy(profiles = profiles, selected = old.selected?.takeIf { it != id } ?: profiles.firstOrNull()?.profile?.id)
    } }
    fun preferences(ipv6: Boolean, reducedMotion: Boolean) = local({ it.copy(ipv6=ipv6,reducedMotion=reducedMotion) })
}
