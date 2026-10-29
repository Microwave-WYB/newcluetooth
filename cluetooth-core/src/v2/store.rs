//! The write path into `local.db` (`docs/v2-api.md` §2.4 step 5, §3.1, §3.5): one SQLite transaction per commit
//! of exactly the observations the staging append covered.

use std::collections::{BTreeMap, HashMap};

use rusqlite::{Connection, OptionalExtension, Transaction, params};
use sha2::{Digest, Sha256};

use super::adv;
use super::catalog::Catalog;
use super::error::CoreError;
use crate::matcher::{AddrType, Structure};

/// One scan observation as the DB aggregates it (the Parquet row plus its device key).
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Obs {
    pub addr: i64,
    pub at_ms: i64,
    pub rssi: Option<i16>,
    pub raw: Vec<u8>,
    pub tx_power: Option<i16>,
    pub connectable: Option<bool>,
    pub addr_type: Option<i32>,
    pub is_legacy: Option<bool>,
    pub primary_phy: Option<i32>,
    pub secondary_phy: Option<i32>,
    pub adv_sid: Option<i32>,
    pub data_status: Option<i32>,
    pub adv_tx_power: Option<i32>,
    pub periodic_interval: Option<i32>,
    /// The phone's fix attached at ingest: `(lat, lon, accuracy)`.
    pub fix: Option<(f64, f64, f64)>,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct RoutePoint {
    pub t_ms: i64,
    pub lat: f64,
    pub lon: f64,
    pub accuracy: Option<f64>,
}

/// `sighting_sample_score`: rssi/20 − 2·log10(max(accuracy ?? 50, 1)); the coefficients are provisional.
pub(crate) fn sample_score(rssi: Option<i16>, accuracy: Option<f64>) -> Option<f64> {
    rssi.map(|r| f64::from(r) / 20.0 - 2.0 * accuracy.unwrap_or(50.0).max(1.0).log10())
}

/// One located sample of a minute.
#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Sample {
    pub lat: f64,
    pub lon: f64,
    pub accuracy: Option<f64>,
    pub rssi: Option<i16>,
    pub score: Option<f64>,
    pub seen_ms: i64,
}

/// `sighting_sample_better(a, b)`: higher score (a missing score loses), then smaller effective accuracy, then
/// earlier; an absent `b` always loses.
pub(crate) fn sample_better(a: &Sample, b: Option<&Sample>) -> bool {
    let Some(b) = b else { return true };
    if a.score != b.score {
        return match (a.score, b.score) {
            (Some(x), Some(y)) => x > y,
            (Some(_), None) => true,
            _ => false,
        };
    }
    let eff = |s: &Sample| s.accuracy.unwrap_or(50.0).max(1.0);
    if eff(a) != eff(b) {
        return eff(a) < eff(b);
    }
    a.seen_ms < b.seen_ms
}

/// The exact-under-merge aggregate of one `sighting_minutes` key.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct Minute {
    pub sightings: i64,
    pub first_seen_ms: i64,
    pub last_seen_ms: i64,
    pub rssi_min: Option<i64>,
    pub rssi_max: Option<i64>,
    pub rssi_sum: i64,
    pub rssi_count: i64,
    pub tx_power: Option<i64>,
    pub is_connectable: Option<bool>,
    pub adv_tx_power: Option<i64>,
    pub periodic_interval: Option<i64>,
    pub min_lat: Option<f64>,
    pub max_lat: Option<f64>,
    pub min_lon: Option<f64>,
    pub max_lon: Option<f64>,
    pub best: Option<Sample>,
}

fn opt_min<T: PartialOrd>(a: Option<T>, b: Option<T>) -> Option<T> {
    match (a, b) {
        (Some(x), Some(y)) => Some(if y < x { y } else { x }),
        (x, None) => x,
        (None, y) => y,
    }
}

fn opt_max<T: PartialOrd>(a: Option<T>, b: Option<T>) -> Option<T> {
    match (a, b) {
        (Some(x), Some(y)) => Some(if y > x { y } else { x }),
        (x, None) => x,
        (None, y) => y,
    }
}

impl Minute {
    fn of(o: &Obs) -> Self {
        let rssi = o.rssi.map(i64::from);
        let best = o.fix.map(|(lat, lon, acc)| Sample {
            lat,
            lon,
            accuracy: Some(acc),
            rssi: o.rssi,
            score: sample_score(o.rssi, Some(acc)),
            seen_ms: o.at_ms,
        });
        Self {
            sightings: 1,
            first_seen_ms: o.at_ms,
            last_seen_ms: o.at_ms,
            rssi_min: rssi,
            rssi_max: rssi,
            rssi_sum: rssi.unwrap_or(0),
            rssi_count: i64::from(rssi.is_some()),
            tx_power: o.tx_power.map(i64::from),
            is_connectable: o.connectable,
            adv_tx_power: o.adv_tx_power.map(i64::from),
            periodic_interval: o.periodic_interval.map(i64::from),
            min_lat: o.fix.map(|f| f.0),
            max_lat: o.fix.map(|f| f.0),
            min_lon: o.fix.map(|f| f.1),
            max_lon: o.fix.map(|f| f.1),
            best,
        }
    }

    /// The research merge (`merge_minutes.sql`), so merging a minute in parts equals one pass.
    pub(crate) fn merge(&mut self, other: &Minute) {
        self.sightings += other.sightings;
        self.first_seen_ms = self.first_seen_ms.min(other.first_seen_ms);
        self.last_seen_ms = self.last_seen_ms.max(other.last_seen_ms);
        self.rssi_min = opt_min(self.rssi_min, other.rssi_min);
        self.rssi_max = opt_max(self.rssi_max, other.rssi_max);
        self.rssi_sum += other.rssi_sum;
        self.rssi_count += other.rssi_count;
        self.tx_power = opt_max(self.tx_power, other.tx_power);
        self.is_connectable = match (self.is_connectable, other.is_connectable) {
            (Some(a), Some(b)) => Some(a || b),
            (a, None) => a,
            (None, b) => b,
        };
        self.adv_tx_power = opt_max(self.adv_tx_power, other.adv_tx_power);
        self.periodic_interval = opt_max(self.periodic_interval, other.periodic_interval);
        self.min_lat = opt_min(self.min_lat, other.min_lat);
        self.max_lat = opt_max(self.max_lat, other.max_lat);
        self.min_lon = opt_min(self.min_lon, other.min_lon);
        self.max_lon = opt_max(self.max_lon, other.max_lon);
        if let Some(b) = &other.best
            && sample_better(b, self.best.as_ref())
        {
            self.best = Some(*b);
        }
    }
}

/// `sighting_minutes`' key minus the session; nullable columns stored as −1.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
struct MinuteKey {
    minute: i64,
    addr: i64,
    payload_id: i64,
    addr_type: i64,
    is_legacy: i64,
    primary_phy: i64,
    secondary_phy: i64,
    adv_sid: i64,
    data_status: i64,
}

fn n(v: Option<i32>) -> i64 {
    v.map_or(-1, i64::from)
}

/// §3.2 `tile17`.
pub(crate) fn tile17(lon: f64, lat: f64) -> i64 {
    let (x, y) = tile(lon, lat, 17);
    (x << 17) | y
}

pub(crate) fn tile(lon: f64, lat: f64, z: u32) -> (i64, i64) {
    let scale = (1i64 << z) as f64;
    let x = ((lon + 180.0) / 360.0 * scale).floor() as i64;
    let r = lat.to_radians();
    let y = ((1.0 - (r.tan() + 1.0 / r.cos()).ln() / std::f64::consts::PI) / 2.0 * scale).floor()
        as i64;
    (x.clamp(0, (1 << z) - 1), y.clamp(0, (1 << z) - 1))
}

#[derive(Clone, Debug)]
struct PayloadInfo {
    local_name: Option<String>,
    company_id: Option<u16>,
    is_apple: bool,
}

/// A device's strongest located sighting (M-3): highest RSSI, then earlier.
#[derive(Clone, Copy, Debug, PartialEq)]
struct BestPoint {
    lon: f64,
    lat: f64,
    accuracy: Option<f64>,
    rssi: Option<i64>,
    seen_ms: i64,
}

fn point_better(a: &BestPoint, b: Option<&BestPoint>) -> bool {
    let Some(b) = b else { return true };
    match (a.rssi, b.rssi) {
        (Some(x), Some(y)) if x != y => x > y,
        (Some(_), None) => true,
        (None, Some(_)) => false,
        _ => a.seen_ms < b.seen_ms,
    }
}

pub(crate) struct Store {
    pub conn: Connection,
    payloads: HashMap<[u8; 32], i64>,
    info: HashMap<i64, PayloadInfo>,
    /// Test hook: the next commit fails after writing everything, before `COMMIT`.
    #[cfg(test)]
    pub fail_next_commit: bool,
}

/// The cache entries one commit learned: sha256 → payload id, and payload facts.
type Learned = (HashMap<[u8; 32], i64>, HashMap<i64, PayloadInfo>);

/// Bound of the in-memory sha256 → payload id map (§2.4: an LRU of 50k hashes, then the DB).
const PAYLOAD_CACHE: usize = 50_000;

impl Store {
    pub fn new(conn: Connection) -> Self {
        Self {
            conn,
            payloads: HashMap::new(),
            info: HashMap::new(),
            #[cfg(test)]
            fail_next_commit: false,
        }
    }

    pub fn create_session(&mut self, uuid: &str, started_ms: i64) -> Result<i64, CoreError> {
        self.conn.execute(
            "INSERT INTO sessions (uuid, status, started_ms) VALUES (?1, 0, ?2)",
            params![uuid, started_ms],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn set_status(&mut self, session: i64, status: i64) -> Result<(), CoreError> {
        self.conn.execute(
            "UPDATE sessions SET status = ?2 WHERE id = ?1",
            params![session, status],
        )?;
        Ok(())
    }

    pub fn pause(&mut self, session: i64, from_ms: i64) -> Result<(), CoreError> {
        self.conn.execute(
            "INSERT OR IGNORE INTO session_pauses (session_id, from_ms) VALUES (?1, ?2)",
            params![session, from_ms],
        )?;
        self.set_status(session, 1)
    }

    pub fn resume(&mut self, session: i64, to_ms: i64) -> Result<(), CoreError> {
        self.conn.execute(
            "UPDATE session_pauses SET to_ms = ?2 WHERE session_id = ?1 AND to_ms IS NULL",
            params![session, to_ms],
        )?;
        self.set_status(session, 0)
    }

    /// Finish a session: status 2 (completed) or 3 (interrupted).
    pub fn finish(
        &mut self,
        session: i64,
        status: i64,
        ended_ms: i64,
        active_ms: i64,
    ) -> Result<(), CoreError> {
        self.conn.execute(
            "UPDATE sessions SET status = ?2, ended_ms = ?3, active_ms = ?4 WHERE id = ?1",
            params![session, status, ended_ms, active_ms],
        )?;
        Ok(())
    }

    /// Commits `obs` and `route` of one session in one transaction.
    pub fn commit(
        &mut self,
        session: i64,
        obs: &[Obs],
        route: &[RoutePoint],
        catalog: &Catalog,
    ) -> Result<(), CoreError> {
        if obs.is_empty() && route.is_empty() {
            return Ok(());
        }
        // The caches change only after the transaction commits. A rolled-back transaction frees the ids of the
        // payloads it inserted and SQLite hands them out again, so caching them early would attribute a later
        // payload's rows to the rolled-back one.
        let (fresh_payloads, fresh_info) = self.commit_tx(session, obs, route, catalog)?;
        if self.payloads.len() + fresh_payloads.len() > PAYLOAD_CACHE {
            self.payloads.clear();
        }
        self.payloads.extend(fresh_payloads);
        if self.info.len() + fresh_info.len() > PAYLOAD_CACHE {
            self.info.clear();
        }
        self.info.extend(fresh_info);
        Ok(())
    }

    /// One commit's transaction; returns the cache entries it learned, for the caller to keep once it committed.
    fn commit_tx(
        &mut self,
        session: i64,
        obs: &[Obs],
        route: &[RoutePoint],
        catalog: &Catalog,
    ) -> Result<Learned, CoreError> {
        let tx = self.conn.transaction()?;
        let mut fresh_payloads = HashMap::new();
        let payload_ids = resolve_payloads(&tx, &self.payloads, &mut fresh_payloads, obs, catalog)?;
        // this batch's payload facts: the cache, else the DB (new ones only become cache entries after COMMIT)
        let mut info: HashMap<i64, PayloadInfo> = HashMap::new();
        let mut fresh_info = HashMap::new();
        for id in &payload_ids {
            if info.contains_key(id) {
                continue;
            }
            let known = match self.info.get(id) {
                Some(known) => known.clone(),
                None => {
                    let read = tx.query_row(
                        "SELECT local_name, company_id, is_apple FROM payloads WHERE id = ?1",
                        [id],
                        |r| {
                            Ok(PayloadInfo {
                                local_name: r.get(0)?,
                                company_id: r.get(1)?,
                                is_apple: r.get(2)?,
                            })
                        },
                    )?;
                    fresh_info.insert(*id, read.clone());
                    read
                }
            };
            info.insert(*id, known);
        }
        merge_minutes(&tx, session, obs, &payload_ids)?;
        update_payload_stats(&tx, obs, &payload_ids)?;
        let addrs = merge_session_devices(&tx, session, obs, &payload_ids, &info)?;
        for addr in &addrs {
            refresh_device(&tx, *addr)?;
        }
        insert_route(&tx, session, route)?;
        refresh_session(&tx, session, obs.len() as i64)?;
        #[cfg(test)]
        if std::mem::take(&mut self.fail_next_commit) {
            return Err(CoreError::Db {
                detail: "injected commit failure".into(),
            });
        }
        tx.commit()?;
        Ok((fresh_payloads, fresh_info))
    }
}

/// Payload ids of the batch: the committed cache, then this transaction's new entries, then the DB, else a new
/// payload (recorded in `fresh`).
fn resolve_payloads(
    tx: &Transaction<'_>,
    cache: &HashMap<[u8; 32], i64>,
    fresh: &mut HashMap<[u8; 32], i64>,
    obs: &[Obs],
    catalog: &Catalog,
) -> Result<Vec<i64>, CoreError> {
    let mut out = Vec::with_capacity(obs.len());
    for o in obs {
        let sha: [u8; 32] = Sha256::digest(&o.raw).into();
        if let Some(id) = cache.get(&sha).or_else(|| fresh.get(&sha)) {
            out.push(*id);
            continue;
        }
        let existing: Option<i64> = tx
            .query_row(
                "SELECT id FROM payloads WHERE sha256 = ?1",
                [&sha[..]],
                |r| r.get(0),
            )
            .optional()?;
        let id = match existing {
            Some(id) => id,
            None => insert_payload(tx, &sha, o, catalog)?,
        };
        fresh.insert(sha, id);
        out.push(id);
    }
    Ok(out)
}

/// A new payload: split, insert its structures, match its page once (§3.5), and verify the round trip.
fn insert_payload(
    tx: &Transaction<'_>,
    sha: &[u8; 32],
    o: &Obs,
    catalog: &Catalog,
) -> Result<i64, CoreError> {
    let split = adv::split(&o.raw);
    if adv::join(&split) != o.raw {
        return Err(CoreError::InvalidPayload {
            detail: "advertisement does not round-trip through its structures".into(),
        });
    }
    let fields = adv::payload_fields(&split);
    let structures: Vec<Structure> = crate::matcher::structures(&o.raw);
    let addr = super::keys::addr_of(o.addr);
    let addr_type = match o.addr_type {
        Some(0) => Some(AddrType::Public),
        Some(1) => Some(AddrType::Random),
        _ => None,
    };
    let page = catalog.page_of_payload(&structures, Some(&addr), addr_type);
    tx.execute(
        "INSERT INTO payloads (sha256, zero_padding, tail, local_name, company_id, service_uuid, is_apple, page_id,
           match_key, first_seen_ms, last_seen_ms, sightings)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?10, 0)",
        params![
            &sha[..],
            split.zero_padding,
            split.tail,
            fields.local_name,
            fields.company_id,
            fields.service_uuid,
            fields.is_apple,
            page,
            catalog.rules_key,
            o.at_ms
        ],
    )?;
    let id = tx.last_insert_rowid();
    for (ord, (ad_type, data)) in split.structures.iter().enumerate() {
        tx.execute(
            "INSERT OR IGNORE INTO ad_structures (ad_type, data, local_name, company_id, uuids)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                ad_type,
                data,
                adv::local_name(*ad_type, data),
                adv::company_id(*ad_type, data),
                adv::uuids(*ad_type, data)
            ],
        )?;
        let structure: i64 = tx.query_row(
            "SELECT id FROM ad_structures WHERE ad_type = ?1 AND data = ?2",
            params![ad_type, data],
            |r| r.get(0),
        )?;
        tx.execute(
            "INSERT INTO payload_structures (payload_id, ord, structure_id) VALUES (?1, ?2, ?3)",
            params![id, ord as i64 + 1, structure],
        )?;
    }
    Ok(id)
}

/// `payload_raw(id)`: the exact bytes back from the DB.
pub(crate) fn payload_raw(conn: &Connection, id: i64) -> Result<Vec<u8>, CoreError> {
    let (zero_padding, tail): (u32, Option<Vec<u8>>) = conn.query_row(
        "SELECT zero_padding, tail FROM payloads WHERE id = ?1",
        [id],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    let mut stmt = conn.prepare_cached(
        "SELECT s.ad_type, s.data FROM payload_structures p JOIN ad_structures s ON s.id = p.structure_id
         WHERE p.payload_id = ?1 ORDER BY p.ord",
    )?;
    let structures = stmt
        .query_map([id], |r| Ok((r.get::<_, u8>(0)?, r.get::<_, Vec<u8>>(1)?)))?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(adv::join(&adv::Split {
        structures,
        zero_padding,
        tail,
    }))
}

fn merge_minutes(
    tx: &Transaction<'_>,
    session: i64,
    obs: &[Obs],
    ids: &[i64],
) -> Result<(), CoreError> {
    let mut batch: BTreeMap<MinuteKey, Minute> = BTreeMap::new();
    for (o, &payload_id) in obs.iter().zip(ids) {
        let key = MinuteKey {
            minute: o.at_ms.div_euclid(60_000) * 60_000,
            addr: o.addr,
            payload_id,
            addr_type: n(o.addr_type),
            is_legacy: o.is_legacy.map_or(-1, i64::from),
            primary_phy: n(o.primary_phy),
            secondary_phy: n(o.secondary_phy),
            adv_sid: n(o.adv_sid),
            data_status: n(o.data_status),
        };
        let m = Minute::of(o);
        batch.entry(key).and_modify(|x| x.merge(&m)).or_insert(m);
    }
    let mut select = tx.prepare_cached(
        "SELECT sightings, first_seen_ms, last_seen_ms, rssi_min, rssi_max, rssi_sum, rssi_count, tx_power,
                is_connectable, adv_tx_power, periodic_interval, min_lat, max_lat, min_lon, max_lon,
                best_lat, best_lon, best_accuracy, best_rssi, best_seen_ms, best_score
         FROM sighting_minutes WHERE session_id = ?1 AND minute = ?2 AND addr = ?3 AND payload_id = ?4
           AND addr_type = ?5 AND is_legacy = ?6 AND primary_phy = ?7 AND secondary_phy = ?8 AND adv_sid = ?9
           AND data_status = ?10",
    )?;
    let mut upsert = tx.prepare_cached(
        "INSERT INTO sighting_minutes (session_id, minute, addr, payload_id, addr_type, is_legacy, primary_phy,
           secondary_phy, adv_sid, data_status, sightings, first_seen_ms, last_seen_ms, rssi_min, rssi_max,
           rssi_sum, rssi_count, tx_power, is_connectable, adv_tx_power, periodic_interval, min_lat, max_lat,
           min_lon, max_lon, best_lat, best_lon, best_accuracy, best_rssi, best_seen_ms, best_score)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21,
           ?22, ?23, ?24, ?25, ?26, ?27, ?28, ?29, ?30, ?31)
         ON CONFLICT (session_id, minute, addr, payload_id, addr_type, is_legacy, primary_phy, secondary_phy,
           adv_sid, data_status) DO UPDATE SET
           sightings = excluded.sightings, first_seen_ms = excluded.first_seen_ms,
           last_seen_ms = excluded.last_seen_ms, rssi_min = excluded.rssi_min, rssi_max = excluded.rssi_max,
           rssi_sum = excluded.rssi_sum, rssi_count = excluded.rssi_count, tx_power = excluded.tx_power,
           is_connectable = excluded.is_connectable, adv_tx_power = excluded.adv_tx_power,
           periodic_interval = excluded.periodic_interval, min_lat = excluded.min_lat,
           max_lat = excluded.max_lat, min_lon = excluded.min_lon, max_lon = excluded.max_lon,
           best_lat = excluded.best_lat, best_lon = excluded.best_lon, best_accuracy = excluded.best_accuracy,
           best_rssi = excluded.best_rssi, best_seen_ms = excluded.best_seen_ms, best_score = excluded.best_score",
    )?;
    for (k, mut m) in batch {
        let key = params![
            session,
            k.minute,
            k.addr,
            k.payload_id,
            k.addr_type,
            k.is_legacy,
            k.primary_phy,
            k.secondary_phy,
            k.adv_sid,
            k.data_status
        ];
        let existing = select
            .query_row(key, |r| {
                let best_seen: Option<i64> = r.get(19)?;
                Ok(Minute {
                    sightings: r.get(0)?,
                    first_seen_ms: r.get(1)?,
                    last_seen_ms: r.get(2)?,
                    rssi_min: r.get(3)?,
                    rssi_max: r.get(4)?,
                    rssi_sum: r.get(5)?,
                    rssi_count: r.get(6)?,
                    tx_power: r.get(7)?,
                    is_connectable: r.get(8)?,
                    adv_tx_power: r.get(9)?,
                    periodic_interval: r.get(10)?,
                    min_lat: r.get(11)?,
                    max_lat: r.get(12)?,
                    min_lon: r.get(13)?,
                    max_lon: r.get(14)?,
                    best: match best_seen {
                        None => None,
                        Some(seen_ms) => Some(Sample {
                            lat: r.get(15)?,
                            lon: r.get(16)?,
                            accuracy: r.get(17)?,
                            rssi: r.get(18)?,
                            seen_ms,
                            score: r.get(20)?,
                        }),
                    },
                })
            })
            .optional()?;
        if let Some(mut prev) = existing {
            prev.merge(&m);
            m = prev;
        }
        let b = m.best;
        upsert.execute(params![
            session,
            k.minute,
            k.addr,
            k.payload_id,
            k.addr_type,
            k.is_legacy,
            k.primary_phy,
            k.secondary_phy,
            k.adv_sid,
            k.data_status,
            m.sightings,
            m.first_seen_ms,
            m.last_seen_ms,
            m.rssi_min,
            m.rssi_max,
            m.rssi_sum,
            m.rssi_count,
            m.tx_power,
            m.is_connectable,
            m.adv_tx_power,
            m.periodic_interval,
            m.min_lat,
            m.max_lat,
            m.min_lon,
            m.max_lon,
            b.map(|s| s.lat),
            b.map(|s| s.lon),
            b.and_then(|s| s.accuracy),
            b.and_then(|s| s.rssi),
            b.map(|s| s.seen_ms),
            b.and_then(|s| s.score)
        ])?;
    }
    Ok(())
}

fn update_payload_stats(tx: &Transaction<'_>, obs: &[Obs], ids: &[i64]) -> Result<(), CoreError> {
    let mut stats: HashMap<i64, (i64, i64, i64)> = HashMap::new();
    for (o, id) in obs.iter().zip(ids) {
        let e = stats.entry(*id).or_insert((o.at_ms, o.at_ms, 0));
        e.0 = e.0.min(o.at_ms);
        e.1 = e.1.max(o.at_ms);
        e.2 += 1;
    }
    let mut stmt = tx.prepare_cached(
        "UPDATE payloads SET first_seen_ms = min(first_seen_ms, ?2), last_seen_ms = max(last_seen_ms, ?3),
           sightings = sightings + ?4 WHERE id = ?1",
    )?;
    for (id, (first, last, count)) in stats {
        stmt.execute(params![id, first, last, count])?;
    }
    Ok(())
}

/// §3.5 step 2 over the rows of `filter` (`session_id = ?` or every session of the device).
fn device_page(
    tx: &Transaction<'_>,
    session: Option<i64>,
    addr: i64,
) -> Result<Option<String>, CoreError> {
    let sql =
        "SELECT p.page_id FROM session_device_payloads s JOIN payloads p ON p.id = s.payload_id
               WHERE s.addr = ?1 AND (?2 IS NULL OR s.session_id = ?2) AND p.page_id IS NOT NULL
               GROUP BY s.payload_id ORDER BY sum(s.sightings) DESC, s.payload_id LIMIT 1";
    Ok(tx
        .prepare_cached(sql)?
        .query_row(params![addr, session], |r| r.get(0))
        .optional()?)
}

#[derive(Default)]
struct DeviceBatch {
    first: i64,
    last: i64,
    sightings: i64,
    rssi_max: Option<i64>,
    best: Option<BestPoint>,
    /// (at_ms, name) of the latest named sighting.
    name: Option<(i64, String)>,
    /// (at_ms, payload) of the latest sighting.
    latest: (i64, i64),
    is_apple: bool,
    payloads: HashMap<i64, (i64, i64)>,
}

fn merge_session_devices(
    tx: &Transaction<'_>,
    session: i64,
    obs: &[Obs],
    ids: &[i64],
    info: &HashMap<i64, PayloadInfo>,
) -> Result<Vec<i64>, CoreError> {
    let mut batch: BTreeMap<i64, DeviceBatch> = BTreeMap::new();
    for (o, &pid) in obs.iter().zip(ids) {
        let d = batch.entry(o.addr).or_insert_with(|| DeviceBatch {
            first: o.at_ms,
            last: o.at_ms,
            latest: (o.at_ms, pid),
            ..Default::default()
        });
        d.first = d.first.min(o.at_ms);
        d.last = d.last.max(o.at_ms);
        d.sightings += 1;
        d.rssi_max = opt_max(d.rssi_max, o.rssi.map(i64::from));
        if o.at_ms >= d.latest.0 {
            d.latest = (o.at_ms, pid);
        }
        let p = &info[&pid];
        d.is_apple |= p.is_apple;
        if let Some(name) = &p.local_name
            && d.name.as_ref().is_none_or(|(t, _)| o.at_ms >= *t)
        {
            d.name = Some((o.at_ms, name.clone()));
        }
        if let Some((lat, lon, acc)) = o.fix {
            let point = BestPoint {
                lon,
                lat,
                accuracy: Some(acc),
                rssi: o.rssi.map(i64::from),
                seen_ms: o.at_ms,
            };
            if point_better(&point, d.best.as_ref()) {
                d.best = Some(point);
            }
        }
        let e = d.payloads.entry(pid).or_insert((0, o.at_ms));
        e.0 += 1;
        e.1 = e.1.max(o.at_ms);
    }
    for (&addr, d) in &batch {
        for (pid, (count, last)) in &d.payloads {
            tx.prepare_cached(
                "INSERT INTO session_device_payloads (session_id, addr, payload_id, sightings, last_seen_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5)
                 ON CONFLICT (session_id, addr, payload_id) DO UPDATE SET
                   sightings = sightings + excluded.sightings, last_seen_ms = max(last_seen_ms, excluded.last_seen_ms)",
            )?
            .execute(params![session, addr, pid, count, last])?;
        }
        let prev = tx
            .prepare_cached(
                "SELECT id, name, is_apple, latest_payload_id, first_seen_ms, last_seen_ms, sightings, rssi_max,
                        best_lon, best_lat, best_accuracy, best_rssi, best_seen_ms
                 FROM session_devices WHERE session_id = ?1 AND addr = ?2",
            )?
            .query_row(params![session, addr], |r| {
                let best_seen: Option<i64> = r.get(12)?;
                Ok((
                    r.get::<_, i64>(0)?,
                    r.get::<_, Option<String>>(1)?,
                    r.get::<_, bool>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, i64>(4)?,
                    r.get::<_, i64>(5)?,
                    r.get::<_, i64>(6)?,
                    r.get::<_, Option<i64>>(7)?,
                    match best_seen {
                        None => None,
                        Some(seen_ms) => Some(BestPoint {
                            lon: r.get(8)?,
                            lat: r.get(9)?,
                            accuracy: r.get(10)?,
                            rssi: r.get(11)?,
                            seen_ms,
                        }),
                    },
                ))
            })
            .optional()?;
        let (name, is_apple, latest_payload, first, last, sightings, rssi_max, best) = match &prev {
            None => (
                d.name.as_ref().map(|n| n.1.clone()),
                d.is_apple,
                d.latest.1,
                d.first,
                d.last,
                d.sightings,
                d.rssi_max,
                d.best,
            ),
            Some((_, pname, papple, plat, pfirst, plast, psight, prssi, pbest)) => {
                let best = match (d.best, pbest) {
                    (Some(b), p) if point_better(&b, p.as_ref()) => Some(b),
                    (_, p) => *p,
                };
                (
                    // the latest non-empty name; a later batch never reverts to none
                    d.name
                        .as_ref()
                        .filter(|(t, _)| *t >= *plast || pname.is_none())
                        .map(|n| n.1.clone())
                        .or_else(|| pname.clone()),
                    *papple || d.is_apple,
                    if d.latest.0 >= *plast {
                        d.latest.1
                    } else {
                        *plat
                    },
                    (*pfirst).min(d.first),
                    (*plast).max(d.last),
                    psight + d.sightings,
                    opt_max(*prssi, d.rssi_max),
                    best,
                )
            }
        };
        let company: Option<u16> = info
            .get(&latest_payload)
            .and_then(|p| p.company_id)
            .or_else(|| {
                tx.query_row(
                    "SELECT company_id FROM payloads WHERE id = ?1",
                    [latest_payload],
                    |r| r.get(0),
                )
                .ok()
                .flatten()
            });
        let page = device_page(tx, Some(session), addr)?;
        let tile = best.map(|b| tile17(b.lon, b.lat));
        tx.prepare_cached(
            "INSERT INTO session_devices (session_id, addr, name, company_id, is_apple, page_id, latest_payload_id,
               first_seen_ms, last_seen_ms, sightings, rssi_max, best_lon, best_lat, best_accuracy, best_rssi,
               best_seen_ms, tile17)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17)
             ON CONFLICT (session_id, addr) DO UPDATE SET name = excluded.name, company_id = excluded.company_id,
               is_apple = excluded.is_apple, page_id = excluded.page_id, latest_payload_id = excluded.latest_payload_id,
               first_seen_ms = excluded.first_seen_ms, last_seen_ms = excluded.last_seen_ms,
               sightings = excluded.sightings, rssi_max = excluded.rssi_max, best_lon = excluded.best_lon,
               best_lat = excluded.best_lat, best_accuracy = excluded.best_accuracy, best_rssi = excluded.best_rssi,
               best_seen_ms = excluded.best_seen_ms, tile17 = excluded.tile17",
        )?
        .execute(params![
            session,
            addr,
            name,
            company,
            is_apple,
            page,
            latest_payload,
            first,
            last,
            sightings,
            rssi_max,
            best.map(|b| b.lon),
            best.map(|b| b.lat),
            best.and_then(|b| b.accuracy),
            best.and_then(|b| b.rssi),
            best.map(|b| b.seen_ms),
            tile
        ])?;
        if let Some(b) = best {
            let id: i64 = tx.query_row(
                "SELECT id FROM session_devices WHERE session_id = ?1 AND addr = ?2",
                params![session, addr],
                |r| r.get(0),
            )?;
            tx.prepare_cached(
                "INSERT OR REPLACE INTO session_devices_rtree VALUES (?1, ?2, ?2, ?3, ?3)",
            )?
            .execute(params![id, b.lon, b.lat])?;
        }
    }
    Ok(batch.into_keys().collect())
}

/// Recomputes one `devices` row (All) from the device's `session_devices` rows (§2.1 union rules).
pub(crate) fn refresh_device(tx: &Transaction<'_>, addr: i64) -> Result<(), CoreError> {
    struct Row {
        session: i64,
        name: Option<String>,
        company: Option<i64>,
        is_apple: bool,
        latest_payload: i64,
        first: i64,
        last: i64,
        sightings: i64,
        rssi_max: Option<i64>,
        best: Option<BestPoint>,
    }
    let rows = tx
        .prepare_cached(
            "SELECT session_id, name, company_id, is_apple, latest_payload_id, first_seen_ms, last_seen_ms, sightings,
                    rssi_max, best_lon, best_lat, best_accuracy, best_rssi, best_seen_ms
             FROM session_devices WHERE addr = ?1 ORDER BY session_id",
        )?
        .query_map([addr], |r| {
            let best_seen: Option<i64> = r.get(13)?;
            Ok(Row {
                session: r.get(0)?,
                name: r.get(1)?,
                company: r.get(2)?,
                is_apple: r.get(3)?,
                latest_payload: r.get(4)?,
                first: r.get(5)?,
                last: r.get(6)?,
                sightings: r.get(7)?,
                rssi_max: r.get(8)?,
                best: match best_seen {
                    None => None,
                    Some(seen_ms) => Some(BestPoint {
                        lon: r.get(9)?,
                        lat: r.get(10)?,
                        accuracy: r.get(11)?,
                        rssi: r.get(12)?,
                        seen_ms,
                    }),
                },
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    if rows.is_empty() {
        tx.execute("DELETE FROM devices WHERE addr = ?1", [addr])?;
        tx.execute("DELETE FROM devices_rtree WHERE id = ?1", [addr])?;
        return Ok(());
    }
    let Some(latest) = rows.iter().max_by_key(|r| (r.last, r.session)) else {
        return Ok(());
    };
    let named = rows
        .iter()
        .filter(|r| r.name.is_some())
        .max_by_key(|r| (r.last, r.session));
    let mut best: Option<(&Row, BestPoint)> = None;
    for r in &rows {
        if let Some(b) = r.best
            && point_better(&b, best.as_ref().map(|x| &x.1))
        {
            best = Some((r, b));
        }
    }
    let page = device_page(tx, None, addr)?;
    let b = best.map(|x| x.1);
    tx.prepare_cached(
        "INSERT OR REPLACE INTO devices (addr, name, company_id, is_apple, page_id, latest_payload_id, first_seen_ms,
           last_seen_ms, session_count, sightings, rssi_max, best_lon, best_lat, best_accuracy, best_rssi,
           best_seen_ms, best_session_id, tile17)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)",
    )?
    .execute(params![
        addr,
        named.and_then(|r| r.name.clone()),
        latest.company,
        rows.iter().any(|r| r.is_apple),
        page,
        latest.latest_payload,
        rows.iter().map(|r| r.first).min(),
        rows.iter().map(|r| r.last).max(),
        rows.len() as i64,
        rows.iter().map(|r| r.sightings).sum::<i64>(),
        rows.iter().filter_map(|r| r.rssi_max).max(),
        b.map(|b| b.lon),
        b.map(|b| b.lat),
        b.and_then(|b| b.accuracy),
        b.and_then(|b| b.rssi),
        b.map(|b| b.seen_ms),
        best.map(|x| x.0.session),
        b.map(|b| tile17(b.lon, b.lat))
    ])?;
    match b {
        Some(b) => {
            tx.prepare_cached("INSERT OR REPLACE INTO devices_rtree VALUES (?1, ?2, ?2, ?3, ?3)")?
                .execute(params![addr, b.lon, b.lat])?;
        }
        None => {
            tx.execute("DELETE FROM devices_rtree WHERE id = ?1", [addr])?;
        }
    }
    Ok(())
}

fn insert_route(tx: &Transaction<'_>, session: i64, route: &[RoutePoint]) -> Result<(), CoreError> {
    let mut last: Option<(f64, f64)> = tx
        .query_row(
            "SELECT lat, lon FROM route_points WHERE session_id = ?1 ORDER BY t_ms DESC LIMIT 1",
            [session],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()?;
    let mut distance = 0.0;
    for p in route {
        tx.prepare_cached(
            "INSERT OR REPLACE INTO route_points (session_id, t_ms, lat, lon, accuracy) VALUES (?1, ?2, ?3, ?4, ?5)",
        )?
        .execute(params![session, p.t_ms, p.lat, p.lon, p.accuracy])?;
        if let Some((lat, lon)) = last {
            distance += haversine_m(lat, lon, p.lat, p.lon);
        }
        last = Some((p.lat, p.lon));
    }
    if distance > 0.0 {
        tx.execute(
            "UPDATE sessions SET distance_m = distance_m + ?2 WHERE id = ?1",
            params![session, distance],
        )?;
    }
    Ok(())
}

pub(crate) fn haversine_m(lat1: f64, lon1: f64, lat2: f64, lon2: f64) -> f64 {
    let (p1, p2) = (lat1.to_radians(), lat2.to_radians());
    let (dp, dl) = ((lat2 - lat1).to_radians(), (lon2 - lon1).to_radians());
    let h = (dp / 2.0).sin().powi(2) + p1.cos() * p2.cos() * (dl / 2.0).sin().powi(2);
    2.0 * 6_371_000.0 * h.sqrt().asin()
}

/// Session counters and its bbox (`sessions_rtree`): every device's best point and the route.
fn refresh_session(
    tx: &Transaction<'_>,
    session: i64,
    new_sightings: i64,
) -> Result<(), CoreError> {
    tx.execute(
        "UPDATE sessions SET
           device_count = (SELECT count(*) FROM session_devices WHERE session_id = ?1),
           device_count_shown = (SELECT count(*) FROM session_devices WHERE session_id = ?1 AND shown),
           sightings = sightings + ?2,
           min_lon = (SELECT min(v) FROM (SELECT best_lon v FROM session_devices WHERE session_id = ?1
                                          UNION ALL SELECT lon FROM route_points WHERE session_id = ?1)),
           max_lon = (SELECT max(v) FROM (SELECT best_lon v FROM session_devices WHERE session_id = ?1
                                          UNION ALL SELECT lon FROM route_points WHERE session_id = ?1)),
           min_lat = (SELECT min(v) FROM (SELECT best_lat v FROM session_devices WHERE session_id = ?1
                                          UNION ALL SELECT lat FROM route_points WHERE session_id = ?1)),
           max_lat = (SELECT max(v) FROM (SELECT best_lat v FROM session_devices WHERE session_id = ?1
                                          UNION ALL SELECT lat FROM route_points WHERE session_id = ?1))
         WHERE id = ?1",
        params![session, new_sightings],
    )?;
    let bbox: Option<(f64, f64, f64, f64)> = tx.query_row(
        "SELECT min_lon, max_lon, min_lat, max_lat FROM sessions WHERE id = ?1",
        [session],
        |r| {
            Ok(match (r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?) {
                (Some(a), Some(b), Some(c), Some(d)) => Some((a, b, c, d)),
                _ => None,
            })
        },
    )?;
    if let Some((a, b, c, d)) = bbox {
        tx.execute(
            "INSERT OR REPLACE INTO sessions_rtree VALUES (?1, ?2, ?3, ?4, ?5)",
            params![session, a, b, c, d],
        )?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(score: Option<f64>, accuracy: Option<f64>, seen_ms: i64) -> Sample {
        Sample {
            lat: 0.0,
            lon: 0.0,
            accuracy,
            rssi: None,
            score,
            seen_ms,
        }
    }

    /// Parity with `sighting_sample_better` (db/db/migrations/20261026000000_minute_index_schema.sql).
    #[test]
    fn sample_better_matches_the_research_function() {
        assert!(sample_better(&sample(None, None, 5), None));
        assert!(sample_better(
            &sample(Some(-3.0), None, 5),
            Some(&sample(Some(-4.0), None, 1))
        ));
        assert!(!sample_better(
            &sample(None, None, 1),
            Some(&sample(Some(-9.0), None, 5))
        ));
        assert!(sample_better(
            &sample(Some(-9.0), None, 5),
            Some(&sample(None, None, 1))
        ));
        // equal scores: smaller effective accuracy (null = 50, at least 1), then earlier
        assert!(sample_better(
            &sample(Some(-1.0), Some(10.0), 5),
            Some(&sample(Some(-1.0), None, 1))
        ));
        assert!(!sample_better(
            &sample(Some(-1.0), Some(0.2), 5),
            Some(&sample(Some(-1.0), Some(1.0), 1))
        ));
        assert!(sample_better(
            &sample(Some(-1.0), Some(0.2), 1),
            Some(&sample(Some(-1.0), Some(1.0), 5))
        ));
        assert_eq!(sample_score(Some(-60), Some(10.0)), Some(-3.0 - 2.0));
        assert_eq!(
            sample_score(Some(-60), None),
            Some(-3.0 - 2.0 * 50f64.log10())
        );
        assert_eq!(sample_score(None, Some(1.0)), None);
    }

    fn obs(at_ms: i64, rssi: Option<i16>, fix: Option<(f64, f64, f64)>) -> Obs {
        Obs {
            addr: 1,
            at_ms,
            rssi,
            raw: vec![2, 1, 6],
            tx_power: None,
            connectable: None,
            addr_type: None,
            is_legacy: None,
            primary_phy: None,
            secondary_phy: None,
            adv_sid: None,
            data_status: None,
            adv_tx_power: None,
            periodic_interval: None,
            fix,
        }
    }

    /// Merging a minute in parts equals one pass, in any split.
    #[test]
    fn minute_merge_is_exact_in_parts() {
        let all = [
            obs(1, Some(-70), Some((32.0, -117.0, 5.0))),
            obs(2, None, None),
            obs(3, Some(-50), Some((32.1, -117.1, 40.0))),
            obs(4, Some(-60), Some((32.2, -117.2, 3.0))),
        ];
        let one = all.iter().skip(1).fold(Minute::of(&all[0]), |mut m, o| {
            m.merge(&Minute::of(o));
            m
        });
        for cut in 1..all.len() {
            let mut a = all[..cut]
                .iter()
                .skip(1)
                .fold(Minute::of(&all[0]), |mut m, o| {
                    m.merge(&Minute::of(o));
                    m
                });
            let b = all[cut + 1..]
                .iter()
                .fold(Minute::of(&all[cut]), |mut m, o| {
                    m.merge(&Minute::of(o));
                    m
                });
            a.merge(&b);
            assert_eq!(a, one, "cut at {cut}");
        }
        assert_eq!(one.sightings, 4);
        assert_eq!(
            (one.rssi_min, one.rssi_max, one.rssi_sum, one.rssi_count),
            (Some(-70), Some(-50), -180, 3)
        );
        assert_eq!(one.best.unwrap().seen_ms, 4);
    }

    #[test]
    fn tiles() {
        assert_eq!(tile(0.0, 0.0, 1), (1, 1));
        let t = tile17(-117.234, 32.88);
        assert_eq!(t >> 17, tile(-117.234, 32.88, 17).0);
    }
}
