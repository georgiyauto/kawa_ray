package pro.vinnikov.kawavpn.vpn

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import libXray.DialerController
import libXray.LibXray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.*
import pro.vinnikov.kawavpn.MainActivity
import pro.vinnikov.kawavpn.diag.Diagnostics
import pro.vinnikov.kawavpn.readBounded
import pro.vinnikov.kawavpn.data.PrivateStore
import pro.vinnikov.kawavpn.domain.*
import com.google.gson.JsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.net.Proxy
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID

data class TunnelStatus(val state: ConnectionState = ConnectionState.DISCONNECTED, val name: String = "", val since: Long = 0,
    val error: String? = null, val latency: Long? = null, val originCountry: String? = null, val exitCountry: String? = null, val received: Long = 0, val sent: Long = 0, val sampledAt: Long = 0, val receivedPerSecond: Long = 0, val sentPerSecond: Long = 0, val lastPingAt: Long = 0)

class KawaVpnService : VpnService() {
    companion object {
        const val CONNECT = "pro.vinnikov.kawavpn.CONNECT"
        const val STOP = "pro.vinnikov.kawavpn.STOP"
        const val RECONNECT = "pro.vinnikov.kawavpn.RECONNECT"
        const val PING = "pro.vinnikov.kawavpn.PING"
        val status = MutableStateFlow(TunnelStatus())
        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val transitionLock = Any()
        @Volatile private var engineOwner: KawaVpnService? = null
        private val controller = DialerController { fd -> engineOwner?.protectFd(fd) ?: false }
        private val registered = java.util.concurrent.atomic.AtomicBoolean(false)
    }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var tun: ParcelFileDescriptor? = null
    private var session: VpnSession? = null
    private var monitor: Job? = null
    private val generation = AtomicLong()
    /** Automatic reconnects since the user last pressed connect (a flaky line, a network switch). */
    @Volatile private var autoRetries = 0
    /** Whether this connect reached CONNECTED, and the last reason it fell over (for the report). */
    @Volatile private var reachedConnected = false
    @Volatile private var lastFailure = ""
    @Volatile private var activeCall: Call? = null
    private var proxyHttp: OkHttpClient? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var physicalNetwork: Network? = null
    private val networkChangedAt = AtomicLong(-1)
    // libXray keeps the first registered controller for the process; a recreated service
    // must not leave sockets protected by a destroyed instance. One shared controller
    // delegates to whichever service currently owns the engine.
    private fun protectFd(fd: Long) = fd in 0..Int.MAX_VALUE.toLong() && protect(fd.toInt())
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS).build()
    private val healthHttp = http.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()

    override fun onBind(intent:Intent?):IBinder?=if(intent?.action==ProbeProtection.ACTION) ProbeProtection.binder(this) else super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        Diagnostics.init(applicationContext)
        runCatching { PrivateStore(this).read().let { Diagnostics.configure(it.telemetry, it.reportInstallId.orEmpty()) } }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("vpn", "VPN-соединение", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "vpn").setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("Kawa VPN")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Отключить", stop).build()).build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Pending intents carry actions only; all configuration comes from private storage.
        if (intent?.action == RECONNECT) {
            autoRetries = 0; reachedConnected = false; lastFailure = ""
            invalidate()
            val attempt = generation.get()
            startForeground(1, notification("Смена сервера…"))
            scope.launch { disconnect(); if(generation.get()==attempt) { startForeground(1,notification("Подключение…"));connect(attempt) } }
        } else if (intent?.action == CONNECT) {
            autoRetries = 0; reachedConnected = false; lastFailure = ""
            val attempt = generation.get()
            startForeground(1, notification("Подключение…"))
            scope.launch { connect(attempt) }
        } else if (intent?.action == PING) scope.launch { if (status.value.state == ConnectionState.CONNECTED) ping() }
        else { invalidate(); scope.launch { disconnect(); stopSelf(startId) } }
        return START_NOT_STICKY
    }
    private fun probe(client: OkHttpClient, url: String): Boolean = runCatching {
        val call = client.newCall(Request.Builder().url(url).build())
        activeCall = call
        try { call.execute().use { if (it.code != 204) Diagnostics.log("probe ${if (client === healthHttp) "direct" else "proxy"} $url: HTTP ${it.code}"); it.code == 204 } } finally { activeCall = null }
    }.onFailure { Diagnostics.log("probe ${if (client === healthHttp) "direct" else "proxy"} $url: ${it.javaClass.simpleName} ${it.message.orEmpty()}") }.getOrDefault(false)
    private fun ping(): Boolean {
        val attempt = generation.get()
        val networkEpoch = networkChangedAt.get()
        val current = { generation.get() == attempt && networkChangedAt.get() == networkEpoch }
        val client = proxyHttp ?: return false
        val start = SystemClock.elapsedRealtime()
        val proxySuccess = TunnelHealthChecks.verify({ probe(client, it) }, current)
        val latency = SystemClock.elapsedRealtime() - start
        val success = proxySuccess && TunnelHealthChecks.verify({ probe(healthHttp, it) }, current)
        // STOP/network changes must not publish a stale measurement over a new state.
        if (current()) status.value = status.value.copy(latency = if (success) latency else null, lastPingAt = SystemClock.elapsedRealtime())
        return success
    }
    /** The first connectivity check: retried for up to 15 s while balancers settle. */
    private fun settle(attempt: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var tries = 0
        while (generation.get() == attempt) {
            tries++
            if (ping()) { if (tries > 1) Diagnostics.log("tunnel ready after $tries checks"); return true }
            if (SystemClock.elapsedRealtime() >= deadline || !runCatching { NativeEngineBridge.running() }.getOrDefault(false)) return false
            Thread.sleep(1200)
        }
        return false
    }
    private fun physicalNetwork(cm: ConnectivityManager, excluded: Network? = null): Network? =
        cm.allNetworks.filter { it != excluded }.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            val score = (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 10 else 0) +
                (if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) 2 else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) 3 else 0)
            network to score
        }.maxByOrNull { it.second }?.first

    private fun updateResolver(cm: ConnectivityManager, network: Network?) {
        // Routers often list an IPv6 link-local resolver (fe80::…%wlan0) first: Xray
        // cannot dial it ("invalid argument") and then no server name resolves.
        // Prefer IPv4, then a global IPv6; never link-local, loopback or site-local v6.
        val usable = network?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
        val server = usable.firstOrNull { it is java.net.Inet4Address } ?: usable.firstOrNull { it is java.net.Inet6Address && !it.isSiteLocalAddress }
        val dns = server?.hostAddress?.substringBefore('%') ?: "1.1.1.1"
        Diagnostics.log("resolver: ${if (server == null) "fallback" else if (server is java.net.Inet4Address) "network ipv4" else "network ipv6"}")
        LibXray.setDNS(controller, if (dns.contains(':')) "[$dns]:53" else "$dns:53")
    }
    private fun observeNetworks(cm: ConnectivityManager, attempt: Long) {
        check(networkCallback == null)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun refresh(excluded: Network? = null, resolverChanged: Boolean = false) {
                if (generation.get() != attempt) return
                val next = physicalNetwork(cm, excluded)
                if (next == physicalNetwork && !resolverChanged) return
                physicalNetwork = next
                val changed = SystemClock.elapsedRealtime()
                networkChangedAt.set(changed)
                activeCall?.cancel()
                scope.launch {
                    if (generation.get() != attempt || engineOwner !== this@KawaVpnService || networkChangedAt.get() != changed) return@launch
                    // Protected Xray sockets follow Android's default physical route.
                    // Do not advertise a pinned Network: no socket is explicitly bound.
                    runCatching { updateResolver(cm, next) }
                    proxyHttp?.connectionPool?.evictAll()
                    http.connectionPool.evictAll()
                }
            }
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh(excluded = network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == physicalNetwork) refresh(resolverChanged = true)
            }
        }
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        networkCallback = callback
        try { cm.registerNetworkCallback(request, callback, Handler(Looper.getMainLooper())) }
        catch (error: Exception) { networkCallback = null; throw error }
    }
    private fun readTraffic() {
        runCatching {
            val client=proxyHttp ?: return
            val call=client.newCall(Request.Builder().url("http://127.0.0.1:33217/debug/vars").build());activeCall=call
            try { call.execute().use { response ->
                if(!response.isSuccessful) return@use
                val bytes=response.body.byteStream().readBounded(256*1024)
                val counters=com.google.gson.JsonParser.parseString(String(bytes,Charsets.UTF_8)).asJsonObject["stats"].asJsonObject["inbound"].asJsonObject[RuntimeConfig.TUN_TAG]?.asJsonObject ?: return@use
                val now=SystemClock.elapsedRealtime();val old=status.value
                val received=counters["downlink"]?.asLong?.coerceAtLeast(0) ?: 0
                val sent=counters["uplink"]?.asLong?.coerceAtLeast(0) ?: 0
                val elapsed=(now-(old.sampledAt.takeIf { it>0 } ?: old.since)).coerceAtLeast(1)
                status.value=old.copy(received=received,sent=sent,sampledAt=now,receivedPerSecond=(received-old.received).coerceAtLeast(0)*1000/elapsed,sentPerSecond=(sent-old.sent).coerceAtLeast(0)*1000/elapsed)
            } } finally { activeCall=null }
        }
    }
    private fun country(network:Network?=null):String?=runCatching {
        val client=if(network!=null) http.newBuilder().socketFactory(network.socketFactory).dns(Dns { name -> network.getAllByName(name).toList() }).proxy(Proxy.NO_PROXY).build() else proxyHttp ?: return@runCatching null
        val call=client.newCall(Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build());activeCall=call
        try { call.execute().use { response ->
            val text=String(response.body.byteStream().readBounded(16*1024),Charsets.UTF_8)
            text.lineSequence().firstOrNull { it.startsWith("loc=") }?.removePrefix("loc=")?.takeIf { it.matches(Regex("[A-Z]{2}")) }
        } } finally { activeCall=null }
    }.getOrNull()
    private suspend fun connect(attempt: Long, resume: Boolean = false) {
        if (generation.get() != attempt) return
        // A recovery already shows "connecting" while it waits; only a fresh connect is deduplicated.
        if (!resume && (status.value.state == ConnectionState.CONNECTED || status.value.state == ConnectionState.CONNECTING)) return
        try {
            check(prepare(this) == null)
            val saved = PrivateStore(this).read()
            val profile = saved.profiles.firstOrNull { it.profile.id == saved.selected && !ProfileNotice.of(it.profile) }?.profile ?: error("Выберите сервер")
            status.value = TunnelStatus(ConnectionState.CONNECTING, profile.name, originCountry = status.value.originCountry)
            val protocol = runCatching { com.google.gson.JsonParser.parseString(profile.configJson).asJsonObject["outbounds"].asJsonArray.map { it.asJsonObject }.firstOrNull { it["protocol"]?.asString !in setOf("freedom", "blackhole", "dns", "loopback") }?.let { o -> listOfNotNull(o["protocol"]?.asString, o["streamSettings"]?.asJsonObject?.get("network")?.asString, o["streamSettings"]?.asJsonObject?.get("security")?.asString).joinToString("/") } }.getOrNull() ?: "?"
            Diagnostics.context = JsonObject().apply { addProperty("server", profile.name); addProperty("protocol", protocol); addProperty("all_traffic", saved.allTraffic); addProperty("ipv6", saved.ipv6); addProperty("rules", saved.routing.rules.size); addProperty("excluded_apps", saved.routing.excludedApps.size); addProperty("attempt", autoRetries) }
            Diagnostics.log(if (resume) "reconnect ${autoRetries}/2 · ${profile.name} ($protocol)" else "connect · ${profile.name} ($protocol)")
            val cm = getSystemService(ConnectivityManager::class.java)
            val physical = physicalNetwork(cm)
            physicalNetwork = physical
            networkChangedAt.set(-1)
            // The physical country only labels the globe: never make the user wait for it.
            physical?.let { network -> scope.launch(Dispatchers.IO) {
                val code = country(network)
                if (generation.get() == attempt && code != null) status.update { it.copy(originCountry = code) }
            } }
            val port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val health = HealthProbe(port, UUID.randomUUID().toString(), UUID.randomUUID().toString())
            proxyHttp = healthHttp.newBuilder().proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))).proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(health.user, health.password)).build()
            }.build()
            val assetDir = File(filesDir, "xray-assets").apply { mkdirs() }
            for (name in listOf("geoip.dat", "geosite.dat")) {
                val output = File(assetDir, name)
                if (!output.exists()) assets.open("xray/$name").use { input -> output.outputStream().use { input.copyTo(it) } }
            }
            val driver = object : EngineDriver {
                override fun start() {
                    check(engineOwner == null || engineOwner === this@KawaVpnService)
                    engineOwner = this@KawaVpnService
                    if (registered.compareAndSet(false, true)) {
                        LibXray.registerDialerController(controller)
                        LibXray.registerListenerController(controller)
                    }
                    updateResolver(cm, physical)
                    check(generation.get() == attempt && prepare(this@KawaVpnService) == null)
                    val builder = Builder().setSession("Kawa VPN · ${profile.name}").setMtu(1500)
                        .addAddress("172.19.0.2", 30).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1").setBlocking(false)
                    if (saved.ipv6) builder.addAddress("fdfe:dcba:9876::2", 126).addRoute("::", 0).addDnsServer("2606:4700:4700::1111")
                    // Without IPv6 enabled, do not allowFamily(AF_INET6): Android blocks IPv6 rather than bypassing the tunnel.
                    val routing = saved.routing.validated()
                    val chosen = routing.excludedApps.filter { it != packageName }
                    // Only-selected mode with nothing installed from the list would route every app: fall back to all.
                    var allowed = 0
                    if (routing.onlySelectedApps) chosen.forEach { app ->
                        try { builder.addAllowedApplication(app); allowed++ } catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
                    } else chosen.forEach { app ->
                        // Packages may have been uninstalled since this setting was saved.
                        try { builder.addDisallowedApplication(app) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
                    }
                    if (routing.onlySelectedApps) Diagnostics.log("apps: only $allowed selected use the VPN")
                    tun = builder.establish() ?: error("Разрешение VPN отозвано")
                    val built = RuntimeConfig.build(profile, tun!!.fd, assetDir.absolutePath, saved.ipv6, health, saved.allTraffic, saved.routing, trafficStats = true)
                    // Xray's own errors go to a file that reports carry (sanitized), fresh per tunnel.
                    val config = Diagnostics.xrayLog()?.let { file -> runCatching {
                        runCatching { file.writeText("") }
                        com.google.gson.JsonParser.parseString(built).asJsonObject.apply { (getAsJsonObject("log") ?: JsonObject().also { add("log", it) }).apply { addProperty("error", file.absolutePath); addProperty("loglevel", "info") } }.toString()
                    }.getOrNull() } ?: built
                    NativeEngineBridge.invoke("runXray", JsonObject().apply { addProperty("xrayJson", config) })
                }
                override fun running() = NativeEngineBridge.running()
                override fun stop() {
                    try {
                        if (engineOwner === this@KawaVpnService) {
                            try { NativeEngineBridge.invoke("stopXray") } finally {
                                try { LibXray.resetDNS() } finally { engineOwner = null }
                            }
                        }
                    } finally {
                        tun?.close(); tun = null
                    }
                }
            }
            // Balancers in panel configs pick servers from Xray's observatory, which
            // has no results for the first seconds: until then traffic takes the last
            // fallback (often Hysteria). Keep checking for a while instead of failing.
            session = VpnSession(driver) { settle(attempt) }
            if (session!!.connect()) {
                val exit = country()
                synchronized(transitionLock) {
                    check(engineOwner === this && generation.get() == attempt && prepare(this) == null)
                    status.value = status.value.copy(state = ConnectionState.CONNECTED, since = SystemClock.elapsedRealtime(), exitCountry = exit)
                }
                getSystemService(NotificationManager::class.java).notify(1, notification("Подключено · ${profile.name}"))
                reachedConnected = true
                Diagnostics.log("connected · exit ${exit ?: "?"}")
                observeNetworks(cm, attempt)
                monitor = scope.launch {
                    val healthPolicy = TunnelHealthPolicy()
                    var ticks = 0
                    while (isActive && status.value.state == ConnectionState.CONNECTED) {
                        delay(1000); ticks++
                        readTraffic()
                        if (engineOwner !== this@KawaVpnService) break
                        if (ticks % 5 == 0 && !runCatching { NativeEngineBridge.running() }.getOrDefault(false)) { lastFailure = "Xray остановился"; Diagnostics.log("xray stopped"); recover(attempt); break }
                        if (ticks == 120) autoRetries = 0 // two stable minutes: a later drop gets fresh retries
                        if (ticks % 60 == 0) Diagnostics.xrayLog()?.takeIf { it.length() > 2L * 1024 * 1024 }?.let { runCatching { it.writeText("") } }
                        // Hysteria can stall after a while; a fresh Xray revives it (desktop: hysteria_restart).
                        if (saved.hysteriaRestart && protocol.startsWith("hysteria") && ticks >= saved.hysteriaMinutes.coerceIn(10, 720) * 60) {
                            Diagnostics.log("scheduled Hysteria restart after ${saved.hysteriaMinutes} min")
                            scope.launch {
                                val (origin, name) = status.value.originCountry to status.value.name
                                disconnect(keepForeground = true)
                                status.value = TunnelStatus(ConnectionState.CONNECTING, name, originCountry = origin)
                                if (generation.get() == attempt) connect(attempt, resume = true)
                            }
                            break
                        }
                        if (ticks % 30 == 0) {
                            val success = ping()
                            if (generation.get() != attempt) break
                            if (!success) Diagnostics.log("health check failed")
                            if (healthPolicy.record(success, SystemClock.elapsedRealtime(), networkChangedAt.get().takeIf { it >= 0 })) { lastFailure = "Туннель не отвечает (проверка связи)"; recover(attempt); break }
                        }
                    }
                }
            } else { lastFailure = "Нет связи через туннель после запуска Xray"; Diagnostics.log("connectivity check failed"); recover(attempt) }
        } catch (e: Exception) { lastFailure = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"; Diagnostics.log("connect failed: $lastFailure"); runCatching { session?.disconnect() }; runCatching { tun?.close() }; tun = null; recover(attempt) }
    }
    /** A dead tunnel is rebuilt up to twice (1.5 s, then 4 s later) before the error is shown. */
    // Runs as its own job: the caller may be the monitor, which disconnect() cancels.
    private fun recover(attempt: Long) { scope.launch {
        // Cancelled by the user (stop / another server): nothing to report.
        if (generation.get() != attempt) return@launch
        if (autoRetries >= 2) { disconnect(); fail(); return@launch }
        Diagnostics.log("tunnel check failed, reconnect ${autoRetries + 1}/2")
        autoRetries++
        val (origin, name) = status.value.originCountry to status.value.name
        disconnect(keepForeground = true)
        getSystemService(NotificationManager::class.java).notify(1, notification("Переподключение…"))
        status.value = TunnelStatus(ConnectionState.CONNECTING, name, originCountry = origin)
        delay(if (autoRetries == 1) 1500 else 4000)
        if (generation.get() == attempt) connect(attempt, resume = true)
    } }
    private fun fail() {
        val kind = if (reachedConnected) "tunnel_stopped" else "connect_failed"
        Diagnostics.auto(kind, (if (reachedConnected) "Туннель упал и не восстановился после 2 переподключений" else "Не удалось подключиться после 2 попыток") + "\n" + lastFailure)
        status.value = status.value.copy(state = ConnectionState.ERROR, error = "Нет доступа через туннель. Обновите подписку или выберите другой сервер.")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    private fun disconnect(preserveError: Boolean = false, keepForeground: Boolean = false) {
        networkCallback?.let { callback -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) } }
        networkCallback = null
        physicalNetwork = null
        val updateState = engineOwner == null || engineOwner === this
        val previous = status.value
        monitor?.cancel(); monitor = null
        runCatching { session?.disconnect() }; session = null
        runCatching { tun?.close() }; tun = null
        http.connectionPool.evictAll()
        proxyHttp = null
        if (updateState) status.value = if (preserveError && previous.state == ConnectionState.ERROR) previous else TunnelStatus()
        if (!keepForeground) stopForeground(STOP_FOREGROUND_REMOVE)
    }
    private fun invalidate() {
        synchronized(transitionLock) { generation.incrementAndGet(); if (engineOwner == null || engineOwner === this) status.value = TunnelStatus() }
        activeCall?.cancel()
    }
    override fun onRevoke() { invalidate(); scope.launch { disconnect(); stopSelf() } }
    override fun onDestroy() {
        synchronized(transitionLock) { generation.incrementAndGet(); if (status.value.state != ConnectionState.ERROR && (engineOwner == null || engineOwner === this)) status.value = TunnelStatus() }
        activeCall?.cancel()
        // Cleanup is queued behind any in-flight native start, never run concurrently with it.
        scope.launch { disconnect(preserveError = true); scope.cancel() }
        super.onDestroy()
    }
}
