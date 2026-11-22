package pro.vinnikov.kawavpn.domain

import com.google.gson.JsonObject
import java.net.URI

/** Same negotiation as desktop: full source JSON wins over projected share links. */
object SubscriptionContract {
    fun jsonUrl(url: String): String? {
        val uri = URI(NetworkContract.subscriptionUrl(url))
        if (uri.path.trimEnd('/').endsWith("/json")) return null
        // Keep the raw query (subscription credentials may be URL encoded).
        return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath.trimEnd('/')}/json" +
            (uri.rawQuery?.let { "?$it" } ?: "")
    }
    fun fetch(url: String, get: (String) -> String, convert: ((String) -> List<JsonObject>)? = null): List<VpnProfile> {
        val base = runCatching { get(NetworkContract.subscriptionUrl(url)) }
        val parsed = base.mapCatching { ProfileParser.parse(it, convert) }
        if (base.isSuccess && base.getOrThrow().trimStart().let { it.startsWith("{") || it.startsWith("[") } && parsed.isSuccess)
            return parsed.getOrThrow()
        jsonUrl(url)?.let { json ->
            runCatching { ProfileParser.parse(get(json), convert) }.getOrNull()?.let { return it }
        }
        return parsed.getOrThrow()
    }
}
