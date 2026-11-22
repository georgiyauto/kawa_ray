package pro.vinnikov.kawavpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

data class ProbeConfig(val json: String, val outboundTag: String? = null)

/** Profiles cross Binder only in memory; callbacks never contain native error text. */
object NativeProbeClient {
    suspend fun ping(context: Context, configs: List<ProbeConfig>): List<Long?> {
        require(configs.size <= 5) { "A probe batch supports at most five profiles" }
        if (configs.isEmpty()) return emptyList()
        val failed = List<Long?>(configs.size) { null }
        val payload = JsonObject().apply {
            add("configs", JsonArray().apply { configs.forEach { config ->
                add(JsonObject().apply {
                    addProperty("xrayJson", config.json)
                    config.outboundTag?.let { addProperty("outboundTag", it) }
                })
            } })
            addProperty("timeout", 8)
            addProperty("url", "https://www.gstatic.com/generate_204")
        }.toString()
        if (!ProbeWire.fits(payload)) return failed
        return try {
            withTimeout(25_000) {
                val result = CompletableDeferred<List<Long?>>()
                val callback = ProbeReceiver(configs.size, result)
                val connection = ProbeBinding(context.applicationContext, Intent(context, ProbeService::class.java))
                val requestId = UUID.randomUUID().toString()
                var remote: IBinder? = null
                val death = IBinder.DeathRecipient { result.complete(failed) }
                try {
                    val connected = connection.connect()
                    remote = connected
                    connected.linkToDeath(death, 0)
                    withContext(Dispatchers.IO) {
                        val request = Parcel.obtain()
                        val response = Parcel.obtain()
                        try {
                            request.writeInterfaceToken(ProbeWire.DESCRIPTOR)
                            request.writeString(requestId)
                            request.writeString(payload)
                            // Encode the framework receiver itself, not the private subclass name.
                            callback.writeToParcel(request, 0)
                            check(connected.transact(ProbeWire.PING, request, response, 0))
                            response.readException()
                        } finally { response.recycle(); request.recycle() }
                    }
                    result.await()
                } finally {
                    callback.detach()
                    withContext(NonCancellable + Dispatchers.IO) {
                        val connected = remote
                        if (connected != null) runCatching {
                            val request = Parcel.obtain()
                            val response = Parcel.obtain()
                            try {
                                request.writeInterfaceToken(ProbeWire.DESCRIPTOR)
                                request.writeString(requestId)
                                connected.transact(ProbeWire.CANCEL, request, response, 0)
                                response.readException()
                            } finally { response.recycle(); request.recycle() }
                        }
                    }
                    runCatching { remote?.unlinkToDeath(death, 0) }
                    withContext(NonCancellable + Dispatchers.Main.immediate) { connection.close() }
                }
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (cancelled is TimeoutCancellationException) failed else throw cancelled
        } catch (_: Exception) { failed }
    }
}

internal object ProbeWire {
    const val DESCRIPTOR = "pro.vinnikov.kawavpn.NativeProbe"
    const val PING = IBinder.FIRST_CALL_TRANSACTION
    const val CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1
    const val LIMIT = 512 * 1024
    const val DELAYS = "delays"
    fun fits(payload: String): Boolean {
        if (payload.length > LIMIT / 2) return false
        val parcel = Parcel.obtain()
        return try { parcel.writeString(payload); parcel.dataSize() <= LIMIT - 4096 }
        finally { parcel.recycle() }
    }
}

private class ProbeReceiver(count: Int, deferred: CompletableDeferred<List<Long?>>) : ResultReceiver(null) {
    private val expected = count
    private val target = AtomicReference(deferred)
    fun detach() { target.set(null) }
    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        val delays = resultData?.getLongArray(ProbeWire.DELAYS)
        target.getAndSet(null)?.complete(List(expected) { index -> delays?.getOrNull(index)?.takeIf { it >= 0 } })
    }
}

/** Always bind/unbind on main, including cancellation during asynchronous binding. */
internal class ProbeBinding(private val context: Context, private val intent: Intent) : ServiceConnection {
    private val ready = CompletableDeferred<IBinder>()
    private var bound = false
    suspend fun connect(): IBinder = withContext(Dispatchers.Main.immediate) {
        bound = context.bindService(intent, this@ProbeBinding, Context.BIND_AUTO_CREATE)
        check(bound)
        ready.await()
    }
    override fun onServiceConnected(name: ComponentName, service: IBinder) { ready.complete(service) }
    override fun onServiceDisconnected(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Probe disconnected")) }
    override fun onNullBinding(name: ComponentName) { ready.completeExceptionally(IllegalStateException("Probe unavailable")) }
    override fun onBindingDied(name: ComponentName) { onNullBinding(name) }
    fun close() { if (bound) { bound = false; runCatching { context.unbindService(this) } } }
}
