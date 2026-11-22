package pro.vinnikov.kawavpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.vinnikov.kawavpn.*
import pro.vinnikov.kawavpn.data.*
import pro.vinnikov.kawavpn.domain.*

/** Settings in the desktop's categories, one at a time, chosen from glass chips. */
private val CATEGORIES = listOf("Подключение", "Подписки", "Маршрутизация", "Оформление", "Диагностика", "О программе")

@Composable internal fun PhoneSettings(screen: ScreenState, model: KawaViewModel, licenses: () -> Unit, report: () -> Unit = {}, import: () -> Unit = {}, initialCategory: Int = 0) {
    var category by rememberSaveable(initialCategory) { mutableIntStateOf(initialCategory) }
    var routing by remember { mutableStateOf(false) }
    var applications by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val currentTunnel by model.tunnel.collectAsState()
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.importFile(uri) }
    Column(Modifier.fillMaxSize()) {
        Text("Настройки", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Text("Управляйте Kawa VPN так, как удобно вам", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CATEGORIES.forEachIndexed { index, title -> GlassChip(title, category == index) { category = index } }
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (category) {
                0 -> {
                    SectionLabel("ПОДКЛЮЧЕНИЕ")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Preference("Подключаться при запуске", "Kawa VPN подключится к выбранному серверу, как только откроется.", screen.data.autoConnect) { model.autoConnect(it) }
                        SettingsDivider()
                        Preference("Перезапуск Hysteria", "Hysteria со временем может зависать; свежий Xray это лечит. Перезапуск занимает пару секунд.", screen.data.hysteriaRestart) { model.hysteriaRestart(it, screen.data.hysteriaMinutes) }
                        if (screen.data.hysteriaRestart) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(15, 30, 60, 120, 240).forEach { m -> GlassChip(if (m < 60) "$m мин" else "${m / 60} ч", screen.data.hysteriaMinutes == m) { model.hysteriaRestart(true, m) } }
                        }
                        SettingsDivider()
                        Preference("Проверять серверы через VPN-протокол", "VLESS, Reality и игровые UDP-серверы. Выключено — быстрая проверка только TCP.", screen.data.realPing) { model.pingMode(it) }
                        SettingsDivider()
                        Preference("IPv6 в туннеле", "При выключении IPv6 блокируется, чтобы избежать утечки.", screen.data.ipv6) { model.preferences(it, screen.data.reducedMotion) }
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Постоянный VPN", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Android может держать Kawa VPN включённым всегда и блокировать интернет без VPN. Включается в системных настройках: Kawa VPN → «Постоянная VPN» и «Блокировать без VPN».", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        GlassButton("Открыть системные настройки VPN", Modifier.fillMaxWidth(), height = 44.dp) { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS)) } }
                    }
                    DnsCard(screen, model)
                    HappCard(screen, model)
                }
                1 -> {
                    SectionLabel("АККАУНТ")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Кабинет Kawa VPN", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (screen.data.session == null) "Войдите через бота — подписки появятся автоматически." else "Вы вошли. Подписки и покупки доступны в приложении.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        GlassButton(if (screen.data.session == null) "Войти через бота" else "Обновить подписки", Modifier.fillMaxWidth(), primary = screen.data.session == null, enabled = !screen.busy && screen.loginWaiting == null, height = 44.dp) { if (screen.data.session == null) model.login() else model.refresh() }
                        if (screen.data.session != null) TextButton(onClick = { model.logout() }, enabled = !screen.busy) { Text("Выйти из аккаунта", color = Ink) }
                    }
                    if (screen.data.session != null) CabinetPanel(screen, model)
                    SubscriptionList(screen, model, import, { file.launch(arrayOf("application/json", "text/*", "application/octet-stream")) })
                    GlassCard(Modifier.fillMaxWidth()) {
                        Preference("Обновлять подписки при запуске", "Сохранённые серверы доступны и без обновления.", screen.data.autoRefresh) { model.autoRefresh(it) }
                    }
                }
                2 -> {
                    SectionLabel("МАРШРУТИЗАЦИЯ")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Настройки сети применяются после переподключения.", color = Muted, fontSize = 13.sp)
                        Preference("Весь трафик через VPN", if (screen.data.allTraffic) "Весь трафик идёт через выбранный VPN; ваши исключения имеют приоритет." else "Используются правила, DNS и балансировщики из подписки.", screen.data.allTraffic) { model.routeAll(it) }
                        SettingsDivider()
                        Preference("Локальная сеть напрямую", "Доступ к принтерам и другим устройствам в вашей сети.", screen.data.routing.bypassLan) { model.routing(screen.data.routing.copy(bypassLan = it)) }
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Приложения", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(when {
                            screen.data.routing.excludedApps.isEmpty() -> "Все приложения идут через VPN."
                            screen.data.routing.onlySelectedApps -> "Только выбранные через VPN: ${screen.data.routing.excludedApps.size}."
                            else -> "Мимо VPN: ${screen.data.routing.excludedApps.size}."
                        }, color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        GlassButton("Выбрать приложения", Modifier.fillMaxWidth(), height = 44.dp) { applications = true }
                    }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Сайты и адреса", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text("Свои правила для доменов и IP: напрямую, через VPN или блокировать. Проверяются раньше правил подписки.", color = Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(6.dp))
                        GlassButton("Правила доменов и IP · ${screen.data.routing.rules.size}", Modifier.fillMaxWidth(), height = 44.dp) { routing = true }
                    }
                }
                3 -> {
                    SectionLabel("ОФОРМЛЕНИЕ")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text("Тема", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("dark" to "Тёмная", "light" to "Светлая", "system" to "Как в системе").forEach { (id, title) -> GlassChip(title, screen.data.theme == id) { model.theme(id) } }
                        }
                        SettingsDivider()
                        Text("Размер текста", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(.9f to "Мелкий", 1f to "Обычный", 1.15f to "Крупный", 1.3f to "Очень крупный").forEach { (value, title) -> GlassChip(title, screen.data.textScale == value) { model.textScale(value) } }
                        }
                        SettingsDivider()
                        Preference("Уменьшить движение", "Отключить анимацию цифр, стекла, меню и маршрута.", screen.data.reducedMotion) { model.preferences(screen.data.ipv6, it) }
                    }
                }
                4 -> {
                    SectionLabel("ОТЧЁТЫ И КОНФИДЕНЦИАЛЬНОСТЬ")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Preference("Отправлять отчёты об ошибках", "Когда туннель упал, подключение не удалось или приложение закрылось с ошибкой. Версия, модель, очищенные журналы приложения и Xray. Получатель — поддержка Kawa VPN, хранение 90 дней.", screen.data.telemetry == true) { model.telemetry(it) }
                        SettingsDivider()
                        Preference("Сведения об устройстве для подписки", "Стабильный ID, модель и версия Android для лимита устройств. Без этого некоторые провайдеры отказывают в доступе.", screen.data.sendDeviceInfo) { model.privacy(it) }
                        SettingsDivider()
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Сообщить о проблеме", fontWeight = FontWeight.SemiBold)
                                Text("Опишите, что случилось, — вы получите код отчёта. Передайте его в поддержку.", color = Muted, fontSize = 12.sp)
                            }
                            GlassButton("Написать", height = 40.dp, fontSize = 13, onClick = report)
                        }
                    }
                    GlassButton("Диагностика соединения", Modifier.fillMaxWidth(), height = 46.dp) { diagnostics = true }
                }
                5 -> {
                    SectionLabel("О ПРОГРАММЕ")
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Kawa VPN ${BuildConfig.VERSION_NAME}", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("Kawa VPN. Твои серверы, подписка и поддержка — в одном месте.", color = Muted, fontSize = 12.sp)
                            }
                            GlassButton("@your_bot", height = 40.dp, fontSize = 13) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/your_bot"))) } }
                        }
                        SettingsDivider()
                        Text("Основано на KAWARAY · GPL-3.0-only", fontWeight = FontWeight.SemiBold)
                        Text("Свободная программа: распространяется по лицензии GNU GPL v3, её можно изучать, менять и распространять. Поставляется без каких-либо гарантий. Исходный код открыт на GitHub, он и лицензии также вложены в приложение.", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        GlassButton("Исходный код на GitHub", Modifier.fillMaxWidth(), primary = true, height = 44.dp) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/georgiyauto/kawa_ray"))) } }
                        GlassButton("Лицензии и сторонние компоненты", Modifier.fillMaxWidth(), height = 44.dp, onClick = licenses)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (routing) RoutingDialog(screen.data.routing, { model.routing(it); routing = false }, { routing = false })
    if (applications) ApplicationsScreen(screen.data.routing, { model.routing(it); applications = false }, { applications = false })
    if (diagnostics) {
        val text = "Kawa VPN ${BuildConfig.VERSION_NAME}\nAndroid ${android.os.Build.VERSION.RELEASE}\nСостояние: ${currentTunnel.state}\nПрофилей: ${screen.data.profiles.size}\nIPv6: ${screen.data.ipv6}\nВесь трафик: ${screen.data.allTraffic}\nПравил: ${screen.data.routing.rules.size}\nПриложений в списке: ${screen.data.routing.excludedApps.size}\nОшибка: ${screen.error ?: currentTunnel.error ?: "нет"}"
        AlertDialog(onDismissRequest = { diagnostics = false }, containerColor = Sheet, title = { Text("Диагностика") }, text = { Column { Text("Без ссылок подписок, IP серверов, ключей и токенов.", color = Muted, fontSize = 13.sp); Spacer(Modifier.height(12.dp)); Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp) } },
            confirmButton = { TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kawa diagnostics", text)) }) { Text("Копировать", color = Ink) } },
            dismissButton = { TextButton(onClick = { diagnostics = false }) { Text("Закрыть", color = Ink) } })
    }
}

@Composable private fun SectionLabel(text: String) = Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
@Composable private fun SettingsDivider() = HorizontalDivider(color = Border.copy(alpha = .6f), modifier = Modifier.padding(vertical = 8.dp))

@Composable private fun DnsCard(screen: ScreenState, model: KawaViewModel) {
    var dns by remember(screen.data.routing.dns) { mutableStateOf(screen.data.routing.dns) }
    GlassCard(Modifier.fillMaxWidth()) {
        Text("Свой DNS", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(dns, { dns = it }, placeholder = { Text("IP или https://…/dns-query") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp))
        Text("Пусто — DNS из подписки.", color = Muted, fontSize = 12.sp)
        if (dns != screen.data.routing.dns) GlassButton("Сохранить DNS", Modifier.fillMaxWidth(), height = 44.dp) { model.routing(screen.data.routing.copy(dns = dns)) }
    }
}

/** Happ Spoof, as on the desktop: the subscription request looks like the Happ client. */
@Composable private fun HappCard(screen: ScreenState, model: KawaViewModel) {
    val happ = screen.data.happ ?: HappSpoof()
    GlassCard(Modifier.fillMaxWidth()) {
        Preference("Happ Spoof", "Для панелей, которые выдают подписку только приложению Happ: запрос подписки повторяет отпечаток Happ (User-Agent, x-hwid, устройство).", happ.enabled) { model.happ(happ.copy(enabled = it)) }
        if (happ.enabled) {
            Text(happ.userAgent(), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Muted)
            Text("${happ.os} ${happ.osVersion} · ${happ.model} · ${happ.locale} · hwid ${happ.hwid}", fontSize = 12.sp, color = Muted)
            var editing by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton("Случайное устройство", Modifier.weight(1f), height = 40.dp, fontSize = 12) { model.happ(happ.randomDevice().copy(enabled = true)) }
                GlassButton("Новые ID", Modifier.weight(1f), height = 40.dp, fontSize = 12) { model.happ(happ.newIds()) }
            }
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "Скрыть поля" else "Изменить вручную", color = Ink) }
            if (editing) {
                var version by remember { mutableStateOf(happ.appVersion) }; var os by remember { mutableStateOf(happ.os) }
                var osVersion by remember { mutableStateOf(happ.osVersion) }; var device by remember { mutableStateOf(happ.model) }; var locale by remember { mutableStateOf(happ.locale) }
                listOf<Triple<String, String, (String) -> Unit>>(Triple("Версия Happ", version) { version = it }, Triple("ОС (Android, iOS, macOS, Windows)", os) { os = it }, Triple("Версия ОС", osVersion) { osVersion = it }, Triple("Модель", device) { device = it }, Triple("Язык", locale) { locale = it })
                    .forEach { (label, value, change) -> OutlinedTextField(value, { change(it.take(40)) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) }
                GlassButton("Сохранить", Modifier.fillMaxWidth(), primary = true, height = 42.dp) { model.happ(happ.copy(appVersion = version.trim(), os = os.trim(), osVersion = osVersion.trim(), model = device.trim(), locale = locale.trim())); editing = false }
            }
            Text("После изменения обновите подписки.", fontSize = 12.sp, color = Muted)
        }
    }
}

/** «Мои подписки», as the desktop lists them: servers, traffic, term; choose or delete. */
@Composable private fun SubscriptionList(screen: ScreenState, model: KawaViewModel, add: () -> Unit, fromFile: () -> Unit) {
    val context = LocalContext.current
    val sources = screen.data.profiles.map { it.source }.distinct()
    val selectedSource = screen.data.profiles.firstOrNull { it.profile.id == screen.data.selected }?.source
    var deleting by remember { mutableStateOf<String?>(null) }
    SectionLabel("МОИ ПОДПИСКИ · ${sources.size}")
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        if (sources.isEmpty()) Text("Подписок пока нет. Войдите через бота или добавьте ссылку.", color = Muted, fontSize = 13.sp)
        sources.forEachIndexed { index, source ->
            val info = screen.data.subscriptionInfo[source]
            val servers = screen.data.profiles.count { it.source == source && !ProfileNotice.of(it.profile) }
            val managed = screen.data.profiles.any { it.source == source && it.managed }
            val title = if (source == "manual") "Мои серверы" else info?.title?.takeIf { it.isNotBlank() } ?: "Подписка ${index + 1}"
            val used = (info?.download ?: 0) + (info?.upload ?: 0)
            val meta = listOfNotNull("$servers серверов",
                if (info?.download != null || info?.upload != null) bytes(used) + (info?.total?.takeIf { it > 0 }?.let { " из ${bytes(it)}" } ?: " · безлимит") else null,
                info?.expire?.takeIf { it > 0 }?.let { "до " + java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale("ru")).format(java.util.Date(it * 1000)) }).joinToString(" · ")
            if (index > 0) SettingsDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (source == selectedSource) Text("  · выбрана", color = Green, fontSize = 12.sp)
                    if (managed) Text("  · из кабинета", color = Muted, fontSize = 11.sp)
                }
                Text(meta, color = Muted, fontSize = 12.sp)
                info?.total?.takeIf { it > 0 }?.let { total -> LinearProgressIndicator({ (used.toFloat() / total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = Green, trackColor = Border.copy(alpha = .4f)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (source != selectedSource) GlassButton("Выбрать", height = 36.dp, fontSize = 12) { screen.data.profiles.firstOrNull { it.source == source && !ProfileNotice.of(it.profile) }?.let { model.select(it.profile.id) } }
                    if (source.startsWith("https://")) GlassButton("Ссылка", height = 36.dp, fontSize = 12) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kawa subscription", source)); model.notice("Ссылка подписки скопирована") }
                    if (!managed) GlassButton("Удалить", height = 36.dp, fontSize = 12) { deleting = source }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        GlassButton("+ Добавить подписку", Modifier.fillMaxWidth(), primary = true, height = 44.dp, onClick = add)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Из буфера", Modifier.weight(1f), height = 40.dp, fontSize = 13) {
                val text = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrBlank()) model.showError("В буфере обмена нет ссылки или ключа.") else model.import(text)
            }
            GlassButton("Из файла", Modifier.weight(1f), height = 40.dp, fontSize = 13, onClick = fromFile)
            GlassButton("Обновить", Modifier.weight(1f), height = 40.dp, fontSize = 13, enabled = !screen.busy) { model.refresh() }
        }
    }
    deleting?.let { source -> AlertDialog(onDismissRequest = { deleting = null }, containerColor = Sheet, title = { Text("Удалить подписку?") }, text = { Text("Её серверы будут удалены из приложения. Подписка у провайдера продолжит действовать.") },
        confirmButton = { TextButton(onClick = { model.removeSource(source); deleting = null }) { Text("Удалить", color = Color(0xFFE5484D)) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена", color = Ink) } }) }
}

@Composable private fun RoutingDialog(options:RoutingOptions,save:(RoutingOptions)->Unit,close:()->Unit) {
    var rules by remember { mutableStateOf(options.rules) }
    var kind by remember { mutableStateOf("domain") }; var target by remember { mutableStateOf("proxy") }
    var value by remember { mutableStateOf("") };var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest=close,containerColor=Sheet,title={Text("Правила маршрутизации")},text={
        Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Правила проверяются сверху вниз, перед правилами подписки.",color=Muted,fontSize=13.sp)
            rules.forEachIndexed { index,rule ->
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(rule.value,fontSize=14.sp);Text("${if(rule.kind=="ip") "IP" else "Домен"} · ${targetLabel(rule.target)}",color=Muted,fontSize=12.sp) }
                    if(index>0) TextButton(onClick={rules=rules.toMutableList().apply { val previous=this[index-1];this[index-1]=this[index];this[index]=previous }}) { Text("↑",color=Ink) }
                    TextButton(onClick={rules=rules.filterIndexed { i,_-> i!=index }}) { Text("×",color=Ink) }
                }
            }
            HorizontalDivider(color=Border)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("domain" to "Домен","ip" to "IP/CIDR").forEach { (id,label)-> GlassChip(label,kind==id) { kind=id } } }
            OutlinedTextField(value,{value=it;error=null},label={Text(if(kind=="domain") "example.com или geosite:…" else "10.0.0.0/8")},singleLine=true,modifier=Modifier.fillMaxWidth(),isError=error!=null)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("proxy","direct","block").forEach { mode -> GlassChip(targetLabel(mode),target==mode) { target=mode } } }
            error?.let { Text(it,color=Color(0xFFFFA49C),fontSize=13.sp) }
            GlassButton("Добавить правило",Modifier.fillMaxWidth(),enabled=value.isNotBlank(),height=44.dp) {
                runCatching { MobileRule(kind,value,target).validated() }.onSuccess { rules=rules+it;value="";error=null }.onFailure { error="Проверьте домен или числовой IP/CIDR. Ссылки с https:// сюда не подходят." }
            }
        }
    },confirmButton={TextButton(onClick={save(options.copy(rules=rules))}) { Text("Сохранить",color=Ink) }},dismissButton={TextButton(onClick=close) { Text("Отмена",color=Ink) }})
}
private fun targetLabel(target:String)=when(target) { "direct"->"Напрямую";"block"->"Блокировать";else->"Через VPN" }

private data class AppEntry(val id: String, val label: String, val system: Boolean, val icon: androidx.compose.ui.graphics.ImageBitmap?)

/** Full-screen app picker: icons, search, «мимо VPN» or «только через VPN». */
@Composable private fun ApplicationsScreen(options: RoutingOptions, save: (RoutingOptions) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(options.excludedApps) }
    var only by remember { mutableStateOf(options.onlySelectedApps) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val size = (40 * context.resources.displayMetrics.density).toInt()
        val visible = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
        val known = visible.filter { it.packageName != context.packageName }.map { info ->
            val icon = runCatching { info.loadIcon(pm).let { d -> android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, size, size); d.draw(android.graphics.Canvas(b)) } }.asImageBitmap() }.getOrNull()
            AppEntry(info.packageName, info.loadLabel(pm).toString(), info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0 && info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0, icon)
        }
        (known + options.excludedApps.filter { id -> known.none { it.id == id } }.map { AppEntry(it, it, false, null) }).sortedBy { it.label.lowercase() }
    } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Base, contentColor = Ink) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Приложения", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    GlassIconButton(KawaIcon.Close, "Закрыть", size = 38.dp, onClick = close)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("Мимо VPN", !only) { only = false }
                    GlassChip("Только через VPN", only) { only = true }
                }
                Text(if (only) "Через VPN пойдут только отмеченные приложения, остальные — напрямую." else "Отмеченные приложения пойдут напрямую, без VPN. Остальные — через VPN.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                GlassSearchField(query, { query = it }, "Найти приложение…")
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Отмечено: ${selected.size}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("Системные", fontSize = 13.sp); Spacer(Modifier.width(8.dp)); Switch(showSystem, { showSystem = it })
                }
                val list = apps
                if (list == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Ink) }
                else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                    val shown = list.filter { (showSystem || !it.system || it.id in selected) && (query.isBlank() || it.label.contains(query, true) || it.id.contains(query, true)) }
                        .sortedByDescending { it.id in options.excludedApps }
                    if (shown.isEmpty()) item { Text("Ничего не найдено", color = Muted, modifier = Modifier.padding(16.dp)) }
                    items(shown, key = { it.id }) { app ->
                        val checked = app.id in selected
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (checked) Green.copy(alpha = .12f) else Color.Transparent)
                            .clickable { selected = if (checked) selected - app.id else selected + app.id }.semantics(mergeDescendants = true) { role = Role.Checkbox; stateDescription = if (checked) "отмечено" else "не отмечено" }
                            .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (app.icon != null) androidx.compose.foundation.Image(app.icon, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)))
                            else Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Border), contentAlignment = Alignment.Center) { Text(app.label.take(1).uppercase(), fontWeight = FontWeight.Bold) }
                            Column(Modifier.weight(1f)) {
                                Text(app.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.id, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(checked, { selected = if (it) selected + app.id else selected - app.id }, colors = CheckboxDefaults.colors(checkedColor = Green, checkmarkColor = Base))
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("Снять все", Modifier.weight(1f), height = 48.dp, enabled = selected.isNotEmpty()) { selected = emptySet() }
                    GlassButton("Сохранить", Modifier.weight(1f), primary = true, height = 48.dp) { save(options.copy(excludedApps = selected.take(500).toSet(), onlySelectedApps = only)) }
                }
            }
        }
    }
}
