package pro.vinnikov.kawavpn.domain

import com.google.gson.*
import java.security.MessageDigest
import java.util.Base64

data class VpnProfile(val id: String, val name: String, val configJson: String)

/**
 * Panels answer an expired/limited/disabled subscription with placeholder
 * entries ("⌛ Подписка истекла", "Продлите подписку в боте") whose servers
 * point at 0.0.0.0. They are messages, not servers: shown as a notice and
 * never selected or connected.
 */
object ProfileNotice {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val service = setOf(null, "freedom", "blackhole", "dns", "loopback")
    fun of(profile: VpnProfile): Boolean = cache.getOrPut(profile.id) { compute(profile.configJson) }
    internal fun compute(json: String): Boolean = runCatching {
        val proxies = JsonParser.parseString(json).asJsonObject["outbounds"].asJsonArray.map { it.asJsonObject }
            .filter { it["protocol"]?.takeIf { p -> p.isJsonPrimitive }?.asString !in service }
        if (proxies.isEmpty()) return@runCatching false
        proxies.all { outbound ->
            val settings = outbound["settings"]?.takeIf { it.isJsonObject }?.asJsonObject ?: return@all false
            val servers = (settings["vnext"] ?: settings["servers"])?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asJsonObject }
                ?: listOf(settings)
            servers.isNotEmpty() && servers.all { it["address"]?.asString in setOf("0.0.0.0", "::", "[::]") }
        }
    }.getOrDefault(false)
}

object ProfileParser {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun parse(input: String, convertLinks: ((String) -> List<JsonObject>)? = null): List<VpnProfile> {
        require(input.toByteArray().size <= MAX_BYTES) { "Подписка слишком большая" }
        var text = input.trim().removePrefix("\uFEFF")
        require(text.isNotEmpty() && !text.startsWith("<")) { "Вместо подписки получена пустая страница или HTML" }
        if (!text.startsWith("{") && !text.startsWith("[") && !text.contains("://")) {
            text = runCatching { String(Base64.getMimeDecoder().decode(text), Charsets.UTF_8).trim() }
                .getOrElse { throw IllegalArgumentException("Неверный формат подписки") }
        }
        val configs: List<JsonObject> = if (text.startsWith("{") || text.startsWith("[")) {
            val value = runCatching { JsonParser.parseString(text) }.getOrElse { throw IllegalArgumentException("Неверный JSON подписки") }
            val elements = when {
                value.isJsonArray -> value.asJsonArray.toList()
                value.isJsonObject && value.asJsonObject.has("outbounds") -> listOf(value)
                value.isJsonObject && value.asJsonObject.has("configs") -> value.asJsonObject["configs"].asJsonArray.toList()
                else -> emptyList()
            }
            elements.filter { it.isJsonObject && it.asJsonObject["outbounds"]?.isJsonArray == true }.map { it.asJsonObject }
        } else {
            require(text.contains("://")) { "Неверный формат подписки" }
            requireNotNull(convertLinks) { "Для ссылок требуется движок Xray" }.invoke(text)
        }
        val profiles = configs.filter { it["outbounds"].asJsonArray.size() > 0 }.mapIndexed { index, json ->
            val raw = json.toString()
            val id = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
            val name = listOf("remarks", "name", "ps").firstNotNullOfOrNull { key -> json[key]?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }
                ?: "Сервер ${index + 1}"
            VpnProfile(id, name.take(160), raw)
        }.filterNot { profile ->
            // Panels encode unsupported-client notices as dummy VLESS/JSON nodes.
            val name = profile.name.lowercase()
            listOf("не поддерживается", "перейдите в другое приложение", "unsupported client", "not supported", "update your client").any { it in name }
        }.distinctBy { it.id }
        require(profiles.isNotEmpty()) { "В подписке нет доступных конфигураций" }
        return profiles
    }
}
