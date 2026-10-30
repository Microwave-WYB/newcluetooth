//! SPEC R-4a discovery rate (`docs/v2-api.md` §2.5.1): the devices first scanned in each 30 s bin of a recording,
//! with its running intervals, computed from the first-sighting times and pauses in `local.db`, so it survives a
//! restart.

use std::collections::BTreeMap;

use rusqlite::{Connection, OptionalExtension};

use super::error::CoreError;
use super::types::{DiscoveryBin, DiscoveryRate, DiscoveryRun};

/// The bin width.
pub(crate) const BIN_MS: i64 = 30_000;

/// The running intervals (wall clock) of a recording that started at `started`, with the pauses `(from, to)` in
/// order (`to = None` while paused) and `ended` once finished. The open run has `end_ms = None`; empty runs are left
/// out.
pub(crate) fn runs(
    started: i64,
    ended: Option<i64>,
    pauses: &[(i64, Option<i64>)],
) -> Vec<DiscoveryRun> {
    let mut out = Vec::new();
    let mut from = Some(started);
    for (p_from, p_to) in pauses {
        if let Some(f) = from
            && *p_from > f
        {
            out.push(DiscoveryRun {
                start_ms: f,
                end_ms: Some(*p_from),
            });
        }
        from = *p_to;
    }
    if let Some(f) = from {
        match ended {
            None => out.push(DiscoveryRun {
                start_ms: f,
                end_ms: None,
            }),
            Some(e) if e > f => out.push(DiscoveryRun {
                start_ms: f,
                end_ms: Some(e),
            }),
            Some(_) => {}
        }
    }
    out
}

/// The start of the bin a first sighting at `t` counts in: bins are 30 s from the start of the run that holds `t`
/// (a time outside every run counts in the nearest run before it, else the first one). `None` without runs.
pub(crate) fn bin_of(runs: &[DiscoveryRun], t: i64) -> Option<i64> {
    let run = runs
        .iter()
        .rev()
        .find(|r| r.start_ms <= t)
        .or_else(|| runs.first())?;
    let t = match run.end_ms {
        Some(end) => t.min(end - 1),
        None => t,
    };
    Some(run.start_ms + (t - run.start_ms).max(0) / BIN_MS * BIN_MS)
}

/// The bins with at least one new device (a missing bin is 0), in order. Pure.
pub(crate) fn bins(runs: &[DiscoveryRun], first_seen: &[i64]) -> Vec<DiscoveryBin> {
    let mut counts: BTreeMap<i64, u32> = BTreeMap::new();
    for t in first_seen {
        if let Some(b) = bin_of(runs, *t) {
            *counts.entry(b).or_default() += 1;
        }
    }
    counts
        .into_iter()
        .map(|(start_ms, new_devices)| DiscoveryBin {
            start_ms,
            new_devices,
        })
        .collect()
}

/// A session's runs and its sparse bins.
pub(crate) type RunsAndBins = (Vec<DiscoveryRun>, Vec<DiscoveryBin>);

/// The runs and bins of session `session` (its row id) from `local.db`; `None` for an unknown session. With
/// `hide_unnamed_apple` (the display filter), the hidden devices are not counted, as in the session's device count.
pub(crate) fn of_session(
    conn: &Connection,
    session: i64,
    hide_unnamed_apple: bool,
) -> Result<Option<RunsAndBins>, CoreError> {
    let Some((started, ended)) = conn
        .query_row(
            "SELECT started_ms, ended_ms FROM sessions WHERE id = ?1",
            [session],
            |r| Ok((r.get::<_, i64>(0)?, r.get::<_, Option<i64>>(1)?)),
        )
        .optional()?
    else {
        return Ok(None);
    };
    let pauses: Vec<(i64, Option<i64>)> = conn
        .prepare_cached(
            "SELECT from_ms, to_ms FROM session_pauses WHERE session_id = ?1 ORDER BY from_ms",
        )?
        .query_map([session], |r| Ok((r.get(0)?, r.get(1)?)))?
        .collect::<Result<_, _>>()?;
    let first_seen: Vec<i64> = conn
        .prepare_cached(
            "SELECT first_seen_ms FROM session_devices WHERE session_id = ?1 AND (shown OR NOT ?2)",
        )?
        .query_map(rusqlite::params![session, hide_unnamed_apple], |r| r.get(0))?
        .collect::<Result<_, _>>()?;
    let runs = runs(started, ended, &pauses);
    let bins = bins(&runs, &first_seen);
    Ok(Some((runs, bins)))
}

/// `discovery_rate(session_id)`: the rate of the session with that UUID at `seq`; an unknown session has no runs and
/// no bins.
pub(crate) fn rate(
    conn: &Connection,
    uuid: &str,
    seq: u64,
    hide_unnamed_apple: bool,
) -> Result<DiscoveryRate, CoreError> {
    let row: Option<i64> = conn
        .query_row("SELECT id FROM sessions WHERE uuid = ?1", [uuid], |r| {
            r.get(0)
        })
        .optional()?;
    let (runs, bins) = match row {
        Some(id) => of_session(conn, id, hide_unnamed_apple)?.unwrap_or_default(),
        None => (Vec::new(), Vec::new()),
    };
    Ok(DiscoveryRate {
        seq,
        bin_ms: BIN_MS as u32,
        bins,
        runs,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(start_ms: i64, end_ms: Option<i64>) -> DiscoveryRun {
        DiscoveryRun { start_ms, end_ms }
    }

    #[test]
    fn runs_leave_out_the_pauses() {
        assert_eq!(runs(0, None, &[]), vec![run(0, None)]);
        assert_eq!(
            runs(0, None, &[(60_000, Some(120_000))]),
            vec![run(0, Some(60_000)), run(120_000, None)]
        );
        assert_eq!(
            runs(0, None, &[(60_000, None)]),
            vec![run(0, Some(60_000))],
            "paused now: no open run"
        );
        // stopped while paused: the stop closes the pause, and the empty last run is left out
        assert_eq!(
            runs(0, Some(90_000), &[(60_000, Some(90_000))]),
            vec![run(0, Some(60_000))]
        );
        assert_eq!(runs(0, Some(50_000), &[]), vec![run(0, Some(50_000))]);
    }

    #[test]
    fn bins_count_from_each_runs_own_start() {
        let rs = vec![run(1_000, Some(61_000)), run(100_000, None)];
        assert_eq!(bin_of(&rs, 1_000), Some(1_000));
        assert_eq!(bin_of(&rs, 30_999), Some(1_000));
        assert_eq!(bin_of(&rs, 31_000), Some(31_000));
        assert_eq!(
            bin_of(&rs, 61_000),
            Some(31_000),
            "the run's end is exclusive"
        );
        assert_eq!(
            bin_of(&rs, 80_000),
            Some(31_000),
            "in a pause: the run before"
        );
        assert_eq!(
            bin_of(&rs, 100_000),
            Some(100_000),
            "after the pause: from the run's own start"
        );
        assert_eq!(
            bin_of(&rs, 500),
            Some(1_000),
            "before the start: the first run"
        );
        assert_eq!(bin_of(&[], 5), None);
        let b = bins(&rs, &[1_000, 2_000, 45_000, 130_000]);
        assert_eq!(
            b.iter()
                .map(|b| (b.start_ms, b.new_devices))
                .collect::<Vec<_>>(),
            vec![(1_000, 2), (31_000, 1), (130_000, 1)],
            "sparse: empty bins are missing"
        );
    }
}
