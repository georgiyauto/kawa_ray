package pro.vinnikov.kawavpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.semantics.*
import pro.vinnikov.kawavpn.*
import pro.vinnikov.kawavpn.data.*

@Composable internal fun PhoneServers(screen: ScreenState, model: KawaViewModel, import: () -> Unit, connect: () -> Unit, initialQuery: String = "", openCabinet: () -> Unit = {}) {
    val context = LocalContext.current
    var query by rememberSaveable(initialQuery) { mutableStateOf(initialQuery) }
    var filter by rememberSaveable { mutableStateOf("Все") }
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<SavedProfile?>(null) }
    var deletingSource by remember { mutableStateOf<String?>(null) }
    val groups = screen.data.profiles.map { it.source }.distinct()
    val selectedGroup = group?.takeIf { it in groups }
    fun groupName(source: String): String = if(source=="manual") "Мои серверы" else screen.data.subscriptionInfo[source]?.title ?: "Подписка ${groups.indexOf(source)+1}"
    var qr by remember { mutableStateOf<String?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) model.importQr(uri) }
    val captureFile=java.io.File(context.cacheDir,"qr/capture.jpg")
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success -> if(success) model.importQr(androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".files",captureFile)) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) model.importFile(uri) }
    val notices = screen.data.profiles.filter { pro.vinnikov.kawavpn.domain.ProfileNotice.of(it.profile) }.map { it.profile.name.trim() }.distinct()
    val entries = screen.data.profiles.filterNot { pro.vinnikov.kawavpn.domain.ProfileNotice.of(it.profile) }.filter { saved ->
        (selectedGroup == null || saved.source == selectedGroup) && saved.profile.name.contains(query,true) && when(filter) {
            "Обход" -> listOf("обход","bypass","lte","авто").any { it in saved.profile.name.lowercase() }
            "Игровые" -> listOf("игров","gaming").any { it in saved.profile.name.lowercase() }
            "LTE" -> saved.profile.name.contains("lte",true)
            "Избранное" -> saved.profile.id in screen.data.favorites
            "Обычные" -> listOf("обход","bypass","lte","игров","gaming").none { it in saved.profile.name.lowercase() }
            else -> true
        }
    }.let { if(sort) it.sortedBy { saved -> screen.latencies[saved.profile.id] ?: Long.MAX_VALUE } else it }
    Column(Modifier.fillMaxSize(), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top=6.dp), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Серверы",fontSize=26.sp,fontWeight=FontWeight.Bold)
            Text("${entries.size} доступно в списке",color=Muted,fontSize=12.sp,modifier=Modifier.weight(1f))
        }
        // The desktop toolbar: add, refresh, ping, sort by ping, more.
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            GlassIconButton(KawaIcon.Add,"Добавить подписку",enabled=!screen.busy,onClick=import)
            GlassIconButton(KawaIcon.Refresh,"Обновить подписки",enabled=!screen.busy) { model.refresh() }
            GlassIconButton(KawaIcon.Speed,if(screen.pinging) "Остановить проверку" else "Проверить пинг") { model.pingServers(entries.map { it.profile.id }.toSet()) }
            GlassButton(if(sort) "Пинг ↑" else "Пинг ↕",height=40.dp,fontSize=12) { sort=!sort }
            Spacer(Modifier.weight(1f))
            Box { GlassIconButton(KawaIcon.More,"Управление подпиской") { groupMenu=true }
                DropdownMenu(groupMenu,{groupMenu=false},containerColor=Sheet,shape=RoundedCornerShape(18.dp)) {
                    DropdownMenuItem(text={Text("QR-код из изображения")},onClick={groupMenu=false;photo.launch(arrayOf("image/*"))})
                    DropdownMenuItem(text={Text("Снять QR-код камерой")},onClick={groupMenu=false;captureFile.parentFile?.mkdirs();runCatching { camera.launch(androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".files",captureFile)) }.onFailure { model.showError("Камера недоступна. Выберите изображение с QR-кодом.") }})
                    if(selectedGroup!=null && selectedGroup!="manual") {
                        DropdownMenuItem(text={Text("QR-код подписки")},onClick={groupMenu=false;qr=selectedGroup})
                        DropdownMenuItem(text={Text("Копировать ссылку подписки")},onClick={groupMenu=false;(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kawa subscription",selectedGroup))})
                    }
                    DropdownMenuItem(text={Text("Импорт из файла")},onClick={groupMenu=false;file.launch(arrayOf("application/json","text/*","application/octet-stream"))})
                    DropdownMenuItem(text={Text("Из буфера обмена")},onClick={groupMenu=false; val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { model.import(it) }})
                    DropdownMenuItem(text={Text(if(screen.data.realPing) "Проверка: через VPN-протокол" else "Проверка: TCP-доступность")},onClick={groupMenu=false;model.pingServers(entries.map { it.profile.id }.toSet())})
                    if(selectedGroup!=null) DropdownMenuItem(text={Text("Удалить эту группу",color=androidx.compose.ui.graphics.Color(0xFFFF8A80))},onClick={groupMenu=false;deletingSource=selectedGroup})
                }
            }
        }
        if(groups.size>1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            GlassChip("Все подписки",selectedGroup==null) { group=null }
            groups.forEach { source -> GlassChip(groupName(source),selectedGroup==source) { group=source } }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Все","Обычные","Обход","Игровые","LTE","Избранное").forEach { item -> GlassChip(item,filter==item) { filter=item } }
        }
        GlassSearchField(query,{query=it},"Найти сервер…")
        screen.data.syncWarning?.let { Text(it,color=Muted,fontSize=12.sp) }
        if(notices.isNotEmpty()) SubscriptionNotice(notices,openCabinet)
        if(screen.pinging) LinearProgressIndicator(Modifier.fillMaxWidth(),color=Ink)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(top=4.dp,bottom=16.dp)) {
            if(entries.isEmpty()) item { GlassCard(Modifier.fillMaxWidth()) {
                Text(if(screen.data.profiles.isEmpty()) "Твои серверы будут здесь" else "Серверы не найдены",fontWeight=FontWeight.SemiBold,fontSize=18.sp)
                Text(if(screen.data.profiles.isEmpty()) "Войди через бота или добавь ссылку на подписку. Обычный VPN — безлимитный." else "Измените запрос или выберите другой фильтр.",color=Muted)
                if(screen.data.profiles.isEmpty()) { Spacer(Modifier.height(8.dp)); GlassButton("Войти через бота",Modifier.fillMaxWidth(),primary=true,enabled=screen.loginWaiting==null) { model.login() } }
            } }
            items(entries,key={it.profile.id}) { saved -> ServerRow(saved,saved.profile.id==screen.data.selected,saved.profile.id in screen.data.favorites,screen.latencies.containsKey(saved.profile.id),screen.latencies[saved.profile.id],screen.data.realPing,
                select={ model.select(saved.profile.id) }, connect={ model.selectAndConnect(saved.profile.id,connect) }, toggleFavorite={ model.toggleFavorite(saved.profile.id) }, menu={ detail=saved }) }
        }
    }
    qr?.let { value ->
        val bitmap=remember(value) { runCatching { val pixels=pro.vinnikov.kawavpn.domain.QrCodec.encode(value,512);android.graphics.Bitmap.createBitmap(pixels,512,512,android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull() }
        AlertDialog(onDismissRequest={qr=null},title={Text("QR-код подписки")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Код содержит доступ к вашей подписке. Не публикуйте его.",color=Muted,fontSize=13.sp)
            if(bitmap!=null) Image(bitmap.asImageBitmap(),"QR-код подписки",Modifier.fillMaxWidth().aspectRatio(1f)) else Text("Конфигурация слишком большая для QR-кода. Используйте файл или копирование JSON.")
        }},confirmButton={TextButton(onClick={qr=null}) { Text("Закрыть") }})
    }
    deletingSource?.let { source -> AlertDialog(onDismissRequest={deletingSource=null},title={Text("Удалить группу?")},text={Text("${groupName(source)} будет удалена из приложения. Подписка в кабинете продолжит действовать.")},confirmButton={TextButton(onClick={model.removeSource(source);deletingSource=null;group=null}) { Text("Удалить") }},dismissButton={TextButton(onClick={deletingSource=null}) { Text("Отмена") }}) }
    detail?.let { saved ->
        var json by remember(saved.profile.id) { mutableStateOf(saved.profile.configJson) }
        var editing by remember(saved.profile.id) { mutableStateOf(false) }
        AlertDialog(onDismissRequest={detail=null},title={Text(saved.profile.name)},text={
            Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(profileProtocol(saved.profile.configJson),color=Muted)
                if(editing) screen.error?.let { Text(it,color=androidx.compose.ui.graphics.Color(0xFFFFA49C),fontSize=13.sp) }
                if(editing) OutlinedTextField(json,{json=it},label={Text("Полная конфигурация JSON")},textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp),modifier=Modifier.fillMaxWidth(),minLines=8)
                else {
                    Text("Конфигурация содержит ключи подключения. Копируйте её только для своего устройства.",fontSize=13.sp,color=Muted)
                    GlassButton("Подключиться",Modifier.fillMaxWidth(),primary=true) { model.selectAndConnect(saved.profile.id,connect);detail=null }
                    GlassButton(if(saved.profile.id in screen.data.favorites) "Убрать из избранного" else "В избранное",Modifier.fillMaxWidth()) { model.toggleFavorite(saved.profile.id);detail=null }
                    GlassButton("Редактировать JSON",Modifier.fillMaxWidth()) { editing=true }
                    GlassButton("Копировать JSON",Modifier.fillMaxWidth()) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kawa VPN",json)) }
                    if(saved.originalJson!=null) TextButton(onClick={model.edit(saved.profile.id,"",true);detail=null}) { Text("Сбросить изменения") }
                    TextButton(onClick={model.remove(saved.profile.id);detail=null}) { Text("Удалить сервер") }
                }
            }
        },confirmButton={TextButton(onClick={if(editing) { model.edit(saved.profile.id,json,onSaved={detail=null}) } else detail=null}) { Text(if(editing) "Сохранить" else "Закрыть") }},dismissButton={if(editing) TextButton(onClick={editing=false}) { Text("Отмена") }})
    }
}
/** One server, as the desktop row: flag, name and protocol, ping chip, star, menu; the chosen one opens a connect button. */
@Composable private fun ServerRow(saved: SavedProfile, selected: Boolean, favorite: Boolean, measured: Boolean, latency: Long?, realPing: Boolean, select: () -> Unit, connect: () -> Unit, toggleFavorite: () -> Unit, menu: () -> Unit) {
    val press = rememberGlassPress()
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().liquidGlass(shape, if (selected) GlassTone.Clear else GlassTone.Regular, press, refraction = 12.dp, elevated = selected)
        .then(if (selected) Modifier.border(1.dp, androidx.compose.ui.graphics.Color(0x85FFFFFF), shape) else Modifier)) {
        Row(Modifier.fillMaxWidth().glassPress(press, true, select).semantics(mergeDescendants = true) { role = androidx.compose.ui.semantics.Role.Button; this.selected = selected }
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountryFlag(countryCode(saved.profile.name))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(saved.profile.name.replace(Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}"), "").trim(), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(profileProtocol(saved.profile.configJson) + if (saved.originalJson != null) " · изменён" else "", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            PingChip(measured, latency, realPing)
            GlassIconButton(if (favorite) KawaIcon.StarFilled else KawaIcon.Star, if (favorite) "Убрать из избранного" else "В избранное", size = 34.dp, tint = if (favorite) androidx.compose.ui.graphics.Color(0xFFF2D087) else Ink, clear = true, onClick = toggleFavorite)
            GlassIconButton(KawaIcon.Chevron, "Параметры сервера", size = 34.dp, clear = true, onClick = menu)
        }
        if (selected) GlassButton("Подключиться", Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp), primary = true, icon = KawaIcon.Shield, height = 44.dp, onClick = connect)
    }
}

/** The desktop ping chip: green fast, amber medium, muted otherwise. */
@Composable private fun PingChip(measured: Boolean, latency: Long?, realPing: Boolean) {
    val level = when { !measured -> 0; latency == null -> 4; latency < 150 -> 1; latency < 400 -> 2; else -> 3 }
    val back = when (level) { 1 -> androidx.compose.ui.graphics.Color(0xBB1E4930); 2 -> androidx.compose.ui.graphics.Color(0x90624616); else -> androidx.compose.ui.graphics.Color(0x0BFFFFFF) }
    val ink = when (level) { 1 -> androidx.compose.ui.graphics.Color(0xFF7BDF91); 2 -> androidx.compose.ui.graphics.Color(0xFFF1C967); else -> Muted }
    val text = when { !measured -> "—"; latency == null -> if (realPing) "Нет" else "Нет TCP"; else -> "$latency мс" }
    Box(Modifier.widthIn(min = 54.dp).height(28.dp).clip(RoundedCornerShape(10.dp)).background(back).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        Text(text, color = ink, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Search field in glass with the desktop's magnifier. */
@Composable internal fun GlassSearchField(value: String, change: (String) -> Unit, placeholder: String) {
    Row(Modifier.fillMaxWidth().height(46.dp).liquidGlass(RoundedCornerShape(50), GlassTone.Clear, refraction = 10.dp, elevated = false).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KawaIconView(KawaIcon.Search, Muted, Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = Muted, fontSize = 14.sp)
            androidx.compose.foundation.text.BasicTextField(value, change, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(color = Ink, fontSize = 14.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink), modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder })
        }
        if (value.isNotEmpty()) GlassIconButton(KawaIcon.Close, "Очистить поиск", size = 28.dp, tint = Muted, clear = true) { change("") }
    }
}
@Composable internal fun phoneChipColors()=FilterChipDefaults.filterChipColors(containerColor=androidx.compose.ui.graphics.Color(0xFF141415),labelColor=Muted,selectedContainerColor=Ink,selectedLabelColor=androidx.compose.ui.graphics.Color.Black)
internal fun profileProtocol(json:String):String=runCatching {
    val root=com.google.gson.JsonParser.parseString(json).asJsonObject
    root["outbounds"].asJsonArray.first { it.asJsonObject["protocol"]?.asString !in listOf("freedom","blackhole","dns","loopback") }.asJsonObject.let { outbound ->
        val stream=outbound["streamSettings"]?.asJsonObject
        listOfNotNull(outbound["protocol"]?.asString?.uppercase(),stream?.get("network")?.asString,stream?.get("security")?.asString).joinToString(" · ")
    }
}.getOrDefault("Полная конфигурация")

/** "⌛ Подписка истекла / Продлите подписку в боте" from the panel, as one message. */
@Composable internal fun SubscriptionNotice(lines: List<String>, renew: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Text(lines.first(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = androidx.compose.ui.graphics.Color(0xFFFFB86B))
        lines.drop(1).forEach { Text(it, color = Muted, fontSize = 13.sp) }
        Spacer(Modifier.height(6.dp))
        GlassButton("Продлить подписку", Modifier.fillMaxWidth(), primary = true, onClick = renew)
    }
}
