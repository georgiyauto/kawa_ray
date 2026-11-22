package pro.vinnikov.kawavpn.domain
import com.google.gson.*

internal object ProxyChoice {
    private val direct=setOf(null,"freedom","blackhole","dns","loopback")
    fun choose(root:JsonObject):Pair<String,String> {
        val routing=root["routing"]?.takeIf { it.isJsonObject }?.asJsonObject
        val balancers=routing?.get("balancers")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.asJsonObject["tag"]?.asString }?.toSet().orEmpty()
        val inboundTags=root["inbounds"]?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.asJsonObject["tag"]?.asString }?.toSet().orEmpty()+RuntimeConfig.TUN_TAG
        val global=routing?.get("rules")?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull { element ->
            val r=element.asJsonObject
            r["balancerTag"]?.asString in balancers &&
                listOf("domain","ip","port","sourcePort","source","user","protocol","attrs").none { r.has(it) } &&
                (r["network"]==null || r["network"].asString.contains("tcp")) &&
                (r["inboundTag"]==null || r["inboundTag"].asJsonArray.any { it.asString in inboundTags })
        }?.asJsonObject
        if(global!=null) return "balancerTag" to global["balancerTag"].asString
        val outbound=root["outbounds"].asJsonArray.firstOrNull { it.asJsonObject["protocol"]?.asString !in direct }?.asJsonObject
            ?: throw IllegalArgumentException("Нет VPN-сервера для маршрутизации")
        if(outbound["tag"]?.asString.isNullOrBlank()) outbound.addProperty("tag","kawa-proxy")
        return "outboundTag" to outbound["tag"].asString
    }
}
/** Real probes follow every candidate of an automatic region, retaining chained outbounds. */
object ProfileProbeTargets {
    fun tags(json:String):List<String> {
        val root=JsonParser.parseString(json).asJsonObject
        val originalTags=root["outbounds"].asJsonArray.mapNotNull { it.asJsonObject["tag"]?.asString }.toSet()
        val choice=ProxyChoice.choose(root)
        if(choice.first=="outboundTag") return if(choice.second in originalTags) listOf(choice.second) else emptyList()
        val balance=root["routing"].asJsonObject["balancers"].asJsonArray.first { it.asJsonObject["tag"]?.asString==choice.second }.asJsonObject
        val selectors=balance["selector"]?.asJsonArray?.map { it.asString }.orEmpty()
        return root["outbounds"].asJsonArray.map { it.asJsonObject }.filter { it["protocol"]?.asString !in setOf(null,"freedom","blackhole","dns","loopback") }
            .mapNotNull { it["tag"]?.asString }.filter { tag -> selectors.any { tag.startsWith(it) } }.distinct().take(20)
    }
}
