package pro.vinnikov.kawavpn.domain
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProfileTest {
    private val full = """{"remarks":"Germany","dns":{"servers":["https://example.test/dns-query"]},"routing":{"balancers":[{"tag":"pool","selector":["p"]}],"rules":[{"inboundTag":["old"],"balancerTag":"pool"}]},"outbounds":[{"tag":"p","protocol":"freedom"}],"inbounds":[{"tag":"old","protocol":"socks"}],"unknown":{"keep":true}}"""
    @Test fun keepsFullConfigAndUnknownFieldsWhenImporting() {
        val profile = ProfileParser.parse(full).single()
        val json = JsonParser.parseString(profile.configJson).asJsonObject
        assertTrue(json["unknown"].asJsonObject["keep"].asBoolean)
        assertTrue(json["routing"].asJsonObject.has("balancers"))
        assertEquals("Germany", profile.name)
        assertEquals(profile.id, ProfileParser.parse(full).single().id)
    }
    @Test fun rejectsHtmlAndEmptyWithoutDeletingExistingProfiles() {
        assertThrows(IllegalArgumentException::class.java) { ProfileParser.parse("<html>Sign in</html>") }
        assertThrows(IllegalArgumentException::class.java) { ProfileParser.parse("[]") }
    }
    @Test fun decodesBase64FullConfigWithoutProjectingOutbounds() {
        val text = java.util.Base64.getEncoder().encodeToString(full.toByteArray())
        assertTrue(ProfileParser.parse(text).single().configJson.contains("unknown"))
    }
    @Test fun tunConfigKeepsDnsBalancerAndMapsOriginalInboundRules() {
        val profile = ProfileParser.parse(full).single()
        val json = JsonParser.parseString(RuntimeConfig.build(profile, 42, "/private/assets", true)).asJsonObject
        assertEquals("42", json["env"].asJsonObject["xray.tun.fd"].asString)
        assertEquals("tun", json["inbounds"].asJsonArray[0].asJsonObject["protocol"].asString)
        assertTrue(json["routing"].asJsonObject["rules"].asJsonArray[0].asJsonObject["inboundTag"].asJsonArray.any { it.asString == "kawa-tun" })
        assertEquals(JsonParser.parseString(full).asJsonObject["dns"], json["dns"])
        assertTrue(json["routing"].asJsonObject.has("balancers"))
    }
    @Test fun rejectsInvalidTunDescriptor() {
        assertThrows(IllegalArgumentException::class.java) { RuntimeConfig.build(ProfileParser.parse(full).single(), -1, "/assets", false) }
    }
    @Test fun healthCheckCannotFollowDirectBypassRules() {
        val source = ProfileParser.parse("""{"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"vless","tag":"vpn","settings":{}}],"routing":{"rules":[{"domain":["www.gstatic.com"],"outboundTag":"direct"}]}}""").single()
        val probe = HealthProbe(25081, "random-user", "random-password")
        val root = JsonParser.parseString(RuntimeConfig.build(source, 42, "/assets", true, probe)).asJsonObject
        val rule = root["routing"].asJsonObject["rules"].asJsonArray[0].asJsonObject
        assertEquals("vpn", rule["outboundTag"].asString)
        assertEquals("kawa-health", rule["inboundTag"].asJsonArray[0].asString)
        val listener = root["inbounds"].asJsonArray[1].asJsonObject
        assertEquals("127.0.0.1", listener["listen"].asString)
        assertEquals("random-password", listener["settings"].asJsonObject["accounts"].asJsonArray[0].asJsonObject["pass"].asString)
    }
    @Test fun directOnlyProfileCannotClaimProtection() {
        assertThrows(IllegalArgumentException::class.java) { RuntimeConfig.build(ProfileParser.parse(full).single(), 42, "/assets", true, HealthProbe(25081,"a","b")) }
    }
    @Test fun tunRetainsSniffingForDomainRoutes() {
        val original = ProfileParser.parse("""{"inbounds":[{"sniffing":{"enabled":true,"destOverride":["http","tls"],"routeOnly":true}}],"outbounds":[{"protocol":"vless"}]}""").single()
        val root = JsonParser.parseString(RuntimeConfig.build(original, 42, "/assets", true)).asJsonObject
        val sniffing = root["inbounds"].asJsonArray[0].asJsonObject["sniffing"].asJsonObject
        assertTrue(sniffing["enabled"].asBoolean)
        assertTrue(sniffing["routeOnly"].asBoolean)
        assertEquals("tls", sniffing["destOverride"].asJsonArray[1].asString)
    }
    @Test fun optionalAllTrafficModeOverridesOnlyTunRules() {
        val profile = ProfileParser.parse("""{"outbounds":[{"protocol":"freedom","tag":"direct"},{"protocol":"vless","tag":"vpn"}],"routing":{"rules":[{"domain":["example.test"],"outboundTag":"direct"}]}}""").single()
        val root = JsonParser.parseString(RuntimeConfig.build(profile, 42, "/assets", true, HealthProbe(25081,"a","b"), allTraffic = true)).asJsonObject
        val rules = root["routing"].asJsonObject["rules"].asJsonArray
        assertEquals("kawa-health", rules[0].asJsonObject["inboundTag"].asJsonArray[0].asString)
        assertEquals("kawa-tun", rules[1].asJsonObject["inboundTag"].asJsonArray[0].asString)
        assertEquals("vpn", rules[1].asJsonObject["outboundTag"].asString)
        assertEquals("direct", rules[2].asJsonObject["outboundTag"].asString)
    }
    @Test fun trafficMetricsAreBehindAuthenticatedHealthListenerNotPublicPort() {
        val source=ProfileParser.parse("""{"outbounds":[{"protocol":"vless","tag":"vpn"}]}""").single()
        val root=JsonParser.parseString(RuntimeConfig.build(source,42,"/assets",false,HealthProbe(25081,"u","p"),trafficStats=true)).asJsonObject
        assertFalse(root["metrics"].asJsonObject.has("listen"))
        assertEquals("kawa-private-metrics",root["metrics"].asJsonObject["tag"].asString)
        val route=root["routing"].asJsonObject["rules"].asJsonArray[0].asJsonObject
        assertEquals("kawa-health",route["inboundTag"].asJsonArray[0].asString)
        assertEquals("kawa-private-metrics",route["outboundTag"].asString)
        assertTrue(root["policy"].asJsonObject["system"].asJsonObject["statsInboundDownlink"].asBoolean)
    }
    @Test fun automaticRegionHealthUsesSourceBalancerInsteadOfFirstPossiblyDeadNode() {
        val source=ProfileParser.parse("""{"outbounds":[{"protocol":"vless","tag":"first"},{"protocol":"vless","tag":"second"}],"routing":{"balancers":[{"tag":"auto","selector":["first","second"]}],"rules":[{"network":"tcp,udp","balancerTag":"auto"}]}}""").single()
        val root=JsonParser.parseString(RuntimeConfig.build(source,42,"/assets",false,HealthProbe(25081,"u","p"),allTraffic=true)).asJsonObject
        val rules=root["routing"].asJsonObject["rules"].asJsonArray
        assertEquals("auto",rules[0].asJsonObject["balancerTag"].asString)
        assertEquals("auto",rules[1].asJsonObject["balancerTag"].asString)
    }
    @Test fun expiredSubscriptionPlaceholdersAreNoticesNotServers() {
        val list=ProfileParser.parse("""[
            {"remarks":"⌛ Подписка истекла ","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"0.0.0.0","port":1,"users":[{"id":"00000000-0000-0000-0000-000000000000"}]}]}}]},
            {"remarks":"Продлите подписку в боте","outbounds":[{"protocol":"vless","settings":{"vnext":[{"address":"0.0.0.0","port":1,"users":[{"id":"00000000-0000-0000-0000-000000000000"}]}]}}]},
            {"remarks":"🇪🇺 Резерв · открытые сервера","outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"127.0.0.1","port":9,"users":[{"id":"x"}]}]}},{"protocol":"freedom","tag":"direct"},{"protocol":"vless","tag":"node-m-01","settings":{"vnext":[{"address":"178.248.236.229","port":443,"users":[{"id":"y"}]}]}}],"routing":{"balancers":[{"tag":"Super_Balancer","selector":["node"],"fallbackTag":"direct"}],"rules":[{"type":"field","network":"tcp,udp","balancerTag":"Super_Balancer"}]}}
        ]""")
        assertEquals(listOf(true,true,false),list.map { ProfileNotice.of(it) })
    }
    @Test fun reserveServerHealthGoesThroughOpenServerBalancerNotDummyAddress() {
        val reserve=ProfileParser.parse("""{"outbounds":[{"protocol":"vless","tag":"proxy","settings":{"vnext":[{"address":"127.0.0.1","port":9,"users":[{"id":"x"}]}]}},{"protocol":"freedom","tag":"direct"},{"protocol":"vless","tag":"node-m-01"},{"protocol":"vless","tag":"node-m-02"}],"routing":{"balancers":[{"tag":"Super_Balancer","selector":["node"],"fallbackTag":"direct"}],"rules":[{"type":"field","network":"tcp,udp","balancerTag":"Super_Balancer"}]}}""").single()
        val root=JsonParser.parseString(RuntimeConfig.build(reserve,42,"/assets",false,HealthProbe(25081,"u","p"))).asJsonObject
        assertEquals("Super_Balancer",root["routing"].asJsonObject["rules"].asJsonArray[0].asJsonObject["balancerTag"].asString)
        assertEquals(listOf("node-m-01","node-m-02"),ProfileProbeTargets.tags(reserve.configJson))
    }
}
