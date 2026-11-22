//! Talking to, and installing, the privileged helper.

use std::io::BufReader;
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use kawaray_core::helper_proto::{self, MIN_PROTOCOL, PROTOCOL, Request, Response, TunRequest};

#[derive(Debug)]
pub enum OpenError {
    /// Not installed or not running.
    Missing,
    /// Installed, but speaks an older protocol.
    Outdated(String),
    Other(anyhow::Error),
}

#[cfg(unix)]
type Conn = std::os::unix::net::UnixStream;
#[cfg(windows)]
type Conn = std::fs::File;

/// An open connection to the helper. The TUN session (if started) lives as
/// long as this value: dropping it makes the helper restore the network.
/// Strictly request → response (see `helper_proto`).
pub struct HelperSession {
    conn: BufReader<Conn>,
    pub version: String,
    pub protocol: u32,
    pub traffic: Option<(u64, u64)>,
}

impl HelperSession {
    pub fn open() -> Result<Self, OpenError> {
        let conn = connect().map_err(|_| OpenError::Missing)?;
        let mut s = Self { conn: BufReader::new(conn), version: String::new(), protocol: 0, traffic: None };
        match s.call(&Request::Hello) {
            // Linux helpers before 0.2.0 lack the fixes that make the tunnel work there
            // (rp_filter, host firewall, DNS redirect): same protocol, but replace them.
            Ok(Response::Hello { version, .. }) if cfg!(target_os = "linux") && older(&version, "0.2.0") => {
                Err(OpenError::Outdated(version))
            }
            Ok(Response::Hello { protocol, version }) if (MIN_PROTOCOL..=PROTOCOL).contains(&protocol) => {
                s.version = version;
                s.protocol = protocol;
                Ok(s)
            }
            Ok(Response::Hello { version, .. }) => Err(OpenError::Outdated(version)),
            Ok(other) => Err(OpenError::Other(anyhow!("unexpected reply {other:?}"))),
            // An old helper closes or answers garbage to requests it does not know.
            Err(e) => Err(OpenError::Other(e)),
        }
    }

    pub fn start(&mut self, req: TunRequest) -> Result<String> {
        // После сбоя или быстрого перезапуска помощник ещё несколько секунд
        // закрывает прежний туннель: ждём его, а не сдаёмся сразу.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(12);
        loop {
            match self.call(&Request::Start(req.clone()))? {
                Response::Started { tun } => return Ok(tun),
                Response::Error { message }
                    if message.contains("another Kawa VPN session") && std::time::Instant::now() < deadline =>
                {
                    std::thread::sleep(std::time::Duration::from_millis(700));
                }
                Response::Error { message } => bail!("{message}"),
                other => bail!("unexpected reply {other:?}"),
            }
        }
    }

    pub fn stop(&mut self) -> Result<()> {
        match self.call(&Request::Stop)? {
            Response::Stopped => Ok(()),
            Response::Error { message } => bail!("{message}"),
            other => bail!("unexpected reply {other:?}"),
        }
    }

    /// `Ok(None)` while the TUN is up; `Ok(Some(reason))` once it went down.
    pub fn status(&mut self) -> Result<Option<String>> {
        match self.call(&Request::Status)? {
            Response::Status { running: true, traffic, .. } => {
                self.traffic = traffic.map(|t| (t.received, t.sent));
                Ok(None)
            },
            Response::Status { error, .. } => {
                self.traffic = None;
                Ok(Some(error.unwrap_or_else(|| "туннель остановлен".into())))
            },
            Response::Error { message } => bail!("{message}"),
            other => bail!("unexpected reply {other:?}"),
        }
    }

    fn call(&mut self, req: &Request) -> Result<Response> {
        helper_proto::write_msg(self.conn.get_mut(), req).context("помощник не отвечает")?;
        helper_proto::read_msg(&mut self.conn)
            .context("помощник не отвечает")?
            .context("помощник закрыл соединение")
    }
}

/// `a < b` for dotted numeric versions.
fn older(a: &str, b: &str) -> bool {
    let parse = |v: &str| v.split('.').map(|p| p.parse::<u32>().unwrap_or(0)).collect::<Vec<_>>();
    parse(a) < parse(b)
}

#[cfg(unix)]
fn connect() -> std::io::Result<Conn> {
    let s = std::os::unix::net::UnixStream::connect(helper_proto::SOCKET_PATH)?;
    install_impl::verify_peer(&s)?;
    // Start can take a while (routes, DNS); nothing should take longer than this.
    s.set_read_timeout(Some(Duration::from_secs(45)))?;
    s.set_write_timeout(Some(Duration::from_secs(5)))?;
    Ok(s)
}

#[cfg(windows)]
fn connect() -> std::io::Result<Conn> {
    // All pipe instances busy for a moment: retry briefly (ERROR_PIPE_BUSY = 231).
    for _ in 0..20 {
        match std::fs::OpenOptions::new().read(true).write(true).open(helper_proto::PIPE_NAME) {
            Err(e) if e.raw_os_error() == Some(231) => std::thread::sleep(Duration::from_millis(100)),
            other => return other,
        }
    }
    std::fs::OpenOptions::new().read(true).write(true).open(helper_proto::PIPE_NAME)
}

// ── Installation ────────────────────────────────────────────────────────────

#[cfg(unix)]
#[path = "unix_helper.rs"]
mod install_impl;
#[cfg(unix)]
pub use install_impl::{install, uninstall};
#[cfg(unix)]
use install_impl::LOG_HINT;
#[cfg(windows)]
const LOG_HINT: &str = "Kawa VPN helper service log";
#[cfg(windows)]
pub use crate::windows_helper::{install, uninstall};

/// Human-readable helper state for the settings window.
pub fn describe() -> String {
    match HelperSession::open() {
        Ok(s) => format!("Установлен, версия {}. Подключение не требует пароля администратора.", s.version),
        Err(OpenError::Missing) => "Не установлен. Понадобится один раз при первом подключении.".into(),
        Err(OpenError::Outdated(v)) => format!("Установлена устаревшая версия {v}, нужно переустановить."),
        Err(OpenError::Other(e)) => format!("Ошибка: {e:#}"),
    }
}

pub fn install_and_wait() -> Result<()> {
    install()?;
    wait_ready(Duration::from_secs(10)).map_err(|e| match e {
        OpenError::Missing => anyhow!("помощник установлен, но не запустился (см. {LOG_HINT})"),
        OpenError::Outdated(v) => anyhow!("запустилась старая версия помощника {v}"),
        OpenError::Other(e) => e,
    })
}

/// Waits for a freshly installed helper to come up.
pub fn wait_ready(timeout: Duration) -> Result<(), OpenError> {
    let deadline = std::time::Instant::now() + timeout;
    loop {
        match HelperSession::open() {
            Ok(_) => return Ok(()),
            Err(OpenError::Missing) if std::time::Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(250));
            }
            Err(e) => return Err(e),
        }
    }
}


