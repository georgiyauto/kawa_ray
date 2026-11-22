package pro.vinnikov.kawavpn.vpn

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libXray.DialerController
import libXray.LibXray
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

/** Manifest must declare this unexported service in the private :probe process. */
class ProbeService : Service() {
    companion object {
        // Survives Service recreation: old native calls and their cleanup finish before a new batch.
        private val nativeDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val batches = Mutex()
        // libXray keeps the first controller registered in a process: registering a new one per
        // batch (and a rejecting one after it) left every later batch failing instantly. One
        // process-wide controller is registered once and delegates to the current batch.
        @Volatile private var current: ((Long) -> Boolean)? = null
        private val shared = DialerController { fd -> current?.invoke(fd) ?: false }
        private val registered = java.util.concurrent.atomic.AtomicBoolean(false)
        private fun install() {
            if (registered.compareAndSet(false, true)) {
                LibXray.registerDialerController(shared)
                LibXray.registerListenerController(shared)
            }
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = ConcurrentHashMap<String, Job>()
    private val endpoint = object : Binder() {
        @Suppress("DEPRECATION")
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != ProbeWire.PING && code != ProbeWire.CANCEL) return super.onTransact(code, data, reply, flags)
            check(Binder.getCallingUid() == Process.myUid())
            data.enforceInterface(ProbeWire.DESCRIPTOR)
            val requestId = data.readString().orEmpty()
            check(requestId.length in 1..64)
            if (code == ProbeWire.CANCEL) {
                requests.remove(requestId)?.cancel()
                reply?.writeNoException()
                return true
            }
            check(data.dataSize() <= ProbeWire.LIMIT)
            val payload = data.readString().orEmpty()
            val callback = ResultReceiver.CREATOR.createFromParcel(data)
            check(ProbeWire.fits(payload))
            val job = scope.launch(start = CoroutineStart.LAZY) { runBatch(payload, callback) }
            check(requests.putIfAbsent(requestId, job) == null)
            job.invokeOnCompletion { requests.remove(requestId, job) }
            job.start()
            reply?.writeNoException()
            return true
        }
    }
    override fun onBind(intent: Intent?): IBinder = endpoint
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private suspend fun runBatch(payload: String, callback: ResultReceiver?) {
        var delays = longArrayOf()
        try {
            batches.withLock {
                val binding = ProbeBinding(this, Intent(this, KawaVpnService::class.java).setAction(ProbeProtection.ACTION))
                try {
                    // No consent prompt for standalone pings. A prepared app protects every socket.
                    val protection = if (VpnService.prepare(this) == null) withTimeout(5_000) { binding.connect() } else null
                    delays = withContext(nativeDispatcher) {
                        val decide: (Long) -> Boolean = { fd ->
                            if (protection != null) ProbeProtection.protect(protection, fd)
                            else VpnService.prepare(this@ProbeService) != null
                        }
                        try {
                            ensureActive()
                            install()
                            current = decide
                            val dns = physicalDns()
                            // A system resolver could otherwise send unprotected DNS into our VPN.
                            check(dns != null || protection == null)
                            dns?.let { LibXray.setDNS(shared, it) }
                            val request = JsonParser.parseString(payload).asJsonObject
                            val count = request["configs"].asJsonArray.size()
                            check(count in 1..5)
                            // Only the internal client selects timeout and target; enforce both again.
                            request.addProperty("timeout", 8)
                            request.addProperty("url", "https://www.gstatic.com/generate_204")
                            val results = NativeEngineBridge.invoke("pingBatch", request).asJsonObject["results"].asJsonArray
                            LongArray(count) { index ->
                                runCatching {
                                    val item = results[index].asJsonObject
                                    item["delay"].asLong.takeIf { item["success"].asBoolean && it >= 0 } ?: -1L
                                }.getOrDefault(-1L)
                            }
                        } finally {
                            // Blocking JNI cannot be interrupted. Cleanup runs here, on the same
                            // dispatcher, even if the client unbound and this Service was destroyed.
                            runCatching { LibXray.resetDNS() }
                            // Between batches nothing may dial: the shared controller rejects.
                            current = null
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) { binding.close() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { /* Native errors can contain profile secrets: log the type only. */ android.util.Log.w("KawaVPN", "probe batch failed: ${e.javaClass.simpleName}") }
        finally {
            runCatching { callback?.send(0, Bundle().apply { putLongArray(ProbeWire.DELAYS, delays) }) }
        }
    }

    private fun physicalDns(): String? = runCatching {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val network = connectivity.allNetworks.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.let { capabilities ->
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }
        network?.let { connectivity.getLinkProperties(it) }?.dnsServers
            ?.firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            ?.hostAddress?.let { if (it.contains(':')) "[$it]:53" else "$it:53" }
    }.getOrNull()
}
