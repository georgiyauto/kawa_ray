//! Turns a subscription entry into the config xray actually runs.
//!
//! The subscription author's config is kept intact (routing, DNS, balancers,
//! observatory, bridges). Only these things change:
//! 1. `inbounds` become one password-protected SOCKS inbound on loopback,
//!    tagged `socks` like the panel's own, so its routing rules still apply;
//! 2. every outbound that dials the network is bound to the physical
//!    interface, otherwise `direct` traffic would loop back into the TUN;
//! 3. server hostnames are resolved now, before the TUN exists (SNI/Host keep
//!    the name), and those IPs are routed around the TUN;
//! 4. the user's routing (see [`crate::routing`]) is merged into the panel's.

use std::net::{IpAddr, SocketAddr, TcpListener, ToSocketAddrs, UdpSocket};

use anyhow::{Context, Result, bail};
use serde_json::{Value, json};

use crate::routing::{self, GeoData, RoutingSettings};
use crate::server::{Server, Source};
use crate::xray;

pub struct Runtime {
    pub config: Value,
    pub socks: SocketAddr,
    pub user: String,
    pub pass: String,
    pub bypass: Vec<IpAddr>,
    /// SOCKS inbound whose traffic always goes direct (per-app bypass).
    pub direct_socks: Option<SocketAddr>,
    /// Routing entries that were skipped (e.g. a missing geo database).
    pub warnings: Vec<String>,
}

/// User routing to merge into the config.
pub struct Routing<'a> {
    pub settings: &'a RoutingSettings,
    pub geo: &'a GeoData,
}

const SERVICE_PROTOCOLS: &[&str] = &["freedom", "blackhole", "dns", "loopback"];

pub fn build(server: &Server, iface: &str, routing: Option<Routing>) -> Result<Runtime> {
    build_with(server, iface, routing, resolve_server)
}

fn system_ipv4(host: &str) -> Result<IpAddr> {
    (host, 0)
        .to_socket_addrs()
        .with_context(|| format!("cannot resolve {host}"))?
        .map(|a| a.ip())
        .find(IpAddr::is_ipv4)
        .with_context(|| format!("{host} has no IPv4 address"))
}

/// DNS over HTTPS to a resolver addressed by IP, for when the system resolver
/// is briefly unusable (right after a tunnel went down, adapter changes).
fn doh_ipv4(host: &str) -> Option<IpAddr> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(std::time::Duration::from_secs(3)))
        .proxy(None)
        .build()
        .into();
    for base in ["https://1.1.1.1/dns-query", "https://8.8.8.8/resolve", "https://1.0.0.1/dns-query"] {
        let Ok(mut response) = agent
            .get(base)
            .query("name", host)
            .query("type", "A")
            .header("accept", "application/dns-json")
            .call()
        else {
            continue;
        };
        let Ok(body) = response.body_mut().read_json::<Value>() else { continue };
        // A resolver that answered is authoritative enough: NXDOMAIN won't
        // change by asking the next one.
        return doh_answer(&body);
    }
    None
}

fn doh_answer(body: &Value) -> Option<IpAddr> {
    body["Answer"]
        .as_array()?
        .iter()
        .filter(|a| a["type"] == 1)
        .find_map(|a| a["data"].as_str()?.parse::<IpAddr>().ok())
        .filter(|ip| ip.is_ipv4() && !ip.is_unspecified() && !ip.is_loopback())
}

fn resolve_cache() -> &'static std::sync::Mutex<std::collections::HashMap<String, IpAddr>> {
    static CACHE: std::sync::OnceLock<std::sync::Mutex<std::collections::HashMap<String, IpAddr>>> =
        std::sync::OnceLock::new();
    CACHE.get_or_init(Default::default)
}

/// System resolver with retries, then DoH, then the last address this process
/// saw for the name. One failed lookup no longer fails the whole connect.
pub fn resolve_server(host: &str) -> Result<IpAddr> {
    let mut last = None;
    for delay in [0u64, 400, 1200] {
        std::thread::sleep(std::time::Duration::from_millis(delay));
        match system_ipv4(host) {
            Ok(ip) => {
                resolve_cache().lock().unwrap().insert(host.to_string(), ip);
                return Ok(ip);
            }
            Err(e) => last = Some(e),
        }
    }
    if let Some(ip) = doh_ipv4(host) {
        resolve_cache().lock().unwrap().insert(host.to_string(), ip);
        return Ok(ip);
    }
    if let Some(ip) = resolve_cache().lock().unwrap().get(host).copied() {
        return Ok(ip);
    }
    Err(last.unwrap_or_else(|| anyhow::anyhow!("cannot resolve {host}")))
}

pub fn build_with(
    server: &Server,
    iface: &str,
    routing: Option<Routing>,
    mut resolve: impl FnMut(&str) -> Result<IpAddr>,
) -> Result<Runtime> {
    let mut config = match &server.source {
        Source::Json(v) => v.clone(),
        Source::Link(p) => xray::config_from_profile(p),
    };
    let mut warnings = match &routing {
        Some(r) => routing::apply(&mut config, r.settings, r.geo),
        None => vec![],
    };

    let port = free_port()?;
    let socks = SocketAddr::from(([127, 0, 0, 1], port));
    let user = random_token(16);
    let pass = random_token(32);

    // Keep the panel's sniffing settings if it had a socks inbound.
    let sniffing = config["inbounds"]
        .as_array()
        .and_then(|a| a.iter().find(|i| i["protocol"] == "socks"))
        .and_then(|i| i.get("sniffing").cloned())
        .unwrap_or_else(|| json!({ "enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": false }));
    config["inbounds"] = json!([{
        "tag": "socks",
        "listen": "127.0.0.1",
        "port": port,
        "protocol": "socks",
        "settings": {
            "auth": "password",
            "accounts": [{ "user": user, "pass": pass }],
            "udp": true,
            "ip": "127.0.0.1"
        },
        "sniffing": sniffing
    }]);
    let mut direct_socks = None;
    if routing.as_ref().is_some_and(|r| r.settings.apps.active()) {
        let port = free_port()?;
        let mut inbound = config["inbounds"][0].clone();
        inbound["tag"] = json!(routing::DIRECT_INBOUND);
        inbound["port"] = json!(port);
        config["inbounds"].as_array_mut().unwrap().push(inbound);
        direct_socks = Some(SocketAddr::from(([127, 0, 0, 1], port)));
    }

    let mut bypass = vec![];
    let (mut proxies, mut first_error, routing_warnings) = (0usize, None, warnings.len());
    let Some(outbounds) = config["outbounds"].as_array_mut() else {
        bail!("config has no outbounds");
    };
    for ob in outbounds.iter_mut() {
        let protocol = ob["protocol"].as_str().unwrap_or_default().to_string();
        if protocol == "blackhole" {
            continue;
        }
        let chained = !ob["streamSettings"]["sockopt"]["dialerProxy"].is_null() || !ob["proxySettings"].is_null();
        if !chained {
            if !ob["streamSettings"].is_object() {
                ob["streamSettings"] = json!({});
            }
            if !ob["streamSettings"]["sockopt"].is_object() {
                ob["streamSettings"]["sockopt"] = json!({});
            }
            ob["streamSettings"]["sockopt"]["interface"] = json!(iface);
        }
        if SERVICE_PROTOCOLS.contains(&protocol.as_str()) {
            continue;
        }
        proxies += 1;
        // A dead hostname in one of many outbounds (balancers over public
        // servers) must not block the rest: xray resolves it itself later.
        if let Err(e) = pin_endpoints(ob, &mut resolve, &mut bypass) {
            warnings.push(format!("{}: {e:#}", ob["tag"].as_str().unwrap_or("outbound")));
            first_error.get_or_insert(e);
        }
    }
    if let Some(e) = first_error
        && warnings.len() - routing_warnings == proxies
    {
        // Nothing could be pinned: that is a real failure.
        return Err(e);
    }
    bypass.sort();
    bypass.dedup();

    Ok(Runtime { config, socks, user, pass, bypass, direct_socks, warnings })
}

/// Replaces hostnames in an outbound's server list with IPs, keeping the name
/// for TLS SNI and HTTP Host.
fn pin_endpoints(
    ob: &mut Value,
    resolve: &mut impl FnMut(&str) -> Result<IpAddr>,
    bypass: &mut Vec<IpAddr>,
) -> Result<()> {
    let mut first_name = None;
    let settings = &mut ob["settings"];
    // vnext (vless/vmess), servers (trojan/ss/socks), or the flat form (hysteria, newer xray).
    let list = ["vnext", "servers"].into_iter().find(|k| settings[*k].is_array());
    let endpoints: Vec<&mut Value> = match list {
        Some(k) => settings[k].as_array_mut().into_iter().flatten().collect(),
        None if settings.get("address").is_some() => vec![settings],
        None => vec![],
    };
    for ep in endpoints {
        let Some(addr) = ep["address"].as_str().map(str::to_string) else { continue };
        let ip = match addr.parse::<IpAddr>() {
            Ok(ip) => ip,
            Err(_) => {
                let ip = resolve(&addr)?;
                ep["address"] = json!(ip.to_string());
                first_name.get_or_insert(addr);
                ip
            }
        };
        bypass.push(ip);
    }

    if let Some(name) = first_name {
        let ss = &mut ob["streamSettings"];
        for key in ["tlsSettings", "realitySettings"] {
            if ss[key].is_object() && ss[key]["serverName"].as_str().unwrap_or_default().is_empty() {
                ss[key]["serverName"] = json!(name);
            }
        }
        for key in ["xhttpSettings", "splithttpSettings", "wsSettings", "httpupgradeSettings"] {
            if ss[key].is_object() && ss[key]["host"].as_str().unwrap_or_default().is_empty() {
                ss[key]["host"] = json!(name);
            }
        }
    }
    Ok(())
}

/// The SOCKS inbound listens on TCP and UDP (`"udp": true`) on one port. A port
/// free for TCP can be held for UDP by a game, browser or messenger, and xray
/// then fails with "bind: ... forbidden by its access permissions".
fn free_port() -> Result<u16> {
    for _ in 0..64 {
        let tcp = TcpListener::bind("127.0.0.1:0")?;
        let port = tcp.local_addr()?.port();
        if UdpSocket::bind(("127.0.0.1", port)).is_ok() {
            return Ok(port);
        }
    }
    bail!("no local port free for both TCP and UDP")
}

fn random_token(len: usize) -> String {
    std::iter::repeat_with(fastrand::alphanumeric).take(len).collect()
}

#[cfg(test)]
mod tests {
    #[test]
    fn local_port_is_free_for_tcp_and_udp() {
        // Hold a UDP port and make sure a TCP-only check would not hand it out.
        for _ in 0..20 {
            let port = super::free_port().unwrap();
            assert!(std::net::UdpSocket::bind(("127.0.0.1", port)).is_ok());
            assert!(std::net::TcpListener::bind(("127.0.0.1", port)).is_ok());
        }
    }

    use super::*;

    fn server(cfg: Value) -> Server {
        Server::from_xray_config(cfg).unwrap()
    }

    #[test]
    fn rewrites_json_config() {
        let s = server(json!({
            "remarks": "X",
            "inbounds": [{"tag": "socks", "port": 10808, "protocol": "socks",
                          "sniffing": {"enabled": true, "destOverride": ["http","tls"], "routeOnly": false}}],
            "outbounds": [
                {"tag": "proxy", "protocol": "vless",
                 "settings": {"vnext": [{"address": "se.example.com", "port": 443, "users": [{"id": "u"}]}]},
                 "streamSettings": {"network": "xhttp", "security": "tls", "tlsSettings": {}, "xhttpSettings": {"path": "/"}}},
                {"tag": "proxy-2", "protocol": "vless",
                 "settings": {"vnext": [{"address": "9.9.9.9", "port": 443}]},
                 "streamSettings": {"sockopt": {"dialerProxy": "proxy"}}},
                {"tag": "hy", "protocol": "hysteria", "settings": {"address": "8.8.4.4", "port": 8443}},
                {"tag": "direct", "protocol": "freedom"},
                {"tag": "block", "protocol": "blackhole"},
                {"tag": "dns-out", "protocol": "dns"}
            ],
            "routing": {"balancers": [{"tag": "b", "selector": ["proxy"]}]}
        }));
        let rt = build_with(&s, "en0", None, |h| {
            assert_eq!(h, "se.example.com");
            Ok("1.2.3.4".parse().unwrap())
        })
        .unwrap();
        let c = &rt.config;
        let obs = c["outbounds"].as_array().unwrap();

        assert_eq!(c["inbounds"].as_array().unwrap().len(), 1);
        assert_eq!(c["inbounds"][0]["tag"], "socks");
        assert_eq!(c["inbounds"][0]["settings"]["accounts"][0]["user"], rt.user.as_str());
        assert_eq!(c["inbounds"][0]["sniffing"]["destOverride"], json!(["http", "tls"]));

        assert_eq!(obs[0]["settings"]["vnext"][0]["address"], "1.2.3.4");
        assert_eq!(obs[0]["streamSettings"]["tlsSettings"]["serverName"], "se.example.com");
        assert_eq!(obs[0]["streamSettings"]["xhttpSettings"]["host"], "se.example.com");
        assert_eq!(obs[0]["streamSettings"]["sockopt"]["interface"], "en0");
        assert!(obs[1]["streamSettings"]["sockopt"]["interface"].is_null(), "chained outbound not bound");
        assert_eq!(obs[2]["streamSettings"]["sockopt"]["interface"], "en0", "Hysteria UDP bound");
        assert_eq!(obs[3]["streamSettings"]["sockopt"]["interface"], "en0", "direct bound");
        assert!(obs[4]["streamSettings"].is_null(), "blackhole untouched");
        assert_eq!(obs[5]["streamSettings"]["sockopt"]["interface"], "en0", "dns bound");
        assert_eq!(c["routing"]["balancers"][0]["tag"], "b", "balancer kept");

        let ips: Vec<String> = rt.bypass.iter().map(|i| i.to_string()).collect();
        assert_eq!(ips, ["1.2.3.4", "8.8.4.4", "9.9.9.9"]);
        assert!(rt.socks.ip().is_loopback());
        assert_eq!(rt.pass.len(), 32);
    }

    #[test]
    fn link_server_gets_full_config() {
        let p = crate::link::parse("vless://id@5.6.7.8:443?security=reality&pbk=K&sni=a.b&type=tcp#L").unwrap();
        let rt = build_with(&Server::from_link(p), "en0", None, |_| unreachable!()).unwrap();
        assert_eq!(rt.config["outbounds"][0]["protocol"], "vless");
        assert_eq!(rt.config["outbounds"][0]["streamSettings"]["realitySettings"]["publicKey"], "K");
        assert_eq!(rt.bypass, vec!["5.6.7.8".parse::<IpAddr>().unwrap()]);
    }

    #[test]
    fn routing_binds_direct_and_adds_bypass_inbound() {
        let p = crate::link::parse("vless://id@5.6.7.8:443?security=reality&pbk=K&sni=a.b&type=tcp#L").unwrap();
        let settings = RoutingSettings {
            apps: crate::routing::AppRouting { mode: crate::routing::AppMode::Bypass, apps: vec!["curl".into()] },
            ..Default::default()
        };
        let geo = GeoData::load(None, None);
        let rt = build_with(&Server::from_link(p), "en0", Some(Routing { settings: &settings, geo: &geo }), |_| {
            unreachable!()
        })
        .unwrap();
        let c = &rt.config;
        let direct = c["outbounds"].as_array().unwrap().iter().find(|o| o["tag"] == routing::DIRECT_TAG).unwrap();
        assert_eq!(direct["streamSettings"]["sockopt"]["interface"], "en0");
        assert_eq!(c["inbounds"][1]["tag"], routing::DIRECT_INBOUND);
        assert_eq!(c["inbounds"][1]["settings"]["accounts"][0]["pass"], rt.pass.as_str());
        assert_eq!(rt.direct_socks.unwrap().port(), c["inbounds"][1]["port"].as_u64().unwrap() as u16);
        assert_eq!(rt.warnings, ["нет базы для geoip:private"], "no geo files in tests");
    }

    #[test]
    fn doh_answer_takes_the_first_a_record() {
        let body = json!({"Status":0,"Answer":[{"name":"x.","type":5,"data":"y."},{"name":"y.","type":1,"data":"203.0.113.10"}]});
        assert_eq!(doh_answer(&body), Some("203.0.113.10".parse().unwrap()));
        assert_eq!(doh_answer(&json!({"Status":3})), None);
        assert_eq!(doh_answer(&json!({"Answer":[{"type":1,"data":"0.0.0.0"}]})), None);
    }

    #[test]
    fn resolver_falls_back_to_the_last_good_answer() {
        resolve_cache().lock().unwrap().insert("kawa-nx-cached.example.com".into(), "10.1.2.3".parse().unwrap());
        // example.com has no such names: system and DoH both answer NXDOMAIN.
        assert_eq!(resolve_server("kawa-nx-cached.example.com").unwrap(), "10.1.2.3".parse::<IpAddr>().unwrap());
        assert!(resolve_server("kawa-nx-nothing.example.com").is_err());
    }

    #[test]
    fn one_dead_hostname_does_not_block_a_balancer_config() {
        let cfg = json!({"outbounds": [
            {"tag": "node-a", "protocol": "vless", "settings": {"vnext": [{"address": "dead.invalid", "port": 443, "users": [{"id": "x"}]}]}},
            {"tag": "node-b", "protocol": "vless", "settings": {"vnext": [{"address": "ok.example", "port": 443, "users": [{"id": "x"}]}]}},
            {"tag": "direct", "protocol": "freedom"}]});
        let resolve = |h: &str| if h == "ok.example" { Ok("9.9.9.9".parse().unwrap()) } else { anyhow::bail!("cannot resolve {h}") };
        let rt = build_with(&server(cfg.clone()), "eth0", None, resolve).unwrap();
        assert_eq!(rt.bypass, vec!["9.9.9.9".parse::<IpAddr>().unwrap()]);
        assert!(rt.warnings.iter().any(|w| w.contains("node-a")));
        let all_dead = |h: &str| -> Result<IpAddr> { anyhow::bail!("cannot resolve {h}") };
        assert!(build_with(&server(cfg), "eth0", None, all_dead).is_err());
    }
}
