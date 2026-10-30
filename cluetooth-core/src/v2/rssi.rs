//! D-10 live chart (`docs/v2-api.md` §2.5): per device of the live session, the max RSSI of each of the last 60
//! seconds ("just use one minute"). Owned by the writer; the reader sees immutable snapshots of it.

use std::collections::HashMap;

use super::types::LiveChart;

/// The window: 60 one-second buckets.
pub(crate) const WINDOW_S: usize = 60;
/// At most this many devices keep a ring; past it the one heard longest ago goes.
pub(crate) const MAX_DEVICES: usize = 2_000;

/// One device's last minute. Bucket `s.rem_euclid(60)` holds second `s` for `s` in `last_sec - 59 ..= last_sec`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Ring {
    last_sec: i64,
    last_ms: i64,
    last_rssi: i16,
    max: [Option<i16>; WINDOW_S],
}

fn slot(sec: i64) -> usize {
    // rem_euclid(60) is in 0..60
    usize::try_from(sec.rem_euclid(WINDOW_S as i64)).unwrap_or(0)
}

impl Ring {
    fn new(sec: i64, at_ms: i64, rssi: i16) -> Self {
        let mut ring = Ring {
            last_sec: sec,
            last_ms: at_ms,
            last_rssi: rssi,
            max: [None; WINDOW_S],
        };
        if let Some(b) = ring.max.get_mut(slot(sec)) {
            *b = Some(rssi);
        }
        ring
    }

    fn hear(&mut self, sec: i64, at_ms: i64, rssi: i16) {
        if sec <= self.last_sec - WINDOW_S as i64 {
            return; // older than the window
        }
        if sec > self.last_sec {
            // the seconds after the last one heard start empty (at most the whole window)
            let from = (self.last_sec + 1).max(sec - WINDOW_S as i64 + 1);
            for s in from..=sec {
                if let Some(b) = self.max.get_mut(slot(s)) {
                    *b = None;
                }
            }
            self.last_sec = sec;
        }
        if at_ms >= self.last_ms {
            self.last_ms = at_ms;
            self.last_rssi = rssi;
        }
        if let Some(b) = self.max.get_mut(slot(sec)) {
            *b = Some(b.map_or(rssi, |m| m.max(rssi)));
        }
    }

    /// The max RSSI of second `sec`, if heard.
    fn at(&self, sec: i64) -> Option<i16> {
        if sec > self.last_sec || sec <= self.last_sec - WINDOW_S as i64 {
            return None;
        }
        self.max.get(slot(sec)).copied().flatten()
    }
}

/// §2.5 encoding: one byte per bucket, −RSSI clamped to 30..100; 0 is a gap.
pub(crate) fn encode(rssi: Option<i16>) -> u8 {
    rssi.map_or(0, |r| {
        u8::try_from((-i32::from(r)).clamp(30, 100)).unwrap_or(0)
    })
}

/// The live session's rings.
#[derive(Clone, Debug, Default)]
pub(crate) struct Rings {
    by_key: HashMap<i64, Ring>,
}

impl Rings {
    /// One sighting at `at_ms` (its scan time).
    pub fn hear(&mut self, key: i64, at_ms: i64, rssi: i16) {
        let sec = at_ms.div_euclid(1000);
        if let Some(ring) = self.by_key.get_mut(&key) {
            ring.hear(sec, at_ms, rssi);
            return;
        }
        if self.by_key.len() >= MAX_DEVICES {
            self.evict(at_ms);
        }
        if self.by_key.len() >= MAX_DEVICES {
            let oldest = self
                .by_key
                .iter()
                .min_by_key(|(k, r)| (r.last_ms, **k))
                .map(|(k, _)| *k);
            if let Some(k) = oldest {
                self.by_key.remove(&k);
            }
        }
        self.by_key.insert(key, Ring::new(sec, at_ms, rssi));
    }

    /// Forgets the devices not heard in the 60 s before `now_ms`.
    pub fn evict(&mut self, now_ms: i64) {
        let now_sec = now_ms.div_euclid(1000);
        self.by_key
            .retain(|_, r| r.last_sec > now_sec - WINDOW_S as i64);
    }

    pub fn len(&self) -> usize {
        self.by_key.len()
    }

    pub fn contains(&self, key: i64) -> bool {
        self.by_key.contains_key(&key)
    }

    /// D-10: the 60 one-second buckets ending at `now_ms`, and the current reading when heard within
    /// `stale_after_ms`. A device without a ring gets an empty chart.
    pub fn chart(&self, key: i64, now_ms: i64, stale_after_ms: i64) -> LiveChart {
        let now_sec = now_ms.div_euclid(1000);
        let ring = self.by_key.get(&key);
        let samples = (0..WINDOW_S as i64)
            .map(|j| encode(ring.and_then(|r| r.at(now_sec - (WINDOW_S as i64 - 1) + j))))
            .collect();
        LiveChart {
            key,
            end_ms: now_ms,
            samples,
            rssi: ring
                .filter(|r| now_ms - r.last_ms < stale_after_ms)
                .map(|r| r.last_rssi),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const T: i64 = 1_761_000_000_000; // a whole second

    fn samples(r: &Rings, key: i64, now: i64) -> Vec<u8> {
        r.chart(key, now, 20_000).samples
    }

    #[test]
    fn the_window_is_the_last_60_seconds() {
        let mut r = Rings::default();
        r.hear(1, T, -50);
        r.hear(1, T + 400, -40); // same second: the max
        r.hear(1, T + 59_000, -70);
        let s = samples(&r, 1, T + 59_999);
        assert_eq!(s.len(), 60);
        assert_eq!(s.first(), Some(&40), "the oldest bucket is 59 s back");
        assert_eq!(s.last(), Some(&70));
        assert_eq!(s.iter().filter(|b| **b != 0).count(), 2);
        // one second later the first one left the window
        let s = samples(&r, 1, T + 60_000);
        assert_eq!(s.iter().filter(|b| **b != 0).count(), 1);
        assert_eq!(s.get(58), Some(&70));
        // clamping and gaps
        let mut c = Rings::default();
        c.hear(2, T, -10);
        c.hear(2, T + 1_000, -120);
        assert_eq!(&samples(&c, 2, T + 2_000)[57..], &[30, 100, 0]);
    }

    #[test]
    fn late_and_old_sightings() {
        let mut r = Rings::default();
        r.hear(1, T + 30_000, -60);
        r.hear(1, T + 10_000, -50); // late but inside the window
        r.hear(1, T - 40_000, -40); // older than the window: ignored
        let s = samples(&r, 1, T + 30_000);
        assert_eq!(s.iter().filter(|b| **b != 0).count(), 2);
        assert_eq!(
            r.chart(1, T + 30_000, 20_000).rssi,
            Some(-60),
            "the latest reading"
        );
        assert_eq!(r.chart(1, T + 50_001, 20_000).rssi, None, "stale");
        // a jump of more than a minute clears everything before
        r.hear(1, T + 200_000, -55);
        assert_eq!(
            samples(&r, 1, T + 200_000)
                .iter()
                .filter(|b| **b != 0)
                .count(),
            1
        );
    }

    #[test]
    fn eviction_and_the_device_cap() {
        let mut r = Rings::default();
        r.hear(1, T, -50);
        r.hear(2, T + 30_000, -50);
        r.evict(T + 60_000);
        assert!(!r.contains(1) && r.contains(2), "1 was last heard 60 s ago");
        assert_eq!(samples(&r, 1, T + 60_000), vec![0; 60]);
        // many devices: never more than the cap, the most recent kept
        let mut many = Rings::default();
        for k in 0..(MAX_DEVICES as i64 * 5) {
            many.hear(k, T + k, -60);
            assert!(many.len() <= MAX_DEVICES);
        }
        assert_eq!(many.len(), MAX_DEVICES);
        assert!(many.contains(MAX_DEVICES as i64 * 5 - 1));
        assert!(!many.contains(0));
    }

    /// Randomized: every chart has exactly 60 buckets, its marks are the distinct heard seconds in the window, each
    /// bucket the max of its second, and the ring count stays at the cap.
    #[test]
    fn randomized_rings_stay_bounded() {
        let mut state = 2026u64;
        let mut rnd = move |n: u64| {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            (state >> 33) % n
        };
        let mut r = Rings::default();
        let mut heard: Vec<(i64, i64, i16)> = Vec::new();
        let mut now = T;
        for _ in 0..20_000 {
            now += rnd(400) as i64;
            let key = rnd(40) as i64;
            let at = now - rnd(5_000) as i64;
            let rssi = -30 - rnd(70) as i16;
            r.hear(key, at, rssi);
            heard.push((key, at, rssi));
            if rnd(50) == 0 {
                r.evict(now);
            }
            assert!(r.len() <= MAX_DEVICES);
        }
        let now_sec = now.div_euclid(1000);
        for key in 0..40 {
            let chart = r.chart(key, now, 20_000);
            assert_eq!(chart.samples.len(), WINDOW_S);
            if !r.contains(key) {
                continue;
            }
            for (j, b) in chart.samples.iter().enumerate() {
                let sec = now_sec - 59 + j as i64;
                let expect = heard
                    .iter()
                    .filter(|(k, at, _)| *k == key && at.div_euclid(1000) == sec)
                    .map(|(_, _, v)| *v)
                    .max();
                // the ring may have dropped seconds that were older than its window when they arrived
                if *b != 0 {
                    assert_eq!(Some(*b), expect.map(|v| encode(Some(v))));
                }
            }
        }
    }
}
