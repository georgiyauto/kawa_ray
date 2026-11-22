package pro.vinnikov.kawavpn.vpn

import com.google.gson.*
import libXray.LibXray
import pro.vinnikov.kawavpn.domain.NativeContract

object NativeEngineBridge {
    fun invoke(method: String, payload: JsonObject = JsonObject()): JsonElement {
        val request = JsonObject().apply { addProperty("apiVersion", 3); addProperty("method", method); add("payload", payload) }
        return NativeContract.data(LibXray.invoke(request.toString()))
    }
    fun running() = invoke("getXrayState").asJsonObject["running"]?.asBoolean == true
    fun convert(text: String): List<JsonObject> {
        val data = invoke("convertShareLinksToXrayJson", JsonObject().apply { addProperty("text", text) })
        // The converter returns an outbound set. Full JSON configurations bypass it.
        val obj = if (data.isJsonPrimitive) JsonParser.parseString(data.asString).asJsonObject else data.asJsonObject
        return obj["outbounds"].asJsonArray.filter { it.isJsonObject }.map { outbound ->
            JsonObject().apply {
                addProperty("remarks", outbound.asJsonObject["tag"]?.asString ?: "Сервер")
                add("outbounds", JsonArray().apply { add(outbound) })
            }
        }
    }
}
