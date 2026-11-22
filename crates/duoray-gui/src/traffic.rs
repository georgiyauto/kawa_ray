//! Rates from the helper's directional session counters, before NAT reinjection.
use std::time::Instant;

#[derive(Default)]
pub struct Meter {
    previous: Option<(String, Instant, u64, u64)>,
    received:u64, sent:u64, peak:u64,
    rates: (u64, u64),
}

impl Meter {
    pub fn reset(&mut self) { *self = Self::default(); }
    pub fn totals(&self)->(u64,u64,u64){(self.received,self.sent,self.peak)}
    pub fn sample(&mut self, interface: &str, now: Instant, rx: u64, tx: u64) -> Option<(u64, u64)> {
        let Some(old) = self.previous.as_ref() else {
            self.previous = Some((interface.into(), now, rx, tx));
            self.received = rx; self.sent = tx;
            return None;
        };
        let seconds = now.saturating_duration_since(old.1).as_secs_f64();
        if old.0 != interface || rx < old.2 || tx < old.3 {
            self.reset(); self.previous = Some((interface.into(), now, rx, tx));
            self.received=rx; self.sent=tx; return None;
        }
        if seconds <= 0.0 { return Some(self.rates); }
        self.rates = (((rx-old.2) as f64/seconds) as u64, ((tx-old.3) as f64/seconds) as u64);
        self.received=rx; self.sent=tx;
        self.peak=self.peak.max(self.rates.0);
        self.previous = Some((interface.into(), now, rx, tx));
        Some(self.rates)
    }
}

pub fn rate(bytes: u64) -> String {
    if bytes >= 1_048_576 { format!("{:.1} МБ/с", bytes as f64/1_048_576.0) }
    else if bytes >= 1024 { format!("{:.1} КБ/с", bytes as f64/1024.0) }
    else { format!("{bytes} Б/с") }
}

/// Chart line as vector path commands in a 200×32 viewbox. Drawn by a Slint
/// `Path`, so it stays crisp at any width and scale factor (a rasterized
/// 202 px SVG stretched across the card came out pixelated).
pub fn sparkline_points(values:&[f32;32])->slint::SharedString {
    let x=|i:usize|i as f32*200.0/31.0;
    // Quadratic segments through the midpoints: a smooth curve that still
    // passes exactly through the first and last samples.
    let mut d=format!("M {:.2} {:.2}",x(0),values[0]);
    for i in 1..31 {
        let (mx,my)=((x(i)+x(i+1))/2.0,(values[i]+values[i+1])/2.0);
        d.push_str(&format!(" Q {:.2} {:.2} {:.2} {:.2}",x(i),values[i],mx,my));
    }
    d.push_str(&format!(" L {:.2} {:.2}",x(31),values[31]));
    d.into()
}
pub fn chart_points(values:&std::collections::VecDeque<f32>)->[f32;32] {
    let peak=values.iter().copied().fold(1.0f32,f32::max);
    std::array::from_fn(|i|28.-values.get(i).copied().unwrap_or(0.)/peak*24.)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn helper_samples_include_early_bytes_and_do_not_spike_when_ui_polls_twice() {
        let mut meter = Meter::default(); let now = Instant::now();
        assert_eq!(meter.sample("session", now, 8192, 64), None);
        assert_eq!(meter.totals(), (8192, 64, 0));
        let later = now + std::time::Duration::from_secs(2);
        assert_eq!(meter.sample("session", later, 16384, 192), Some((4096, 64)));
        assert_eq!(meter.sample("session", later, 16384, 192), Some((4096, 64)));
        assert_eq!(meter.totals(), (16384, 192, 4096));
        assert_eq!(meter.sample("session", later + std::time::Duration::from_secs(2), 24576, 320), Some((4096,64)));
        meter.reset();
        assert_eq!(meter.sample("session", later, 0, 0), None);
        assert_eq!(meter.totals(), (0, 0, 0));
    }
    #[test]
    fn rates_use_elapsed_time_and_reset_on_interface_change() {
        let mut meter=Meter::default(); let now=Instant::now();
        assert_eq!(meter.sample("tun",now,1000,2000),None);
        assert_eq!(meter.sample("tun",now+std::time::Duration::from_secs(2),5096,4048),Some((2048,1024)));
        assert_eq!(meter.totals(),(5096,4048,2048));
        assert_eq!(meter.sample("new",now+std::time::Duration::from_secs(3),99_000,99_000),None);
        assert_eq!(meter.totals(),(99_000,99_000,0));
        assert_eq!(meter.sample("new",now+std::time::Duration::from_secs(4),0,0),None);
        meter.reset();
        assert_eq!(meter.sample("new",now,100,100),None);
    }
    #[test]
    fn rate_units() { assert_eq!(rate(0),"0 Б/с"); assert_eq!(rate(2048),"2.0 КБ/с"); assert_eq!(rate(1_572_864),"1.5 МБ/с"); }

    #[test]
    fn chart_path_is_vector_and_spans_the_viewbox() {
        let mut v=[28.0f32;32];v[10]=4.0;
        let d=sparkline_points(&v);
        assert!(d.starts_with("M 0.00 28.00"));
        assert!(d.ends_with("L 200.00 28.00"));
        assert_eq!(d.matches(" Q ").count(),30);
    }
}
