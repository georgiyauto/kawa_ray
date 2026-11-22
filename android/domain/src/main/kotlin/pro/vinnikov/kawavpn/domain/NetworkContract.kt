package pro.vinnikov.kawavpn.domain
import com.google.gson.*
import java.net.URI

object NetworkContract {
    fun subscriptionUrl(text: String): String {
        val uri = runCatching { URI(text.trim()) }.getOrElse { throw IllegalArgumentException("Неверная ссылка") }
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme == "https" && host.isNotEmpty() && uri.userInfo == null && uri.fragment == null) { "Нужна HTTPS-ссылка без пароля в адресе" }
        require(host != "localhost" && !host.endsWith(".localhost") && !host.startsWith("127.") && host != "[::1]" && host != "0.0.0.0") { "Локальные адреса недоступны" }
        return uri.toASCIIString()
    }
    fun botUrl(bot: String, token: String): String {
        require(bot == "your_bot" && token.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Неверный ответ входа" }
        return "https://t.me/your_bot?start=webauth_$token"
    }
}
object NativeContract {
    fun data(response: String): JsonElement {
        val obj = JsonParser.parseString(response).asJsonObject
        check(obj["success"]?.asBoolean == true) { "Xray не смог выполнить операцию" }
        return obj["data"] ?: JsonNull.INSTANCE
    }
}
