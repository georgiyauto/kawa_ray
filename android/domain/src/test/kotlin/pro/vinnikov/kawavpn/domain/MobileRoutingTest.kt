package pro.vinnikov.kawavpn.domain
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
class MobileRoutingTest {
    private val source = ProfileParser.parse("""{"outbounds":[{"protocol":"vless","tag":"vpn"}],"routing":{"rules":[{"domain":["existing.test"],"outboundTag":"vpn"}],"balancers":[{"tag":"pool","selector":["vpn"]}]}}""").single()
    @Test fun customRulesPrecedeSubscriptionButHealthRemainsFirst() {
        val json = JsonParser.parseString(RuntimeConfig.build(source,42,"/assets",true,HealthProbe(25081,"a","b"), options=RoutingOptions(rules=listOf(MobileRule("domain","example.test","direct"),MobileRule("ip","10.0.0.0/8","block"))))).asJsonObject
        val rules=json["routing"].asJsonObject["rules"].asJsonArray
        assertEquals("kawa-health",rules[0].asJsonObject["inboundTag"].asJsonArray[0].asString)
        assertEquals("kawa-direct",rules[1].asJsonObject["outboundTag"].asString)
        assertEquals("kawa-block",rules[2].asJsonObject["outboundTag"].asString)
        assertTrue(json["routing"].asJsonObject.has("balancers"))
        assertTrue(json["outbounds"].asJsonArray.any { it.asJsonObject["tag"]?.asString == "kawa-block" })
    }
    @Test fun validatesDnsAndRuleValuesBeforeSaving() {
        assertThrows(IllegalArgumentException::class.java) { RoutingOptions(dns="http://unsafe.test/dns").validated() }
        assertThrows(IllegalArgumentException::class.java) { MobileRule("ip","abc","direct").validated() }
        assertThrows(IllegalArgumentException::class.java) { MobileRule("domain","https://example.test/path","direct").validated() }
        assertEquals("",RoutingOptions().validated().dns)
    }
    @Test fun keepsPrivateNetworkBypassAfterHealthAndBeforeCustomRules() {
        val json=JsonParser.parseString(RuntimeConfig.build(source,42,"/assets",false,HealthProbe(25081,"a","b"), options=RoutingOptions(bypassLan=true))).asJsonObject
        val rule=json["routing"].asJsonObject["rules"].asJsonArray[1].asJsonObject
        assertEquals("geoip:private",rule["ip"].asJsonArray[0].asString)
    }
}
