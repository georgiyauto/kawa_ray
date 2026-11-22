package pro.vinnikov.kawavpn.diag

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pro.vinnikov.kawavpn.BuildConfig
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Error reports, the Android side of the desktop's diag.rs: the same hub
 * (POST /v1/report → stored, then sent to support in Telegram), the same
 * report shape and the same sanitizer, so a phone report reads like a PC one.
 *
 * Automatic reports (a tunnel that died, a connect that failed, a crash)
 * need the user's consent; "Сообщить о проблеме" is an explicit action and is
 * always sent. Every text that leaves goes through [sanitize]: links, IPs,
 * host names, UUIDs, e-mails and keys never reach the hub. Reports wait as
 * files and are retried, so a crash or a dead network loses nothing.
 */
object Diagnostics {
    private val HUBS = listOf("https://hub.example.com/kawa-app", "https://hub-mirror.example.com/kawa-app")
    private const val LOG_LINES = 300
    private const val AUTO_LIMIT = 20

    private val enabled = AtomicBoolean(false)
    private val sentAuto = AtomicInteger(0)
    private val seen = Collections.synchronizedSet(HashSet<String>())
    private val log = ArrayDeque<String>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "kawa-reports").apply { isDaemon = true } }
    private val random = SecureRandom()
    private val started = SystemClock.elapsedRealtime()
    @Volatile private var queue: File? = null
    @Volatile private var runDir: File? = null
    @Volatile private var installId = ""
    @Volatile private var device = JsonObject()
    @Volatile var context: JsonObject = JsonObject()
    private val http by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
    }

    /** Where reports and Xray's log live; installs the crash handler. Call once per process. */
    fun init(app: Context) {
        if (queue != null) return
        queue = File(app.filesDir, "reports").apply { mkdirs() }
        runDir = File(app.filesDir, "run").apply { mkdirs() }
        device = deviceMetadata(app)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written synchronously: the process is about to die. Sent on the next start.
            runCatching { if (enabled.get()) build("crash", error.stackTraceToString(), null)?.let(::enqueue) }
            previous?.uncaughtException(thread, error)
        }
        flush()
    }

    /** Applies the consent setting (null = not answered yet: nothing automatic). */
    fun configure(consent: Boolean?, id: String) {
        installId = id
        enabled.set(consent == true)
        if (consent == false) queue?.listFiles()?.forEach { file -> runCatching { val report = JsonParser.parseString(file.readText()).asJsonObject; if (report["kind"]?.asString != "user_report") file.delete() } }
        else if (consent == true) flush()
    }

    /** A random id for grouping one install's reports (not the device id). */
    fun newInstallId(): String = UUID.randomUUID().toString()

    /** The Xray error log of the current tunnel. */
    fun xrayLog(): File? = runDir?.let { File(it, "xray.log") }

    /** One line for the activity log sent with reports (sanitized right away). */
    fun log(line: String) {
        val entry = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " " + sanitize(line)
        synchronized(log) { if (log.size == LOG_LINES) log.removeFirst(); log.addLast(entry) }
        android.util.Log.i("KawaVPN", entry)
    }

    /** Reports an error automatically, if the user agreed. The same error once per run. */
    fun auto(kind: String, message: String) {
        log("$kind: ${message.lineSequence().firstOrNull().orEmpty()}")
        if (!enabled.get() || sentAuto.get() >= AUTO_LIMIT) return
        if (!seen.add(signature(kind, message))) return
        sentAuto.incrementAndGet()
        worker.execute { build(kind, message, null)?.let { runCatching { enqueue(it) }; sendQueued() } }
    }

    /** What "Сообщить о проблеме" sends, for the preview. */
    fun manualReport(note: String): JsonObject = build("user_report", "", note) ?: JsonObject()

    /** Sends a report now. Returns its code (KW-…) and whether it waits in the queue. Blocking. */
    fun sendNow(report: JsonObject): Pair<String, Boolean> {
        val id = report["report_id"]?.asString.orEmpty()
        return when (val result = post(report)) {
            is Post.Ok -> result.id.ifEmpty { id } to false
            is Post.Later -> { enqueue(report); id to true }
            is Post.Rejected -> throw IllegalStateException("Сервер отклонил отчёт")
        }
    }

    /** Sends what waits in the queue, in the background. */
    fun flush() { worker.execute { sendQueued() } }

    private fun sendQueued() {
        val files = queue?.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.lastModified() } ?: return
        for (file in files) {
            val report = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
            if (report == null) { file.delete(); continue }
            if (report["kind"]?.asString != "user_report" && !enabled.get()) continue
            when (post(report)) { is Post.Ok, is Post.Rejected -> file.delete(); is Post.Later -> return }
        }
    }

    private sealed interface Post { data class Ok(val id: String) : Post; data object Later : Post; data object Rejected : Post }

    private fun post(report: JsonObject): Post {
        val body = Gson().toJson(report).toRequestBody("application/json".toMediaType())
        for (hub in HUBS) {
            val result = runCatching {
                http.newCall(Request.Builder().url("$hub/v1/report").header("User-Agent", "KawaVPN-Android/${BuildConfig.VERSION_NAME}").post(body).build()).execute().use { response ->
                    when (response.code) {
                        201 -> Post.Ok(runCatching { JsonParser.parseString(response.body.string()).asJsonObject["id"].asString }.getOrDefault(""))
                        400, 404, 413 -> Post.Rejected
                        else -> Post.Later
                    }
                }
            }.getOrDefault(Post.Later)
            if (result !is Post.Later) return result
        }
        return Post.Later
    }

    private fun enqueue(report: JsonObject) {
        val dir = queue ?: return
        val id = report["report_id"]?.asString ?: return
        val part = File(dir, "$id.part")
        part.writeText(Gson().toJson(report))
        part.renameTo(File(dir, "$id.json"))
        // A dead network must not grow the queue without bound.
        dir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() }?.drop(30)?.forEach { it.delete() }
    }

    private fun build(kind: String, message: String, note: String?): JsonObject? = runCatching {
        val text = bounded(sanitize(message), 32 * 1024)
        JsonObject().apply {
            addProperty("report_id", "KW-%016X".format(random.nextLong()))
            addProperty("kind", kind)
            addProperty("message", text)
            addProperty("signature", signature(kind, text))
            addProperty("version", BuildConfig.VERSION_NAME)
            addProperty("build", "${BuildConfig.VERSION_NAME}-android-${BuildConfig.VERSION_CODE}")
            addProperty("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            addProperty("arch", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
            addProperty("install_id", installId)
            addProperty("uptime_s", (SystemClock.elapsedRealtime() - started) / 1000)
            add("device", device)
            add("privacy", JsonObject().apply { addProperty("device_info_and_ip", true); addProperty("retention_days", 90); addProperty("ip_source", "report_server_observed"); addProperty("provider_ip_outside_vpn", false) })
            add("context", context)
            addProperty("log", bounded(synchronized(log) { log.joinToString("\n") }, 48 * 1024))
            note?.let { addProperty("note", bounded(sanitize(it), 8 * 1024)) }
            if (kind in WITH_LOGS) xrayLog()?.takeIf { it.exists() }?.let { file -> tail(file, 40)?.let { addProperty("xray_log", bounded(sanitize(it), 32 * 1024)) } }
        }
    }.getOrNull()

    private val WITH_LOGS = setOf("crash", "user_report", "connect_failed", "tunnel_stopped")

    private fun tail(file: File, lines: Int): String? = runCatching {
        val bytes = file.length().coerceAtMost(64 * 1024)
        java.io.RandomAccessFile(file, "r").use { it.seek(file.length() - bytes); val buf = ByteArray(bytes.toInt()); it.readFully(buf); String(buf, Charsets.UTF_8) }
            .lines().filter { it.isNotBlank() }.takeLast(lines).joinToString("\n")
    }.getOrNull()

    private fun deviceMetadata(app: Context) = JsonObject().apply {
        addProperty("os", "android")
        addProperty("arch", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        addProperty("model", sanitize("${Build.MANUFACTURER} ${Build.MODEL}"))
        addProperty("cpu", sanitize(if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE))
        addProperty("cpu_threads", Runtime.getRuntime().availableProcessors())
        runCatching { ActivityManager.MemoryInfo().also { app.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }.getOrNull()?.let { addProperty("ram_bytes", it) }
    }

    private fun bounded(text: String, limit: Int): String {
        var out = text
        while (out.toByteArray().size > limit) out = out.take((out.length * 9) / 10)
        return out
    }

    private fun signature(kind: String, message: String): String {
        val first = message.lineSequence().firstOrNull().orEmpty().replace(Regex("\\d+"), "#")
        val digest = java.security.MessageDigest.getInstance("SHA-1").digest("$kind\n$first".toByteArray())
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    private val RULES = listOf(
        Regex("(?i)\\b(password|passwd|token|authorization|secret|refresh_token|access_token|api[_-]?key|private[_-]?key)\\b[\"']?\\s*[:=]\\s*[\"']?[^\\s,;\"}]+") to "$1=<secret>",
        Regex("(?i)\\b[a-z][a-z0-9+.\\-]{1,15}://\\S+") to "<url>",
        Regex("(?i)\\b[\\w.+\\-]+@[\\w\\-]+(\\.[\\w\\-]+)+") to "<email>",
        Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b") to "<uuid>",
        Regex("(?i)\\[?\\b(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}\\b\\]?(:\\d+)?") to "<ip>",
        Regex("(?i)\\[?\\b(?:[0-9a-f]{1,4}:)+:(?:[0-9a-f]{1,4}:?)*[0-9a-f]{0,4}\\b\\]?(:\\d+)?") to "<ip>",
        Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b") to "<ip>",
        Regex("/data/(user/\\d+|data)/[^/\\s]+") to "/data/<app>",
    )
    private val HOST = Regex("(?i)\\b(?:[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?\\.)+(?:[a-z][a-z0-9\\-]{1,62})\\b")
    private val FILE_EXT = Regex("(?i)\\.(json|dat|log|txt|png|so|kt|java|conf|ya?ml|pem)$")
    // Java/Kotlin frames ("pro.vinnikov.kawavpn.vpn.KawaVpnService.connect") are kept.
    private val CODE = Regex("^(java|javax|kotlin|kotlinx|android|androidx|com\\.android|com\\.google|okhttp3|okio|pro\\.vinnikov|libXray|go|dalvik|sun|com\\.kyant)\\.")
    private val TOKEN = Regex("[A-Za-z0-9+/_=]{16,}")

    /** Removes what identifies users or servers; the desktop's rules plus Android paths. */
    fun sanitize(text: String): String {
        var out = text
        for ((re, with) in RULES) out = re.replace(out, with)
        out = HOST.replace(out) { m -> if (FILE_EXT.containsMatchIn(m.value) || CODE.containsMatchIn(m.value)) m.value else "<host>" }
        return TOKEN.replace(out) { m -> if (m.value.any(Char::isDigit)) "<secret>" else m.value }
    }
}
