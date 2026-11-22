package pro.vinnikov.kawavpn.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import pro.vinnikov.kawavpn.*
import pro.vinnikov.kawavpn.R
import pro.vinnikov.kawavpn.domain.ConnectionState
import pro.vinnikov.kawavpn.vpn.TunnelStatus
import kotlin.math.max

/** Theme switch (dark / light / system), read by every colour below; the desktop's palettes. */
internal object Palette { var light by mutableStateOf(false) }
internal val Ink: Color get() = if (Palette.light) Color(0xFF15181D) else Color(0xFFF1F1F3)
internal val Muted: Color get() = if (Palette.light) Color(0xFF5E6672) else Color(0xFFAEAFB6)
internal val Border: Color get() = if (Palette.light) Color(0xFFC9D0DA) else Color(0xFF494B4F)
internal val Green: Color get() = if (Palette.light) Color(0xFF1E8F6B) else Color(0xFF55CBA6)
internal val Base: Color get() = if (Palette.light) Color(0xFFF1F4F8) else Color.Black
internal val Sheet: Color get() = if (Palette.light) Color(0xFFFAFBFD) else Color(0xFF141416)
/** Text on the primary glass: dark on the white pill, white on the light theme's dark pill. */
internal val OnPrimary: Color get() = if (Palette.light) Color.White else Color(0xFF111316)
internal val Glass: Brush get() = if (Palette.light) Brush.verticalGradient(listOf(Color.White, Color(0xFFEEF2F7))) else Brush.verticalGradient(listOf(Color(0xFF222223), Color(0xFF0C0C0D), Color(0xFF030303)))

@Composable fun KawaApp(screen: ScreenState, tunnel: TunnelStatus, model: KawaViewModel, connect: () -> Unit, disconnect: () -> Unit, ping: () -> Unit) {
    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    androidx.activity.compose.BackHandler(enabled = page != 0) { page = 0 }
    var serverSearch by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val globeHeight = (LocalConfiguration.current.screenHeightDp - 592f).coerceIn(150f, 250f).dp
    var input by remember { mutableStateOf("") }
    val connected = tunnel.state == ConnectionState.CONNECTED
    val motion = !screen.data.reducedMotion
    var seconds by remember { mutableLongStateOf(0L) }
    var rx by remember { mutableLongStateOf(0L) }
    var tx by remember { mutableLongStateOf(0L) }
    val liveTunnel by rememberUpdatedState(tunnel)
    var history by remember { mutableStateOf(List(40) { 0f }) }
    LaunchedEffect(tunnel.since, connected) {
        var lastSample=0L
        rx=0;tx=0;seconds=0;history=List(40) { 0f }
        while(true) {
            val now=SystemClock.elapsedRealtime()
            seconds=if(connected) (now-tunnel.since).coerceAtLeast(0)/1000 else 0
            rx=if(connected) liveTunnel.receivedPerSecond else 0
            tx=if(connected) liveTunnel.sentPerSecond else 0
            if(liveTunnel.sampledAt>lastSample) { history=history.drop(1)+rx.toFloat();lastSample=liveTunnel.sampledAt }
            delay(500)
        }
    }
    val density=androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,density.fontScale*screen.data.textScale)) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val light = when (screen.data.theme) { "light" -> true; "system" -> !systemDark; else -> false }
    if (Palette.light != light) Palette.light = light
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect { (view.context as? android.app.Activity)?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = light; androidx.core.view.WindowCompat.getInsetsController(it, view).isAppearanceLightNavigationBars = light } }
    MaterialTheme(colorScheme = if (light) lightColorScheme(primary = Ink, onPrimary = Color.White, secondary = Ink, onSecondary = Color.White, secondaryContainer = Color(0xFFE2E7EF), onSecondaryContainer = Ink, tertiary = Ink, primaryContainer = Color(0xFFE2E7EF), onPrimaryContainer = Ink, background = Base, surface = Sheet, onSurface = Ink, onBackground = Ink, outline = Border, surfaceContainerHigh = Sheet, surfaceContainer = Sheet)
        else darkColorScheme(secondary=Ink,onSecondary=Color.Black,secondaryContainer=Color(0xFF292A2C),onSecondaryContainer=Ink,tertiary=Ink,primaryContainer=Color(0xFF292A2C),onPrimaryContainer=Ink,primary = Ink, onPrimary = Color.Black, background = Color.Black, surface = Color(0xFF121214), onSurface = Ink, onBackground = Ink, outline = Border)) {
        val background = rememberLayerBackdrop()
        val pageLayer = rememberLayerBackdrop()
        var reporting by remember { mutableStateOf(false) }
        val importFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { importing = false; model.importFile(uri) } }
        CompositionLocalProvider(LocalContentColor provides Ink) { Box(Modifier.fillMaxSize().background(Base)) {
            Box(Modifier.fillMaxSize().layerBackdrop(pageLayer)) {
            AmbientBackground(Modifier.fillMaxSize().layerBackdrop(background))
            CompositionLocalProvider(LocalBackdrop provides background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
                KawaHeader(if (screen.data.session == null) "Мой Kawa VPN" else "Мой кабинет", { page = 3 }, { reporting = true })
                if (screen.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink)
                (screen.error ?: tunnel.error)?.let { error ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { Text(error,color=Color(0xFFFFA49C),fontSize=13.sp,modifier=Modifier.weight(1f).padding(vertical=8.dp));if(screen.error!=null) GlassIconButton(KawaIcon.Close,"Скрыть",size=32.dp,clear=true) { model.dismissMessage() } }
                }
                screen.notice?.let { notice -> Row(verticalAlignment=Alignment.CenterVertically) { Text(notice,color=Green,fontSize=13.sp,modifier=Modifier.weight(1f).padding(vertical=6.dp));GlassIconButton(KawaIcon.Close,"Скрыть",size=32.dp,clear=true) { model.dismissMessage() } } }
                val navSpace = Modifier.navigationBarsPadding().padding(bottom = 92.dp)
                when (page) {
                    0 -> Column(Modifier.verticalScroll(rememberScrollState()).then(navSpace), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val selectedSource=screen.data.profiles.firstOrNull { it.profile.id==screen.data.selected }?.source
                        val expiry=screen.data.subscriptionInfo[selectedSource]?.expire
                        val remaining=expiry?.minus(System.currentTimeMillis()/1000)
                        if(remaining!=null && remaining in 1..86400) GlassCard(Modifier.fillMaxWidth()) {
                            Text("Подписка скоро закончится",color=Color(0xFFFFC263),fontWeight=FontWeight.Bold)
                            Text("Осталось менее суток. Продлите подписку в кабинете.",color=Muted,fontSize=13.sp)
                            GlassButton("Открыть кабинет", height = 40.dp, fontSize = 13) { page = 3 }
                        }
                        val notices = screen.data.profiles.filter { pro.vinnikov.kawavpn.domain.ProfileNotice.of(it.profile) }.map { it.profile.name.trim() }.distinct()
                        if (notices.isNotEmpty() && !connected) SubscriptionNotice(notices) { page = 3 }
                        Globe(tunnel, motion, Modifier.fillMaxWidth().height(globeHeight))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CountryFlag(tunnel.exitCountry ?: countryCode(screen.data.profiles.firstOrNull { it.profile.id == screen.data.selected && !pro.vinnikov.kawavpn.domain.ProfileNotice.of(it.profile) }?.profile?.name.orEmpty()))
                            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (connected) Green else Muted))
                            Text(when (tunnel.state) { ConnectionState.CONNECTED -> "ЗАЩИЩЕНО"; ConnectionState.CONNECTING -> "ПОДКЛЮЧЕНИЕ…"; else -> "НЕ ПОДКЛЮЧЕНО" }, color = if (connected) Green else Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.5.sp)
                        }
                        val chosen = screen.data.profiles.firstOrNull { it.profile.id == screen.data.selected && !pro.vinnikov.kawavpn.domain.ProfileNotice.of(it.profile) }?.profile
                        val destinationCode=tunnel.exitCountry ?: countryCode(chosen?.name.orEmpty())
                        val plainName=(chosen?.name ?: tunnel.name).replace(Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}|[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}]\\x{FE0F}?"), "").trim()
                        // As on the desktop: the server's name, the exit country once connected.
                        val heading=(if (connected) tunnel.exitCountry?.let(::countryName) else null) ?: plainName.ifEmpty { "Выберите сервер" }
                        DestinationTitle(heading)
                        chosen?.let { Text((if(it.name!=heading) it.name+" · " else "")+profileProtocol(it.configJson),color=Muted,fontSize=12.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        Row(Modifier.fillMaxWidth().heightIn(min=44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(countryName(tunnel.originCountry) ?: "Вы", fontSize=13.sp,color=Muted,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=72.dp))
                            Box(Modifier.weight(1f).height(1.dp).background(if (connected) Green else Border))
                            GlassButton(tunnel.latency?.let { "Пинг · $it мс" } ?: "Пинг", enabled = connected, height = 30.dp, fontSize = 12, onClick = ping)
                            Text(countryName(destinationCode) ?: "Сервер",fontSize=12.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=80.dp))
                            CountryFlag(tunnel.exitCountry ?: countryCode(chosen?.name.orEmpty()))
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GlassButton(if (connected) "Отключиться" else if(tunnel.state==ConnectionState.CONNECTING) "Отменить" else "Подключиться", Modifier.weight(1f), primary = !connected, icon = if (!connected) KawaIcon.Shield else null,
                                enabled = connected || chosen != null, onClick = if (connected || tunnel.state==ConnectionState.CONNECTING) disconnect else { { model.dismissMessage(); connect() } })
                            GlassButton("Сменить сервер", Modifier.weight(1f)) { serverSearch=""; page = 1 }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth().height((128f*density.fontScale*screen.data.textScale).coerceAtLeast(128f).dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            GlassCard(Modifier.weight(1f).fillMaxHeight().heightIn(min=124.dp).semantics { testTag="session-time" }, padding=12.dp) {
                                Text("ВРЕМЯ", color=Muted, fontSize=12.sp, lineHeight=14.sp, letterSpacing=1.sp)
                                Spacer(Modifier.height(6.dp))
                                StableDigits("%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60), motion, 28)
                            }
                            GlassCard(Modifier.weight(1f).fillMaxHeight().heightIn(min=124.dp).semantics { testTag="session-speed" }, padding=12.dp) {
                                Text("СКОРОСТЬ",color=Muted,fontSize=12.sp,lineHeight=14.sp,letterSpacing=1.sp)
                                Spacer(Modifier.height(6.dp))
                                Text("↓ ${bytes(rx)}/с",fontWeight=FontWeight.SemiBold,fontSize=17.sp,lineHeight=21.sp,style=androidx.compose.ui.text.TextStyle(fontFeatureSettings="tnum"))
                                Text("↑ ${bytes(tx)}/с",color=Muted,fontSize=12.sp,lineHeight=16.sp)
                                Sparkline(history,Modifier.fillMaxWidth().height(24.dp))
                            }
                        }
                    }
                    1 -> Box(navSpace.weight(1f)) { PhoneServers(screen, model, { importing = true }, { model.dismissMessage(); page = 0; connect() }, serverSearch, { page = 3 }) }
                    2 -> Column(Modifier.verticalScroll(rememberScrollState()).then(navSpace), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.text.BasicText("Ваше соединение", style = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold), maxLines = 1, softWrap = false, modifier = Modifier.weight(1f), autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 26.sp))
                            Text(if (connected) "●  В сети" else "○  Не подключено", color = if (connected) Green else Muted, fontSize = 12.sp)
                        }
                        Text(if (connected) "${tunnel.name} · текущая сессия" else "Подключитесь к серверу, чтобы увидеть статистику сессии", color = Muted, fontSize = 13.sp)
                        Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SessionMetric("Время подключения", "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60), "Текущая сессия", Modifier.weight(1f).fillMaxHeight())
                            SessionMetric("Получено", bytes(tunnel.received), "Через VPN", Modifier.weight(1f).fillMaxHeight())
                            SessionMetric("Отправлено", bytes(tunnel.sent), "Через VPN", Modifier.weight(1f).fillMaxHeight())
                        }
                        GlassCard(Modifier.fillMaxWidth(), padding = 20.dp, radius = 20.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Активность сети", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("Последние 40 секунд", color = Muted, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("↓  ${bytes(rx)}/с", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text("↑  ${bytes(tx)}/с", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Sparkline(history, Modifier.fillMaxWidth().height(80.dp).graphicsLayer { alpha = if (connected) 1f else .25f })
                        }
                        screen.data.subscriptionInfo.values.distinct().forEach { info ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text((info.title ?: "Трафик подписки").uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 1.sp)
                                Text("↓ ${info.download?.let(::bytes) ?: "—"}     ↑ ${info.upload?.let(::bytes) ?: "—"}" + (info.total?.takeIf { it > 0 }?.let { "     из ${bytes(it)}" } ?: ""), fontSize = 14.sp)
                            }
                        }
                        Text("Данные подписки приходят от провайдера. Счётчики сессии измеряются на VPN-интерфейсе.", color = Muted, fontSize = 11.sp)
                    }
                    3 -> Box(navSpace.weight(1f)) { PhoneSettings(screen, model, { licenses = true }, { reporting = true }, { importing = true }) }

                }
            }
            }
            }
            // The tab bar is glass over the page itself: the globe and rows bend through it.
            CompositionLocalProvider(LocalBackdrop provides pageLayer) {
                GlassNavigation(page, motion, Modifier.align(Alignment.BottomCenter)) { serverSearch = ""; page = it }
            }
            if (reporting) ReportDialog(model, screen.reportSending) { reporting = false }
            if (screen.loaded && screen.data.telemetry == null) ConsentDialog(model)
            screen.loginWaiting?.let { status -> AlertDialog(onDismissRequest = { }, title={Text("Вход через Telegram")}, text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth());Text(status);Text("После подтверждения подписки загрузятся сюда автоматически.",color=Muted,fontSize=13.sp) }},confirmButton={TextButton(onClick={model.resumeBot()}) { Text("Открыть бота") }},dismissButton={TextButton(onClick={model.cancelLogin()}) { Text("Отменить вход") }}) }
            if (importing) AlertDialog(onDismissRequest = { if (!screen.busy) importing = false }, title = { Text("Добавить подписку") },
                text = { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(screen.busy) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Загружаем полные конфигурации…",color=Muted,fontSize=13.sp) }
                    screen.error?.let { Text(it,color=Color(0xFFFFA49C),fontSize=13.sp) }
                    OutlinedTextField(input, { input = it }, label = { Text("Ссылка подписки, ключ или JSON") }, minLines = 3, maxLines = 8,enabled=!screen.busy)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("Из буфера", Modifier.weight(1f), height = 40.dp, fontSize = 13, enabled = !screen.busy) {
                            (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { input = it.take(8 * 1024 * 1024) }
                        }
                        GlassButton("Из файла", Modifier.weight(1f), height = 40.dp, fontSize = 13, enabled = !screen.busy) { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
                    }
                } },
                confirmButton = { TextButton(onClick = { model.import(input) { importing = false; input = "" } }, enabled = input.isNotBlank() && !screen.busy) { Text("Добавить") } },
                dismissButton = { TextButton(onClick = { importing = false },enabled=!screen.busy) { Text("Отмена") } })
            if (licenses) {
                val notice = remember { context.assets.open("THIRD-PARTY-NOTICES.md").bufferedReader().use { it.readText() } }
                AlertDialog(onDismissRequest = { licenses = false }, title = { Text("Лицензии Kawa VPN") }, text = {
                    Text("Kawa VPN — модифицированная версия KAWARAY: https://github.com/m-a-prod/DUORAY. Полные тексты лицензий находятся в assets APK. Соответствующий исходный код включён в assets/source.zip.\n\n$notice", modifier = Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), fontSize = 13.sp)
                }, confirmButton = { TextButton(onClick = { licenses = false }) { Text("Закрыть") } })
            }
        } }
    }
}

}

@Composable internal fun Preference(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { Column(Modifier.weight(1f)) { Text(title); Text(detail, color = Muted, fontSize = 12.sp) }; Switch(checked, change) }
}
/** The desktop title bar: the wordmark, «Мой Kawa VPN» and «Сообщить о проблеме». */
@Composable private fun KawaHeader(cabinet: String, openCabinet: () -> Unit, report: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Image(androidx.compose.ui.res.painterResource(R.drawable.kawa_wordmark), "Kawa VPN", Modifier.height(28.dp).width(83.dp), colorFilter = ColorFilter.tint(Ink), contentScale = androidx.compose.ui.layout.ContentScale.Fit, alignment = Alignment.CenterStart)
        Spacer(Modifier.weight(1f))
        GlassButton(cabinet, height = 36.dp, fontSize = 13, onClick = openCabinet)
        GlassIconButton(KawaIcon.Report, "Сообщить о проблеме", size = 38.dp, onClick = report)
    }
}

/** Bottom tabs: a glass capsule with a white glass lens that slides to the open tab. */
@Composable private fun GlassNavigation(page: Int, motion: Boolean, modifier: Modifier, select: (Int) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    BoxWithConstraints(modifier.navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth().height(70.dp)
        .liquidGlass(RoundedCornerShape(50), GlassTone.Regular, refraction = 22.dp, blur = 8.dp)) {
        val inner = maxWidth - 16.dp
        val width = inner / 4
        val offset by animateDpAsState(width * page, if (motion) spring(dampingRatio = .72f, stiffness = 380f) else snap(), label = "navigation")
        val press = rememberGlassPress()
        Box(Modifier.padding(8.dp).offset { IntOffset(offset.roundToPx(), 0) }.width(width).fillMaxHeight()
            .liquidGlass(RoundedCornerShape(50), GlassTone.Primary, press, refraction = 10.dp, elevated = false))
        Row(Modifier.fillMaxSize().padding(8.dp)) {
            listOf(KawaIcon.Home to "Главная", KawaIcon.Servers to "Серверы", KawaIcon.Statistics to "Статистика", KawaIcon.Settings to "Настройки").forEachIndexed { index, (icon, title) ->
                val active = page == index
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                    .glassPress(press, true) { if (!active) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick); select(index) }
                    .semantics { selected = active; role = Role.Tab; onClick(title) { select(index); true } },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    KawaIconView(icon, if (active) OnPrimary else Ink, Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    FittedLabel(title, 11, if (active) OnPrimary else Muted, if (active) FontWeight.SemiBold else FontWeight.Medium, Modifier.fillMaxWidth().padding(horizontal = 3.dp))
                }
            }
        }
    }
}

/** Statistics tile (desktop SessionMetric). */
@Composable private fun SessionMetric(title: String, value: String, hint: String, modifier: Modifier) {
    GlassCard(modifier, padding = 14.dp, radius = 18.dp) {
        androidx.compose.foundation.text.BasicText(title, style = androidx.compose.ui.text.TextStyle(color = Muted, fontSize = 11.sp, lineHeight = 13.sp), maxLines = 2, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp))
        Spacer(Modifier.height(4.dp))
        FittedLabel(value, 18, Ink, FontWeight.SemiBold, Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        androidx.compose.foundation.text.BasicText(hint, style = androidx.compose.ui.text.TextStyle(color = Muted, fontSize = 10.sp), maxLines = 1, softWrap = false, autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 10.sp))
    }
}

/** «Сообщить о проблеме»: a note, a look at exactly what goes out, and the code back. */
@Composable private fun ReportDialog(model: KawaViewModel, sending: Boolean, close: () -> Unit) {
    var note by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!sending) close() }, containerColor = Sheet, shape = RoundedCornerShape(28.dp),
        title = { Text("Сообщить о проблеме") },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Опишите, что случилось. К отчёту приложатся журнал приложения и Xray, модель телефона и версия Android. Ссылки подписок, адреса серверов, ключи и токены вырезаются.", color = Muted, fontSize = 13.sp)
            OutlinedTextField(note, { note = it.take(4000) }, placeholder = { Text("Например: не подключается к Германии через LTE") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth(), enabled = !sending)
            TextButton(onClick = { preview = if (preview == null) model.reportPreview(note) else null }) { Text(if (preview == null) "Показать, что будет отправлено" else "Скрыть содержимое отчёта") }
            preview?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp, color = Muted) }
            if (sending) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ink)
        } },
        confirmButton = { GlassButton("Отправить", primary = true, enabled = !sending, height = 44.dp) { model.sendReport(note, close) } },
        dismissButton = { TextButton(onClick = close, enabled = !sending) { Text("Отмена") } })
}

/** First start: the desktop's question about automatic error reports. */
@Composable private fun ConsentDialog(model: KawaViewModel) {
    var agreed by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { }, containerColor = Sheet, shape = RoundedCornerShape(28.dp),
        title = { Text("Диагностика Kawa VPN") },
        text = { Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Разрешаете автоматически отправлять отчёты о сбоях? Это добровольно: отказ не ограничивает VPN и подписку.")
            Text("Зачем нужны отчёты", fontWeight = FontWeight.SemiBold)
            Text("Поддержка Kawa VPN ищет по ним ошибки и чинит подключение. Отчёт уходит, когда туннель упал и не восстановился, подключение не удалось или приложение закрылось с ошибкой.", color = Muted, fontSize = 13.sp)
            Text("Что может быть в отчёте", fontWeight = FontWeight.SemiBold)
            Text("Версия Kawa VPN и Android, модель телефона, процессор и объём памяти; случайный идентификатор установки для группировки отчётов; текст ошибки; очищенные журналы приложения и Xray. Ссылки подписок, адреса серверов, ключи и токены вырезаются.", color = Muted, fontSize = 13.sp)
            Text("Куда уходит", fontWeight = FontWeight.SemiBold)
            Text("По HTTPS на сервер Kawa VPN в Германии и администратору поддержки в Telegram. Сервер видит IP-адрес отправки. Хранится до 90 дней. Без сети отчёт ждёт на телефоне и уходит позже.", color = Muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { agreed = !agreed }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(agreed, { agreed = it }); Text("Я прочитал описание и согласен на указанную диагностику", fontSize = 13.sp)
            }
            Text("Изменить решение можно в Настройках → Конфиденциальность.", color = Muted, fontSize = 12.sp)
        } },
        confirmButton = { GlassButton("Разрешить", primary = true, enabled = agreed, height = 44.dp) { model.telemetry(true) } },
        dismissButton = { GlassButton("Не отправлять", height = 44.dp) { model.telemetry(false) } })
}
/** One-line labels fit their measured slot; full text remains available to accessibility. */
@Composable private fun FittedLabel(value:String,size:Int,color:Color,weight:FontWeight,modifier:Modifier=Modifier) {
    val measurer=androidx.compose.ui.text.rememberTextMeasurer()
    val density=androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(modifier,contentAlignment=Alignment.Center) {
        val style=LocalTextStyle.current.copy(fontSize=size.sp,lineHeight=(size*1.25f).sp,letterSpacing=0.sp,fontWeight=weight)
        val natural=measurer.measure(value,style,softWrap=false).size.width.coerceAtLeast(1)
        val ratio=(with(density) { maxWidth.toPx() }/natural).coerceAtMost(1f)
        Text(value,style=style.copy(fontSize=(size*ratio).sp),color=color,maxLines=1,softWrap=false)
    }
}
@Composable private fun ServiceShortcut(title:String,tint:Color,star:Boolean,click:()->Unit) {
    val press = rememberGlassPress()
    Row(Modifier.heightIn(min=40.dp).liquidGlass(RoundedCornerShape(50),GlassTone.Clear,press,refraction=8.dp,elevated=false).glassPress(press,true,click).semantics { role=Role.Button; onClick(title) { click(); true } }.padding(horizontal=14.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        Canvas(Modifier.size(15.dp)) {
            val p=Path()
            if(star) { p.moveTo(size.width/2,0f);p.lineTo(size.width*.67f,size.height*.33f);p.lineTo(size.width,size.height/2);p.lineTo(size.width*.67f,size.height*.67f);p.lineTo(size.width/2,size.height);p.lineTo(size.width*.33f,size.height*.67f);p.lineTo(0f,size.height/2);p.lineTo(size.width*.33f,size.height*.33f) }
            else { p.moveTo(2f,0f);p.lineTo(size.width,size.height/2);p.lineTo(2f,size.height) };p.close();drawPath(p,tint)
        }
        Text(title,fontSize=14.sp,fontWeight=FontWeight.SemiBold)
    }
}
@Composable private fun DestinationTitle(value:String) {
    val measurer=androidx.compose.ui.text.rememberTextMeasurer()
    val density=androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val style=androidx.compose.ui.text.TextStyle(fontFamily=FontFamily(Font(R.font.kawa_dots)),fontSize=56.sp)
        val natural=measurer.measure(value,style,softWrap=false).size.width.coerceAtLeast(1)
        val ratio=(with(density) { maxWidth.toPx() }/natural).coerceAtMost(1f)
        Text(value,style=style.copy(fontSize=(56*ratio).coerceAtLeast(22f).sp),color=Ink)
    }
}
@Composable private fun StableDigits(value:String,motion:Boolean,size:Int) {
    val measurer=androidx.compose.ui.text.rememberTextMeasurer()
    val density=androidx.compose.ui.platform.LocalDensity.current
    val font=FontFamily(Font(R.font.kawa_dots))
    BoxWithConstraints {
        val original=androidx.compose.ui.text.TextStyle(fontFamily=font,fontSize=size.sp,letterSpacing=0.sp)
        val digitNatural=('0'..'9').maxOf { measurer.measure(it.toString(),original,softWrap=false).size.width }
        val colonNatural=measurer.measure(":",original,softWrap=false).size.width
        val natural=value.sumOf { if(it==':') colonNatural else digitNatural }.coerceAtLeast(1)
        val fit=(with(density) { maxWidth.toPx() }/natural*.96f).coerceAtMost(1f)
        val style=original.copy(fontSize=(size*fit).sp)
        val digitWidth=with(density) { ('0'..'9').maxOf { measurer.measure(it.toString(),style,softWrap=false).size.width }.toDp() }
        val colonWidth=with(density) { measurer.measure(":",style,softWrap=false).size.width.toDp() }
        Row(Modifier.semantics(mergeDescendants=true) { contentDescription=value }) {
            value.forEach { char ->
                AnimatedContent(char,transitionSpec={if(motion) fadeIn(tween(180)) togetherWith fadeOut(tween(120)) else EnterTransition.None togetherWith ExitTransition.None},label="digit") { current ->
                    Text(current.toString(),style=style,maxLines=1,softWrap=false,modifier=Modifier.width(if(current==':') colonWidth else digitWidth).clearAndSetSemantics { })
                }
            }
        }
    }
}
@Composable private fun Sparkline(values: List<Float>, modifier: Modifier) {
    Canvas(modifier.semantics { contentDescription = "Скорость входящего трафика за последние 40 секунд" }) {
        val ceiling = values.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        val path = Path()
        values.forEachIndexed { index, value -> val x = index * size.width / (values.size - 1); val y = size.height - 2f - value / ceiling * (size.height - 6f); if (index == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        drawPath(path, Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
@Composable internal fun CountryFlag(code: String?) {
    val context = LocalContext.current
    val bitmap = remember(code) { code?.let { runCatching { context.assets.open("flags/${it.lowercase()}.png").use { stream -> BitmapFactory.decodeStream(stream).asImageBitmap() } }.getOrNull() } }
    if (bitmap != null) Image(bitmap, countryName(code) ?: code, Modifier.size(28.dp)) else Canvas(Modifier.size(28.dp).semantics { contentDescription = "Регион не определён" }) { drawCircle(Muted, size.minDimension * .35f, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())); drawLine(Muted, Offset(size.width * .15f, size.height / 2), Offset(size.width * .85f, size.height / 2), 1.dp.toPx()) }
}
internal fun bytes(value: Long): String = when { value >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(value / 1073741824.0); value >= 1024 * 1024 -> "%.1f МБ".format(value / 1048576.0); value >= 1024 -> "%.1f КБ".format(value / 1024.0); else -> "$value Б" }
internal fun countryCode(name: String): String? {
    // A flag emoji (two regional indicators) names the country exactly: 🇩🇪 → DE, 🇪🇺 → EU.
    val points = name.codePoints().toArray()
    for (i in 0 until points.size - 1) {
        val a = points[i] - 0x1F1E6; val b = points[i + 1] - 0x1F1E6
        if (a in 0..25 && b in 0..25) return "${'A' + a}${'A' + b}"
    }
    val lower = name.lowercase()
    return when { listOf("герман", "франкфурт", "germany", "frankfurt", "🇩🇪").any { it in lower } -> "DE"; listOf("нидерланд", "netherlands", "amsterdam", "🇳🇱").any { it in lower } -> "NL"; listOf("финлянд", "finland", "🇫🇮").any { it in lower } -> "FI"; listOf("сша", "united states", "🇺🇸").any { it in lower } -> "US"; else -> null }
}
internal fun countryName(code: String?): String? = code?.let { when (it) { "RU" -> "Россия"; "DE" -> "Германия"; "NL" -> "Нидерланды"; "FI" -> "Финляндия"; "US" -> "США"; else -> java.util.Locale("", it).getDisplayCountry(java.util.Locale("ru")) } }
