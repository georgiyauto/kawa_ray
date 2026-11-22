//! Error reports for debugging, sent only with the user's consent.
//!
//! Every text that leaves the machine goes through [`sanitize`]: links, IP
//! addresses in logs, host names, UUIDs, e-mails, keys and the home directory are
//! replaced with placeholders, so subscription links, server addresses and
//! credentials never reach the hub. Disclosed device metadata is separate;
//! the hub records the sender IP only under the new report policy.
//! Reports are queued as files and sent in
//! the background, so a crash or a broken network loses nothing.
//!
//! Automatic reports need `Settings::telemetry == Some(true)`; a report the
//! user sends from "Сообщить о проблеме" is an explicit action and is sent
//! regardless (the dialog shows exactly what goes out).

use std::collections::{HashSet, VecDeque};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{LazyLock, Mutex, OnceLock};
use std::time::{Duration, Instant};

use anyhow::{Context, Result, bail};
use regex::Regex;
use serde_json::{Value, json};

/// Hubs that receive reports and serve updates, main first. The others are
/// mirrors for when a domain is blocked or down; updates are signature-
/// checked whichever one answers.
const HUBS: &[&str] = &[
    "https://hub.example.com/kawa-app",
    "https://hub-mirror.example.com/kawa-app",
];

/// The hub that answered last; tried first next time.
static PREFERRED: AtomicUsize = AtomicUsize::new(0);
static DATA_DIR: OnceLock<PathBuf> = OnceLock::new();

/// Hubs in the order to try them. `KAWA_HUB_URL` replaces the list (tests).
pub fn hubs() -> Vec<String> {
    if let Ok(url) = std::env::var("KAWA_HUB_URL") {
        return vec![url];
    }
    if HUBS.is_empty() {
        return Vec::new();
    }
    let first = PREFERRED.load(Ordering::Relaxed) % HUBS.len();
    (0..HUBS.len())
        .map(|i| HUBS[(first + i) % HUBS.len()].to_string())
        .collect()
}

/// Remembers a hub that worked.
pub fn hub_ok(url: &str) {
    if let Some(i) = HUBS.iter().position(|h| *h == url) {
        PREFERRED.store(i, Ordering::Relaxed);
    }
}

const LOG_LINES: usize = 300;
/// Automatic reports per run; a crash loop must not flood the hub.
const AUTO_LIMIT: usize = 20;

static ENABLED: AtomicBool = AtomicBool::new(false);
static SENT_AUTO: AtomicUsize = AtomicUsize::new(0);
static STATE: LazyLock<Mutex<State>> = LazyLock::new(Default::default);
static STARTED: LazyLock<Instant> = LazyLock::new(Instant::now);
static DEVICE: LazyLock<Value> = LazyLock::new(device_metadata);
static SESSION_IO: Mutex<()> = Mutex::new(());
static AUTO_QUEUE_IO: Mutex<()> = Mutex::new(());

#[derive(Default)]
struct State {
    log: VecDeque<String>,
    queue: Option<PathBuf>,
    install_id: String,
    /// Settings / server summary, refreshed by the app; also used by panics.
    context: Value,
    /// Signatures already reported in this run.
    seen: HashSet<String>,
}

/// Sets where queued reports live and installs the panic hook.
pub fn init(data_dir: &Path) {
    let _ = DATA_DIR.set(data_dir.to_path_buf());
    LazyLock::force(&STARTED);
    let queue = data_dir.join("reports");
    let _ = std::fs::create_dir_all(&queue);
    STATE.lock().unwrap().queue = Some(queue);
    recover_session(data_dir);
    #[cfg(windows)]
    native_crash::init(data_dir);

    let default = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        default(info);
        if !ENABLED.load(Ordering::Relaxed) {
            return;
        }
        let what = info
            .payload()
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| info.payload().downcast_ref::<String>().cloned())
            .unwrap_or_default();
        let place = info
            .location()
            .map(|l| format!("{}:{}", l.file(), l.line()))
            .unwrap_or_default();
        let trace = std::backtrace::Backtrace::force_capture();
        // Written synchronously: the process may be about to die.
        if let Some(report) = capture("panic", &format!("{what} at {place}\n\n{trace}"), None, true) {
            if enqueue(&report).is_ok() {
                if let Ok(_io) = SESSION_IO.try_lock() { remove_session(); }
            }
        }
    }));
}

/// Applies the consent setting.
pub fn configure(enabled: bool, install_id: &str) {
    if !enabled { ENABLED.store(false, Ordering::Relaxed); }
    STATE.lock().unwrap().install_id = install_id.to_string();
    ENABLED.store(enabled, Ordering::Relaxed);
    if enabled {
        std::thread::spawn(|| {
            LazyLock::force(&DEVICE);
            save_session();
        });
    } else {
        clean_shutdown();
    }
}

pub fn set_context(context: Value) {
    STATE.lock().unwrap().context = context;
}

/// One line for the activity log sent with reports (sanitized right away).
pub fn log(line: impl AsRef<str>) {
    let line = format!(
        "{} {}",
        chrono::Local::now().format("%H:%M:%S"),
        sanitize(line.as_ref())
    );
    let mut st = STATE.lock().unwrap();
    if st.log.len() == LOG_LINES {
        st.log.pop_front();
    }
    st.log.push_back(line);
}

/// Reports an error automatically, if the user agreed to it. The same error
/// is reported once per run.
pub fn auto(kind: &str, message: &str) {
    log(format!("{kind}: {}", first_line(message)));
    if !ENABLED.load(Ordering::Relaxed) || SENT_AUTO.load(Ordering::Relaxed) >= AUTO_LIMIT {
        return;
    }
    let sig = signature(kind, message);
    if !STATE.lock().unwrap().seen.insert(sig) {
        return;
    }
    SENT_AUTO.fetch_add(1, Ordering::Relaxed);
    let Some(report) = build(kind, message, None) else { return; };
    let queued = {
        let Ok(_io) = AUTO_QUEUE_IO.lock() else { return; };
        ENABLED.load(Ordering::Relaxed) && enqueue(&report).is_ok()
    };
    if queued {
        flush();
    }
}

/// The report "Сообщить о проблеме" would send, for the preview.
pub fn manual_report(note: &str) -> Value {
    build("user_report", "", Some(note)).unwrap_or(Value::Null)
}

/// Sends a report now and returns its code (DR-XXXXXX). Blocking.
pub struct Delivery {
    pub id: String,
    pub queued: bool,
}

pub fn send_now(report: &Value) -> Result<Delivery> {
    let mut prepared=report.clone();
    attach_provider_ip(&mut prepared, provider_ip);
    let report=&prepared;
    match post(report) {
        Ok(id) => Ok(Delivery { id, queued: false }),
        Err(PostError::Later(_)) => {
            enqueue(report)?;
            Ok(Delivery { id: report["report_id"].as_str().unwrap_or_default().to_string(), queued: true })
        }
        Err(e) => Err(e.into()),
    }
}

/// Deletes reports still waiting to be sent (the user opted out).
pub fn drop_queue() {
    let Ok(_io) = AUTO_QUEUE_IO.lock() else { return; };
    let Some(queue) = STATE.lock().unwrap().queue.clone() else {
        return;
    };
    if let Ok(dir) = std::fs::read_dir(queue) {
        for e in dir.flatten() {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

/// Sends queued reports in the background; failures stay queued.
pub fn flush() {
    let Some(queue) = STATE.lock().unwrap().queue.clone() else {
        return;
    };
    static BUSY: AtomicBool = AtomicBool::new(false);
    if BUSY.swap(true, Ordering::Relaxed) {
        return;
    }
    std::thread::spawn(move || {
        let mut files: Vec<PathBuf> = std::fs::read_dir(&queue)
            .map(|d| {
                d.filter_map(|e| e.ok().map(|e| e.path()))
                    .filter(|p| p.extension().is_some_and(|x| x == "json"))
                    .collect()
            })
            .unwrap_or_default();
        files.sort();
        for path in files {
            let Ok(report) = std::fs::read(&path)
                .map_err(drop)
                .and_then(|b| serde_json::from_slice::<Value>(&b).map_err(drop))
            else {
                let _ = std::fs::remove_file(&path);
                continue;
            };
            if report["kind"] != "user_report" && !ENABLED.load(Ordering::Relaxed) {
                continue;
            }
            match post(&report) {
                Ok(_) | Err(PostError::Rejected) => {
                    let _ = std::fs::remove_file(&path);
                }
                // Offline or rate-limited: try again later.
                Err(PostError::Later(_)) => break,
            }
        }
        BUSY.store(false, Ordering::Relaxed);
    });
}

fn enqueue(report: &Value) -> Result<()> {
    let Some(queue) = STATE.lock().ok().and_then(|s| s.queue.clone()) else {
        bail!("no queue")
    };
    // Keep the queue bounded even if the hub is unreachable for a long time.
    if std::fs::read_dir(&queue).map(|d| d.count()).unwrap_or(0) >= 50 {
        bail!("queue full");
    }
    let name = format!(
        "{}-{:04x}.json",
        chrono::Local::now().format("%Y%m%d%H%M%S"),
        fastrand::u16(..)
    );
    atomic_write(&queue.join(name), &serde_json::to_vec(report)?).context("queue report")
}

enum PostError {
    /// The hub refused this report for good (bad request): drop it.
    Rejected,
    Later(anyhow::Error),
}

impl From<PostError> for anyhow::Error {
    fn from(e: PostError) -> Self {
        match e {
            PostError::Rejected => anyhow::anyhow!("сервер отклонил отчёт"),
            PostError::Later(e) => e,
        }
    }
}

/// Sends to the first hub that takes it; a refusal (bad report) is final.
fn post(report: &Value) -> Result<String, PostError> {
    let mut prepared=report.clone();
    attach_provider_ip(&mut prepared, provider_ip);
    let report=&prepared;
    let mut last = PostError::Later(anyhow::anyhow!("нет серверов"));
    for hub in hubs() {
        match post_to(&hub, report) {
            Ok(id) => {
                hub_ok(&hub);
                return Ok(id);
            }
            Err(PostError::Rejected) => return Err(PostError::Rejected),
            Err(e) => last = e,
        }
    }
    Err(last)
}

// No network lookup while building previews, snapshots or panic reports.
// This runs only in the delivery worker under explicitly disclosed consent.
fn attach_provider_ip(report: &mut Value, lookup: impl FnOnce() -> Option<Value>) {
    if report["privacy"]["device_info_and_ip"] == true && report["privacy"]["provider_ip_outside_vpn"] == true {
        if report["provider_ip"]["address"].as_str().is_none() {
            if let Some(ip)=lookup() { report["provider_ip"]=ip; }
        }
    }
}

static PROVIDER_IP: Mutex<Option<(Instant, String, Value)>> = Mutex::new(None);

fn provider_ip() -> Option<Value> {
    use ureq::unversioned::{transport::{Connector, RustlsConnector}, resolver::DefaultResolver};
    let iface=crate::connection::uplink_interface()?;
    let mut cache=PROVIDER_IP.lock().ok()?;
    if let Some((when, network, value))=&*cache {
        if network==&iface && when.elapsed()<Duration::from_secs(300) { return Some(value.clone()); }
    }
    // Fail closed: there is no fallback to a VPN/default-route socket, proxy,
    // redirected endpoint or a third-party IP service.
    let config=ureq::Agent::config_builder().proxy(None).max_redirects(0)
        .max_idle_connections(0).timeout_global(Some(Duration::from_secs(8))).build();
    let agent=ureq::Agent::with_parts(config, PhysicalConnector(iface.clone()).chain(RustlsConnector::default()), DefaultResolver::default());
    for hub in hubs() {
        if !hub.starts_with("https://") { continue; }
        let value=(|| -> Option<Value> {
            let mut response=agent.get(format!("{hub}/v1/ip")).call().ok()?;
            let body:Value=response.body_mut().with_config().limit(1024).read_json().ok()?;
            let address=provider_ipv4(body["ip"].as_str()?)?;
            Some(json!({"address":address.to_string(),"source":"kawa_hub_physical_uplink","observed_at":chrono::Utc::now().timestamp()}))
        })();
        if let Some(value)=value { *cache=Some((Instant::now(),iface,value.clone())); return Some(value); }
    }
    None
}

fn provider_ipv4(text:&str) -> Option<std::net::Ipv4Addr> {
    let address=text.parse::<std::net::Ipv4Addr>().ok()?;
    let o=address.octets();
    if address.is_private() || address.is_loopback() || address.is_link_local() || address.is_unspecified() || address.is_multicast() || o[0]>=240 || o[0]==0 || (o[0]==100 && (64..128).contains(&o[1])) || (o[0]==192 && o[1]==0) || (o[0]==192 && o[1]==88 && o[2]==99) || (o[0]==198 && (o[1]==18 || o[1]==19 || (o[1]==51 && o[2]==100))) || (o[0]==203 && o[1]==0 && o[2]==113) { return None; }
    Some(address)
}

#[derive(Debug)]
struct PhysicalConnector(String);
#[derive(Debug)]
struct PhysicalTransport { stream: std::net::TcpStream, buffers: ureq::unversioned::transport::LazyBuffers }

impl ureq::unversioned::transport::Connector for PhysicalConnector {
    type Out=PhysicalTransport;
    fn connect(&self, details:&ureq::unversioned::transport::ConnectionDetails, _:Option<()>) -> std::result::Result<Option<Self::Out>,ureq::Error> {
        use socket2::{Domain,Protocol,Socket,Type};
        let start=Instant::now();
        let mut last=std::io::Error::new(std::io::ErrorKind::NotConnected,"no physical IPv4 route");
        for address in details.addrs.iter().filter(|a|a.is_ipv4()) {
            let socket=Socket::new(Domain::IPV4,Type::STREAM,Some(Protocol::TCP))?;
            bind_physical(&socket,&self.0)?;
            let budget=Duration::from_secs(5).min(*details.timeout.after).saturating_sub(start.elapsed());
            if budget.is_zero() { break; }
            match socket.connect_timeout(&(*address).into(),budget) {
                Ok(())=>return Ok(Some(PhysicalTransport {stream:socket.into(),buffers:ureq::unversioned::transport::LazyBuffers::new(8192,8192)})),
                Err(error)=>last=error,
            }
        }
        Err(last.into())
    }
}

fn bind_physical(socket:&socket2::Socket, name:&str) -> std::io::Result<()> {
    #[cfg(windows)] {
        use std::os::windows::io::AsRawSocket;
        use windows_sys::Win32::NetworkManagement::{IpHelper::{ConvertInterfaceAliasToLuid,ConvertInterfaceLuidToIndex},Ndis::NET_LUID_LH};
        let alias:Vec<u16>=name.encode_utf16().chain(Some(0)).collect();
        let mut luid=NET_LUID_LH::default(); let mut index=0u32;
        // IP_UNICAST_IF takes the IPv4 interface index in network byte order.
        // Every failure aborts the lookup rather than revealing a VPN egress IP.
        unsafe {
            let error=ConvertInterfaceAliasToLuid(alias.as_ptr(),&mut luid);
            if error!=0 { return Err(std::io::Error::from_raw_os_error(error as i32)); }
            let error=ConvertInterfaceLuidToIndex(&luid,&mut index);
            if error!=0 { return Err(std::io::Error::from_raw_os_error(error as i32)); }
            let value=index.to_be();
            if windows_sys::Win32::Networking::WinSock::setsockopt(socket.as_raw_socket() as usize,0,31,(&value as *const u32).cast(),4)!=0 { return Err(std::io::Error::from_raw_os_error(windows_sys::Win32::Networking::WinSock::WSAGetLastError())); }
        }
        Ok(())
    }
    #[cfg(target_os="linux")] { socket.bind_device(Some(name.as_bytes())) }
    #[cfg(target_os="macos")] {
        let name=std::ffi::CString::new(name)?;
        let index=unsafe {libc::if_nametoindex(name.as_ptr())};
        let index=std::num::NonZeroU32::new(index).ok_or_else(std::io::Error::last_os_error)?;
        socket.bind_device_by_index_v4(Some(index))
    }
    #[cfg(not(any(windows,target_os="linux",target_os="macos")))] { let _=(socket,name); Err(std::io::ErrorKind::Unsupported.into()) }
}

impl ureq::unversioned::transport::Transport for PhysicalTransport {
    fn buffers(&mut self)->&mut dyn ureq::unversioned::transport::Buffers { &mut self.buffers }
    fn transmit_output(&mut self, amount:usize, timeout:ureq::unversioned::transport::NextTimeout)->std::result::Result<(),ureq::Error> {
        use ureq::unversioned::transport::Buffers;
        self.stream.set_write_timeout(timeout.not_zero().map(|t| *t))?;
        self.stream.write_all(&self.buffers.output()[..amount])?; Ok(())
    }
    fn await_input(&mut self, timeout:ureq::unversioned::transport::NextTimeout)->std::result::Result<bool,ureq::Error> {
        use ureq::unversioned::transport::Buffers;
        self.stream.set_read_timeout(timeout.not_zero().map(|t| *t))?;
        let n=self.stream.read(self.buffers.input_append_buf())?;
        self.buffers.input_appended(n); Ok(n>0)
    }
    fn is_open(&mut self)->bool { false }
}

fn post_to(hub: &str, report: &Value) -> Result<String, PostError> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(20)))
        .http_status_as_error(false)
        .build()
        .into();
    let mut resp = agent
        .post(format!("{hub}/v1/report"))
        .header("User-Agent", crate::update::USER_AGENT)
        .send_json(report)
        .map_err(|e| PostError::Later(e.into()))?;
    match resp.status().as_u16() {
        201 => {
            let body: Value = resp
                .body_mut()
                .read_json()
                .map_err(|e| PostError::Later(e.into()))?;
            Ok(body["id"].as_str().unwrap_or_default().to_string())
        }
        429 => Err(PostError::Later(anyhow::anyhow!(
            "слишком много отчётов, попробуйте позже"
        ))),
        400 | 404 | 413 => Err(PostError::Rejected),
        s => Err(PostError::Later(anyhow::anyhow!("сервер ответил {s}"))),
    }
}

fn build(kind: &str, message: &str, note: Option<&str>) -> Option<Value> {
    capture(kind, message, note, false)
}

fn capture(kind: &str, message: &str, note: Option<&str>, panic: bool) -> Option<Value> {
    // A panic may originate while a diagnostic state lock is held. Keep
    // the last durable session in that case instead of deadlocking the hook.
    let st = if panic { STATE.try_lock().ok()? } else { STATE.lock().ok()? };
    let message = bounded(sanitize(message), 32 * 1024);
    let mut report = json!({
        "report_id": format!("KW-{:016X}", fastrand::u64(..)),
        "kind": kind,
        "message": message,
        "signature": signature(kind, &message),
        "version": env!("CARGO_PKG_VERSION"),
        "build": crate::update::BUILD,
        "os": os_label(),
        "arch": std::env::consts::ARCH,
        "install_id": st.install_id,
        "uptime_s": STARTED.elapsed().as_secs(),
        "device": *DEVICE,
        "privacy": {"device_info_and_ip": true, "retention_days": 90, "ip_source": "report_server_observed", "provider_ip_outside_vpn": true},
        "context": st.context,
        "log": bounded(st.log.iter().cloned().collect::<Vec<_>>().join("\n"), 48 * 1024),
    });
    if let Some(note) = note {
        report["note"] = json!(bounded(sanitize(note), 8 * 1024));
    }
    let data = st.queue.as_deref().and_then(Path::parent).map(Path::to_path_buf);
    drop(st);
    // Where the tunnel and xray say what went wrong; only for those errors.
    if WITH_LOGS.contains(&kind) {
        if let Some(text) = data.and_then(|d| tail_file(&d.join("run").join("xray.log"), 40)) {
            report["xray_log"] = json!(bounded(sanitize(&text), 32 * 1024));
        }
        if let Some(text) = helper_log(150) {
            report["helper_log"] = json!(bounded(sanitize(&text), 48 * 1024));
        }
    }
    Some(fit_report(report))
}

fn fit_report(mut report: Value) -> Value {
    // Include JSON escaping in the bound. The hub accepts 256 KiB.
    while serde_json::to_vec(&report).is_ok_and(|b| b.len() > 220 * 1024) {
        let mut changed = false;
        for key in ["helper_log", "xray_log", "log", "message", "note"] {
            if let Some(text) = report[key].as_str() {
                if text.len() > 256 {
                    report[key] = json!(bounded(text.to_string(), text.len()/2));
                    changed = true;
                }
            }
        }
        if !changed { break; }
    }
    report
}

fn atomic_write(path: &Path, bytes: &[u8]) -> Result<()> {
    let part = path.with_extension("part");
    let mut file = std::fs::File::create(&part)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    drop(file);
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::MoveFileExW;
        let from: Vec<u16> = part.as_os_str().encode_wide().chain(Some(0)).collect();
        let to: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
        if unsafe { MoveFileExW(from.as_ptr(), to.as_ptr(), 1 | 8) } == 0 {
            return Err(std::io::Error::last_os_error().into());
        }
    }
    #[cfg(not(windows))]
    std::fs::rename(&part, path)?;
    Ok(())
}

fn bounded(mut text: String, limit: usize) -> String {
    if text.len() > limit {
        let mut end = limit;
        while !text.is_char_boundary(end) { end -= 1; }
        text.truncate(end);
    }
    text
}

/// Reports that carry the xray and helper logs.
const WITH_LOGS: &[&str] = &[
    "panic",
    "unexpected_exit",
    "user_report",
    "connect_failed",
    "tunnel_stopped",
    "helper_install",
];

fn device_metadata() -> Value {
    let mut value = json!({"os": std::env::consts::OS, "arch": std::env::consts::ARCH,
        "cpu_threads": std::thread::available_parallelism().map(|n| n.get()).unwrap_or(0)});
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let result = std::process::Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command",
                "$ErrorActionPreference='Stop';$cs=Get-CimInstance Win32_ComputerSystem;$cpu=Get-CimInstance Win32_Processor|Select-Object -First 1;[ordered]@{model=$cs.Model;cpu=$cpu.Name;ram_bytes=$cs.TotalPhysicalMemory}|ConvertTo-Json -Compress"])
            .creation_flags(0x08000000).output();
        if let Ok(result) = result {
            if let Ok(hardware) = serde_json::from_slice::<Value>(&result.stdout) {
                for key in ["model", "cpu"] {
                    if let Some(text) = hardware[key].as_str() { value[key] = json!(sanitize(text)); }
                }
                value["ram_bytes"] = hardware["ram_bytes"].clone();
            }
        }
    }
    value
}

/// A bounded, sanitized snapshot survives native faults and forced exits.
pub fn save_session() {
    if !ENABLED.load(Ordering::Relaxed) { return; }
    let Some(report) = build("unexpected_exit", "Предыдущий запуск завершился без штатного закрытия (сбой или принудительное завершение).", None) else { return; };
    let Ok(_io) = SESSION_IO.lock() else { return; };
    if !ENABLED.load(Ordering::Relaxed) { return; }
    let data = DATA_DIR.get();
    if let Some(data) = data {
        if let Ok(bytes) = serde_json::to_vec(&report) {
            let _ = atomic_write(&data.join("last-session.json"), &bytes);
        }
    }
}

pub fn clean_shutdown() {
    ENABLED.store(false, Ordering::Relaxed);
    let Ok(_io) = SESSION_IO.lock() else { return; };
    remove_session();
}

fn remove_session() {
    if let Some(data) = DATA_DIR.get() { let _ = std::fs::remove_file(data.join("last-session.json")); }
}

fn recover_session(data: &Path) {
    let path = data.join("last-session.json");
    if let Ok(bytes) = std::fs::read(&path) {
        if let Ok(mut report) = serde_json::from_slice::<Value>(&bytes) {
            if report["privacy"]["device_info_and_ip"] == true {
                if let Ok(code) = std::fs::read(data.join("native-exception.bin")) {
                    if let Ok(code) = <[u8; 4]>::try_from(code.as_slice()) {
                        report["kind"] = json!("native_crash");
                        report["exception_code"] = json!(format!("0x{:08X}", u32::from_le_bytes(code)));
                    }
                }
                let id = report["report_id"].as_str().filter(|id| id.len() == 19 && id.starts_with("KW-") && id[3..].chars().all(|c| c.is_ascii_hexdigit())).unwrap_or("recovered");
                if let Ok(bytes) = serde_json::to_vec(&report) {
                    if atomic_write(&data.join("reports").join(format!("{id}.json")), &bytes).is_err() { return; }
                }
            }
        }
        let _ = std::fs::remove_file(path);
    }
}

pub fn snapshot_background() {
    if !ENABLED.load(Ordering::Relaxed) { return; }
    static BUSY: AtomicBool = AtomicBool::new(false);
    if BUSY.swap(true, Ordering::Relaxed) { return; }
    std::thread::spawn(|| {
        save_session();
        BUSY.store(false, Ordering::Relaxed);
    });
}

#[cfg(windows)]
mod native_crash {
    use super::*;
    use std::os::windows::io::IntoRawHandle;
    use std::os::windows::fs::OpenOptionsExt;
    use std::sync::atomic::AtomicIsize;
    use windows_sys::Win32::System::Diagnostics::Debug::{SetUnhandledExceptionFilter, EXCEPTION_POINTERS};
    use windows_sys::Win32::Storage::FileSystem::{WriteFile, FlushFileBuffers};
    static HANDLE: AtomicIsize = AtomicIsize::new(0);

    pub fn init(data: &Path) {
        // Open before a fault. The fault handler performs no allocation,
        // locking, network calls, formatting or JSON serialization.
        if let Ok(file) = std::fs::OpenOptions::new().create(true).truncate(true).write(true)
            .share_mode(1 | 2 | 4).open(data.join("native-exception.bin")) {
            HANDLE.store(file.into_raw_handle() as isize, Ordering::Relaxed);
            unsafe { SetUnhandledExceptionFilter(Some(record)); }
        }
    }

    unsafe extern "system" fn record(info: *const EXCEPTION_POINTERS) -> i32 {
        let handle = HANDLE.load(Ordering::Relaxed);
        if ENABLED.load(Ordering::Relaxed) && handle != 0 && !info.is_null() {
            let record = unsafe { (*info).ExceptionRecord };
            if !record.is_null() {
                let bytes = unsafe { (*record).ExceptionCode as u32 }.to_le_bytes();
                let mut written = 0;
                unsafe {
                    WriteFile(handle as _, bytes.as_ptr(), 4, &mut written, std::ptr::null_mut());
                    FlushFileBuffers(handle as _);
                }
            }
        }
        0 // Continue Windows' normal fatal exception handling.
    }
}

fn tail_file(path: &Path, lines: usize) -> Option<String> {
    let mut file = std::fs::File::open(path).ok()?;
    let size = file.metadata().ok()?.len();
    file.seek(SeekFrom::Start(size.saturating_sub(256 * 1024))).ok()?;
    let mut text = Vec::new();
    file.take(256 * 1024).read_to_end(&mut text).ok()?;
    // Big logs: only the end matters.
    let text = String::from_utf8_lossy(&text).into_owned();
    tail(&text, lines)
}

fn tail(text: &str, lines: usize) -> Option<String> {
    let all: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    let tail = all[all.len().saturating_sub(lines)..].join("\n");
    (!tail.is_empty()).then_some(tail)
}

/// The end of the TUN helper's log: per-app decisions, tunnel errors.
fn helper_log(lines: usize) -> Option<String> {
    if cfg!(windows) {
        let dir = PathBuf::from(std::env::var_os("ProgramData")?).join("Kawa VPN");
        tail_file(&dir.join("helper.log"), lines)
    } else if cfg!(target_os = "macos") {
        tail_file(Path::new("/var/log/duoray-helper.log"), lines)
    } else {
        // Readable for users in the systemd-journal / adm group only; else nothing.
        let out = std::process::Command::new("journalctl")
            .args([
                "-u",
                "duoray-helper",
                "-n",
                &lines.to_string(),
                "-o",
                "short-iso",
                "--no-pager",
            ])
            .stdin(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .output()
            .ok()?;
        tail(&String::from_utf8_lossy(&out.stdout), lines)
            .filter(|t| !t.starts_with("-- No entries"))
    }
}

fn os_label() -> String {
    static LABEL: LazyLock<String> = LazyLock::new(|| {
        let d = duoray_core::device::Device::detect();
        format!("{} {}", d.os, d.os_version.unwrap_or_default())
            .trim()
            .to_string()
    });
    LABEL.clone()
}

/// Groups reports of the same error: the kind plus the first line with
/// numbers blanked ("exit code 1" and "exit code 2" are one error).
fn signature(kind: &str, message: &str) -> String {
    static DIGITS: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"\d+").unwrap());
    let line = DIGITS.replace_all(first_line(message), "#");
    let mut h: u64 = 0xcbf29ce484222325;
    for b in kind.bytes().chain(*b"\n").chain(line.bytes()) {
        h = (h ^ u64::from(b)).wrapping_mul(0x100000001b3);
    }
    format!("{h:016x}")[..12].to_string()
}

fn first_line(s: &str) -> &str {
    s.lines().find(|l| !l.trim().is_empty()).unwrap_or("")
}

/// Removes everything that could identify the user, their servers or
/// subscriptions. Over-redacting is fine; leaking is not.
pub fn sanitize(text: &str) -> String {
    static RULES: LazyLock<Vec<(Regex, &'static str)>> = LazyLock::new(|| {
        let rule = |re: &str, with| (Regex::new(re).unwrap(), with);
        vec![
            rule(r#"(?i)\b(password|passwd|token|authorization|secret|refresh_token|access_token|api[_-]?key|private[_-]?key)\b["']?\s*[:=]\s*["']?[^\s,;"}]+"#, "$1=<secret>"),
            rule(r"(?i)\b[a-z][a-z0-9+.\-]{1,15}://\S+", "<url>"),
            rule(r"(?i)\b[\w.+\-]+@[\w\-]+(\.[\w\-]+)+", "<email>"),
            rule(
                r"(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b",
                "<uuid>",
            ),
            // IPv6: full form (4+ groups) or compressed with "::".
            rule(
                r"(?i)\[?\b(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}\b\]?(:\d+)?",
                "<ip>",
            ),
            rule(
                r"(?i)\[?\b(?:[0-9a-f]{1,4}:)+:(?:[0-9a-f]{1,4}:?)*[0-9a-f]{0,4}\b\]?(:\d+)?",
                "<ip>",
            ),
            rule(r"\b\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?\b", "<ip>"),
            // Windows / Unix home directories.
            rule(r"(?i)\b[a-z]:\\Users\\[^\\\s]+", r"C:\Users\<user>"),
            rule(r"/home/[^/\s]+", "/home/<user>"),
            rule(r"/Users/[^/\s]+", "/Users/<user>"),
            // Host names; file names with known extensions are kept.
            rule(
                r"(?i)\b(?:[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?\.)+(?:[a-z][a-z0-9\-]{1,62})\b",
                "<host>",
            ),
        ]
    });
    static FILE_EXT: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(r"(?i)\.(json|exe|dll|dat|log|toml|rs|service|sock|txt|png|svg|so|sh|conf|ya?ml|py|slint|pem|plist|app)$")
            .unwrap()
    });
    static TOKEN: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"[A-Za-z0-9+/_=]{16,}").unwrap());

    let mut out = text.to_string();
    for (re, with) in RULES.iter() {
        out = if *with == "<host>" {
            re.replace_all(&out, |c: &regex::Captures| {
                let m = &c[0];
                if FILE_EXT.is_match(m) {
                    m.to_string()
                } else {
                    "<host>".to_string()
                }
            })
            .into_owned()
        } else {
            re.replace_all(&out, *with).into_owned()
        };
    }
    // Keys, short ids, tokens: long runs of base64/hex that contain a digit.
    TOKEN
        .replace_all(&out, |c: &regex::Captures| {
            let m = &c[0];
            if m.bytes().any(|b| b.is_ascii_digit()) {
                "<secret>".to_string()
            } else {
                m.to_string()
            }
        })
        .into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn provider_address_rejects_local_shared_and_reserved_ranges() {
        for text in ["bad","172.19.0.2","127.0.0.1","10.0.0.1","169.254.1.2","100.64.0.1","224.0.0.1","198.51.100.1","192.0.2.1","203.0.113.1","198.18.0.1","0.1.2.3","255.255.255.255"] {
            assert!(provider_ipv4(text).is_none(),"{text}");
        }
        assert_eq!(provider_ipv4("8.8.8.8").unwrap().to_string(),"8.8.8.8");
    }

    #[test]
    fn provider_lookup_requires_explicit_new_policy() {
        for privacy in [json!({}), json!({"device_info_and_ip":true}), json!({"device_info_and_ip":false,"provider_ip_outside_vpn":true})] {
            let mut report=json!({"privacy":privacy});
            attach_provider_ip(&mut report, || panic!("lookup before consent"));
            assert!(report["provider_ip"].is_null());
        }
        let mut report=json!({"privacy":{"device_info_and_ip":true,"provider_ip_outside_vpn":true}});
        attach_provider_ip(&mut report, || Some(json!({"address":"8.8.8.8","observed_at":1})));
        assert_eq!(report["provider_ip"]["address"], "8.8.8.8");
    }

    #[test]
    fn sanitizer_hides_what_identifies_users() {
        let cases = [
            (
                "fetch https://panel.example.com/sub/AbC123xyz?token=1 failed",
                "fetch <url> failed",
            ),
            ("vless://u@1.2.3.4:443?sni=x#n broke", "<url> broke"),
            (
                "dial tcp 198.51.100.7:443: i/o timeout",
                "dial tcp <ip>: i/o timeout",
            ),
            (
                "lookup de.example.org: no such host",
                "lookup <host>: no such host",
            ),
            (
                "id 0b5e1c9a-2f1d-4a7e-9c3b-1e2d3f4a5b6c rejected",
                "id <uuid> rejected",
            ),
            ("ipv6 [2a01:4f8:c0c:1234::1]:443 down", "ipv6 <ip> down"),
            (
                "pbk Zm9vYmFyYmF6cXV4MTIz sid 6ba85179e30d4fc2",
                "pbk <secret> sid <secret>",
            ),
            (
                "write C:\\Users\\Ivan\\AppData\\x.json",
                "write C:\\Users\\<user>\\AppData\\x.json",
            ),
            (
                "open /home/ivan/.local/share/duoray/store.json",
                "open /home/<user>/.local/share/duoray/store.json",
            ),
            ("mail me at a.b@mail.ru", "mail me at <email>"),
            ("password=Abc12!", "password=<secret>"),
        ];
        for (input, want) in cases {
            assert_eq!(sanitize(input), want, "{input}");
        }
        // Ordinary diagnostics survive.
        let keep = "12:00:01 xray остановился (exit status: 1). geoip.dat не найден, flow xtls-rprx-vision";
        assert_eq!(sanitize(keep), keep);
    }

    #[test]
    fn no_foreign_service_endpoints() {
        assert_eq!(HUBS, &["https://hub.example.com/kawa-app", "https://hub-mirror.example.com/kawa-app"]);
        hub_ok("https://retired-upstream.invalid");
        assert!(hubs().iter().all(|url| url.contains("example.com/")));
    }

    #[test]
    fn report_declares_device_and_ip_and_has_retry_identity() {
        let report = manual_report("test");
        assert_eq!(report["privacy"]["device_info_and_ip"], true);
        assert!(report["report_id"].as_str().is_some_and(|id| id.starts_with("KW-")));
        assert!(report["device"].is_object());
    }

    #[test]
    fn previous_native_fault_is_queued_once_with_its_device_and_code() {
        let data = std::env::temp_dir().join(format!("kawa-recovery-{}", fastrand::u64(..)));
        std::fs::create_dir_all(data.join("reports")).unwrap();
        let report = json!({"report_id":"KW-0123456789ABCDEF","privacy":{"device_info_and_ip":true},"device":{"model":"test"},"log":"clean log"});
        std::fs::write(data.join("last-session.json"), serde_json::to_vec(&report).unwrap()).unwrap();
        std::fs::write(data.join("native-exception.bin"), 0xC0000005u32.to_le_bytes()).unwrap();
        recover_session(&data);
        recover_session(&data);
        let files: Vec<_> = std::fs::read_dir(data.join("reports")).unwrap().collect();
        assert_eq!(files.len(), 1);
        let report: Value = serde_json::from_slice(&std::fs::read(data.join("reports/KW-0123456789ABCDEF.json")).unwrap()).unwrap();
        assert_eq!(report["exception_code"], "0xC0000005");
        assert_eq!(report["kind"], "native_crash");
        assert_eq!(report["device"]["model"], "test");
        std::fs::remove_dir_all(data).unwrap();
    }

    #[test]
    fn report_text_bounds_preserve_utf8() {
        assert_eq!(bounded("яяя".into(), 5), "яя");
    }

    #[test]
    fn escaped_wire_payload_is_under_hub_limit() {
        let large = "\u{0000}".repeat(256*1024);
        let report = fit_report(json!({"helper_log":large,"xray_log":large,"message":large,"log":large,"note":large,"device":{"model":"test"}}));
        assert!(serde_json::to_vec(&report).unwrap().len() < 256*1024);
        assert_eq!(report["device"]["model"], "test");
    }

    #[test]
    fn atomic_report_replacement_keeps_valid_json() {
        let dir = std::env::temp_dir().join(format!("kawa-atomic-{}", fastrand::u64(..)));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("test.json");
        atomic_write(&path, b"{\"n\":1}").unwrap();
        atomic_write(&path, b"{\"n\":2}").unwrap();
        let v: Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        assert_eq!(v["n"], 2);
        assert!(!path.with_extension("part").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    #[cfg(windows)]
    fn isolated_process_crashes_and_offline_manual_report_are_recoverable() {
        use std::os::windows::process::CommandExt;
        for mode in ["panic", "native", "offline", "clean"] {
            let data = std::env::temp_dir().join(format!("kawa-process-{}", fastrand::u64(..)));
            std::fs::create_dir_all(&data).unwrap();
            let output = std::process::Command::new(std::env::current_exe().unwrap())
                .args(["--exact", "diag::tests::diagnostic_process_probe", "--ignored", "--nocapture"])
                .env("KAWA_DIAG_TEST_DIR", &data).env("KAWA_DIAG_TEST_MODE", mode)
                .env("KAWA_HUB_URL", "http://127.0.0.1:1")
                .creation_flags(0x08000000).output().unwrap();
            assert_eq!(output.status.success(), matches!(mode, "clean" | "offline"), "{mode}: {}", String::from_utf8_lossy(&output.stderr));
            recover_session(&data);
            let files: Vec<_> = std::fs::read_dir(data.join("reports")).unwrap().filter_map(|p|p.ok()).filter(|p|p.path().extension().is_some_and(|e|e=="json")).collect();
            if mode == "clean" { assert!(files.is_empty()); }
            else {
                assert_eq!(files.len(), 1, "{mode}");
                let report: Value = serde_json::from_slice(&std::fs::read(files[0].path()).unwrap()).unwrap();
                let kind = match mode {"panic"=>"panic", "native"=>"native_crash", _=>"user_report"};
                assert_eq!(report["kind"], kind, "{mode}");
                if mode == "native" { assert_eq!(report["exception_code"], "0xC0000005"); }
                assert!(report["device"].is_object());
            }
            assert!(data.starts_with(std::env::temp_dir()));
            std::fs::remove_dir_all(data).unwrap();
        }
    }

    #[test]
    #[ignore = "child process fault/offline probe, launched only by the isolated parent test"]
    fn diagnostic_process_probe() {
        let data = PathBuf::from(std::env::var_os("KAWA_DIAG_TEST_DIR").expect("isolated QA directory required"));
        init(&data);configure(true,"qa-process");log("Controlled crash recovery QA");save_session();
        match std::env::var("KAWA_DIAG_TEST_MODE").unwrap().as_str() {
            "panic" => panic!("Controlled panic for recovery test"),
            #[cfg(windows)]
            "native" => unsafe {
                use windows_sys::Win32::System::Diagnostics::Debug::{SetErrorMode, RaiseException};
                SetErrorMode(2);RaiseException(0xC0000005, 0, 0, std::ptr::null());
            },
            "offline" => {let delivery=send_now(&manual_report("Controlled offline report QA")).unwrap();assert!(delivery.queued);clean_shutdown();},
            "clean" => {
                let guard = STATE.lock().unwrap();
                clean_shutdown();
                assert!(!data.join("last-session.json").exists());
                drop(guard);
                save_session();
                assert!(!data.join("last-session.json").exists());
            },
            _ => panic!("unknown QA mode"),
        }
    }

    #[test]
    #[ignore = "explicit live delivery test; sends one clearly labelled report to the owner"]
    fn live_hub_delivery_probe() {
        assert_eq!(std::env::var("KAWA_LIVE_HUB_TEST").as_deref(), Ok("1"));
        let delivery=send_now(&manual_report("ТЕСТ Kawa VPN 0.4.9: проверка доставки репорта с данными устройства и IP. Это тест, приложение не упало.")).unwrap();
        assert!(!delivery.queued, "live report was not accepted by the server");
        println!("Live report accepted: {}", delivery.id);
    }

    #[test]
    fn signatures_ignore_numbers() {
        assert_eq!(
            signature("x", "exit code 1\nmore"),
            signature("x", "exit code 23")
        );
        assert_ne!(signature("x", "exit code 1"), signature("y", "exit code 1"));
    }
}
