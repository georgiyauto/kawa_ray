//! Per-session IP traffic before NAT reinjection. Adapter RX/TX cannot be used:
//! the system TCP stack reads and writes each packet through the same TUN.
use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Default)]
pub struct TrafficCounters {
    received: AtomicU64,
    sent: AtomicU64,
}

impl TrafficCounters {
    pub fn received(&self, bytes: usize) { self.received.fetch_add(bytes as u64, Ordering::Relaxed); }
    pub fn sent(&self, bytes: usize) { self.sent.fetch_add(bytes as u64, Ordering::Relaxed); }
    pub fn snapshot(&self) -> (u64, u64) {
        (self.received.load(Ordering::Relaxed), self.sent.load(Ordering::Relaxed))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn independent_directions_and_fresh_session() {
        let traffic = TrafficCounters::default();
        traffic.sent(64); traffic.received(8192); traffic.received(512);
        assert_eq!(traffic.snapshot(), (8704, 64));
        assert_eq!(TrafficCounters::default().snapshot(), (0, 0));
    }
}
