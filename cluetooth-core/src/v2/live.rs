//! Live deltas (`docs/v2-api.md` §2.6), first cut: one delta per commit of the live recording, built by diffing
//! the session's devices and cards against what was last sent. The focused device's chart (D-10) comes from the
//! in-memory RSSI rings; rows carry no sparklines (C-5/C-6).

use std::collections::HashMap;
use std::sync::Arc;

use rusqlite::Connection;

use super::catalog::{Catalog, UNIDENTIFIED};
use super::error::CoreError;
use super::keys::addr_of;
use super::rssi::Rings;
use super::store::RoutePoint;
use super::types::*;

#[derive(Clone, Debug, PartialEq)]
struct Sent {
    rssi: Option<i16>,
    in_range: bool,
    name: Option<String>,
    page: Option<String>,
    best: Option<(f64, f64)>,
}

/// What the UI has been told about the live session.
#[derive(Default)]
pub(crate) struct LiveState {
    pub seq: u64,
    sent: HashMap<i64, Sent>,
    cards: HashMap<String, ProductCard>,
    /// The latest RSSI heard per device ("live: current", §2.5), committed or not.
    pub current: Arc<HashMap<i64, i16>>,
    /// D-10: each device's last minute of RSSI, committed or not. The writer owns it; the reader gets snapshots.
    pub rings: Arc<Rings>,
    /// The device whose chart rides on the deltas (`set_live_focus`).
    pub focus: Option<i64>,
    /// The latest fix: (lat, lon, accuracy, observed_at_ms).
    pub fix: Option<(f64, f64, f64, i64)>,
}

impl LiveState {
    pub fn hear(&mut self, key: i64, rssi: Option<i16>, at_ms: i64) {
        if let Some(r) = rssi {
            Arc::make_mut(&mut self.current).insert(key, r);
            Arc::make_mut(&mut self.rings).hear(key, at_ms, r);
        }
    }
}

pub(crate) struct DeltaInput<'a> {
    pub conn: &'a Connection,
    pub catalog: &'a Catalog,
    pub hide_unnamed_apple: bool,
    pub session: i64,
    pub uuid: &'a str,
    pub now: i64,
    pub stale_after_ms: i64,
    pub state: RecordingState,
    pub route: &'a [RoutePoint],
    pub force: bool,
}

/// The delta since the last one, or `None` when nothing changed (unless `force`, e.g. the delta carrying Paused).
pub(crate) fn delta(
    live: &mut LiveState,
    input: DeltaInput<'_>,
) -> Result<Option<LiveDelta>, CoreError> {
    let DeltaInput {
        conn,
        catalog,
        hide_unnamed_apple,
        session,
        uuid,
        now,
        stale_after_ms,
        state,
        route,
        force,
    } = input;
    let fresh = now - stale_after_ms;
    if live.rings.len() > 0 {
        Arc::make_mut(&mut live.rings).evict(now);
    }
    let chart = live
        .focus
        .map(|key| live.rings.chart(key, now, stale_after_ms));
    let mut stmt = conn.prepare_cached(
        "SELECT addr, name, is_apple, page_id, rssi_max, best_lon, best_lat, last_seen_ms
         FROM session_devices WHERE session_id = ?1 ORDER BY id",
    )?;
    struct Row {
        key: i64,
        name: Option<String>,
        page: Option<String>,
        rssi_max: Option<i16>,
        best: Option<(f64, f64)>,
        last_seen_ms: i64,
    }
    let rows = stmt
        .query_map([session], |r| {
            let apple: bool = r.get(2)?;
            let name: Option<String> = r.get(1)?;
            let lon: Option<f64> = r.get(5)?;
            let lat: Option<f64> = r.get(6)?;
            Ok((
                !(hide_unnamed_apple && apple && name.is_none()),
                Row {
                    key: r.get(0)?,
                    name,
                    page: r.get(3)?,
                    rssi_max: r.get(4)?,
                    best: lon.zip(lat),
                    last_seen_ms: r.get(7)?,
                },
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    let mut changed = Vec::new();
    let mut appended = Vec::new();
    let mut cards: HashMap<String, (u32, u32, i64)> = HashMap::new();
    for (shown, row) in rows {
        if !shown {
            continue;
        }
        let rssi = live.current.get(&row.key).copied().or(row.rssi_max);
        let in_range = row.last_seen_ms >= fresh;
        let card = cards
            .entry(row.page.clone().unwrap_or_else(|| UNIDENTIFIED.to_owned()))
            .or_insert((0, 0, i64::MIN));
        card.0 += 1;
        card.1 += u32::from(in_range);
        card.2 = card.2.max(row.last_seen_ms);
        let now_sent = Sent {
            rssi,
            in_range,
            name: row.name.clone(),
            page: row.page.clone(),
            best: row.best,
        };
        match live.sent.get(&row.key) {
            None => {
                let page = row.page.as_deref().and_then(|id| catalog.page(id));
                appended.push(DeviceRow {
                    key: row.key,
                    addr: addr_of(row.key),
                    name: row.name.clone(),
                    page: page.map(|p| p.page_ref()),
                    categories: page.map(|p| p.categories.clone()).unwrap_or_default(),
                    traits: page.map(|p| p.traits.clone()).unwrap_or_default(),
                    readable: page.is_some_and(|p| p.readable),
                    vulnerable: page.is_some_and(|p| p.vulnerable()),
                    icon: page.and_then(|p| p.icon.as_ref().map(|i| i.asset.clone())),
                    rssi,
                    in_range,
                    spark: None,
                    best: row.best.map(|(lon, lat)| GeoPoint { lon, lat }),
                    last_seen_ms: row.last_seen_ms,
                });
            }
            Some(prev) if *prev != now_sent => {
                changed.push(DeviceLive {
                    key: row.key,
                    rssi,
                    in_range,
                    spark: Vec::new(),
                    name: (prev.name != row.name).then(|| row.name.clone()).flatten(),
                    page: (prev.page != row.page)
                        .then(|| {
                            row.page
                                .as_deref()
                                .and_then(|id| catalog.page(id))
                                .map(|p| p.page_ref())
                        })
                        .flatten(),
                    best: (prev.best != row.best)
                        .then_some(row.best)
                        .flatten()
                        .map(|(lon, lat)| GeoPoint { lon, lat }),
                });
            }
            Some(_) => {}
        }
        live.sent.insert(row.key, now_sent);
    }
    let mut products = Vec::new();
    for (id, (count, in_range, last_seen_ms)) in cards {
        let page = catalog.page(&id);
        let card = ProductCard {
            id: id.clone(),
            title: page.map_or_else(|| "Unidentified devices".to_owned(), |p| p.title.clone()),
            kind: page.map(|p| p.kind),
            icon: page.and_then(|p| p.icon.as_ref().map(|i| i.asset.clone())),
            live: true,
            count,
            in_range,
            last_seen_ms,
        };
        if live.cards.get(&id) != Some(&card) {
            live.cards.insert(id, card.clone());
            products.push(card);
        }
    }
    products.sort_by_key(|c| catalog.order(&c.id));
    let route_appended: Vec<GeoPoint> = route
        .iter()
        .map(|p| GeoPoint {
            lon: p.lon,
            lat: p.lat,
        })
        .collect();
    if !force
        && chart.is_none()
        && changed.is_empty()
        && appended.is_empty()
        && products.is_empty()
        && route_appended.is_empty()
    {
        return Ok(None);
    }
    live.seq += 1;
    Ok(Some(LiveDelta {
        session_id: uuid.to_owned(),
        seq: live.seq,
        at_ms: now,
        state,
        changed,
        appended,
        products,
        route_appended,
        fix: live.fix.map(|(lat, lon, accuracy_m, at)| FixSummary {
            lat,
            lon,
            accuracy_m,
            age_ms: now - at,
        }),
        chart,
    }))
}
