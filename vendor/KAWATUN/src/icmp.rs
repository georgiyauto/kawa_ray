//! ICMP echo ("ping") is not proxied: Xray carries only TCP/UDP. Echo requests that
//! enter the TUN are answered by a real ping sent from the physical uplink, so `ping`
//! keeps working while the VPN is on (it measures the direct path, outside the tunnel).

use std::net::Ipv4Addr;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use crate::udp::PacketSink;

const MAX_INFLIGHT: usize = 64;
static INFLIGHT: AtomicUsize = AtomicUsize::new(0);

/// IPv4 ICMP echo request: (src, dst, ttl, ident+seq+payload after the 4-byte type/code/checksum).
pub fn parse_echo_v4(pkt: &[u8]) -> Option<(Ipv4Addr, Ipv4Addr, u8, &[u8])> {
    if pkt.len() < 28 || pkt[0] >> 4 != 4 || pkt[9] != 1 { return None; }
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    let total = u16::from_be_bytes([pkt[2], pkt[3]]) as usize;
    if ihl < 20 || total > pkt.len() || total < ihl + 8 { return None; }
    if u16::from_be_bytes([pkt[6], pkt[7]]) & 0x3fff != 0 { return None; } // fragments
    let icmp = &pkt[ihl..total];
    if icmp[0] != 8 || icmp[1] != 0 { return None; } // echo request only
    let src = Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]);
    let dst = Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
    Some((src, dst, pkt[8], &icmp[4..]))
}

fn checksum(data: &[u8]) -> u16 {
    let mut sum: u32 = 0;
    for chunk in data.chunks(2) {
        sum += u32::from(u16::from_be_bytes([chunk[0], *chunk.get(1).unwrap_or(&0)]));
    }
    while sum >> 16 != 0 { sum = (sum & 0xffff) + (sum >> 16); }
    !(sum as u16)
}

/// Echo reply as the TUN would receive it from `from`.
pub fn build_reply_v4(from: Ipv4Addr, to: Ipv4Addr, ttl: u8, rest: &[u8]) -> Vec<u8> {
    let total = 20 + 4 + rest.len();
    let mut p = vec![0u8; total];
    p[0] = 0x45;
    p[2..4].copy_from_slice(&(total as u16).to_be_bytes());
    p[8] = ttl;
    p[9] = 1;
    p[12..16].copy_from_slice(&from.octets());
    p[16..20].copy_from_slice(&to.octets());
    let c = checksum(&p[..20]);
    p[10..12].copy_from_slice(&c.to_be_bytes());
    p[24..].copy_from_slice(rest); // type 0 code 0, identifier, sequence, payload
    let c = checksum(&p[20..]);
    p[22..24].copy_from_slice(&c.to_be_bytes());
    p
}

/// Answers an echo request in the background; non-echo ICMP is ignored (dropped as before).
pub fn handle<S: PacketSink + Send + Sync + 'static>(pkt: &[u8], sink: &Arc<S>, tun: &str) {
    let Some((src, dst, _ttl, rest)) = parse_echo_v4(pkt) else { return };
    if INFLIGHT.fetch_add(1, Ordering::Relaxed) >= MAX_INFLIGHT { INFLIGHT.fetch_sub(1, Ordering::Relaxed); return; }
    let (rest, sink, tun) = (rest.to_vec(), sink.clone(), tun.to_string());
    tokio::spawn(async move {
        let payload = rest[4..].to_vec();
        let res = tokio::task::spawn_blocking(move || sys_ping(&tun, dst, &payload)).await.ok().flatten();
        if let Some(ttl) = res {
            sink.send(build_reply_v4(dst, src, ttl, &rest)).await;
        }
        INFLIGHT.fetch_sub(1, Ordering::Relaxed);
    });
}

#[cfg(windows)]
fn sys_ping(tun: &str, dst: Ipv4Addr, payload: &[u8]) -> Option<u8> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{IcmpCloseHandle, IcmpCreateFile, IcmpSendEcho2Ex, ICMP_ECHO_REPLY, IP_OPTION_INFORMATION};
    let source = crate::sys::uplink_source_v4(tun)?;
    let mut reply = vec![0u8; std::mem::size_of::<ICMP_ECHO_REPLY>() + payload.len() + 64];
    let opts = IP_OPTION_INFORMATION { Ttl: 128, Tos: 0, Flags: 0, OptionsSize: 0, OptionsData: std::ptr::null_mut() };
    unsafe {
        let h = IcmpCreateFile();
        if h.is_null() || h as isize == -1 { return None; }
        let n = IcmpSendEcho2Ex(h, std::ptr::null_mut(), None, std::ptr::null(),
            u32::from_ne_bytes(source.octets()), u32::from_ne_bytes(dst.octets()),
            payload.as_ptr().cast(), payload.len() as u16, &opts, reply.as_mut_ptr().cast(), reply.len() as u32, 3000);
        IcmpCloseHandle(h);
        if n == 0 { return None; }
        let r = &*(reply.as_ptr() as *const ICMP_ECHO_REPLY);
        (r.Status == 0).then_some(r.Options.Ttl)
    }
}

#[cfg(not(windows))]
fn sys_ping(_tun: &str, _dst: Ipv4Addr, _payload: &[u8]) -> Option<u8> { None }

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn reply_mirrors_request_with_valid_checksums() {
        // echo request 10.0.0.2 -> 8.8.8.8, id 0x1234 seq 1, payload "abcd"
        let mut req = build_reply_v4(Ipv4Addr::new(10, 0, 0, 2), Ipv4Addr::new(8, 8, 8, 8), 64, &[0x12, 0x34, 0, 1, b'a', b'b', b'c', b'd']);
        req[20] = 8; let c = checksum(&{ let mut x = req[20..].to_vec(); x[2] = 0; x[3] = 0; x }); req[22..24].copy_from_slice(&c.to_be_bytes());
        let (src, dst, _, rest) = parse_echo_v4(&req).unwrap();
        assert_eq!((src, dst), (Ipv4Addr::new(10, 0, 0, 2), Ipv4Addr::new(8, 8, 8, 8)));
        let rep = build_reply_v4(dst, src, 57, rest);
        assert_eq!(checksum(&rep[..20]), 0);
        assert_eq!(checksum(&rep[20..]), 0);
        assert_eq!(&rep[24..], &[0x12, 0x34, 0, 1, b'a', b'b', b'c', b'd']);
        assert_eq!(rep[20], 0);
        assert!(parse_echo_v4(&rep).is_none());
    }
}
