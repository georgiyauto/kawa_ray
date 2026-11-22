package pro.vinnikov.kawavpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pro.vinnikov.kawavpn.*
import pro.vinnikov.kawavpn.data.*
import pro.vinnikov.kawavpn.domain.BillingPhase

@Composable internal fun CabinetPanel(screen: ScreenState, model: KawaViewModel) {
    val owner = screen.data.session?.userId
    val pending = owner?.let { screen.data.pendingOrders[it.toString()] }
    var selection by remember { mutableStateOf<Pair<CabinetTariff, CabinetPeriod>?>(null) }
    var method by remember { mutableStateOf<String?>(null) }
    Text("Баланс: %.2f ₽".format(screen.data.cabinet.balanceKopeks / 100.0),fontSize=22.sp,fontWeight=FontWeight.SemiBold)
    if (pending != null) GlassCard(Modifier.fillMaxWidth()) {
        Text("Сохранённый заказ", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Text(when {
            pending.phase in setOf(BillingPhase.REJECTED, BillingPhase.PAYMENT_CANCELLED) -> "Заказ отменён или отклонён."
            pending.paid -> "Оплата подтверждена. Ожидаем активацию подписки."
            else -> "Проверьте статус перед новой оплатой. Заказ сохранён после перезапуска приложения."
        }, color = Muted, fontSize = 13.sp)
        GlassButton("Проверить оплату", Modifier.fillMaxWidth(), enabled = !screen.busy) { model.recoverPayment() }
        val paymentUrl = pending.paymentUrl
        if (paymentUrl != null && !pending.paid && pending.phase == BillingPhase.INVOICE_PENDING) GlassButton("Открыть оплату", Modifier.fillMaxWidth(), enabled = !screen.busy) { model.openPayment(paymentUrl) }
        if (pending.phase in setOf(BillingPhase.REJECTED, BillingPhase.PAYMENT_CANCELLED)) TextButton(onClick = { model.clearCancelledPayment() }, enabled = !screen.busy) { Text("Убрать отменённый заказ") }
    }
    for (subscription in screen.data.cabinet.subscriptions) GlassCard(Modifier.fillMaxWidth()) {
        Text(subscription.tariff_name ?: "Подписка №${subscription.id}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(if (subscription.status == "active") "Активна" else subscription.status, color = Muted)
        subscription.end_date?.let { Text("До $it", color = Muted, fontSize = 13.sp) }
        GlassButton("Продлить", Modifier.fillMaxWidth(), enabled = !screen.busy && pending == null) { model.showOffers(subscription.id) }
    }
    GlassButton("Выбрать подписку", Modifier.fillMaxWidth(), enabled = !screen.busy && pending == null) { model.showOffers() }
    screen.offers?.let { offers ->
        AlertDialog(onDismissRequest = { model.dismissOffers() }, title = { Text(if (screen.offerTarget == null) "Выберите тариф" else "Продление подписки") }, text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (offers.isEmpty()) Text("Сейчас нет доступных тарифов.")
                for (tariff in offers) {
                    Text(tariff.name, fontWeight = FontWeight.SemiBold)
                    tariff.description?.let { Text(it, fontSize = 13.sp) }
                    for (period in tariff.periods) GlassButton("${period.label} · ${period.price_label}", Modifier.fillMaxWidth(), enabled = !screen.busy) { selection = tariff to period }
                }
            }
        }, confirmButton = { TextButton(onClick = { model.dismissOffers() }) { Text("Закрыть") } })
    }
    selection?.let { (tariff, period) ->
        val options = screen.data.cabinet.paymentOptions.ifEmpty { listOf(CabinetPaymentOption("hosted", "Онлайн-оплата")) }
        val option = method?.takeIf { id -> options.any { it.id == id } } ?: options.first().id
        AlertDialog(onDismissRequest = { selection = null }, title = { Text("Подтвердите заказ") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${tariff.name}\n${period.label} · ${period.price_label}")
                Text("Если баланса недостаточно, откроется защищённая страница оплаты. При сбое нажмите «Проверить оплату».", fontSize = 13.sp)
                options.forEach { item -> Row { RadioButton(option == item.id, { method = item.id }); TextButton(onClick = { method = item.id }) { Text(item.name) } } }
            }
        }, confirmButton = { TextButton(onClick = { selection = null; model.dismissOffers(); model.purchase(tariff.id, period.days, screen.offerTarget, option) }, enabled = !screen.busy) { Text("Подтвердить") } },
            dismissButton = { TextButton(onClick = { selection = null }) { Text("Отмена") } })
    }
}
