//! Kawa cabinet contract. Billing mutations are issued once, never retried.
use anyhow::{Result, bail};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::time::Duration;

pub const API_BASE: &str = "https://cabinet.example.com/api/cabinet";

#[derive(Clone)]
pub struct Api {
    base: String,
    agent: ureq::Agent,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Session {
    pub access_token: String,
    pub refresh_token: String,
    pub user: Value,
}

#[derive(Clone, Deserialize)]
pub struct Login {
    pub token: String,
    pub bot_username: String,
    pub expires_in: u64,
}
impl Login {
    pub fn url(&self) -> Result<String> {
        if self.bot_username != "your_bot"
            || self.token.is_empty()
            || self.token.len() > 64
            || !self
                .token
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
        {
            bail!("Некорректный ответ авторизации");
        }
        Ok(format!(
            "https://t.me/{}?start=webauth_{}",
            self.bot_username, self.token
        ))
    }
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Purchase {
    pub tariff_id: i64,
    pub period_days: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub subscription_id: Option<i64>,
}

#[derive(Debug)]
pub enum Checkout {
    Fulfilled(Value),
    Invoice {
        url: String,
        payment_id: String,
        amount: i64,
    },
}

#[derive(Debug)]
pub struct RejectedPurchase(pub String);
impl std::fmt::Display for RejectedPurchase {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for RejectedPurchase {}

#[derive(Debug)]
pub struct SessionExpired;
impl std::fmt::Display for SessionExpired {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("Сессия истекла. Войдите через бота снова.")
    }
}
impl std::error::Error for SessionExpired {}

#[derive(Clone, Deserialize)]
pub struct AccountSubscription {
    pub id: i64,
    pub tariff_id: Option<i64>,
    pub tariff_name: Option<String>,
    pub status: String,
    pub end_date: Option<String>,
    pub subscription_url: Option<String>,
}
#[derive(Clone, Deserialize)]
pub struct Period {
    pub days: i64,
    pub label: String,
    pub price_kopeks: i64,
    pub price_label: String,
}
#[derive(Clone, Deserialize)]
pub struct Tariff {
    pub id: i64,
    pub name: String,
    pub description: Option<String>,
    pub periods: Vec<Period>,
    #[serde(default)]
    pub is_daily: bool,
}
#[derive(Clone, Deserialize)]
pub struct PaymentOption {
    pub id: String,
    pub name: String,
}
#[derive(Clone, Deserialize)]
pub struct PaymentMethod {
    pub id: String,
    pub min_amount_kopeks: i64,
    pub max_amount_kopeks: Option<i64>,
    #[serde(default)]
    pub options: Option<Vec<PaymentOption>>,
}
#[derive(Clone)]
pub struct Snapshot {
    pub user: Value,
    pub subscriptions: Vec<AccountSubscription>,
    pub tariffs: Vec<Tariff>,
    pub platega: Option<PaymentMethod>,
}

impl Default for Api {
    fn default() -> Self {
        Self::new(API_BASE.into())
    }
}
impl Api {
    fn new(base: String) -> Self {
        let agent = ureq::Agent::config_builder()
            .timeout_global(Some(Duration::from_secs(30)))
            .http_status_as_error(false)
            .max_redirects(0)
            .build()
            .into();
        Self { base, agent }
    }
    /// Test-only endpoint is restricted to the loopback interface.
    pub fn test_endpoint(base: String) -> Self {
        let u = url::Url::parse(&base).expect("test URL");
        assert!(u.scheme() == "http" && u.host_str() == Some("127.0.0.1"));
        Self::new(base)
    }
    fn request(
        &self,
        path: &str,
        token: Option<&str>,
        body: Option<&Value>,
    ) -> Result<(u16, Value)> {
        let url = format!("{}{path}", self.base);
        let auth = token.map(|t| format!("Bearer {t}")).unwrap_or_default();
        let response = if let Some(body) = body {
            let mut r = self.agent.post(&url).header("User-Agent", "KawaVPN/0.4.11");
            if token.is_some() {
                r = r.header("Authorization", &auth);
            }
            r.send_json(body)
        } else {
            let mut r = self.agent.get(&url).header("User-Agent", "KawaVPN/0.4.11");
            if token.is_some() {
                r = r.header("Authorization", &auth);
            }
            r.call()
        };
        // Do not include ureq errors: they may contain the credential-bearing URL.
        let mut response = response.map_err(|_| {
            anyhow::anyhow!(
                "Нет ответа сервера. Проверьте состояние заказа перед повторной покупкой."
            )
        })?;
        let status = response.status().as_u16();
        let body = response.body_mut().read_json::<Value>().map_err(|_| {
            anyhow::anyhow!("Некорректный ответ сервера. Проверьте состояние заказа.")
        })?;
        Ok((status, body))
    }
    fn accepted(status: u16, body: Value) -> Result<Value> {
        match status {
            200..=201 => Ok(body),
            401 => Err(SessionExpired.into()),
            410 => bail!("Ссылка входа истекла или отклонена. Начните вход снова."),
            429 => bail!("Слишком много запросов. Подождите минуту."),
            _ => bail!("Сервер вернул HTTP {status}. Проверьте кабинет перед повторной покупкой."),
        }
    }
    pub fn login(&self) -> Result<Login> {
        let (s, b) = self.request("/auth/deeplink/request", None, Some(&json!({})))?;
        Ok(serde_json::from_value(Self::accepted(s, b)?)?)
    }
    pub fn poll_login(&self, token: &str) -> Result<Option<Session>> {
        let (s, b) = self.request("/auth/deeplink/poll", None, Some(&json!({"token":token})))?;
        if s == 202 {
            return Ok(None);
        }
        Ok(Some(serde_json::from_value(Self::accepted(s, b)?)?))
    }
    pub fn refresh(&self, refresh: &str) -> Result<Session> {
        let (s, b) = self.request(
            "/auth/refresh",
            None,
            Some(&json!({"refresh_token":refresh})),
        )?;
        // /auth/refresh returns TokenResponse, not the login AuthResponse.
        #[derive(Deserialize)]
        struct Tokens { access_token: String, refresh_token: String }
        let tokens: Tokens = serde_json::from_value(Self::accepted(s, b)?)?;
        let user = self.get("/auth/me", &tokens.access_token).map_err(|error| {
            if error.downcast_ref::<SessionExpired>().is_some() {
                anyhow::anyhow!("Сервер отклонил обновлённую сессию. Повторите проверку позже.")
            } else { error }
        })?;
        if user["id"].as_i64().is_none() {
            bail!("Не удалось определить аккаунт. Повторите обновление.");
        }
        Ok(Session { access_token: tokens.access_token, refresh_token: tokens.refresh_token, user })
    }
    pub fn logout(&self, session: &Session) -> Result<()> {
        let (s, b) = self.request(
            "/auth/logout",
            Some(&session.access_token),
            Some(&json!({"refresh_token":session.refresh_token})),
        )?;
        Self::accepted(s, b)?;
        Ok(())
    }
    pub fn get(&self, path: &str, token: &str) -> Result<Value> {
        let (s, b) = self.request(path, Some(token), None)?;
        Self::accepted(s, b)
    }
    pub fn order(&self, token: &str, id: &str) -> Result<Option<Value>> {
        if id.len() != 32
            || !id
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        {
            bail!("Некорректный ID заказа");
        }
        let (s, b) = self.request(&format!("/balance/app-checkout/{id}"), Some(token), None)?;
        if s == 404 {
            return Ok(None);
        }
        Ok(Some(Self::accepted(s, b)?))
    }
    fn checked_order(&self, token: &str, id: &str) -> Result<Option<Value>> {
        let Some(payment) = self.order(token, id)? else { return Ok(None); };
        if payment["is_paid"] == false && payment["is_checkable"] == true {
            let local_id = payment["id"].as_i64().filter(|id| *id > 0)
                .ok_or_else(|| anyhow::anyhow!("Некорректный номер оплаты"))?;
            let (status, body) = self.request(
                &format!("/balance/pending-payments/platega/{local_id}/check"),
                Some(token), Some(&json!({})))?;
            let body = Self::accepted(status, body)?;
            if body["payment"].is_object() { return Ok(Some(body["payment"].clone())); }
        }
        Ok(Some(payment))
    }
    pub fn snapshot(&self, token: &str, subscription_id: Option<i64>) -> Result<Snapshot> {
        let user = self.get("/auth/me", token)?;
        let subs = self.get("/subscriptions", token)?;
        let path = subscription_id
            .map(|id| format!("/subscription/purchase-options?subscription_id={id}"))
            .unwrap_or_else(|| "/subscription/purchase-options".into());
        let options = self.get(&path, token)?;
        let methods: Vec<PaymentMethod> =
            serde_json::from_value(self.get("/balance/payment-methods", token)?)?;
        Ok(Snapshot {
            user,
            subscriptions: serde_json::from_value(subs["subscriptions"].clone())?,
            tariffs: serde_json::from_value(options["tariffs"].clone())?,
            platega: methods.into_iter().find(|m| m.id == "platega"),
        })
    }
    pub fn snapshot_refreshed(
        &self,
        session: &mut Session,
        target: Option<i64>,
    ) -> Result<Snapshot> {
        match self.snapshot(&session.access_token, target) {
            Err(error) if error.downcast_ref::<SessionExpired>().is_some() => {
                *session = self.refresh(&session.refresh_token)?;
                self.snapshot(&session.access_token, target)
                    .map_err(|error| {
                        if error.downcast_ref::<SessionExpired>().is_some() {
                            anyhow::anyhow!(
                                "Сервер отклонил обновлённую сессию. Повторите проверку позже."
                            )
                        } else {
                            error
                        }
                    })
            }
            result => result,
        }
    }
    pub fn order_refreshed(&self, session: &mut Session, id: &str) -> Result<Option<Value>> {
        match self.checked_order(&session.access_token, id) {
            Err(error) if error.downcast_ref::<SessionExpired>().is_some() => {
                *session = self.refresh(&session.refresh_token)?;
                self.checked_order(&session.access_token, id).map_err(|error| {
                    if error.downcast_ref::<SessionExpired>().is_some() {
                        anyhow::anyhow!(
                            "Сервер отклонил обновлённую сессию. Повторите проверку позже."
                        )
                    } else {
                        error
                    }
                })
            }
            result => result,
        }
    }
    pub fn checkout(
        &self,
        token: &str,
        purchase: &Purchase,
        option: &str,
        min: i64,
        max: Option<i64>,
    ) -> Result<Checkout> {
        self.checkout_for_order(
            token,
            purchase,
            option,
            min,
            max,
            &format!("{:032x}", fastrand::u128(..)),
        )
    }
    pub fn checkout_for_order(
        &self,
        token: &str,
        purchase: &Purchase,
        option: &str,
        min: i64,
        max: Option<i64>,
        checkout_id: &str,
    ) -> Result<Checkout> {
        if checkout_id.len() != 32
            || !checkout_id
                .bytes()
                .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
        {
            return Err(RejectedPurchase("Некорректный ID заказа".into()).into());
        }
        let (s, b) = self.request(
            "/subscription/purchase-tariff",
            Some(token),
            Some(&serde_json::to_value(purchase)?),
        )?;
        if s == 200 || s == 201 {
            return Ok(Checkout::Fulfilled(b));
        }
        let detail = &b["detail"];
        if s != 402 || detail["code"] != "insufficient_funds" || detail["cart_saved"] != true {
            if matches!(s, 400 | 401 | 403 | 404 | 409 | 422)
                || (s == 402 && detail["cart_saved"] != true)
                || detail["code"] == "cart_unavailable"
            {
                let message = if s == 401 {
                    "Сессия истекла. Обновите аккаунт или войдите снова.".into()
                } else {
                    format!("Заказ отклонён (HTTP {s}). Оплата не создана. Обновите аккаунт.")
                };
                return Err(RejectedPurchase(message).into());
            }
            Self::accepted(s, b)?;
            unreachable!();
        }
        let missing = detail["missing_amount"]
            .as_i64()
            .filter(|m| *m > 0)
            .ok_or_else(|| anyhow::anyhow!("Некорректная сумма заказа"))?;
        let amount = missing.max(min).max(1000);
        if max.is_some_and(|max| amount > max) {
            return Err(RejectedPurchase(
                "Сумма превышает лимит Platega. Откройте кабинет.".into(),
            )
            .into());
        }
        let (s,b) = self.request("/balance/topup",Some(token),Some(&json!({"amount_kopeks":amount,"payment_method":"platega","payment_option":option,"checkout_id":checkout_id})))?;
        if matches!(s, 400 | 401 | 403 | 404 | 422 | 429) {
            return Err(RejectedPurchase(format!(
                "Создание оплаты отклонено (HTTP {s}). Обновите аккаунт."
            ))
            .into());
        }
        let b = Self::accepted(s, b)?;
        let url = b["payment_url"]
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("Ссылка оплаты не получена. Проверьте кабинет."))?
            .to_string();
        let parsed = url::Url::parse(&url)?;
        if parsed.scheme() != "https"
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            bail!("Некорректная ссылка Platega");
        }
        let payment_id = b["payment_id"]
            .as_str()
            .map(str::to_owned)
            .or_else(|| b["payment_id"].as_i64().map(|i| i.to_string()))
            .unwrap_or_default();
        Ok(Checkout::Invoice {
            url,
            payment_id,
            amount,
        })
    }
}
