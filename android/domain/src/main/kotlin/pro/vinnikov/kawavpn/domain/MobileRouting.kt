package pro.vinnikov.kawavpn.domain

import com.google.gson.*
import java.net.InetAddress
import java.net.URI

data class MobileRule(val kind: String, val value: String, val target: String = "proxy") {
    fun validated(): MobileRule {
        require(kind in setOf("domain", "ip") && target in setOf("proxy", "direct", "block"))
        val v = value.trim()
        require(v.isNotEmpty() && v.length <= 512 && v.none { it.isWhitespace() || it == '\u0000' })
        if (kind == "ip") {
            val parts = v.split('/'); require(parts.size in 1..2)
            require(parts[0].matches(Regex("[0-9a-fA-F:.]+")))
            val address = runCatching { InetAddress.getByName(parts[0]) }.getOrElse { throw IllegalArgumentException("Неверный IP") }; val bits = address.address.size * 8
            if (parts.size == 2) require(parts[1].toIntOrNull() in 0..bits)
        } else require(v.matches(Regex("(?:(?:domain|full|geosite|keyword):)?[\\p{L}0-9_.-]+")) && !v.contains(".."))
        return copy(value = v)
    }
}
data class RoutingOptions(val rules: List<MobileRule> = emptyList(), val bypassLan: Boolean = false,
    val dns: String = "", val excludedApps: Set<String> = emptySet(),
    /** false: the chosen apps bypass the VPN; true: only the chosen apps use it. */
    val onlySelectedApps: Boolean = false) {
    fun validated(): RoutingOptions {
        require(rules.size <= 500 && excludedApps.size <= 500)
        require(excludedApps.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) })
        if (dns.isNotBlank()) {
            if (dns.startsWith("https://")) { val u=URI(dns); require(!u.host.isNullOrBlank() && u.userInfo == null && u.fragment == null) }
            else { require(dns.matches(Regex("[0-9a-fA-F:.]+"))); InetAddress.getByName(dns) }
        }
        return copy(rules = rules.map { it.validated() }, dns = dns.trim())
    }
}
internal object MobileRouting {
    fun apply(root: JsonObject, input: RoutingOptions) {
        val options = input.validated()
        if (options.dns.isNotBlank()) root.add("dns", JsonObject().apply { add("servers", JsonArray().apply { add(options.dns) }) })
        if (options.rules.isEmpty() && !options.bypassLan) return
        val outbounds = root["outbounds"].asJsonArray
        val proxy = outbounds.firstOrNull { it.asJsonObject["protocol"]?.asString !in setOf(null,"freedom","blackhole","dns","loopback") }?.asJsonObject
            ?: throw IllegalArgumentException("Нет VPN-сервера для маршрутизации")
        if (proxy["tag"]?.asString.isNullOrBlank()) proxy.addProperty("tag","kawa-proxy")
        fun target(mode: String): String {
            if (mode == "proxy") return proxy["tag"].asString
            val tag="kawa-$mode"
            if (outbounds.none { it.asJsonObject["tag"]?.asString == tag }) outbounds.add(JsonObject().apply {
                addProperty("tag",tag); addProperty("protocol",if(mode=="direct") "freedom" else "blackhole")
            })
            return tag
        }
        val routing = root["routing"]?.asJsonObject ?: JsonObject().also { root.add("routing",it) }
        val old = routing["rules"]?.asJsonArray?.toList().orEmpty()
        val health = old.filter { it.asJsonObject["inboundTag"]?.asJsonArray?.any { t -> t.asString=="kawa-health" } == true }
        val rules=JsonArray(); health.forEach { rules.add(it) }
        fun rule(kind:String,value:String,mode:String) { rules.add(JsonObject().apply {
            addProperty("type","field"); add(kind,JsonArray().apply { add(value) }); add("inboundTag",JsonArray().apply { add(RuntimeConfig.TUN_TAG) }); if(mode=="proxy") { val choice=ProxyChoice.choose(root);addProperty(choice.first,choice.second) } else addProperty("outboundTag",target(mode))
        }) }
        if(options.bypassLan) rule("ip","geoip:private","direct")
        options.rules.forEach { rule(it.kind,it.value,it.target) }
        old.filterNot { it in health }.forEach { rules.add(it) }
        routing.add("rules",rules)
    }
}
