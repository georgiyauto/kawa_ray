/// A startup request can launch at most one network probe batch per process.
pub struct StartupPing {
    pending: bool,
}

impl StartupPing {
    pub fn new(enabled: bool) -> Self {
        Self { pending: enabled }
    }
    pub fn pending(&self) -> bool {
        self.pending
    }
    pub fn consume(&mut self) {
        self.pending = false;
    }
    pub fn poll(&mut self, enabled: bool, has_servers: bool, blocked: bool) -> bool {
        if !enabled {
            self.consume();
        }
        if self.pending && has_servers && !blocked {
            self.consume();
            true
        } else {
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn disabled_startup_never_sends_a_probe_or_rearms_when_enabled_later() {
        let mut request = StartupPing::new(false);
        assert!(!request.poll(false, true, false));
        assert!(!request.poll(true, true, false));
        assert!(!request.pending());
    }

    #[test]
    fn enabled_startup_waits_for_loaded_servers_and_then_runs_only_once() {
        let mut request = StartupPing::new(true);
        assert!(!request.poll(true, false, false));
        assert!(!request.poll(true, true, true));
        assert!(request.poll(true, true, false));
        assert!(!request.pending());
        for _ in 0..10 {
            assert!(!request.poll(true, true, false));
        }
    }

    #[test]
    fn manual_check_or_disabling_cancels_the_pending_automatic_batch() {
        let mut request = StartupPing::new(true);
        request.consume();
        assert!(!request.poll(true, true, false));
        let mut request = StartupPing::new(true);
        assert!(!request.poll(false, true, false));
        assert!(!request.pending());
        assert!(!request.poll(true, true, false));
    }
}
