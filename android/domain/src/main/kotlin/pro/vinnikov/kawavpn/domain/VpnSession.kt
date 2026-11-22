package pro.vinnikov.kawavpn.domain

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }
interface EngineDriver { fun start(); fun stop(); fun running(): Boolean }

/** One serial owner must call the lifecycle, normally the service IO dispatcher. */
class VpnSession(private val driver: EngineDriver, private val verifyConnectivity: () -> Boolean) {
    var state = ConnectionState.DISCONNECTED; private set
    var error: String? = null; private set
    fun connect(): Boolean {
        check(state != ConnectionState.CONNECTED && state != ConnectionState.CONNECTING)
        state = ConnectionState.CONNECTING; error = null
        return try {
            driver.start()
            check(driver.running() && verifyConnectivity())
            state = ConnectionState.CONNECTED; true
        } catch (_: Exception) {
            runCatching { driver.stop() }
            state = ConnectionState.ERROR
            error = "Соединение не прошло проверку. Выберите другой сервер или обновите подписку."
            false
        }
    }
    fun disconnect() {
        try { driver.stop() } finally { state = ConnectionState.DISCONNECTED; error = null }
    }
}
