package pro.vinnikov.kawavpn.domain

import com.google.gson.*

data class HealthProbe(val port: Int, val user: String, val password: String)

object RuntimeConfig {
    const val TUN_TAG = "kawa-tun"
    fun build(profile: VpnProfile, fd: Int, assetDirectory: String, ipv6: Boolean, probe: HealthProbe? = null, allTraffic: Boolean = false, options: RoutingOptions = RoutingOptions(), trafficStats: Boolean = false): String {
        require(fd > 0) { "Неверный дескриптор VPN" }
        val root = JsonParser.parseString(profile.configJson).asJsonObject.deepCopy()
        val sniffing = root["inbounds"]?.takeIf { it.isJsonArray }?.asJsonArray?.firstNotNullOfOrNull { inbound ->
            inbound.asJsonObject["sniffing"]?.takeIf { it.isJsonObject }?.deepCopy()
        } ?: JsonObject().apply {
            addProperty("enabled", true); addProperty("routeOnly", true)
            add("destOverride", JsonArray().apply { add("http"); add("tls"); add("quic") })
        }
        val oldTags = root["inbounds"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            it.takeIf { it.isJsonObject }?.asJsonObject?.get("tag")?.takeIf { v -> v.isJsonPrimitive }?.asString
        }?.toSet().orEmpty()
        val env = root["env"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        env.addProperty("xray.tun.fd", fd.toString())
        env.addProperty("xray.location.asset", assetDirectory)
        root.add("env", env)
        val settings = JsonObject().apply {
            addProperty("name", "Kawa VPN"); addProperty("MTU", 1500)
            add("gateway", JsonArray().apply { add("172.19.0.1/30"); if (ipv6) add("fdfe:dcba:9876::1/126") })
            add("dns", JsonArray().apply { add("1.1.1.1"); if (ipv6) add("2606:4700:4700::1111") })
        }
        root.add("inbounds", JsonArray().apply { add(JsonObject().apply { addProperty("tag", TUN_TAG); addProperty("protocol", "tun"); add("settings", settings); add("sniffing", sniffing) }) })
        root["routing"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { rule ->
            if (rule.isJsonObject) {
                val tags = rule.asJsonObject["inboundTag"]?.takeIf { it.isJsonArray }?.asJsonArray
                if (tags != null && tags.any { it.isJsonPrimitive && it.asString in oldTags } && tags.none { it.asString == TUN_TAG }) tags.add(TUN_TAG)
            }
        }
        // The source keeps DNS, routing, balancers and unknown root options;
        // only external listeners and the OS-owned TUN descriptor are replaced.
        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        if (probe != null) {
            require(probe.port in 1024..65535 && probe.user.isNotEmpty() && probe.password.isNotEmpty())
            val outbound = root["outbounds"].asJsonArray.firstOrNull { element ->
                element.asJsonObject["protocol"]?.asString !in listOf(null, "freedom", "blackhole", "dns", "loopback")
            }?.asJsonObject ?: throw IllegalArgumentException("В конфигурации нет прокси для проверки защиты")
            if (outbound["tag"]?.asString.isNullOrBlank()) outbound.addProperty("tag", "kawa-health-target")
            root["inbounds"].asJsonArray.add(JsonObject().apply {
                addProperty("tag", "kawa-health"); addProperty("listen", "127.0.0.1"); addProperty("port", probe.port); addProperty("protocol", "http")
                add("settings", JsonObject().apply { add("accounts", JsonArray().apply { add(JsonObject().apply { addProperty("user", probe.user); addProperty("pass", probe.password) }) }) })
            })
            val routing = root["routing"]?.asJsonObject ?: JsonObject().also { root.add("routing", it) }
            val destination=ProxyChoice.choose(root)
            val rules = JsonArray().apply {
                add(JsonObject().apply { addProperty("type", "field"); add("inboundTag", JsonArray().apply { add("kawa-health") }); addProperty(destination.first,destination.second) })
                if (allTraffic) add(JsonObject().apply { addProperty("type", "field"); add("inboundTag", JsonArray().apply { add(TUN_TAG) }); addProperty(destination.first,destination.second) })
                routing["rules"]?.asJsonArray?.forEach { add(it) }
            }
            routing.add("rules", rules)
        }
        MobileRouting.apply(root, options)
        if (trafficStats) {
            require(probe != null)
            root.add("stats", root["stats"]?.takeIf { it.isJsonObject } ?: JsonObject())
            val policy=root["policy"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { root.add("policy",it) }
            val system=policy["system"]?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { policy.add("system",it) }
            system.addProperty("statsInboundUplink",true); system.addProperty("statsInboundDownlink",true)
            // No metrics listener: the only access is our random-password HTTP proxy.
            root.add("metrics",JsonObject().apply { addProperty("tag","kawa-private-metrics") })
            val rules=root["routing"].asJsonObject["rules"].asJsonArray
            root["routing"].asJsonObject.add("rules",JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type","field");add("inboundTag",JsonArray().apply { add("kawa-health") })
                    add("ip",JsonArray().apply { add("127.0.0.1") });addProperty("port","33217");addProperty("outboundTag","kawa-private-metrics")
                })
                rules.forEach { add(it) }
            })
        }
        return root.toString()
    }
}
