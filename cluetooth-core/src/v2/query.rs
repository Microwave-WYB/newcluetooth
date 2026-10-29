//! The read side (`docs/v2-api.md` §2.1, §2.5, §3.6, §3.7): scope resolution, the device relation `D(scope)`,
//! the filter predicate `F`, and the queries over committed data.

use std::collections::{BTreeMap, HashMap};
use std::rc::Rc;

use rusqlite::types::Value;
use rusqlite::{Connection, OptionalExtension, ToSql};

use super::catalog::{Catalog, PageSet, UNIDENTIFIED, WIKI_URL};
use super::error::CoreError;
use super::keys::addr_of;
use super::types::*;

/// A session row the scope resolution looks at.
#[derive(Clone, Debug)]
pub(crate) struct SessionRow {
    pub id: i64,
    pub uuid: String,
    pub status: i64,
    pub started_ms: i64,
    pub ended_ms: Option<i64>,
    pub active_ms: i64,
    pub distance_m: f64,
    pub device_count: i64,
    pub device_count_shown: i64,
}

/// What the current recording looks like to a query.
#[derive(Clone, Debug)]
pub(crate) struct Live {
    pub session: i64,
    pub uuid: String,
    pub stale_before_ms: i64,
    pub seq: u64,
}

/// The query context: the catalog, the display filter and the current recording.
pub(crate) struct Ctx<'a> {
    pub catalog: &'a Catalog,
    pub hide_unnamed_apple: bool,
    pub live: Option<Live>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum Rel {
    All,
    One(i64),
    Union(Vec<i64>),
    Empty,
}

/// A resolved scope (§2.1).
#[derive(Clone, Debug)]
pub(crate) struct Resolved {
    pub rel: Rel,
    pub sessions: Vec<SessionRow>,
    pub live: bool,
    /// One session asked for by id: one route with Start and End; otherwise every route faint.
    pub single: bool,
}

pub(crate) fn session_rows(conn: &Connection) -> Result<Vec<SessionRow>, CoreError> {
    let mut stmt = conn.prepare_cached(
        "SELECT id, uuid, status, started_ms, ended_ms, active_ms, distance_m, device_count, device_count_shown
         FROM sessions ORDER BY started_ms DESC, id DESC",
    )?;
    let rows = stmt
        .query_map([], |r| {
            Ok(SessionRow {
                id: r.get(0)?,
                uuid: r.get(1)?,
                status: r.get(2)?,
                started_ms: r.get(3)?,
                ended_ms: r.get(4)?,
                active_ms: r.get(5)?,
                distance_m: r.get(6)?,
                device_count: r.get(7)?,
                device_count_shown: r.get(8)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(rows)
}

/// `sessions ∩ filter`; empty sessions never take part, except the live scope, which is the current session even
/// before its first device. Unknown ids are ignored.
pub(crate) fn resolve(conn: &Connection, ctx: &Ctx<'_>, scope: &Scope) -> Result<Resolved, CoreError> {
    let rows = session_rows(conn)?;
    if let (Some(live), SessionScope::Sessions { ids }, None) = (&ctx.live, &scope.sessions, &scope.filter)
        && ids.len() == 1
        && ids[0] == live.uuid
    {
        let sessions = rows.into_iter().filter(|s| s.id == live.session).collect();
        return Ok(Resolved {
            rel: Rel::One(live.session),
            sessions,
            live: true,
            single: true,
        });
    }
    let f = scope.filter.clone().unwrap_or_default();
    let keep = |s: &SessionRow| {
        s.device_count > 0
            && f.started_from_ms.is_none_or(|t| s.started_ms >= t)
            && f.started_to_ms.is_none_or(|t| s.started_ms <= t)
    };
    let (picked, all_unfiltered, single): (Vec<SessionRow>, bool, bool) = match &scope.sessions {
        SessionScope::All => (
            rows.into_iter().filter(keep).collect(),
            scope.filter.is_none(),
            false,
        ),
        SessionScope::Sessions { ids } => (
            rows.into_iter().filter(|s| ids.contains(&s.uuid) && keep(s)).collect(),
            false,
            ids.len() == 1,
        ),
    };
    let rel = if all_unfiltered {
        Rel::All
    } else {
        match picked.len() {
            0 => Rel::Empty,
            1 => Rel::One(picked[0].id),
            _ => Rel::Union(picked.iter().map(|s| s.id).collect()),
        }
    };
    Ok(Resolved {
        rel,
        sessions: picked,
        live: false,
        single,
    })
}

const D_COLUMNS: &str =
    "addr, name, is_apple, page_id, rssi_max, best_lon, best_lat, tile17, last_seen_ms, latest_payload_id";

/// The `WITH … d AS (…)` prefix that defines `D(scope)` (§3.6), plus its parameters.
fn relation(r: &Resolved, params: &mut Params) -> String {
    match &r.rel {
        Rel::All => format!("WITH d AS (SELECT {D_COLUMNS} FROM devices)"),
        Rel::One(id) => format!("WITH d AS (SELECT {D_COLUMNS} FROM session_devices WHERE session_id = {id})"),
        Rel::Empty => format!("WITH d AS (SELECT {D_COLUMNS} FROM devices WHERE 0)"),
        Rel::Union(ids) => {
            params.push(":ids", Rc::new(ids.iter().map(|i| Value::from(*i)).collect::<Vec<_>>()));
            // §3.6 U(:ids): the strongest RSSI and the strongest located point are taken separately (§2.1).
            "WITH sd AS (SELECT * FROM session_devices WHERE session_id IN rarray(:ids)),
              bp AS (SELECT addr, best_lon, best_lat, tile17,
                       row_number() OVER (PARTITION BY addr ORDER BY best_rssi DESC, best_seen_ms) r
                     FROM sd WHERE best_lat IS NOT NULL),
              nm AS (SELECT addr, name, row_number() OVER (PARTITION BY addr ORDER BY last_seen_ms DESC) r
                     FROM sd WHERE name IS NOT NULL),
              lp AS (SELECT addr, latest_payload_id,
                       row_number() OVER (PARTITION BY addr ORDER BY last_seen_ms DESC) r FROM sd),
              pg AS (SELECT addr, page_id, row_number() OVER (PARTITION BY addr ORDER BY n DESC, payload_id) r
                     FROM (SELECT s.addr, s.payload_id, p.page_id, sum(s.sightings) n
                           FROM session_device_payloads s JOIN payloads p ON p.id = s.payload_id
                           WHERE s.session_id IN rarray(:ids) AND p.page_id IS NOT NULL
                           GROUP BY s.addr, s.payload_id)),
              u AS (SELECT addr, max(rssi_max) rssi_max, max(is_apple) is_apple, max(last_seen_ms) last_seen_ms
                    FROM sd GROUP BY addr),
              d AS (SELECT u.addr, nm.name, u.is_apple, pg.page_id, u.rssi_max, bp.best_lon, bp.best_lat,
                      bp.tile17, u.last_seen_ms, lp.latest_payload_id
                    FROM u LEFT JOIN nm ON nm.addr = u.addr AND nm.r = 1
                    LEFT JOIN bp ON bp.addr = u.addr AND bp.r = 1
                    LEFT JOIN pg ON pg.addr = u.addr AND pg.r = 1
                    LEFT JOIN lp ON lp.addr = u.addr AND lp.r = 1)"
                .to_owned()
        }
    }
}

/// Named SQL parameters, owned.
#[derive(Default)]
pub(crate) struct Params(Vec<(&'static str, Box<dyn ToSql>)>);

impl Params {
    fn push(&mut self, name: &'static str, value: impl ToSql + 'static) {
        self.0.push((name, Box::new(value)));
    }
    fn refs(&self) -> Vec<(&str, &dyn ToSql)> {
        self.0.iter().map(|(n, v)| (*n, v.as_ref())).collect()
    }
}

/// `F` (§3.6) as a SQL condition on `d`. `categories = false` is `F'` (faceted counts).
fn predicate(ctx: &Ctx<'_>, r: &Resolved, f: &DeviceFilter, categories: bool, params: &mut Params) -> String {
    let mut terms: Vec<String> = Vec::new();
    if ctx.hide_unnamed_apple {
        terms.push("NOT (d.is_apple AND d.name IS NULL)".into());
    }
    match ctx.catalog.page_set(f, categories) {
        PageSet::Any => {}
        PageSet::Unidentified => terms.push("d.page_id IS NULL".into()),
        PageSet::Nothing => terms.push("0".into()),
        PageSet::Only(set) => {
            params.push(":pages", Rc::new(set.into_iter().map(Value::from).collect::<Vec<_>>()));
            terms.push("d.page_id IN rarray(:pages)".into());
        }
    }
    if let Some(b) = f.area {
        push_bbox(params, ":aw", ":as", ":ae", ":an", b);
        terms.push(
            "d.best_lat IS NOT NULL AND d.best_lon BETWEEN :aw AND :ae AND d.best_lat BETWEEN :as AND :an".into(),
        );
    }
    if f.in_range_only && r.live {
        terms.push(in_range_sql(ctx, r, params));
    }
    if let Some(q) = &f.query {
        params.push(":q", q.clone());
        terms.push("match_device(:q, d.name, d.addr)".into());
    }
    if terms.is_empty() {
        "1".into()
    } else {
        terms.join(" AND ")
    }
}

fn push_bbox(params: &mut Params, w: &'static str, s: &'static str, e: &'static str, n: &'static str, b: Bbox) {
    params.push(w, b.west);
    params.push(s, b.south);
    params.push(e, b.east);
    params.push(n, b.north);
}

/// Live: not stale; frozen: always in range.
fn in_range_sql(ctx: &Ctx<'_>, r: &Resolved, params: &mut Params) -> String {
    match (&ctx.live, r.live) {
        (Some(live), true) => {
            params.push(":fresh", live.stale_before_ms);
            "(d.last_seen_ms >= :fresh)".into()
        }
        _ => "1".into(),
    }
}

fn seq_of(ctx: &Ctx<'_>, r: &Resolved) -> u64 {
    if r.live { ctx.live.as_ref().map_or(0, |l| l.seq) } else { 0 }
}

fn pack(keys: &[i64]) -> Vec<u8> {
    keys.iter().flat_map(|k| k.to_le_bytes()).collect()
}

fn keys_query(
    conn: &Connection,
    ctx: &Ctx<'_>,
    r: &Resolved,
    f: Option<&DeviceFilter>,
) -> Result<DeviceKeys, CoreError> {
    let mut params = Params::default();
    let with = relation(r, &mut params);
    let filter = match f {
        Some(f) => predicate(ctx, r, f, true, &mut params),
        None => predicate(ctx, r, &DeviceFilter::default(), true, &mut params),
    };
    let sql = format!("{with} SELECT d.addr FROM d WHERE {filter} ORDER BY d.rssi_max DESC, d.addr");
    let mut stmt = conn.prepare(&sql)?;
    let keys = stmt
        .query_map(params.refs().as_slice(), |row| row.get::<_, i64>(0))?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(DeviceKeys {
        le_i64: pack(&keys),
        seq: seq_of(ctx, r),
    })
}

/// G-9 `device_order`: every shown device, sorted once by RSSI desc, ties by key.
pub(crate) fn device_order(conn: &Connection, ctx: &Ctx<'_>, scope: &Scope) -> Result<DeviceKeys, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    keys_query(conn, ctx, &r, None)
}

/// D-3/D-4 `device_keys`: keys passing the filter, in `device_order` order.
pub(crate) fn device_keys(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    filter: &DeviceFilter,
) -> Result<DeviceKeys, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    keys_query(conn, ctx, &r, Some(filter))
}

/// Q-1/Q-2: `None` for a blank query; search ignores the filters (D-3a), only `{query, area}`.
pub(crate) fn search(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    query: &str,
    area: Option<Bbox>,
) -> Result<Option<DeviceKeys>, CoreError> {
    if super::search::is_blank(query) {
        return Ok(None);
    }
    let f = DeviceFilter {
        area,
        query: Some(query.to_owned()),
        ..Default::default()
    };
    device_keys(conn, ctx, scope, &f).map(Some)
}

struct DRow {
    addr: i64,
    name: Option<String>,
    page_id: Option<String>,
    rssi_max: Option<i64>,
    best: Option<GeoPoint>,
    last_seen_ms: i64,
    latest_payload_id: i64,
    in_range: bool,
}

fn d_rows(
    conn: &Connection,
    ctx: &Ctx<'_>,
    r: &Resolved,
    extra: &str,
    params: &mut Params,
) -> Result<Vec<DRow>, CoreError> {
    let with = relation(r, params);
    let in_range = in_range_sql(ctx, r, params);
    let sql = format!(
        "{with} SELECT d.addr, d.name, d.page_id, d.rssi_max, d.best_lon, d.best_lat, d.last_seen_ms,
                d.latest_payload_id, {in_range} FROM d WHERE {extra}"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt
        .query_map(params.refs().as_slice(), |row| {
            let lon: Option<f64> = row.get(4)?;
            let lat: Option<f64> = row.get(5)?;
            Ok(DRow {
                addr: row.get(0)?,
                name: row.get(1)?,
                page_id: row.get(2)?,
                rssi_max: row.get(3)?,
                best: lon.zip(lat).map(|(lon, lat)| GeoPoint { lon, lat }),
                last_seen_ms: row.get(6)?,
                latest_payload_id: row.get(7)?,
                in_range: row.get(8)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(rows)
}

fn rssi(v: Option<i64>) -> Option<i16> {
    v.and_then(|x| i16::try_from(x).ok())
}

pub(crate) const MAX_ROWS_PER_CALL: usize = 200;

/// `device_rows`: ≤ 200 keys, rows in the given order (unknown keys are skipped).
pub(crate) fn device_rows(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    keys: &[i64],
) -> Result<Vec<DeviceRow>, CoreError> {
    if keys.len() > MAX_ROWS_PER_CALL {
        return Err(CoreError::InvalidConfig {
            detail: format!("at most {MAX_ROWS_PER_CALL} keys per call"),
        });
    }
    let r = resolve(conn, ctx, scope)?;
    let mut params = Params::default();
    params.push(":keys", Rc::new(keys.iter().map(|k| Value::from(*k)).collect::<Vec<_>>()));
    let rows = d_rows(conn, ctx, &r, "d.addr IN rarray(:keys)", &mut params)?;
    let mut by_key: HashMap<i64, DRow> = rows.into_iter().map(|d| (d.addr, d)).collect();
    Ok(keys
        .iter()
        .filter_map(|k| by_key.remove(k))
        .map(|d| device_row(ctx, d))
        .collect())
}

fn device_row(ctx: &Ctx<'_>, d: DRow) -> DeviceRow {
    let page = d.page_id.as_deref().and_then(|id| ctx.catalog.page(id));
    DeviceRow {
        key: d.addr,
        addr: addr_of(d.addr),
        name: d.name,
        page: page.map(|p| p.page_ref()),
        categories: page.map(|p| p.categories.clone()).unwrap_or_default(),
        traits: page.map(|p| p.traits.clone()).unwrap_or_default(),
        readable: page.is_some_and(|p| p.readable),
        vulnerable: page.is_some_and(|p| p.vulnerable()),
        icon: page.and_then(|p| p.icon.as_ref().map(|i| i.asset.clone())),
        rssi: rssi(d.rssi_max),
        in_range: d.in_range,
        spark: None,
        best: d.best,
        last_seen_ms: d.last_seen_ms,
    }
}

/// `category_counts` (D-3a): `F'` grouped by page, folded into the page's categories; `device_count` desc,
/// then label.
pub(crate) fn category_counts(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    filter: &DeviceFilter,
) -> Result<Vec<CategoryCount>, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    let mut params = Params::default();
    let with = relation(&r, &mut params);
    let f = predicate(ctx, &r, filter, false, &mut params);
    let sql = format!("{with} SELECT d.page_id, count(*) FROM d WHERE {f} AND d.page_id IS NOT NULL GROUP BY 1");
    let mut stmt = conn.prepare(&sql)?;
    let mut counts: BTreeMap<String, u32> = BTreeMap::new();
    let rows = stmt.query_map(params.refs().as_slice(), |row| {
        Ok((row.get::<_, String>(0)?, row.get::<_, u32>(1)?))
    })?;
    for row in rows {
        let (page, n) = row?;
        if let Some(p) = ctx.catalog.page(&page) {
            let mut seen: Vec<&String> = Vec::new();
            for c in &p.categories {
                if !seen.contains(&c) {
                    seen.push(c);
                    *counts.entry(c.clone()).or_default() += n;
                }
            }
        }
    }
    let mut out: Vec<CategoryCount> = counts
        .into_iter()
        .map(|(category, device_count)| CategoryCount {
            label: ctx.catalog.category_title(&category),
            category,
            device_count,
        })
        .collect();
    out.sort_by(|a, b| b.device_count.cmp(&a.device_count).then_with(|| a.label.cmp(&b.label)));
    Ok(out)
}

struct PageAgg {
    count: u32,
    in_range: u32,
    last_seen_ms: i64,
}

fn page_aggregates(
    conn: &Connection,
    ctx: &Ctx<'_>,
    r: &Resolved,
    filter: &DeviceFilter,
) -> Result<Vec<(Option<String>, PageAgg)>, CoreError> {
    let mut params = Params::default();
    let with = relation(r, &mut params);
    let f = predicate(ctx, r, filter, true, &mut params);
    let in_range = in_range_sql(ctx, r, &mut params);
    let sql = format!(
        "{with} SELECT d.page_id, count(*), sum({in_range}), max(d.last_seen_ms) FROM d WHERE {f} GROUP BY 1"
    );
    let mut stmt = conn.prepare(&sql)?;
    let rows = stmt
        .query_map(params.refs().as_slice(), |row| {
            Ok((
                row.get::<_, Option<String>>(0)?,
                PageAgg {
                    count: row.get(1)?,
                    in_range: row.get(2)?,
                    last_seen_ms: row.get(3)?,
                },
            ))
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(rows)
}

/// D-2 `products`: groups by the page's first category, by device count desc (ties in vocabulary order), cards in
/// bundle page order, "unidentified" last as one card; `filter.page` is ignored.
pub(crate) fn products(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    filter: &DeviceFilter,
) -> Result<Vec<CategoryGroup>, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    let f = DeviceFilter {
        page: None,
        ..filter.clone()
    };
    let mut groups: BTreeMap<String, Vec<ProductCard>> = BTreeMap::new();
    let mut unidentified = None;
    for (page_id, agg) in page_aggregates(conn, ctx, &r, &f)? {
        let page = page_id.as_deref().and_then(|id| ctx.catalog.page(id));
        let card = ProductCard {
            id: page.map_or_else(|| UNIDENTIFIED.to_owned(), |p| p.id.clone()),
            title: page.map_or_else(|| "Unidentified devices".to_owned(), |p| p.title.clone()),
            kind: page.map(|p| p.kind),
            icon: page.and_then(|p| p.icon.as_ref().map(|i| i.asset.clone())),
            live: r.live,
            count: agg.count,
            in_range: agg.in_range,
            last_seen_ms: agg.last_seen_ms,
        };
        match page.and_then(|p| p.categories.first()) {
            Some(category) => groups.entry(category.clone()).or_default().push(card),
            None if page.is_none() => unidentified = Some(card),
            // a matched page without categories (identifier pages) shows with the unidentified card's group
            None => groups.entry(UNIDENTIFIED.to_owned()).or_default().push(card),
        }
    }
    let mut out: Vec<CategoryGroup> = groups
        .into_iter()
        .filter(|(c, _)| c != UNIDENTIFIED)
        .map(|(category, mut products)| {
            products.sort_by_key(|p| ctx.catalog.order(&p.id));
            CategoryGroup {
                label: ctx.catalog.category_title(&category),
                category,
                products,
            }
        })
        .collect();
    out.sort_by(|a, b| {
        let n = |g: &CategoryGroup| g.products.iter().map(|p| p.count).sum::<u32>();
        n(b).cmp(&n(a))
            .then_with(|| ctx.catalog.category_order(&a.category).cmp(&ctx.catalog.category_order(&b.category)))
    });
    if let Some(card) = unidentified {
        out.push(CategoryGroup {
            category: UNIDENTIFIED.into(),
            label: "Unidentified".into(),
            products: vec![card],
        });
    }
    Ok(out)
}

/// D-6/D-7 `product`: a `None` page is the Unidentified devices card; an unknown page id is `None`.
pub(crate) fn product(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    page_id: Option<&str>,
    filter: &DeviceFilter,
) -> Result<Option<ProductDetail>, CoreError> {
    let page = match page_id {
        Some(id) => match ctx.catalog.page(id) {
            Some(p) => Some(p),
            None => return Ok(None),
        },
        None => None,
    };
    let r = resolve(conn, ctx, scope)?;
    let mut params = Params::default();
    let with = relation(&r, &mut params);
    let f = predicate(ctx, &r, &DeviceFilter { page: None, ..filter.clone() }, true, &mut params);
    let page_term = match page_id {
        Some(id) => {
            params.push(":page", id.to_owned());
            "d.page_id = :page"
        }
        None => "d.page_id IS NULL",
    };
    let sql = format!(
        "{with} SELECT count(*), count(DISTINCT d.tile17), max(d.last_seen_ms) FROM d WHERE {f} AND {page_term}"
    );
    let (device_count, places, last_seen_ms): (u32, u32, Option<i64>) =
        conn.query_row(&sql, params.refs().as_slice(), |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)))?;
    let vulnerabilities = page
        .map(|p| {
            p.vulnerabilities
                .iter()
                .filter_map(|v| ctx.catalog.page(v).map(|v| v.page_ref()))
                .collect()
        })
        .unwrap_or_default();
    Ok(Some(ProductDetail {
        page: page.map(|p| p.page_ref()),
        title: page.map_or_else(|| "Unidentified devices".to_owned(), |p| p.title.clone()),
        one_line: page.map_or_else(|| Some("Devices no wiki family matches yet.".to_owned()), |p| p.one_line.clone()),
        categories: page.map(|p| p.categories.clone()).unwrap_or_default(),
        traits: page.map(|p| p.traits.clone()).unwrap_or_default(),
        readable: page.is_some_and(|p| p.readable),
        vulnerabilities,
        wiki_url: page.map(|p| format!("{WIKI_URL}{}", p.id)),
        icon: page.and_then(|p| p.icon.as_ref().map(|i| i.asset.clone())),
        icon_credit: page.and_then(|p| {
            p.icon.as_ref().map(|i| IconCredit {
                source: i.source.clone(),
                license: i.license.clone(),
                author: i.author.clone(),
            })
        }),
        places,
        last_seen_ms,
        device_count,
    }))
}

/// D-9/D-12 `device`: the `D` row, its latest payload's structures, and that payload's latest minute.
pub(crate) fn device(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    key: i64,
) -> Result<Option<DeviceDetail>, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    let mut params = Params::default();
    params.push(":key", key);
    let Some(d) = d_rows(conn, ctx, &r, "d.addr = :key", &mut params)?.into_iter().next() else {
        return Ok(None);
    };
    let (company_id, service_uuid, tail): (Option<u16>, Option<String>, Option<Vec<u8>>) = conn.query_row(
        "SELECT company_id, service_uuid, tail FROM payloads WHERE id = ?1",
        [d.latest_payload_id],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
    )?;
    let structures = conn
        .prepare_cached(
            "SELECT s.ad_type, s.data FROM payload_structures p JOIN ad_structures s ON s.id = p.structure_id
             WHERE p.payload_id = ?1 ORDER BY p.ord",
        )?
        .query_map([d.latest_payload_id], |row| {
            let data: Vec<u8> = row.get(1)?;
            Ok(AdStructure {
                len: (data.len() + 1) as u8,
                ad_type: row.get(0)?,
                data,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    let ids: Vec<Value> = r.sessions.iter().map(|s| Value::from(s.id)).collect();
    let minute: Option<(Option<bool>, Option<i64>, i64)> = conn
        .query_row(
            "SELECT is_connectable, tx_power, is_legacy FROM sighting_minutes
             WHERE addr = ?1 AND payload_id = ?2 AND session_id IN rarray(?3) ORDER BY last_seen_ms DESC LIMIT 1",
            rusqlite::params![key, d.latest_payload_id, Rc::new(ids)],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    let page = d.page_id.as_deref().and_then(|id| ctx.catalog.page(id));
    let (connectable, tx_power, legacy) = minute.unwrap_or((None, None, -1));
    Ok(Some(DeviceDetail {
        key,
        addr: addr_of(key),
        name: d.name,
        page: page.map(|p| p.page_ref()),
        categories: page.map(|p| p.categories.clone()).unwrap_or_default(),
        traits: page.map(|p| p.traits.clone()).unwrap_or_default(),
        readable: page.is_some_and(|p| p.readable),
        vulnerable: page.is_some_and(|p| p.vulnerable()),
        vulnerabilities: page
            .map(|p| {
                p.vulnerabilities
                    .iter()
                    .filter_map(|v| ctx.catalog.page(v).map(|v| v.page_ref()))
                    .collect()
            })
            .unwrap_or_default(),
        wiki_url: page.map(|p| format!("{WIKI_URL}{}", p.id)),
        icon: page.and_then(|p| p.icon.as_ref().map(|i| i.asset.clone())),
        rssi: rssi(d.rssi_max),
        in_range: d.in_range,
        live: r.live,
        connectable,
        format: match legacy {
            1 => AdvFormat::Legacy,
            0 => AdvFormat::Extended,
            _ => AdvFormat::Unknown,
        },
        company_id,
        company_name: company_id.and_then(|c| ctx.catalog.company_name(c)),
        service_uuid,
        tx_power: tx_power.and_then(|t| i16::try_from(t).ok()),
        structures,
        tail,
        decoded: None,
        last_seen_ms: d.last_seen_ms,
    }))
}

/// `device_sightings`: the best located sample of each of the device's minutes in scope, newest first, ≤ 10 000.
pub(crate) fn device_sightings(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    key: i64,
) -> Result<Vec<Sighting>, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    let ids: Vec<Value> = r.sessions.iter().map(|s| Value::from(s.id)).collect();
    let mut stmt = conn.prepare_cached(
        "SELECT best_lon, best_lat, max(best_rssi), best_seen_ms FROM sighting_minutes
         WHERE addr = ?1 AND session_id IN rarray(?2) AND best_lat IS NOT NULL AND best_rssi IS NOT NULL
         GROUP BY session_id, minute ORDER BY minute DESC LIMIT 10000",
    )?;
    let rows = stmt
        .query_map(rusqlite::params![key, Rc::new(ids)], |row| {
            Ok(Sighting {
                at: GeoPoint {
                    lon: row.get(0)?,
                    lat: row.get(1)?,
                },
                rssi: row.get(2)?,
                seen_ms: row.get(3)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(rows)
}

/// §3.7: the viewport padded by a quarter of its width and height on each side.
pub(crate) fn grow(b: Bbox) -> Bbox {
    let dx = (b.east - b.west) / 4.0;
    let dy = (b.north - b.south) / 4.0;
    Bbox {
        west: b.west - dx,
        south: b.south - dy,
        east: b.east + dx,
        north: b.north + dy,
    }
}

pub(crate) const POINTS_CAP: u32 = 10_000;

fn abbreviated(n: u32) -> String {
    match n {
        n if n >= 1_000_000 => format!("{}M", n / 1_000_000),
        n if n >= 10_000 => format!("{}k", n / 1_000),
        n if n >= 1_000 => {
            let s = format!("{:.1}k", f64::from(n) / 1000.0);
            s.replace(".0k", "k")
        }
        n => n.to_string(),
    }
}

/// M-2/M-3 `map_points` (§3.7): only the padded viewport, never the scope. Points `{addr, rssi}` (`addr` in the
/// uppercase colon form) up to the cap, else Cells `{point_count, point_count_abbreviated}`.
pub(crate) fn map_points(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    filter: &DeviceFilter,
    viewport: Bbox,
) -> Result<MapData, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    let view = grow(viewport);
    let mut params = Params::default();
    let with = relation(&r, &mut params);
    let f = predicate(ctx, &r, filter, true, &mut params);
    push_bbox(&mut params, ":vw", ":vs", ":ve", ":vn", view);
    let inside = "d.best_lat IS NOT NULL AND d.best_lon BETWEEN :vw AND :ve AND d.best_lat BETWEEN :vs AND :vn";
    let count_sql = format!("{with} SELECT count(*) FROM (SELECT 1 FROM d WHERE {f} AND {inside} LIMIT 10001)");
    let count: u32 = conn.query_row(&count_sql, params.refs().as_slice(), |row| row.get(0))?;
    let seq = seq_of(ctx, &r);
    if count <= POINTS_CAP {
        let sql = format!(
            "{with} SELECT d.addr, d.rssi_max, d.best_lon, d.best_lat FROM d WHERE {f} AND {inside}
             ORDER BY d.rssi_max DESC, d.addr"
        );
        let mut stmt = conn.prepare(&sql)?;
        let mut json = String::from(r#"{"type":"FeatureCollection","features":["#);
        let mut n = 0u32;
        let mut rows = stmt.query(params.refs().as_slice())?;
        while let Some(row) = rows.next()? {
            if n > 0 {
                json.push(',');
            }
            let rssi: Option<i64> = row.get(1)?;
            json.push_str(&format!(
                r#"{{"type":"Feature","properties":{{"addr":"{}","rssi":{}}},"geometry":{{"type":"Point","coordinates":[{},{}]}}}}"#,
                addr_of(row.get(0)?),
                rssi.map_or_else(|| "null".to_owned(), |v| v.to_string()),
                row.get::<_, f64>(2)?,
                row.get::<_, f64>(3)?
            ));
            n += 1;
        }
        json.push_str("]}");
        return Ok(MapData {
            mode: MapMode::Points,
            count: n,
            geojson: json,
            seq,
        });
    }
    // Cells: the finest tile level whose cell is at least 1/16 of the viewport width, over this scope's devices.
    let width = (viewport.east - viewport.west).max(1e-9);
    let z = ((360.0 * 16.0 / width).log2().floor() as i64).clamp(0, 17);
    let shift = 17 - z;
    let sql = format!(
        "{with} SELECT count(*), avg(d.best_lon), avg(d.best_lat) FROM d WHERE {f} AND {inside}
         GROUP BY (d.tile17 >> 17) >> {shift}, (d.tile17 & 131071) >> {shift}"
    );
    let mut stmt = conn.prepare(&sql)?;
    let mut json = String::from(r#"{"type":"FeatureCollection","features":["#);
    let mut total = 0u32;
    let mut rows = stmt.query(params.refs().as_slice())?;
    let mut first = true;
    while let Some(row) = rows.next()? {
        let n: u32 = row.get(0)?;
        total += n;
        if !first {
            json.push(',');
        }
        first = false;
        json.push_str(&format!(
            r#"{{"type":"Feature","properties":{{"point_count":{n},"point_count_abbreviated":"{}"}},"geometry":{{"type":"Point","coordinates":[{},{}]}}}}"#,
            abbreviated(n),
            row.get::<_, f64>(1)?,
            row.get::<_, f64>(2)?
        ));
    }
    json.push_str("]}");
    Ok(MapData {
        mode: MapMode::Cells,
        count: total,
        geojson: json,
        seq,
    })
}

fn route_of(conn: &Connection, session: i64) -> Result<Vec<GeoPoint>, CoreError> {
    let mut stmt = conn.prepare_cached("SELECT lon, lat FROM route_points WHERE session_id = ?1 ORDER BY t_ms")?;
    let pts = stmt
        .query_map([session], |row| {
            Ok(GeoPoint {
                lon: row.get(0)?,
                lat: row.get(1)?,
            })
        })?
        .collect::<Result<Vec<_>, _>>()?;
    Ok(pts)
}

fn line(points: &[GeoPoint]) -> String {
    let coords: Vec<String> = points.iter().map(|p| format!("[{},{}]", p.lon, p.lat)).collect();
    format!(
        r#"{{"type":"Feature","properties":{{}},"geometry":{{"type":"LineString","coordinates":[{}]}}}}"#,
        coords.join(",")
    )
}

/// `routes`: one session's route, or every resolved session's route that meets the viewport.
pub(crate) fn routes(
    conn: &Connection,
    ctx: &Ctx<'_>,
    scope: &Scope,
    viewport: Option<Bbox>,
) -> Result<String, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    let mut lines = Vec::new();
    for s in &r.sessions {
        if !r.single
            && let Some(v) = viewport
        {
            let meets: bool = conn
                .query_row(
                    "SELECT count(*) FROM sessions_rtree WHERE id = ?1 AND min_lon <= ?4 AND max_lon >= ?2
                       AND min_lat <= ?5 AND max_lat >= ?3",
                    rusqlite::params![s.id, v.west, v.south, v.east, v.north],
                    |row| row.get::<_, i64>(0),
                )
                .map(|n| n > 0)?;
            if !meets {
                continue;
            }
        }
        let pts = route_of(conn, s.id)?;
        if pts.len() > 1 {
            lines.push(line(&pts));
        }
    }
    Ok(format!(r#"{{"type":"FeatureCollection","features":[{}]}}"#, lines.join(",")))
}

fn bbox_union(a: Option<Bbox>, b: Option<Bbox>) -> Option<Bbox> {
    match (a, b) {
        (Some(a), Some(b)) => Some(Bbox {
            west: a.west.min(b.west),
            south: a.south.min(b.south),
            east: a.east.max(b.east),
            north: a.north.max(b.north),
        }),
        (a, None) => a,
        (None, b) => b,
    }
}

fn session_bbox(conn: &Connection, id: i64) -> Result<Option<Bbox>, CoreError> {
    Ok(conn.query_row(
        "SELECT min_lon, min_lat, max_lon, max_lat FROM sessions WHERE id = ?1",
        [id],
        |row| {
            Ok(match (row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?) {
                (Some(west), Some(south), Some(east), Some(north)) => Some(Bbox { west, south, east, north }),
                _ => None,
            })
        },
    )?)
}

fn shown_count(conn: &Connection, ctx: &Ctx<'_>, r: &Resolved) -> Result<u32, CoreError> {
    let mut params = Params::default();
    let with = relation(r, &mut params);
    let f = predicate(ctx, r, &DeviceFilter::default(), true, &mut params);
    Ok(conn.query_row(&format!("{with} SELECT count(*) FROM d WHERE {f}"), params.refs().as_slice(), |row| {
        row.get(0)
    })?)
}

/// `session_view`: `getRecording` minus the points.
pub(crate) fn session_view(conn: &Connection, ctx: &Ctx<'_>, scope: &Scope) -> Result<SessionView, CoreError> {
    let r = resolve(conn, ctx, scope)?;
    let mut frame = None;
    for s in &r.sessions {
        frame = bbox_union(frame, session_bbox(conn, s.id)?);
    }
    let route = match (r.single, r.sessions.first()) {
        (true, Some(s)) => route_of(conn, s.id)?,
        _ => Vec::new(),
    };
    Ok(SessionView {
        scope: scope.clone(),
        live: r.live,
        session_ids: r.sessions.iter().map(|s| s.uuid.clone()).collect(),
        device_count: shown_count(conn, ctx, &r)?,
        frame,
        route,
        routes_faint: !r.single,
        seq: seq_of(ctx, &r),
    })
}

pub(crate) fn summary(ctx: &Ctx<'_>, s: &SessionRow, upload: UploadState) -> SessionSummary {
    SessionSummary {
        id: s.uuid.clone(),
        status: match s.status {
            0 => SessionStatus::Recording,
            1 => SessionStatus::Paused,
            2 => SessionStatus::Completed,
            _ => SessionStatus::Interrupted,
        },
        started_ms: s.started_ms,
        ended_ms: s.ended_ms,
        active_ms: s.active_ms,
        distance_m: s.distance_m,
        device_count: if ctx.hide_unnamed_apple { s.device_count_shown } else { s.device_count } as u32,
        upload,
    }
}

/// L-2 `sessions()`: finished, newest first, no empty ones.
pub(crate) fn sessions(
    conn: &Connection,
    ctx: &Ctx<'_>,
    upload: &dyn Fn(&str) -> UploadState,
) -> Result<Vec<SessionSummary>, CoreError> {
    Ok(session_rows(conn)?
        .iter()
        .filter(|s| matches!(s.status, 2 | 3) && s.device_count > 0)
        .map(|s| summary(ctx, s, upload(&s.uuid)))
        .collect())
}

pub(crate) fn all_sessions_summary(conn: &Connection, ctx: &Ctx<'_>) -> Result<AllSessionsSummary, CoreError> {
    let r = resolve(conn, ctx, &Scope { sessions: SessionScope::All, filter: None })?;
    let mut bbox = None;
    for s in &r.sessions {
        bbox = bbox_union(bbox, session_bbox(conn, s.id)?);
    }
    Ok(AllSessionsSummary {
        session_count: r.sessions.len() as u32,
        device_count: shown_count(conn, ctx, &r)?,
        bbox,
    })
}
