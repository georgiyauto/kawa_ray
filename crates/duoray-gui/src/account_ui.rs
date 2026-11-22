use crate::{AppWindow, Shared, account_storage, open_url, refresh, render, save};
use anyhow::{Result, bail};
use duoray_core::{
    cabinet::{Api, Checkout, Purchase, Session, Snapshot},
    device::Device,
};
use serde::{Deserialize, Serialize};
use slint::{ComponentHandle, ModelRc, VecModel};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

#[derive(Clone, Serialize, Deserialize)]
struct Pending {
    #[serde(default)]
    checkout_id: String,
    #[serde(default)]
    started_at: i64,
    purchase: Purchase,
    baseline: Vec<(i64, Option<String>)>,
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    invoice_id: Option<String>,
    #[serde(default)]
    local_invoice: Option<i64>,
    #[serde(default)]
    paid: bool,
}
#[derive(Default, Serialize, Deserialize)]
struct Stored {
    #[serde(default)]
    owner: Option<i64>,
    refresh: String,
    managed: Vec<String>,
    pending: Option<Pending>,
}
struct State {
    stored: Stored,
    path: PathBuf,
    session: Option<Session>,
    snapshot: Option<Snapshot>,
    offers: Vec<(Purchase, String)>,
    target: Option<i64>,
    busy: bool,
    can_reset: bool,
    login_cancel: Arc<std::sync::atomic::AtomicBool>,
}
type Account = Arc<Mutex<State>>;

fn persist(s: &State) -> Result<()> {
    account_storage::save(&s.path, &s.stored)
}
fn strings(items: Vec<String>) -> ModelRc<slint::SharedString> {
    ModelRc::new(VecModel::from(
        items.into_iter().map(Into::into).collect::<Vec<_>>(),
    ))
}
fn fulfilled(p: &Pending, snapshot: &Snapshot) -> bool {
    if p.invoice_id.is_some() && !p.paid {
        return false;
    }
    snapshot.subscriptions.iter().any(|sub| {
        sub.tariff_id == Some(p.purchase.tariff_id)
            && p.purchase.subscription_id.is_none_or(|id| sub.id == id)
            && sub.status.eq_ignore_ascii_case("active")
            && match p.baseline.iter().find(|(id, _)| *id == sub.id) {
                None => true,
                Some((_, old)) => sub
                    .end_date
                    .as_ref()
                    .is_some_and(|date| old.as_ref().is_none_or(|old| date > old)),
            }
    })
}

fn show(ui: &AppWindow, s: &State) {
    ui.set_account_busy(s.busy);
    ui.set_account_logged_in(s.session.is_some());
    ui.set_account_pending(s.stored.pending.is_some());
    ui.set_account_can_reset(s.can_reset && !s.busy);
    ui.set_account_has_invoice(s.stored.pending.as_ref().is_some_and(|p| p.url.is_some()));
    if let Some(snapshot) = &s.snapshot {
        let user = &snapshot.user;
        let name = user["first_name"]
            .as_str()
            .or_else(|| user["username"].as_str())
            .unwrap_or("Аккаунт Kawa VPN");
        let balance = user["balance_kopeks"].as_i64().unwrap_or(0) as f64 / 100.;
        ui.set_account_summary(format!("{name} · баланс {balance:.2} ₽").into());
        let mut targets = vec!["Купить подписку".into()];
        targets.extend(snapshot.subscriptions.iter().map(|sub| {
            format!(
                "Продлить: {} · до {}",
                sub.tariff_name.as_deref().unwrap_or("Подписка"),
                sub.end_date
                    .as_deref()
                    .unwrap_or("—")
                    .split('T')
                    .next()
                    .unwrap_or("—")
            )
        }));
        ui.set_account_targets(strings(targets));
        ui.set_account_offers(strings(
            s.offers.iter().map(|(_, label)| label.clone()).collect(),
        ));
        let methods = snapshot.platega.as_ref()
            .map(|_| vec!["Platega".into()]).unwrap_or_default();
        ui.set_account_methods(strings(methods));
        ui.set_account_can_pay(snapshot.platega.is_some() && !s.offers.is_empty());
    }
}

fn make_offers(s: &mut State) {
    s.offers.clear();
    if let Some(snapshot) = &s.snapshot {
        for tariff in snapshot.tariffs.iter().filter(|t| !t.is_daily) {
            if let Some(target) = s.target {
                if let Some(sub) = snapshot.subscriptions.iter().find(|sub| sub.id == target) {
                    if sub.tariff_id.is_some() && sub.tariff_id != Some(tariff.id) {
                        continue;
                    }
                }
            }
            for period in &tariff.periods {
                if period.days <= 0 || period.price_kopeks < 0 {
                    continue;
                }
                let target = s.target.or_else(|| {
                    snapshot
                        .subscriptions
                        .iter()
                        .find(|sub| sub.tariff_id == Some(tariff.id))
                        .map(|sub| sub.id)
                });
                s.offers.push((
                    Purchase {
                        tariff_id: tariff.id,
                        period_days: period.days,
                        subscription_id: target,
                    },
                    format!(
                        "{} · {} · {}",
                        tariff.name, period.label, period.price_label
                    ),
                ));
            }
        }
    }
}

fn sync_subscriptions(
    ui: &AppWindow,
    app: &Shared,
    device: &Arc<Device>,
    account: &Account,
) -> Result<()> {
    let mut s = account.lock().unwrap();
    let Some(snapshot) = &s.snapshot else {
        return Ok(());
    };
    let links: Vec<_> = snapshot
        .subscriptions
        .iter()
        .filter_map(|sub| {
            sub.subscription_url.as_ref().map(|url| {
                (
                    url.clone(),
                    sub.tariff_name.clone().unwrap_or_else(|| "Kawa VPN".into()),
                )
            })
        })
        .collect();
    let mut st = app.lock().unwrap();
    let current_urls: Vec<_> = links.iter().map(|(url, _)| url.clone()).collect();
    st.store
        .subscriptions
        .retain(|sub| !s.stored.managed.contains(&sub.url) || current_urls.contains(&sub.url));
    s.stored.managed.retain(|url| current_urls.contains(url));
    let mut ids = Vec::new();
    for (url, name) in links {
        if !url.starts_with("https://") {
            continue;
        }
        let id = st
            .store
            .subscriptions
            .iter()
            .find(|sub| sub.url == url)
            .map(|sub| sub.id.clone());
        let id = match id {
            Some(id) => id,
            None => st.store.add(&url, &name)?,
        };
        if !s.stored.managed.contains(&url) {
            s.stored.managed.push(url);
        }
        ids.push(id);
    }
    save(&mut st);
    if (ui.get_current_sub() < 0 || ui.get_current_sub() as usize >= st.store.subscriptions.len()
        || st.store.subscriptions.get(ui.get_current_sub() as usize)
            .is_some_and(|sub| sub.is_manual() && sub.servers.is_empty()))
        && !st.store.subscriptions.is_empty()
    {
        if let Some(index) = st.store.preferred_subscription_index() {
            ui.set_current_sub(index as i32);
        }
    }
    render(ui, &st);
    drop(st);
    persist(&s)?;
    drop(s);
    for id in ids {
        refresh(ui, app, device, id);
    }
    Ok(())
}

fn reload(ui: &AppWindow, app: &Shared, device: &Arc<Device>, account: &Account, rotate: bool) {
    {
        let mut s = account.lock().unwrap();
        if s.busy || (s.session.is_none() && s.stored.refresh.is_empty()) {
            return;
        }
        s.busy = true;
        show(ui, &s);
    }
    let (weak, app, device, account) = (ui.as_weak(), app.clone(), device.clone(), account.clone());
    std::thread::spawn(move || {
        let mut completed = false;
        let result: Result<()> = (|| {
            let (refresh_token, session, target) = {
                let s = account.lock().unwrap();
                (s.stored.refresh.clone(), s.session.clone(), s.target)
            };
            let api = Api::default();
            let mut session = if rotate || session.is_none() {
                api.refresh(&refresh_token)?
            } else {
                session.unwrap()
            };
            {
                let mut s = account.lock().unwrap();
                let owner = session.user["id"].as_i64();
                if s.stored.owner.is_some() && s.stored.owner != owner {
                    bail!("Аккаунт сессии не совпадает. Войдите через бота снова.");
                }
                s.stored.owner = owner;
                s.stored.refresh = session.refresh_token.clone();
                s.session = Some(session.clone());
                persist(&s)?;
            }
            let snapshot_result = api.snapshot_refreshed(&mut session, target);
            {
                let mut s = account.lock().unwrap();
                s.stored.refresh = session.refresh_token.clone();
                s.session = Some(session.clone());
                persist(&s)?;
            }
            let snapshot = snapshot_result?;
            let pending = account.lock().unwrap().stored.pending.clone();
            let mut payment_cancelled = false;
            if let Some(pending) = pending {
                let payment_result = api.order_refreshed(&mut session, &pending.checkout_id);
                {
                    let mut s = account.lock().unwrap();
                    s.stored.refresh = session.refresh_token.clone();
                    s.session = Some(session.clone());
                    persist(&s)?;
                }
                let payment = payment_result?;
                let mut s = account.lock().unwrap();
                s.can_reset = payment.is_none()
                    && pending.url.is_none()
                    && chrono::Utc::now().timestamp() - pending.started_at > 120;
                if let Some(payment) = payment {
                    if let Some(p) = s.stored.pending.as_mut() {
                        p.local_invoice = payment["id"].as_i64();
                        p.paid = payment["is_paid"] == true;
                        p.invoice_id = payment["identifier"].as_str().map(str::to_owned);
                        if let Some(url) = payment["payment_url"].as_str() {
                            if url::Url::parse(url).is_ok_and(|u| {
                                u.scheme() == "https"
                                    && u.username().is_empty()
                                    && u.password().is_none()
                            }) {
                                p.url = Some(url.into());
                            }
                        }
                        payment_cancelled = !p.paid
                            && matches!(
                                payment["status"].as_str(),
                                Some("FAILED" | "CANCELED" | "EXPIRED")
                            );
                    }
                }
            }
            let mut s = account.lock().unwrap();
            let delivered = s
                .stored
                .pending
                .as_ref()
                .is_some_and(|p| fulfilled(p, &snapshot));
            completed = delivered;
            if delivered || payment_cancelled {
                s.stored.pending = None;
                s.can_reset = false;
            }
            s.snapshot = Some(snapshot);
            make_offers(&mut s);
            persist(&s)?;
            Ok(())
        })();
        let _=weak.upgrade_in_event_loop(move |ui| {
            let mut s=account.lock().unwrap(); s.busy=false;
            match result {
                Ok(())=>ui.set_account_message(if s.stored.pending.as_ref().is_some_and(|p|p.paid){"Оплата подтверждена. Ожидаем выдачу или продление подписки…"}else if s.stored.pending.is_some(){"Ожидаем оплату. Проверяем Platega автоматически; заказ сохранён и после перезапуска."}else if completed{"Подписка выдана или продлена. Серверы обновлены."}else{"Данные аккаунта обновлены."}.into()),
                Err(e)=>{
                    if e.downcast_ref::<duoray_core::cabinet::SessionExpired>().is_some(){s.session=None;s.stored.refresh.clear();let _=persist(&s);}
                    ui.set_account_message(e.to_string().into());
                },
            }
            show(&ui,&s);
            drop(s);
            if let Err(e)=sync_subscriptions(&ui,&app,&device,&account){ui.set_account_message(e.to_string().into());}
        });
    });
}

pub fn install(ui: &AppWindow, app: &Shared, device: &Arc<Device>) {
    let path = app.lock().unwrap().path.with_file_name("account.dpapi");
    let stored = match account_storage::load(&path) {
        Ok(Some(stored)) => stored,
        Ok(None) => Stored::default(),
        Err(_) => {
            ui.set_account_message(
                "Не удалось прочитать сохранённый вход. Войдите через бота снова.".into(),
            );
            Stored::default()
        }
    };
    let account: Account = Arc::new(Mutex::new(State {
        stored,
        path,
        session: None,
        snapshot: None,
        offers: vec![],
        target: None,
        busy: false,
        can_reset: false,
        login_cancel: Arc::new(std::sync::atomic::AtomicBool::new(false)),
    }));
    ui.on_buy({
        let weak = ui.as_weak();
        move || weak.unwrap().set_account_open(true)
    });
    ui.on_account_login({
        let (weak, account, app, device) =
            (ui.as_weak(), account.clone(), app.clone(), device.clone());
        move || {
            let ui = weak.unwrap();
            let cancel = {
                let mut s = account.lock().unwrap();
                if s.busy {
                    return;
                }
                s.busy = true;
                s.login_cancel = Arc::new(std::sync::atomic::AtomicBool::new(false));
                show(&ui, &s);
                s.login_cancel.clone()
            };
            ui.set_account_message(
                "Подтвердите вход в @your_bot. Ссылка действует 5 минут.".into(),
            );
            let (weak, account, app, device) =
                (ui.as_weak(), account.clone(), app.clone(), device.clone());
            std::thread::spawn(move || {
                let result: Result<Session> = (|| {
                    let api = Api::default();
                    let login = api.login()?;
                    let url = login.url()?;
                    let _ = weak.upgrade_in_event_loop(move |_| open_url(&url));
                    let deadline = Instant::now() + Duration::from_secs(login.expires_in.min(300));
                    while Instant::now() < deadline {
                        if cancel.load(std::sync::atomic::Ordering::Relaxed) {
                            bail!("Вход отменён");
                        }
                        if let Some(session) = api.poll_login(&login.token)? {
                            if cancel.load(std::sync::atomic::Ordering::Relaxed) {
                                let _ = api.logout(&session);
                                bail!("Вход отменён");
                            }
                            return Ok(session);
                        }
                        std::thread::sleep(Duration::from_secs(3));
                    }
                    bail!("Время входа истекло. Нажмите «Войти» снова.")
                })();
                let _ = weak.upgrade_in_event_loop(move |ui| {
                    let mut s = account.lock().unwrap();
                    s.busy = false;
                    match result {
                        Ok(session) => {
                            let owner = session.user["id"].as_i64();
                            if s.stored.owner.is_some() && s.stored.owner != owner {
                                let mut st = app.lock().unwrap();
                                if let Some(conn) = st.conn.take() {
                                    std::thread::spawn(move || conn.disconnect());
                                }
                                st.conn_state = crate::ConnState::Idle;
                                st.store
                                    .subscriptions
                                    .retain(|sub| !s.stored.managed.contains(&sub.url));
                                save(&mut st);
                                render(&ui, &st);
                                s.stored = Stored::default();
                                s.snapshot = None;
                                s.target = None;
                                s.can_reset = false;
                                ui.set_account_target_index(0);
                                ui.set_account_offer_index(0);
                                ui.set_current_sub(-1);
                            }
                            s.stored.owner = owner;
                            s.stored.refresh = session.refresh_token.clone();
                            s.session = Some(session);
                            if let Err(e) = persist(&s) {
                                ui.set_account_message(e.to_string().into());
                            }
                        }
                        Err(e) => ui.set_account_message(e.to_string().into()),
                    }
                    show(&ui, &s);
                    drop(s);
                    reload(&ui, &app, &device, &account, false);
                });
            });
        }
    });
    ui.on_account_cancel_login({
        let account = account.clone();
        move || {
            account
                .lock()
                .unwrap()
                .login_cancel
                .store(true, std::sync::atomic::Ordering::Relaxed)
        }
    });
    ui.on_account_reload({
        let (weak, account, app, device) =
            (ui.as_weak(), account.clone(), app.clone(), device.clone());
        move || reload(&weak.unwrap(), &app, &device, &account, true)
    });
    ui.on_account_target_changed({
        let (weak, account, app, device) =
            (ui.as_weak(), account.clone(), app.clone(), device.clone());
        move |index| {
            let ui = weak.unwrap();
            {
                let mut s = account.lock().unwrap();
                if s.busy || s.stored.pending.is_some() {
                    return;
                }
                s.target = s
                    .snapshot
                    .as_ref()
                    .and_then(|snap| snap.subscriptions.get((index - 1) as usize))
                    .map(|sub| sub.id);
                ui.set_account_offer_index(0);
            }
            reload(&ui, &app, &device, &account, false);
        }
    });
    ui.on_account_pay({
        let (weak,account,app,device)=(ui.as_weak(),account.clone(),app.clone(),device.clone());
        move || {
            let ui=weak.unwrap();
            let request:Result<_>=(|| {
                let mut s=account.lock().unwrap();if s.busy || s.stored.pending.is_some(){bail!("Сначала дождитесь завершения текущего заказа");}
                let snapshot=s.snapshot.as_ref().ok_or_else(||anyhow::anyhow!("Обновите аккаунт"))?;
                let purchase=s.offers.get(ui.get_account_offer_index() as usize).ok_or_else(||anyhow::anyhow!("Выберите тариф"))?.0.clone();
                let method=snapshot.platega.as_ref().ok_or_else(||anyhow::anyhow!("Platega сейчас недоступна"))?;
                let option="hosted".to_string();
                let (min,max)=(method.min_amount_kopeks,method.max_amount_kopeks);
                let session=s.session.as_ref().ok_or_else(||anyhow::anyhow!("Войдите через бота"))?.clone();
                let pending=Pending{checkout_id:format!("{:032x}",fastrand::u128(..)),started_at:chrono::Utc::now().timestamp(),purchase:purchase.clone(),baseline:snapshot.subscriptions.iter().map(|sub|(sub.id,sub.end_date.clone())).collect(),url:None,invoice_id:None,local_invoice:None,paid:false};
                s.stored.pending=Some(pending);persist(&s)?;s.busy=true;show(&ui,&s);
                Ok((session,purchase,option,min,max))
            })();
            let (session,purchase,option,min,max)=match request {Ok(r)=>r,Err(e)=>{ui.set_account_message(e.to_string().into());return;}};
            ui.set_account_message("Создаём заказ. Не закрывайте приложение.".into());
            let (weak,account,app,device)=(ui.as_weak(),account.clone(),app.clone(),device.clone());
            std::thread::spawn(move || {
                let checkout_id=account.lock().unwrap().stored.pending.as_ref().unwrap().checkout_id.clone();
                // Refresh and persist credentials BEFORE any billing mutation. Never
                // retry purchase/topup when a response could have been lost.
                let result: Result<Checkout> = (|| {
                    let api=Api::default();
                    let session=api.refresh(&session.refresh_token).map_err(|e|
                        duoray_core::cabinet::RejectedPurchase(e.to_string()))?;
                    {
                        let mut s=account.lock().unwrap();
                        if session.user["id"].as_i64()!=s.stored.owner {
                            return Err(duoray_core::cabinet::RejectedPurchase(
                                "Аккаунт изменился. Войдите через бота снова.".into()).into());
                        }
                        s.stored.refresh=session.refresh_token.clone();
                        s.session=Some(session.clone());
                        persist(&s).map_err(|_|duoray_core::cabinet::RejectedPurchase(
                            "Не удалось сохранить заказ. Оплата не создана.".into()))?;
                    }
                    api.checkout_for_order(&session.access_token,&purchase,&option,min,max,&checkout_id)
                })();
                let _=weak.upgrade_in_event_loop(move |ui| {
                    let mut s=account.lock().unwrap();s.busy=false;
                    match result {
                        Ok(Checkout::Invoice{url,payment_id,..})=>{
                            if let Some(p)=s.stored.pending.as_mut(){p.url=Some(url.clone());p.invoice_id=Some(payment_id);}
                            if persist(&s).is_ok(){open_url(&url);ui.set_account_message("Выберите способ оплаты на странице Platega. Подписка выдастся автоматически после подтверждения платежа.".into());}
                            else {ui.set_account_message("Заказ создан, но не сохранён. Откройте кабинет для проверки.".into());}
                        },
                        Ok(Checkout::Fulfilled(_))=>ui.set_account_message("Покупка принята. Проверяем выдачу подписки…".into()),
                        Err(e)=>{
                            if e.downcast_ref::<duoray_core::cabinet::RejectedPurchase>().is_some(){s.stored.pending=None;let _=persist(&s);}
                            ui.set_account_message(e.to_string().into());
                        },
                    }
                    show(&ui,&s);drop(s);reload(&ui,&app,&device,&account,false);
                });
            });
        }
    });
    ui.on_account_open_payment({
        let account = account.clone();
        move || {
            if let Some(url) = account
                .lock()
                .unwrap()
                .stored
                .pending
                .as_ref()
                .and_then(|p| p.url.as_ref())
            {
                open_url(url);
            }
        }
    });
    ui.on_account_reset({
        let (weak, account) = (ui.as_weak(), account.clone());
        move || {
            let ui = weak.unwrap();
            let mut s = account.lock().unwrap();
            if s.busy || !s.can_reset {
                return;
            }
            s.stored.pending = None;
            s.can_reset = false;
            match persist(&s) {
                Ok(()) => ui.set_account_message(
                    "Незавершённый заказ сброшен. Новая покупка потребует вашего нажатия.".into(),
                ),
                Err(e) => ui.set_account_message(e.to_string().into()),
            }
            ui.set_account_reset_confirm(false);
            show(&ui, &s);
        }
    });
    ui.on_account_logout({
        let (weak, account, app) = (ui.as_weak(), account.clone(), app.clone());
        move || {
            let ui = weak.unwrap();
            let mut s = account.lock().unwrap();
            if s.busy {
                return;
            }
            if s.stored.pending.is_some() {
                ui.set_account_message(
                    "Сначала завершите заказ или проверьте его в кабинете.".into(),
                );
                return;
            }
            if let Some(session) = s.session.take() {
                std::thread::spawn(move || {
                    let _ = Api::default().logout(&session);
                });
            }
            let mut st = app.lock().unwrap();
            if let Some(conn) = st.conn.take() {
                std::thread::spawn(move || conn.disconnect());
            }
            st.conn_state = crate::ConnState::Idle;
            st.store
                .subscriptions
                .retain(|sub| !s.stored.managed.contains(&sub.url));
            save(&mut st);
            ui.set_current_sub(-1);
            render(&ui, &st);
            s.stored = Stored::default();
            s.snapshot = None;
            s.offers.clear();
            s.target = None;
            s.can_reset = false;
            let storage_error = crate::account_storage::remove(&s.path).err();
            ui.set_account_target_index(0);
            ui.set_account_offer_index(0);
            ui.set_account_message(storage_error.map(|e| format!("Вы вышли. Не удалось удалить сохранённые данные: {e}")).unwrap_or_else(|| "Вы вышли из аккаунта.".into()).into());
            show(&ui, &s);
        }
    });
    let (weak, account_, app_, device_) =
        (ui.as_weak(), account.clone(), app.clone(), device.clone());
    let timer = Box::leak(Box::new(slint::Timer::default()));
    timer.start(
        slint::TimerMode::Repeated,
        Duration::from_secs(15),
        move || {
            let retry = {
                let s=account_.lock().unwrap();
                s.stored.pending.is_some() || (s.session.is_none() && !s.stored.refresh.is_empty())
            };
            if retry {
                if let Some(ui) = weak.upgrade() {
                    reload(&ui, &app_, &device_, &account_, false);
                }
            }
        },
    );
    reload(ui, app, device, &account, true);
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn paid_balance_alone_is_not_provisioning_and_renewal_checks_exact_id() {
        let mut pending = Pending {
            checkout_id: "a".repeat(32),
            started_at: 0,
            purchase: Purchase {
                tariff_id: 1,
                period_days: 30,
                subscription_id: Some(399),
            },
            baseline: vec![(399, Some("2026-10-06".into()))],
            url: None,
            invoice_id: None,
            local_invoice: None,
            paid: false,
        };
        let sub = serde_json::from_value(
            serde_json::json!({"id":400,"tariff_id":1,"status":"active","end_date":"2026-12-06"}),
        )
        .unwrap();
        let mut snapshot = Snapshot {
            user: serde_json::json!({"balance_kopeks":7500}),
            subscriptions: vec![sub],
            tariffs: vec![],
            platega: None,
        };
        assert!(!fulfilled(&pending, &snapshot));
        snapshot.subscriptions[0].id = 399;
        snapshot.subscriptions[0].end_date = Some("2026-10-06".into());
        assert!(!fulfilled(&pending, &snapshot));
        snapshot.subscriptions[0].end_date = Some("2026-11-06".into());
        assert!(fulfilled(&pending, &snapshot));
        pending.invoice_id = Some("invoice".into());
        assert!(!fulfilled(&pending, &snapshot));
        pending.paid = true;
        assert!(fulfilled(&pending, &snapshot));
    }
}

