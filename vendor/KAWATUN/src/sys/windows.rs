//! Windows: routes via `route`/`netsh`, DNS on the Wintun adapter, and a
//! Windows Firewall rule that blocks DNS to anything but the TUN's resolver
//! (Windows otherwise queries every adapter's DNS server in parallel).

use std::net::IpAddr;

use anyhow::{Context, Result, bail};
use tracing::{info, warn};
use windows_sys::Win32::NetworkManagement::IpHelper::{
    ConvertInterfaceAliasToLuid, ConvertInterfaceLuidToAlias, ConvertInterfaceLuidToIndex,
    FreeMibTable, GetIpForwardTable2, MIB_IPFORWARD_ROW2, MIB_IPFORWARD_TABLE2,
};
use windows_sys::Win32::NetworkManagement::Ndis::NET_LUID_LH;
use windows_sys::Win32::Networking::WinSock::{AF_INET, AF_INET6, SOCKADDR_INET};

use super::{SysConfig, UndoLog, run};

const FW_RULE: &str = "Kawa VPN DNS guard";
const FW_INBOUND: &str = "Kawa VPN TUN";

/// Turns off duplicate address detection on the new adapter so its addresses
/// become usable at once (WireGuard does the same). Best effort.
pub fn skip_dad(tun: &str) {
    for family in ["ipv4", "ipv6"] {
        let _ = run(&[
            "netsh",
            "interface",
            family,
            "set",
            "interface",
            tun,
            "dadtransmits=0",
            "store=active",
        ]);
    }
}

struct DefaultRoute {
    gateway: String,
    if_index: u32,
    alias: String,
}

pub fn apply(cfg: &SysConfig, log: &mut UndoLog) -> Result<()> {
    let setup_started = std::time::Instant::now();
    let tun = cfg.tun_name.as_str();
    let tun_index = adapter_index(tun)?;
    let v4 = default_route(false, tun).context("no IPv4 default route")?;
    let v6 = default_route(true, tun).ok();
    info!("uplink: {} via {}", v4.alias, v4.gateway);
    info!(elapsed_ms = setup_started.elapsed().as_millis(), "Windows adapter and uplink discovery complete");

    // 1. Proxy servers keep using the physical uplink.
    for ip in &cfg.bypass {
        let ip_s = ip.to_string();
        match ip {
            IpAddr::V4(_) => {
                log.push_cmd(&["route", "DELETE", &ip_s])?;
                run(&[
                    "route",
                    "ADD",
                    &ip_s,
                    "MASK",
                    "255.255.255.255",
                    &v4.gateway,
                    "IF",
                    &v4.if_index.to_string(),
                    "METRIC",
                    "1",
                ])?;
            }
            IpAddr::V6(_) => {
                let Some(r) = &v6 else {
                    warn!("no IPv6 default route, cannot bypass {ip}");
                    continue;
                };
                let dst = format!("{ip_s}/128");
                let idx = r.if_index.to_string();
                log.push_cmd(&["netsh", "interface", "ipv6", "delete", "route", &dst, &idx])?;
                run(&[
                    "netsh",
                    "interface",
                    "ipv6",
                    "add",
                    "route",
                    &dst,
                    &idx,
                    &r.gateway,
                    "store=active",
                ])?;
            }
        }
        info!("bypass {ip}");
    }

    // The kernel hands NATed TCP to our listener on the TUN address; Windows
    // Firewall drops such inbound connections on an unknown network otherwise.
    allow_inbound_on_tun(log)?;

    // 2. Everything else into the TUN (two halves leave the default route alone).
    let peer = cfg.dns.to_string();
    let tun_idx = tun_index.to_string();
    for net in ["0.0.0.0", "128.0.0.0"] {
        log.push_cmd(&["route", "DELETE", net, "MASK", "128.0.0.0", &peer])?;
        run(&[
            "route",
            "ADD",
            net,
            "MASK",
            "128.0.0.0",
            &peer,
            "IF",
            &tun_idx,
            "METRIC",
            "1",
        ])?;
    }
    if cfg.ipv6.is_some() {
        for net in ["::/1", "8000::/1"] {
            log.push_cmd(&[
                "netsh",
                "interface",
                "ipv6",
                "delete",
                "route",
                net,
                &tun_idx,
            ])?;
            run(&[
                "netsh",
                "interface",
                "ipv6",
                "add",
                "route",
                net,
                &tun_idx,
                "store=active",
            ])?;
        }
    }

    // 3. The TUN's resolver, preferred over every other adapter.
    run(&[
        "netsh",
        "interface",
        "ipv4",
        "set",
        "dnsservers",
        &format!("name={tun_idx}"),
        "source=static",
        &format!("address={peer}"),
        "register=none",
        "validate=no",
    ])?;
    let _ = run(&[
        "netsh",
        "interface",
        "ipv4",
        "set",
        "interface",
        &tun_idx,
        "metric=1",
    ]);
    let _ = run(&[
        "netsh",
        "interface",
        "ipv6",
        "set",
        "interface",
        &tun_idx,
        "metric=1",
    ]);
    let _ = run(&["ipconfig", "/flushdns"]);

    if cfg.strict_dns {
        block_dns_outside_tun(&peer, log)?;
    }
    info!(elapsed_ms = setup_started.elapsed().as_millis(), "Windows routes, DNS and firewall setup complete");
    Ok(())
}

fn allow_inbound_on_tun(log: &mut UndoLog) -> Result<()> {
    let exe = std::env::current_exe()?.display().to_string();
    let name = format!("name={FW_INBOUND}");
    let _ = run(&["netsh", "advfirewall", "firewall", "delete", "rule", &name]);
    log.push_cmd(&["netsh", "advfirewall", "firewall", "delete", "rule", &name])?;
    if let Err(e) = run(&[
        "netsh",
        "advfirewall",
        "firewall",
        "add",
        "rule",
        &name,
        "dir=in",
        "action=allow",
        "protocol=TCP",
        &format!("program={exe}"),
        "enable=yes",
    ]) {
        warn!("could not add the inbound firewall rule: {e}");
    }
    Ok(())
}

/// Blocks port 53 to every address except the TUN's resolver. Best effort:
/// without the firewall service the tunnel still works, only unguarded.
fn block_dns_outside_tun(dns: &str, log: &mut UndoLog) -> Result<()> {
    let Ok(ip) = dns.parse::<std::net::Ipv4Addr>() else {
        bail!("TUN DNS must be IPv4");
    };
    let below = std::net::Ipv4Addr::from(u32::from(ip) - 1);
    let above = std::net::Ipv4Addr::from(u32::from(ip) + 1);
    let ranges = format!(
        "0.0.0.0-{below},{above}-255.255.255.255,::-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"
    );
    let name = format!("name={FW_RULE}");
    let _ = run(&["netsh", "advfirewall", "firewall", "delete", "rule", &name]);
    log.push_cmd(&["netsh", "advfirewall", "firewall", "delete", "rule", &name])?;
    for proto in ["UDP", "TCP"] {
        let r = run(&[
            "netsh",
            "advfirewall",
            "firewall",
            "add",
            "rule",
            &name,
            "dir=out",
            "action=block",
            &format!("protocol={proto}"),
            "remoteport=53",
            &format!("remoteip={ranges}"),
        ]);
        if let Err(e) = r {
            warn!("firewall rule failed, strict DNS is NOT active: {e}");
            return Ok(());
        }
    }
    info!("strict dns: port 53 blocked except {dns} (Windows Firewall)");
    Ok(())
}

fn adapter_index(name: &str) -> Result<u32> {
    // The adapter may take a moment to appear after Wintun creates it.
    for _ in 0..20 {
        if let Ok(i) = adapter_index_once(name) {
            return Ok(i);
        }
        std::thread::sleep(std::time::Duration::from_millis(250));
    }
    bail!("adapter {name} not found")
}

fn win_result(code: u32) -> Result<()> {
    if code == 0 {
        Ok(())
    } else {
        Err(std::io::Error::from_raw_os_error(code as i32).into())
    }
}

fn adapter_index_once(name: &str) -> Result<u32> {
    let wide: Vec<u16> = name.encode_utf16().chain(Some(0)).collect();
    let mut luid = NET_LUID_LH::default();
    let mut index = 0;
    // The UTF-16 alias is terminated and both output pointers are valid.
    unsafe {
        win_result(ConvertInterfaceAliasToLuid(wide.as_ptr(), &mut luid))?;
        win_result(ConvertInterfaceLuidToIndex(&luid, &mut index))?;
    }
    Ok(index)
}

struct RouteTable(*mut MIB_IPFORWARD_TABLE2);

impl Drop for RouteTable {
    fn drop(&mut self) {
        // GetIpForwardTable2 transfers this allocation to the caller.
        unsafe { FreeMibTable(self.0.cast()) };
    }
}

fn select_default(
    rows: &[MIB_IPFORWARD_ROW2],
    excluded: Option<u32>,
) -> Option<&MIB_IPFORWARD_ROW2> {
    // Preserve Get-NetRoute | Sort-Object RouteMetric behavior. Interface
    // metrics are intentionally not added here to avoid changing uplink choice.
    rows.iter()
        .filter(|row| row.DestinationPrefix.PrefixLength == 0 && Some(row.InterfaceIndex) != excluded)
        .min_by_key(|row| row.Metric)
}

fn socket_address(addr: &SOCKADDR_INET) -> Result<String> {
    // Inspect the discriminant before reading the corresponding union member.
    unsafe {
        match addr.si_family {
            AF_INET => Ok(
                std::net::Ipv4Addr::from(addr.Ipv4.sin_addr.S_un.S_addr.to_ne_bytes()).to_string(),
            ),
            AF_INET6 => {
                let ip = std::net::Ipv6Addr::from(addr.Ipv6.sin6_addr.u.Byte);
                let scope = addr.Ipv6.Anonymous.sin6_scope_id;
                Ok(if scope == 0 {
                    ip.to_string()
                } else {
                    format!("{ip}%{scope}")
                })
            }
            _ => bail!("unsupported route address family"),
        }
    }
}

/// IPv4 address of the physical uplink: packets sent from it leave outside the TUN
/// (Windows uses the strong host model for the source address).
pub fn uplink_source_v4(tun: &str) -> Option<std::net::Ipv4Addr> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{GetUnicastIpAddressTable, MIB_UNICASTIPADDRESS_TABLE};
    let index = default_route(false, tun).ok()?.if_index;
    let mut ptr: *mut MIB_UNICASTIPADDRESS_TABLE = std::ptr::null_mut();
    unsafe {
        if GetUnicastIpAddressTable(AF_INET, &mut ptr) != 0 || ptr.is_null() { return None; }
        let rows = std::slice::from_raw_parts((*ptr).Table.as_ptr(), (*ptr).NumEntries as usize);
        let ip = rows.iter().find(|r| r.InterfaceIndex == index)
            .map(|r| std::net::Ipv4Addr::from(r.Address.Ipv4.sin_addr.S_un.S_addr.to_ne_bytes()));
        FreeMibTable(ptr.cast());
        ip
    }
}

fn default_route(v6: bool, tun: &str) -> Result<DefaultRoute> {
    let mut ptr = std::ptr::null_mut();
    unsafe {
        win_result(GetIpForwardTable2(if v6 { AF_INET6 } else { AF_INET }, &mut ptr))?;
    }
    if ptr.is_null() {
        bail!("Windows returned no route table");
    }
    let table = RouteTable(ptr);
    // Table is a variable-length array with NumEntries rows allocated by Windows.
    let rows = unsafe {
        std::slice::from_raw_parts((*table.0).Table.as_ptr(), (*table.0).NumEntries as usize)
    };
    let row = select_default(rows, adapter_index_once(tun).ok()).context("no default route")?;
    let mut alias = [0u16; 257];
    let alias = unsafe {
        if ConvertInterfaceLuidToAlias(&row.InterfaceLuid, alias.as_mut_ptr(), alias.len()) == 0 {
            let len = alias.iter().position(|&c| c == 0).unwrap_or(alias.len());
            String::from_utf16_lossy(&alias[..len])
        } else {
            row.InterfaceIndex.to_string()
        }
    };
    Ok(DefaultRoute {
        gateway: socket_address(&row.NextHop)?,
        if_index: row.InterfaceIndex,
        alias,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    // Reads existing routing state only; does not install routes or touch a TUN.
    #[test]
    #[ignore = "read-only performance probe requires a Windows IPv4 default route"]
    fn default_route_discovery_avoids_shell_startup() {
        let started = std::time::Instant::now();
        let route = default_route(false, "Kawa test nonexistent adapter").unwrap();
        assert!(route.if_index > 0);
        assert!(started.elapsed() < std::time::Duration::from_millis(500),
            "read-only route discovery took {:?}", started.elapsed());
        eprintln!("native route discovery: {:?}", started.elapsed());
        let started = std::time::Instant::now();
        assert_eq!(adapter_index(&route.alias).unwrap(), route.if_index);
        assert!(started.elapsed() < std::time::Duration::from_millis(500));
        eprintln!("native adapter discovery: {:?}", started.elapsed());
    }

    #[test]
    fn default_route_uses_lowest_route_metric_and_excludes_tun() {
        let mut rows = [MIB_IPFORWARD_ROW2::default(); 4];
        rows[0].InterfaceIndex = 7;
        rows[0].Metric = 100;
        rows[1].InterfaceIndex = 8;
        rows[1].Metric = 1;
        rows[1].DestinationPrefix.PrefixLength = 24;
        rows[2].InterfaceIndex = 9;
        rows[2].Metric = 1;
        rows[3].InterfaceIndex = 10;
        rows[3].Metric = 5;
        assert_eq!(select_default(&rows, Some(9)).unwrap().InterfaceIndex, 10);
        assert_eq!(select_default(&rows, None).unwrap().InterfaceIndex, 9);
        assert!(select_default(&[], None).is_none());
        assert!(select_default(&rows[1..2], None).is_none());
        assert!(select_default(&rows[2..3], Some(9)).is_none());
    }

    #[test]
    fn route_addresses_preserve_network_byte_order_and_ipv6_scope() {
        let mut addr = SOCKADDR_INET::default();
        addr.Ipv4.sin_family = AF_INET;
        addr.Ipv4.sin_addr.S_un.S_addr = u32::from_ne_bytes([192, 168, 1, 1]);
        assert_eq!(socket_address(&addr).unwrap(), "192.168.1.1");
        addr = SOCKADDR_INET::default();
        addr.Ipv6.sin6_family = AF_INET6;
        addr.Ipv6.sin6_addr.u.Byte = "fe80::1".parse::<std::net::Ipv6Addr>().unwrap().octets();
        addr.Ipv6.Anonymous.sin6_scope_id = 12;
        assert_eq!(socket_address(&addr).unwrap(), "fe80::1%12");
        addr.Ipv6.Anonymous.sin6_scope_id = 0;
        assert_eq!(socket_address(&addr).unwrap(), "fe80::1");
        assert!(socket_address(&SOCKADDR_INET::default()).is_err());
    }
}
