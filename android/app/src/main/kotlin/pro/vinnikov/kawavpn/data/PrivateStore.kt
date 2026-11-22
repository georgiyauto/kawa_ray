package pro.vinnikov.kawavpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import pro.vinnikov.kawavpn.domain.VpnProfile
import pro.vinnikov.kawavpn.domain.RoutingOptions
import pro.vinnikov.kawavpn.domain.PendingCheckout
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SavedProfile(val profile: VpnProfile, val source: String, val managed: Boolean = false, val originalJson: String? = null)
data class PendingLogin(val token: String, val url: String, val expiresAt: Long)
/** Happ's request fingerprint for panels that only serve Happ (desktop identity.rs). */
data class HappSpoof(val enabled: Boolean = false, val appVersion: String = "3.23.0", val os: String = "Android",
    val osVersion: String = android.os.Build.VERSION.RELEASE, val model: String = android.os.Build.MODEL, val locale: String = "ru",
    val userId: String = randomUserId(), val hwid: String = randomHwid()) {
    fun userAgent() = "Happ/$appVersion/$os/$userId"
    fun newIds() = copy(userId = randomUserId(), hwid = randomHwid())
    fun randomDevice(): HappSpoof {
        val (os, versions, models) = DEVICES.random()
        return copy(os = os, osVersion = versions.random(), model = models.random(), appVersion = listOf("3.23.0", "3.22.1", "3.21.1", "3.21.0", "3.20.4").random(),
            locale = listOf("ru", "en", "uk", "tr", "de", "fa").random()).newIds()
    }
    companion object {
        private val DEVICES = listOf(
            Triple("macOS", listOf("14.7", "15.5", "15.6", "26.0", "26.3"), listOf("Mac14,2", "Mac15,3", "Mac15,6", "Mac16,1", "Mac16,8")),
            Triple("Windows", listOf("10.0.19045", "10.0.22631", "10.0.26100"), listOf("Desktop PC", "Laptop")),
            Triple("iOS", listOf("17.6", "18.3", "18.6", "26.0"), listOf("iPhone15,3", "iPhone16,1", "iPhone17,1", "iPhone17,3")),
            Triple("Android", listOf("13", "14", "15", "16"), listOf("Pixel 8", "Pixel 9 Pro", "SM-S921B", "SM-S928B", "Redmi Note 13 Pro")))
        fun randomUserId(): String = (1..9).random().toString() + (1..19).joinToString("") { (0..9).random().toString() }
        fun randomHwid(): String = "%016x".format(java.security.SecureRandom().nextLong())
    }
}
data class SubscriptionInfo(val title: String? = null, val download: Long? = null, val upload: Long? = null, val total: Long? = null, val expire: Long? = null, val support: String? = null)
data class AccountSession(val access_token: String, val refresh_token: String, val userId: Long? = null)
data class CabinetSubscription(val id: Long, val tariff_id: Long?, val tariff_name: String?, val status: String, val end_date: String?)
data class CabinetPeriod(val days: Long, val label: String, val price_kopeks: Long, val price_label: String)
data class CabinetTariff(val id: Long, val name: String, val description: String?, val periods: List<CabinetPeriod>)
data class CabinetPaymentOption(val id: String, val name: String)
data class CabinetData(val subscriptions: List<CabinetSubscription> = emptyList(), val tariffs: List<CabinetTariff> = emptyList(),
    val paymentOptions: List<CabinetPaymentOption> = emptyList(), val minPayment: Long = 1000, val maxPayment: Long? = null, val balanceKopeks: Long = 0)
data class AppData(
    val profiles: List<SavedProfile> = emptyList(), val selected: String? = null,
    val session: AccountSession? = null, val ipv6: Boolean = true, val reducedMotion: Boolean = false,
    val subscriptionUrls: List<String> = emptyList(), val cabinet: CabinetData = CabinetData(),
    val pendingOrders: Map<String, PendingCheckout> = emptyMap(), val allTraffic: Boolean = false, val favorites: Set<String> = emptySet(),
    val pendingLogin: PendingLogin? = null, val deviceId: String = java.util.UUID.randomUUID().toString(),
    val sendDeviceInfo: Boolean = true, val routing: RoutingOptions = RoutingOptions(), val subscriptionInfo: Map<String, SubscriptionInfo> = emptyMap(), val textScale: Float = 1f, val syncWarning: String? = null, val autoRefresh: Boolean = true, val realPing: Boolean = true,
    /** Automatic error reports: null until the first-start question is answered. */
    val telemetry: Boolean? = null, val reportInstallId: String? = null,
    /** Connect when the app opens; restart Xray on Hysteria every N minutes (as on the desktop). */
    val autoConnect: Boolean = false, val hysteriaRestart: Boolean = false, val hysteriaMinutes: Int = 60,
    val happ: HappSpoof? = null,
    /** "dark", "light" or "system". */
    val theme: String = "dark"
)

/** No plaintext fallback: invalidated keys or damaged ciphertext require explicit reset. */
class PrivateStore(context: Context) {
    companion object { private val lock = Any() }
    private val file = AtomicFile(File(context.noBackupFilesDir, "kawa-private.v1"))
    private val alias = "kawa.private.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun read(): AppData = synchronized(lock) {
        if (!file.baseFile.exists()) return AppData().also { write(it) }
        val bytes = file.readFully()
        check(bytes.size > 28 && bytes[0] == 1.toByte()) { "Хранилище повреждено" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        return Gson().fromJson(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8), AppData::class.java)
    }
    fun write(data: AppData) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(Gson().toJson(data).toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun update(change: (AppData) -> AppData): AppData = synchronized(lock) { change(read()).also { write(it) } }
}
