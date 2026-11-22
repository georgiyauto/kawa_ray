package pro.vinnikov.kawavpn

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.vinnikov.kawavpn.ui.KawaApp
import pro.vinnikov.kawavpn.vpn.KawaVpnService

class MainActivity : ComponentActivity() {
    private val model: KawaViewModel by viewModels()
    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) connect()
    }
    private fun connect() {
        val action = if(model.tunnel.value.state == pro.vinnikov.kawavpn.domain.ConnectionState.CONNECTED) KawaVpnService.RECONNECT else KawaVpnService.CONNECT
        startForegroundService(Intent(this, KawaVpnService::class.java).setAction(action))
    }
    private fun requestConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) consent.launch(intent) else connect()
    }
    private fun handleImport(intent: Intent?) {
        if(intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.length <= 8 * 1024 * 1024 }?.let { model.import(it) }
        if(intent?.action == Intent.ACTION_VIEW) intent.data?.toString()?.takeIf { it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("trojan://") || it.startsWith("ss://") }?.let { model.import(it) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleImport(intent) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleImport(intent)
        setContent {
            val screen by model.screen.collectAsStateWithLifecycle()
            val tunnel by model.tunnel.collectAsStateWithLifecycle()
            // «Подключаться при запуске»: once per app start, after the store is read.
            var autoTried by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(savedInstanceState != null) }
            LaunchedEffect(screen.loaded) {
                if (screen.loaded && !autoTried) {
                    autoTried = true
                    if (screen.data.autoConnect && tunnel.state == pro.vinnikov.kawavpn.domain.ConnectionState.DISCONNECTED && screen.data.selected != null) requestConnect()
                }
            }
            LaunchedEffect(screen.botUrl) {
                screen.botUrl?.let { url ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    model.botOpened()
                }
            }
            KawaApp(screen, tunnel, model, ::requestConnect,
                { startService(Intent(this, KawaVpnService::class.java).setAction(KawaVpnService.STOP)) },
                { startService(Intent(this, KawaVpnService::class.java).setAction(KawaVpnService.PING)) })
        }
    }
}
