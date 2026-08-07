use std::collections::VecDeque;

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct LocationFix {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_meters: f64,
    pub observed_at_ms: i64,
    pub elapsed_realtime_nanos: u64,
}

#[derive(Debug)]
pub(crate) struct RecentLocationFixes {
    fixes: VecDeque<LocationFix>,
    capacity: usize,
    max_age_nanos: u64,
}

impl LocationFix {
    pub(crate) fn is_usable(&self) -> bool {
        self.lat.is_finite()
            && self.lon.is_finite()
            && self.accuracy_meters.is_finite()
            && (-90.0..=90.0).contains(&self.lat)
            && (-180.0..=180.0).contains(&self.lon)
            && self.accuracy_meters >= 0.0
    }
}

impl RecentLocationFixes {
    pub(crate) fn new(capacity: usize, max_age_ms: u64) -> Self {
        Self {
            fixes: VecDeque::new(),
            capacity,
            max_age_nanos: max_age_ms.saturating_mul(1_000_000),
        }
    }

    pub(crate) fn push(&mut self, fix: LocationFix) {
        let insertion_index = self
            .fixes
            .iter()
            .position(|existing| existing.elapsed_realtime_nanos > fix.elapsed_realtime_nanos)
            .unwrap_or(self.fixes.len());
        self.fixes.insert(insertion_index, fix);
        if self.fixes.len() > self.capacity {
            self.fixes.pop_front();
        }
    }

    pub(crate) fn clear(&mut self) {
        self.fixes.clear();
    }

    pub(crate) fn len(&self) -> usize {
        self.fixes.len()
    }

    pub(crate) fn most_recent_preceding(
        &self,
        elapsed_realtime_nanos: u64,
    ) -> Option<&LocationFix> {
        self.fixes.iter().rev().find(|fix| {
            fix.elapsed_realtime_nanos <= elapsed_realtime_nanos
                && elapsed_realtime_nanos - fix.elapsed_realtime_nanos <= self.max_age_nanos
        })
    }
}
