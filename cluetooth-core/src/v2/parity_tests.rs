//! Parity with `:core-fake`: the data.js world is recorded through the real ingest path, then the fake's query
//! contracts run against the real core. The data comes from `scanner/core-fake/src/test/resources/`:
//! `data-js-fixture.json` (dumped from data.js) and `fake-bundle.json` (rules that reproduce data.js's products).

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::Arc;

use serde_json::Value as Json;

use super::core::Core;
use super::error::CoreError;
use super::keys::{addr_of, key_of};
use super::types::*;
use crate::{LocationFix, ScanEnvironment, ScanObservationInput};

const RESOURCES: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../scanner/core-fake/src/test/resources"
);

fn resource(name: &str) -> String {
    std::fs::read_to_string(format!("{RESOURCES}/{name}")).expect("core-fake test resource")
}

fn env() -> ScanEnvironment {
    ScanEnvironment {
        device_model: "Pixel 8".into(),
        android_release: "15".into(),
        android_sdk: 35,
        app_version: "0.1.0".into(),
        cap_ext_adv: true,
        cap_coded_phy: true,
        cap_max_adv_len: 1650,
        scan_phy: "all_supported".into(),
        scan_legacy_only: false,
    }
}

pub(crate) fn config(dir: &std::path::Path) -> CoreConfig {
    CoreConfig {
        data_directory: dir.to_string_lossy().into_owned(),
        recipient_public_key: crate::default_core_config().recipient_public_key,
        location_max_age_ms: 5_000,
        payload_max_rows: 100_000,
        payload_max_estimated_bytes: 10 << 20,
        payload_max_age_ms: 300_000,
        durability_interval_ms: 5_000,
        stale_after_ms: 20_000,
        live_interval_ms: 1_000,
    }
}

pub(crate) struct FakeDevice {
    pub name: Option<String>,
    pub product: Option<String>,
    pub company: Option<u16>,
    pub raw: Vec<u8>,
    pub connectable: bool,
    pub legacy: bool,
}

/// (key, lon, lat, rssi) of one data.js point.
pub(crate) type Point = (i64, f64, f64, i16);

pub(crate) struct World {
    pub core: Arc<Core>,
    pub _dir: tempfile::TempDir,
    /// The devices of the stored sessions (the live-only data.js devices are left out).
    pub devices: HashMap<i64, FakeDevice>,
    /// data.js recording id → (session uuid, [(key, lon, lat, rssi)]).
    pub sessions: BTreeMap<String, (String, Vec<Point>)>,
    /// product → page id
    pub page_of_product: HashMap<String, String>,
}

fn hex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

/// Records r6 … r1 (oldest first, an hour apart) through `start_recording`, `ingest_location`, `ingest_scans` and
/// `stop_recording`, one fix and one sighting per data.js point.
pub(crate) fn world() -> World {
    let fixture: Json = serde_json::from_str(&resource("data-js-fixture.json")).unwrap();
    let bundle = resource("fake-bundle.json");
    let b: Json = serde_json::from_str(&bundle).unwrap();
    let page_of_product = b["pages"]
        .as_array()
        .unwrap()
        .iter()
        .filter_map(|p| {
            Some((
                p["fake_product"].as_str()?.to_owned(),
                p["id"].as_str()?.to_owned(),
            ))
        })
        .collect();
    let mut devices = HashMap::new();
    for d in fixture["allDevices"].as_array().unwrap() {
        let mut raw = Vec::new();
        for s in d["structures"].as_array().unwrap() {
            let (t, data) = s.as_str().unwrap().split_once(':').unwrap();
            let data = hex(data);
            raw.push(data.len() as u8 + 1);
            raw.push(u8::from_str_radix(t, 16).unwrap());
            raw.extend(data);
        }
        devices.insert(
            key_of(d["addr"].as_str().unwrap()).unwrap(),
            FakeDevice {
                name: d["name"].as_str().map(str::to_owned),
                product: d["product"].as_str().map(str::to_owned),
                company: d["company"].as_u64().map(|c| c as u16),
                raw,
                connectable: d["connectable"].as_bool().unwrap_or(false),
                legacy: d["legacy"].as_bool().unwrap_or(true),
            },
        );
    }
    let dir = tempfile::tempdir().unwrap();
    let base = 1_761_000_000_000i64;
    let core = Core::open_at(
        config(dir.path()),
        CoreAssets {
            wiki_bundle_json: bundle,
            sig_numbers_tsv: "company\t004C\tApple, Inc.\ncompany\t0057\tHarman International\n"
                .into(),
        },
        base,
    )
    .unwrap();
    let mut sessions = BTreeMap::new();
    let ids = ["r6", "r5", "r4", "r3", "r2", "r1"];
    let mut clock_nanos = 0u64;
    for (n, id) in ids.iter().enumerate() {
        let start = base + n as i64 * 3_600_000;
        let uuid = core.start_recording_at(env(), start).unwrap();
        let mut points = Vec::new();
        let mut batch = Vec::new();
        for (i, p) in fixture["recordings"][id]
            .as_array()
            .unwrap()
            .iter()
            .enumerate()
        {
            let p = p.as_array().unwrap();
            let key = key_of(p[0].as_str().unwrap()).unwrap();
            let (lon, lat, rssi) = (
                p[1].as_f64().unwrap(),
                p[2].as_f64().unwrap(),
                p[3].as_i64().unwrap() as i16,
            );
            let t = start + i as i64 * 1_000;
            clock_nanos += 1_000_000_000; // elapsedRealtimeNanos only grows
            let nanos = clock_nanos;
            core.ingest_location_at(LocationFix {
                lat,
                lon,
                accuracy_meters: 8.0,
                observed_at_ms: t,
                elapsed_realtime_nanos: nanos,
            });
            let d = &devices[&key];
            batch.push(ScanObservationInput {
                addr: addr_of(key),
                rssi: Some(rssi),
                scanned_at_ms: t,
                elapsed_realtime_nanos: nanos,
                raw: d.raw.clone(),
                local_name: d.name.clone(),
                tx_power: Some(-12),
                is_connectable: Some(d.connectable),
                is_legacy: Some(d.legacy),
                primary_phy: Some(1),
                secondary_phy: Some(0),
                adv_sid: None,
                adv_tx_power: None,
                periodic_interval: None,
                data_status: Some(0),
                addr_type: Some(1),
            });
            // one sighting per fix: the fix ring keeps the most recent preceding fix
            core.ingest_scans_at(std::mem::take(&mut batch), t).unwrap();
            points.push((key, lon, lat, rssi));
        }
        core.stop_recording_at(start + 3_000_000).unwrap();
        sessions.insert((*id).to_owned(), (uuid, points));
    }
    let recorded: BTreeSet<i64> = sessions
        .values()
        .flat_map(|(_, p): &(String, Vec<Point>)| p.iter().map(|x| x.0))
        .collect();
    devices.retain(|k, _| recorded.contains(k));
    World {
        core,
        _dir: dir,
        devices,
        sessions,
        page_of_product,
    }
}

fn block<F: std::future::Future>(f: F) -> F::Output {
    futures_executor::block_on(f)
}

fn keys(k: &DeviceKeys) -> Vec<i64> {
    k.le_i64
        .chunks_exact(8)
        .map(|c| i64::from_le_bytes(c.try_into().unwrap()))
        .collect()
}

fn scope(w: &World, id: &str) -> Scope {
    Scope {
        sessions: SessionScope::Sessions {
            ids: vec![w.sessions[id].0.clone()],
        },
        filter: None,
    }
}

const ALL: Scope = Scope {
    sessions: SessionScope::All,
    filter: None,
};

fn shown(d: &FakeDevice) -> bool {
    !(d.company == Some(76) && d.name.is_none())
}

fn rows(w: &World, s: &Scope, ks: &[i64]) -> Vec<DeviceRow> {
    ks.chunks(200)
        .flat_map(|c| block(w.core.device_rows(s.clone(), c.to_vec())).unwrap())
        .collect()
}

fn categories() -> Vec<String> {
    let b: Json = serde_json::from_str(&resource("fake-bundle.json")).unwrap();
    b["vocabularies"]["categories"]
        .as_array()
        .unwrap()
        .iter()
        .map(|c| c["id"].as_str().unwrap().to_owned())
        .collect()
}

/// Matching reproduces data.js's product of every device, and the page fields follow the bundle.
#[test]
fn pages_match_the_fake() {
    let w = world();
    let order = keys(&block(w.core.device_order(ALL)).unwrap());
    let all_rows = rows(&w, &ALL, &order);
    assert_eq!(
        all_rows.len(),
        w.devices.values().filter(|d| shown(d)).count()
    );
    for r in &all_rows {
        let d = &w.devices[&r.key];
        let expected = d.product.as_ref().map(|p| w.page_of_product[p].clone());
        assert_eq!(
            r.page.as_ref().map(|p| p.id.clone()),
            expected,
            "{}",
            r.addr
        );
        assert_eq!(r.name, d.name);
        assert_eq!(r.vulnerable, d.product.as_deref() == Some("tesla"));
        assert_eq!(
            r.readable,
            matches!(d.product.as_deref(), Some("govee" | "xiaomi" | "switchbot"))
        );
    }
}

/// G-9 and §2.1: RSSI desc then key; each device once at its strongest RSSI across sessions; filters only hide.
#[test]
fn order_union_and_filters_match_the_fake() {
    let w = world();
    let mut strongest: HashMap<i64, i16> = HashMap::new();
    for (_, points) in w.sessions.values() {
        for &(k, _, _, rssi) in points {
            let e = strongest.entry(k).or_insert(rssi);
            *e = (*e).max(rssi);
        }
    }
    let mut expected: Vec<(i16, i64)> = strongest
        .iter()
        .filter(|(k, _)| shown(&w.devices[k]))
        .map(|(k, r)| (*r, *k))
        .collect();
    expected.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    let order = keys(&block(w.core.device_order(ALL)).unwrap());
    assert_eq!(order, expected.iter().map(|e| e.1).collect::<Vec<_>>());
    let rs = rows(&w, &ALL, &order);
    for r in &rs {
        assert_eq!(r.rssi, Some(strongest[&r.key]), "{}", r.addr);
        assert!(r.best.is_some());
    }
    let summary = block(w.core.all_sessions_summary()).unwrap();
    assert_eq!(summary.session_count, 5);
    assert_eq!(summary.device_count as usize, order.len());
    for f in [
        DeviceFilter {
            readable: true,
            ..Default::default()
        },
        DeviceFilter {
            trackers: true,
            ..Default::default()
        },
        DeviceFilter {
            vulnerable: true,
            ..Default::default()
        },
        DeviceFilter {
            identification: Identification::Unidentified,
            ..Default::default()
        },
        DeviceFilter {
            area: Some(Bbox {
                west: -117.236,
                south: 32.878,
                east: -117.231,
                north: 32.883,
            }),
            ..Default::default()
        },
    ] {
        for s in [ALL, scope(&w, "r1"), scope(&w, "r3")] {
            let base = keys(&block(w.core.device_order(s.clone())).unwrap());
            let passing: BTreeSet<i64> =
                keys(&block(w.core.device_keys(s.clone(), f.clone())).unwrap())
                    .into_iter()
                    .collect();
            let filtered: Vec<i64> =
                keys(&block(w.core.device_keys(s.clone(), f.clone())).unwrap());
            assert_eq!(
                filtered,
                base.iter()
                    .copied()
                    .filter(|k| passing.contains(k))
                    .collect::<Vec<_>>()
            );
        }
    }
    let two = Scope {
        sessions: SessionScope::Sessions {
            ids: vec![w.sessions["r1"].0.clone(), w.sessions["r3"].0.clone()],
        },
        filter: None,
    };
    let union = keys(&block(w.core.device_order(two.clone())).unwrap());
    let r1: BTreeSet<i64> = w.sessions["r1"]
        .1
        .iter()
        .map(|p| p.0)
        .filter(|k| shown(&w.devices[k]))
        .collect();
    let r3: BTreeSet<i64> = w.sessions["r3"]
        .1
        .iter()
        .map(|p| p.0)
        .filter(|k| shown(&w.devices[k]))
        .collect();
    assert_eq!(union.iter().copied().collect::<BTreeSet<_>>(), &r1 | &r3);
    let view = block(w.core.session_view(two)).unwrap();
    assert!(view.routes_faint);
    assert_eq!(view.session_ids.len(), 2);
}

/// D-3a (lane D's contract, unchanged from `FakeCoreTest.facetCountEqualsListLengthPerCategoryAndSwitch`): for
/// every category and each switch, the facet count equals the list length of that filter plus the category.
#[test]
fn facet_count_equals_list_length_per_category_and_switch() {
    let w = world();
    let switches = [
        DeviceFilter::default(),
        DeviceFilter {
            readable: true,
            ..Default::default()
        },
        DeviceFilter {
            trackers: true,
            ..Default::default()
        },
        DeviceFilter {
            vulnerable: true,
            ..Default::default()
        },
    ];
    for s in [ALL, scope(&w, "r1")] {
        for base in &switches {
            let counts: HashMap<String, u32> =
                block(w.core.category_counts(s.clone(), base.clone()))
                    .unwrap()
                    .into_iter()
                    .map(|c| (c.category, c.device_count))
                    .collect();
            assert!(!counts.is_empty(), "{s:?} {base:?}");
            for category in categories() {
                let f = DeviceFilter {
                    categories: vec![category.clone()],
                    ..base.clone()
                };
                let list = keys(&block(w.core.device_keys(s.clone(), f)).unwrap()).len() as u32;
                assert_eq!(
                    counts.get(&category).copied().unwrap_or(0),
                    list,
                    "{s:?} {base:?} {category}"
                );
            }
        }
    }
}

/// D-2: groups by the first category, by count, Unidentified last; counts add up to the list.
#[test]
fn products_and_product_match_the_fake() {
    let w = world();
    for s in [ALL, scope(&w, "r1")] {
        let groups = block(w.core.products(s.clone(), DeviceFilter::default())).unwrap();
        assert_eq!(groups.last().unwrap().category, "unidentified");
        let total: u32 = groups
            .iter()
            .flat_map(|g| &g.products)
            .map(|p| p.count)
            .sum();
        assert_eq!(
            total as usize,
            keys(&block(w.core.device_order(s.clone())).unwrap()).len()
        );
        let ns: Vec<u32> = groups[..groups.len() - 1]
            .iter()
            .map(|g| g.products.iter().map(|p| p.count).sum())
            .collect();
        assert!(ns.windows(2).all(|x| x[0] >= x[1]), "{ns:?}");
        let tile = block(w.core.product(
            s.clone(),
            Some("device--tile-feed".into()),
            DeviceFilter::default(),
        ))
        .unwrap()
        .unwrap();
        let listed = keys(
            &block(w.core.device_keys(
                s.clone(),
                DeviceFilter {
                    page: Some("device--tile-feed".into()),
                    ..Default::default()
                },
            ))
            .unwrap(),
        );
        assert_eq!(tile.device_count as usize, listed.len());
        assert_eq!(
            tile.wiki_url.as_deref(),
            Some("https://wirelesswiki.org/p/device--tile-feed")
        );
        assert!(tile.places > 0);
    }
    assert!(
        block(
            w.core
                .product(ALL, Some("device--nope".into()), DeviceFilter::default())
        )
        .unwrap()
        .is_none()
    );
}

/// L-2: newest first, the empty session (r4) never appears in the list; on its own (V-3) it resolves, empty.
#[test]
fn sessions_match_the_fake() {
    let w = world();
    let ids: Vec<String> = block(w.core.sessions())
        .unwrap()
        .into_iter()
        .map(|s| s.id)
        .collect();
    let expected: Vec<String> = ["r1", "r2", "r3", "r5", "r6"]
        .iter()
        .map(|id| w.sessions[*id].0.clone())
        .collect();
    assert_eq!(ids, expected);
    let r4 = block(w.core.session_view(scope(&w, "r4"))).unwrap();
    assert_eq!(r4.session_ids, vec![w.sessions["r4"].0.clone()]);
    assert_eq!(r4.device_count, 0);
}

/// Q-1/Q-2 and §2.5 map points: search keeps the order; `addr` is uppercase colon; the viewport is padded by a
/// quarter on each side.
#[test]
fn search_and_map_points_match_the_fake() {
    let w = world();
    assert!(
        block(w.core.search(ALL, "  ".into(), None))
            .unwrap()
            .is_none()
    );
    let hits = keys(
        &block(w.core.search(ALL, "govee".into(), None))
            .unwrap()
            .unwrap(),
    );
    let order = keys(&block(w.core.device_order(ALL)).unwrap());
    let expected: Vec<i64> = order
        .iter()
        .copied()
        .filter(|k| super::search::matches("govee", w.devices[k].name.as_deref(), &addr_of(*k)))
        .collect();
    assert_eq!(hits, expected);
    assert!(!hits.is_empty());
    let view = Bbox {
        west: -117.236,
        south: 32.878,
        east: -117.232,
        north: 32.882,
    };
    let data = block(w.core.map_points(ALL, DeviceFilter::default(), view, 16.0)).unwrap();
    assert_eq!(data.mode, MapMode::Points);
    let fc: Json = serde_json::from_str(&data.geojson).unwrap();
    let addrs: Vec<&str> = fc["features"]
        .as_array()
        .unwrap()
        .iter()
        .map(|f| f["properties"]["addr"].as_str().unwrap())
        .collect();
    assert_eq!(addrs.len() as u32, data.count);
    assert!(
        addrs
            .iter()
            .all(|a| key_of(a).is_some() && a.to_uppercase() == *a)
    );
    let padded = Bbox {
        west: -117.237,
        south: 32.877,
        east: -117.231,
        north: 32.883,
    };
    let area = keys(
        &block(w.core.device_keys(
            ALL,
            DeviceFilter {
                area: Some(padded),
                ..Default::default()
            },
        ))
        .unwrap(),
    );
    assert_eq!(area.len() as u32, data.count);
}

/// G-12: turning the display filter off shows the unnamed Apple devices everywhere.
#[test]
fn display_filter_matches_the_fake() {
    let w = world();
    let hidden = w.devices.values().filter(|d| !shown(d)).count();
    assert!(hidden > 0);
    let shown_n = keys(&block(w.core.device_order(ALL)).unwrap()).len();
    w.core
        .set_display_filter(DisplayFilter {
            hide_unnamed_apple: false,
        })
        .unwrap();
    let all_n = keys(&block(w.core.device_order(ALL)).unwrap()).len();
    assert_eq!(all_n, shown_n + hidden);
    assert_eq!(
        block(w.core.all_sessions_summary()).unwrap().device_count as usize,
        all_n
    );
}

/// D-9/D-12: the device detail comes from its latest payload.
#[test]
fn device_detail_matches_the_fake() {
    let w = world();
    let (key, d) = w
        .devices
        .iter()
        .find(|(_, d)| d.name.as_deref() == Some("JBL Flip 6"))
        .unwrap();
    let detail = block(w.core.device(ALL, *key)).unwrap().unwrap();
    assert_eq!(detail.name.as_deref(), Some("JBL Flip 6"));
    assert_eq!(detail.company_id, Some(87));
    assert_eq!(detail.company_name.as_deref(), Some("Harman International"));
    assert_eq!(detail.connectable, Some(d.connectable));
    assert_eq!(detail.format, AdvFormat::Legacy);
    assert_eq!(detail.tx_power, Some(-12));
    let raw: Vec<u8> = detail
        .structures
        .iter()
        .flat_map(|s| [vec![s.len, s.ad_type], s.data.clone()].concat())
        .collect();
    assert_eq!(raw, d.raw);
    let sightings = block(w.core.device_sightings(ALL, *key)).unwrap();
    assert!(!sightings.is_empty());
}

/// `payload_raw` rebuilds every stored payload exactly, and each distinct structure is stored once.
#[test]
fn every_payload_round_trips() {
    let w = world();
    let raws: BTreeSet<Vec<u8>> = w.devices.values().map(|d| d.raw.clone()).collect();
    w.core.with_writer_conn(move |conn| {
        let ids: Vec<i64> = conn
            .prepare("SELECT id FROM payloads")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        assert_eq!(ids.len(), raws.len());
        for id in ids {
            assert!(raws.contains(&super::store::payload_raw(conn, id).unwrap()));
        }
        let dupes: i64 = conn
            .query_row(
                "SELECT count(*) FROM (SELECT ad_type, data FROM ad_structures GROUP BY 1, 2 HAVING count(*) > 1)",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(dupes, 0);
    });
}

#[derive(Default)]
struct Collect {
    deltas: std::sync::Mutex<Vec<LiveDelta>>,
    events: std::sync::Mutex<Vec<CoreEvent>>,
}

impl super::core::CoreListener for Collect {
    fn on_live(&self, delta: LiveDelta) {
        self.deltas.lock().unwrap().push(delta);
    }
    fn on_event(&self, event: CoreEvent) {
        self.events.lock().unwrap().push(event);
    }
}

fn wait_for(c: &Collect, n: usize) -> Vec<LiveDelta> {
    for _ in 0..200 {
        if c.deltas.lock().unwrap().len() >= n {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    c.deltas.lock().unwrap().clone()
}

fn obs(addr: &str, rssi: i16, at: i64, nanos: u64, name: &str) -> ScanObservationInput {
    let mut raw = vec![2, 1, 6, name.len() as u8 + 1, 9];
    raw.extend(name.as_bytes());
    ScanObservationInput {
        addr: addr.into(),
        rssi: Some(rssi),
        scanned_at_ms: at,
        elapsed_realtime_nanos: nanos,
        raw,
        local_name: Some(name.into()),
        tx_power: None,
        is_connectable: Some(true),
        is_legacy: Some(true),
        primary_phy: Some(1),
        secondary_phy: Some(0),
        adv_sid: None,
        adv_tx_power: None,
        periodic_interval: None,
        data_status: Some(0),
        addr_type: Some(1),
    }
}

/// §2.6 (first cut): one delta per commit with appends, changes, staleness flips and the route; `seq` grows and
/// matches the snapshot; the delta carrying Paused is the last; live rows and order use the current RSSI.
#[test]
fn live_deltas_per_commit() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = 1_761_000_000_000i64;
    let core = Core::open_at(
        config(dir.path()),
        CoreAssets {
            wiki_bundle_json: resource("fake-bundle.json"),
            sig_numbers_tsv: String::new(),
        },
        t0,
    )
    .unwrap();
    let c = Arc::new(Collect::default());
    core.set_listener(Some(c.clone())).unwrap();
    let id = core.start_recording_at(env(), t0).unwrap();
    let live = Scope {
        sessions: SessionScope::Sessions {
            ids: vec![id.clone()],
        },
        filter: None,
    };
    let fix = |at: i64, nanos: u64| LocationFix {
        lat: 32.88,
        lon: -117.234 + (at - t0) as f64 * 1e-9,
        accuracy_meters: 5.0,
        observed_at_ms: at,
        elapsed_realtime_nanos: nanos,
    };
    // commit 1 at +5 s: A and B appear
    core.ingest_location_at(fix(t0 + 1_000, 1_000_000_000));
    core.ingest_scans_at(
        vec![obs(
            "AA:00:00:00:00:01",
            -70,
            t0 + 1_000,
            1_000_000_000,
            "A",
        )],
        t0 + 1_000,
    )
    .unwrap();
    core.ingest_scans_at(
        vec![obs(
            "AA:00:00:00:00:02",
            -50,
            t0 + 5_000,
            5_000_000_000,
            "B",
        )],
        t0 + 5_000,
    )
    .unwrap();
    let d = wait_for(&c, 1);
    assert_eq!(d.len(), 1, "events: {:?}", c.events.lock().unwrap());
    assert_eq!(d[0].seq, 1);
    assert_eq!(d[0].session_id, id);
    let appended: Vec<i64> = d[0].appended.iter().map(|r| r.key).collect();
    assert_eq!(appended, vec![0xAA0000000001, 0xAA0000000002]);
    assert_eq!(d[0].route_appended.len(), 1);
    assert!(
        d[0].products
            .iter()
            .any(|p| p.id == "unidentified" && p.count == 2 && p.live)
    );
    // the snapshot carries the seq of the last delta; the order is by current RSSI
    let snap = block(core.device_order(live.clone())).unwrap();
    assert_eq!(snap.seq, 1);
    assert_eq!(keys(&snap), vec![0xAA0000000002, 0xAA0000000001]);
    // commit 2 at +30 s: A is heard stronger now, B went stale
    core.ingest_scans_at(
        vec![obs(
            "AA:00:00:00:00:01",
            -40,
            t0 + 30_000,
            30_000_000_000,
            "A",
        )],
        t0 + 30_000,
    )
    .unwrap();
    let d = wait_for(&c, 2);
    assert_eq!(d.len(), 2);
    assert_eq!(d[1].seq, 2);
    assert!(d[1].appended.is_empty());
    let a = d[1]
        .changed
        .iter()
        .find(|x| x.key == 0xAA0000000001)
        .unwrap();
    assert_eq!((a.rssi, a.in_range), (Some(-40), true));
    let b = d[1]
        .changed
        .iter()
        .find(|x| x.key == 0xAA0000000002)
        .unwrap();
    assert!(!b.in_range);
    let rows = block(core.device_rows(live.clone(), vec![0xAA0000000001])).unwrap();
    assert_eq!(rows[0].rssi, Some(-40));
    assert!(block(core.session_view(live.clone())).unwrap().live);
    // pause: one forced delta carrying Paused, then nothing
    core.pause_recording_at(t0 + 31_000).unwrap();
    let d = wait_for(&c, 3);
    assert_eq!(d.len(), 3);
    assert_eq!(d[2].state.phase, RecordingPhase::Paused);
    assert_eq!(
        core.ingest_scans_at(
            vec![obs(
                "AA:00:00:00:00:03",
                -60,
                t0 + 40_000,
                40_000_000_000,
                "C"
            )],
            t0 + 40_000
        )
        .unwrap()
        .ignored_not_recording,
        1
    );
    std::thread::sleep(std::time::Duration::from_millis(50));
    assert_eq!(c.deltas.lock().unwrap().len(), 3);
    core.stop_recording_at(t0 + 50_000).unwrap();
    for _ in 0..200 {
        if c.events
            .lock()
            .unwrap()
            .iter()
            .any(|e| matches!(e, CoreEvent::SessionsChanged))
        {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    let events = c.events.lock().unwrap().clone();
    assert!(
        events
            .iter()
            .any(|e| matches!(e, CoreEvent::SessionsChanged))
    );
    // frozen after stop: the strongest RSSI again
    let rows = block(core.device_rows(live, vec![0xAA0000000001])).unwrap();
    assert_eq!(rows[0].rssi, Some(-40));
}

fn gatt_json(id: &str, addr: &str, outcome: &str, session: Option<&str>) -> String {
    let (stage, error, disconnect, timings) = match outcome {
        "complete" => (
            "disconnect",
            Json::Null,
            serde_json::json!({"initiator": "local", "status": 0}),
            serde_json::json!({"connect": 800, "mtu": 120, "discover": 1500, "read": 4000}),
        ),
        "failed" => (
            "connect",
            serde_json::json!({"stage": "connect", "status": -1, "name": "TIMEOUT"}),
            serde_json::json!({"initiator": "timeout", "status": null}),
            serde_json::json!({"connect": null, "mtu": null, "discover": null, "read": null}),
        ),
        _ => (
            "read",
            serde_json::json!({"stage": "read", "status": 19, "name": "GATT_CONN_TERMINATE_PEER_USER"}),
            serde_json::json!({"initiator": "remote", "status": 19}),
            serde_json::json!({"connect": 800, "mtu": 120, "discover": 1500, "read": 4000}),
        ),
    };
    serde_json::json!({
        "schema": "gatt/v1", "id": id, "session_id": session, "app_version": "0.1.0",
        "device": {"model": "Pixel 8", "android": "15"},
        "target": {"addr": addr, "addr_type": "random", "name": "JBL Flip 6", "trigger_adv": null},
        "started_at": "2026-10-28T19:00:01.000Z", "ended_at": "2026-10-28T19:00:09.500Z",
        "location": {"lat": 32.88, "lon": -117.23, "accuracy": 4.5},
        "outcome": outcome, "stage_reached": stage, "error": error, "disconnect": disconnect,
        "mtu": if outcome == "failed" { Json::Null } else { serde_json::json!(247) },
        "phy": if outcome == "failed" { Json::Null } else { serde_json::json!({"tx": 2, "rx": 2}) },
        "timings_ms": timings,
        "services": if outcome == "failed" { serde_json::json!([]) } else { serde_json::json!([
            {"uuid": "180F", "primary": true, "instance": 12, "characteristics": [
                {"uuid": "2A19", "instance": 14, "properties": ["read", "notify"],
                 "value": {"hex": "4e", "status": "ok"}, "descriptors": []}]},
            {"uuid": "180A", "primary": true, "instance": 20, "characteristics": [
                {"uuid": "2A29", "instance": 22, "properties": ["read"],
                 "value": {"hex": "4861726d616e", "status": "ok"},
                 "descriptors": [{"uuid": "2901", "instance": null, "value": {"hex": "", "status": "ok"}}]},
                {"uuid": "2A25", "instance": 24, "properties": ["read"],
                 "value": {"hex": null, "status": "insufficient_authentication"}, "descriptors": []}]}
        ]) },
    })
    .to_string()
}

/// §2.4 `gatt_target`: the device's latest sighting gives the address, its type and the trigger advertisement;
/// uncommitted sightings of the current recording count; an unknown device has none.
#[test]
fn gatt_target_comes_from_the_latest_sighting() {
    let w = world();
    let (key, d) = w
        .devices
        .iter()
        .find(|(_, d)| d.name.as_deref() == Some("JBL Flip 6"))
        .unwrap();
    let t = block(w.core.gatt_target(*key)).unwrap().unwrap();
    assert_eq!(t.addr, addr_of(*key));
    assert_eq!(t.addr_type.as_deref(), Some("random"));
    assert_eq!(t.name.as_deref(), Some("JBL Flip 6"));
    assert_eq!(t.trigger_raw, d.raw);
    assert_eq!(t.trigger_is_legacy, Some(d.legacy));
    assert_eq!(
        (
            t.trigger_primary_phy,
            t.trigger_secondary_phy,
            t.trigger_adv_sid
        ),
        (Some(1), Some(0), None)
    );
    assert!(t.trigger_rssi.is_some());
    assert!(t.session_id.is_none());
    assert!(
        block(w.core.gatt_target(0x0102_0304_0506))
            .unwrap()
            .is_none()
    );
    // a device seen only in the current recording's uncommitted batch
    let id = w.core.start_recording_at(env(), 1_800_000_000_000).unwrap();
    let raw = vec![2, 1, 6, 4, 9, b'N', b'e', b'w'];
    w.core
        .ingest_scans_at(
            vec![ScanObservationInput {
                addr: "C0:FF:EE:00:00:09".into(),
                rssi: Some(-44),
                scanned_at_ms: 1_800_000_000_100,
                elapsed_realtime_nanos: u64::MAX / 2,
                raw: raw.clone(),
                local_name: Some("New".into()),
                tx_power: None,
                is_connectable: Some(true),
                is_legacy: Some(false),
                primary_phy: Some(1),
                secondary_phy: Some(2),
                adv_sid: Some(3),
                adv_tx_power: None,
                periodic_interval: None,
                data_status: Some(0),
                addr_type: Some(0),
            }],
            1_800_000_000_100,
        )
        .unwrap();
    let t = block(w.core.gatt_target(0xC0FFEE000009)).unwrap().unwrap();
    assert_eq!(t.addr, "C0:FF:EE:00:00:09");
    assert_eq!(t.addr_type.as_deref(), Some("public"));
    assert_eq!(t.name.as_deref(), Some("New"));
    assert_eq!(t.trigger_raw, raw);
    assert_eq!(
        (
            t.trigger_rssi,
            t.trigger_is_legacy,
            t.trigger_secondary_phy,
            t.trigger_adv_sid
        ),
        (Some(-44), Some(false), Some(2), Some(3))
    );
    assert_eq!(t.session_id.as_deref(), Some(id.as_str()));
}

/// §2.4/§2.7 `ingest_gatt` and D-13.7: the run is validated, kept as pending plaintext, stored with its
/// attributes, returned as a view, and the device opens on its last result; a cancelled run never shows.
#[test]
fn ingest_gatt_stores_the_run_and_the_device_opens_on_it() {
    let w = world();
    let (key, _) = w
        .devices
        .iter()
        .find(|(_, d)| d.name.as_deref() == Some("JBL Flip 6"))
        .unwrap();
    let addr = addr_of(*key);
    let session = w.sessions["r1"].0.clone();
    let id = w.core.new_gatt_discovery_id().unwrap();
    let json = gatt_json(&id, &addr, "complete", Some(&session));
    let v = w.core.ingest_gatt(json.clone()).unwrap();
    assert!(v.ok);
    assert_eq!(v.key, *key);
    assert_eq!(v.discovery_id, id);
    assert_eq!(
        v.started_ms,
        super::gatt_view::parse_ms("2026-10-28T19:00:01.000Z").unwrap()
    );
    let kinds: Vec<_> = v.steps.iter().map(|s| (s.kind, s.state)).collect();
    assert_eq!(
        kinds,
        vec![
            (GattStepKind::Connecting, GattStepState::Done),
            (GattStepKind::DiscoveringServices, GattStepState::Done),
            (GattStepKind::ReadingCharacteristics, GattStepState::Done),
            (GattStepKind::Disconnected, GattStepState::Done),
        ]
    );
    assert_eq!(v.steps[0].duration_ms, Some(920)); // MTU folded into Connecting
    assert_eq!(v.steps[1].services, Some(2));
    assert_eq!((v.steps[2].read, v.steps[2].readable), (Some(2), Some(3)));
    assert_eq!(
        v.services[0].characteristics[0].value_text.as_deref(),
        Some("78 %")
    );
    assert_eq!(
        v.services[0].characteristics[0].name.as_deref(),
        Some("Battery Level")
    );
    assert_eq!(
        v.services[1].characteristics[0].value_text.as_deref(),
        Some("Harman")
    );
    assert_eq!(v.services[1].characteristics[1].value_text, None);
    // plaintext kept under pending/gatt/v1
    let found = walk(w._dir.path().join("pending/gatt/v1").as_path());
    assert!(
        found.iter().any(|p| p.ends_with(format!("{id}.json"))),
        "{found:?}"
    );
    // rows
    w.core.with_writer_conn(move |c| {
        let (n, linked): (i64, Option<i64>) = c
            .query_row(
                "SELECT count(*), max(session_id) FROM gatt_discoveries",
                [],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .unwrap();
        assert_eq!(n, 1);
        assert!(linked.is_some());
        let attrs: i64 = c
            .query_row("SELECT count(*) FROM gatt_attributes", [], |r| r.get(0))
            .unwrap();
        assert_eq!(attrs, 6); // 2 services, 3 characteristics, 1 descriptor
    });
    // idempotent
    assert_eq!(w.core.ingest_gatt(json).unwrap(), v);
    assert_eq!(block(w.core.device_gatt(*key)).unwrap(), Some(v.clone()));
    // a cancelled run is never shown; a later failed one is the last result
    let cancelled = w.core.new_gatt_discovery_id().unwrap();
    w.core
        .ingest_gatt(gatt_json(&cancelled, &addr, "cancelled", None))
        .unwrap();
    assert_eq!(block(w.core.device_gatt(*key)).unwrap(), Some(v.clone()));
    let failed = w.core.new_gatt_discovery_id().unwrap();
    let f = w
        .core
        .ingest_gatt(gatt_json(&failed, &addr, "failed", None).replace("T19:00:", "T19:05:"))
        .unwrap();
    assert!(!f.ok);
    assert_eq!(f.steps.len(), 1);
    assert_eq!(f.steps[0].failure, Some(GattFailure::TimedOut));
    assert!(f.services.is_empty());
    assert_eq!(
        block(w.core.device_gatt(*key))
            .unwrap()
            .unwrap()
            .discovery_id,
        failed
    );
    assert!(
        block(w.core.device_gatt(0x0102_0304_0506))
            .unwrap()
            .is_none()
    );
    // invalid objects are refused and leave nothing behind
    assert!(matches!(
        w.core.ingest_gatt("{}".into()),
        Err(super::CoreError::InvalidPayload { .. })
    ));
}

/// §2.7 failure mapping: a remote disconnect after connecting stops at the failed step, with its status (#99).
#[test]
fn gatt_partial_stops_at_the_failed_step() {
    let w = world();
    let id = w.core.new_gatt_discovery_id().unwrap();
    let v = w
        .core
        .ingest_gatt(gatt_json(&id, "AA:BB:CC:DD:EE:FF", "partial", None))
        .unwrap();
    assert!(!v.ok);
    assert_eq!(v.steps.len(), 3);
    assert_eq!(v.steps[2].kind, GattStepKind::ReadingCharacteristics);
    assert_eq!(v.steps[2].state, GattStepState::Failed);
    assert_eq!(
        v.steps[2].failure,
        Some(GattFailure::Other { status: 19 }),
        "the stored status reaches the UI"
    );
    assert!(
        v.services.is_empty(),
        "D-13.6: services show only after a complete run"
    );
}

fn walk(dir: &std::path::Path) -> Vec<std::path::PathBuf> {
    let mut out = Vec::new();
    if let Ok(entries) = std::fs::read_dir(dir) {
        for e in entries.flatten() {
            let p = e.path();
            if p.is_dir() {
                out.extend(walk(&p));
            } else {
                out.push(p);
            }
        }
    }
    out
}

/// A failed commit loses nothing and misattributes nothing: the batch and route go back to pending, and the
/// payload ids the rolled-back transaction handed out are forgotten, so the retry re-inserts the payload instead
/// of pointing its rows at whichever payload SQLite gives the freed id to next.
#[test]
fn failed_commit_is_retried_without_loss_or_misattribution() {
    let dir = tempfile::tempdir().unwrap();
    let t0 = 1_761_000_000_000i64;
    let core = Core::open_at(
        config(dir.path()),
        CoreAssets {
            wiki_bundle_json: resource("fake-bundle.json"),
            sig_numbers_tsv: String::new(),
        },
        t0,
    )
    .unwrap();
    let c = Arc::new(Collect::default());
    core.set_listener(Some(c.clone())).unwrap();
    let id = core.start_recording_at(env(), t0).unwrap();
    core.ingest_location_at(LocationFix {
        lat: 32.88,
        lon: -117.234,
        accuracy_meters: 5.0,
        observed_at_ms: t0 + 1_000,
        elapsed_realtime_nanos: 1_000_000_000,
    });
    core.ingest_scans_at(
        vec![obs(
            "AA:00:00:00:00:01",
            -70,
            t0 + 1_000,
            1_000_000_000,
            "Alpha",
        )],
        t0 + 1_000,
    )
    .unwrap();
    // the commit at +5 s fails after writing everything
    core.fail_next_commit();
    let ack = core
        .ingest_scans_at(
            vec![obs(
                "AA:00:00:00:00:01",
                -60,
                t0 + 5_000,
                5_000_000_000,
                "Alpha",
            )],
            t0 + 5_000,
        )
        .unwrap();
    assert_eq!(ack.accepted, 1, "a failed commit still accepts the batch");
    for _ in 0..200 {
        if c.events
            .lock()
            .unwrap()
            .iter()
            .any(|e| matches!(e, CoreEvent::StorageWarning { .. }))
        {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    assert!(
        c.events
            .lock()
            .unwrap()
            .iter()
            .any(|e| matches!(e, CoreEvent::StorageWarning { .. }))
    );
    core.with_writer_conn(move |conn| {
        let n: i64 = conn
            .query_row("SELECT count(*) FROM payloads", [], |r| r.get(0))
            .unwrap();
        assert_eq!(n, 0, "the failed transaction rolled back");
    });
    // a new payload arrives before the retry: it must not take over Alpha's freed id
    core.ingest_scans_at(
        vec![obs(
            "AA:00:00:00:00:02",
            -50,
            t0 + 10_000,
            10_000_000_000,
            "Bravo",
        )],
        t0 + 10_000,
    )
    .unwrap();
    core.stop_recording_at(t0 + 11_000).unwrap();
    core.with_writer_conn(move |conn| {
        let rows: Vec<(i64, i64, i64)> = conn
            .prepare(
                "SELECT addr, payload_id, sightings FROM sighting_minutes ORDER BY addr, minute",
            )
            .unwrap()
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        let mut by_addr: HashMap<i64, i64> = HashMap::new();
        for (addr, payload, sightings) in &rows {
            let raw = super::store::payload_raw(conn, *payload).unwrap();
            let name = if *addr == 0xAA0000000001 {
                "Alpha"
            } else {
                "Bravo"
            };
            assert!(
                raw.ends_with(name.as_bytes()),
                "{addr:x} points at a payload of the other device"
            );
            *by_addr.entry(*addr).or_default() += sightings;
        }
        assert_eq!(
            by_addr[&0xAA0000000001], 2,
            "both sightings of the failed batch were kept"
        );
        assert_eq!(by_addr[&0xAA0000000002], 1);
        let route: i64 = conn
            .query_row("SELECT count(*) FROM route_points", [], |r| r.get(0))
            .unwrap();
        assert_eq!(route, 1, "the route point of the failed commit was kept");
        let names: Vec<Option<String>> = conn
            .prepare("SELECT name FROM session_devices ORDER BY addr")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        assert_eq!(names, vec![Some("Alpha".into()), Some("Bravo".into())]);
    });
    let rows = block(core.device_rows(
        Scope {
            sessions: SessionScope::Sessions { ids: vec![id] },
            filter: None,
        },
        vec![0xAA0000000001, 0xAA0000000002],
    ))
    .unwrap();
    assert_eq!(
        rows.iter().map(|r| r.rssi).collect::<Vec<_>>(),
        vec![Some(-60), Some(-50)]
    );
}

fn fresh_core(t0: i64) -> (Arc<Core>, tempfile::TempDir, Arc<Collect>) {
    let dir = tempfile::tempdir().unwrap();
    let core = Core::open_at(
        config(dir.path()),
        CoreAssets {
            wiki_bundle_json: resource("fake-bundle.json"),
            sig_numbers_tsv: String::new(),
        },
        t0,
    )
    .unwrap();
    let c = Arc::new(Collect::default());
    core.set_listener(Some(c.clone())).unwrap();
    (core, dir, c)
}

fn fix_at(at: i64, nanos: u64) -> LocationFix {
    LocationFix {
        lat: 32.88,
        lon: -117.234,
        accuracy_meters: 5.0,
        observed_at_ms: at,
        elapsed_realtime_nanos: nanos,
    }
}

/// Actor ordering: a fix told before a batch is applied first (the batch gets it); the 5 s tick commits without
/// scans, persists the route, and flips staleness; queries sent after a reply see the writer's state.
#[test]
fn writer_messages_apply_in_order_and_the_tick_commits_without_scans() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    let live = Scope {
        sessions: SessionScope::Sessions { ids: vec![id] },
        filter: None,
    };
    core.ingest_location_at(fix_at(t0 + 1_000, 1_000_000_000)); // no reply: queued before the scans
    core.ingest_scans_at(
        vec![obs(
            "AA:00:00:00:00:01",
            -60,
            t0 + 1_000,
            1_000_000_000,
            "A",
        )],
        t0 + 1_000,
    )
    .unwrap();
    assert_eq!(core.pending_len(), 1, "no commit before the interval");
    assert!(keys(&block(core.device_order(live.clone())).unwrap()).is_empty());
    // no more scans: the tick commits at +6 s
    core.tick_at(t0 + 4_999);
    assert_eq!(core.pending_len(), 1);
    core.tick_at(t0 + 6_000);
    assert_eq!(core.pending_len(), 0);
    let rows = block(core.device_rows(live.clone(), vec![0xAA0000000001])).unwrap();
    assert_eq!(rows.len(), 1);
    assert!(
        rows[0].best.is_some(),
        "the fix told first reached the sighting"
    );
    let d = wait_for(&c, 1);
    assert_eq!(d.len(), 1);
    assert_eq!(d[0].route_appended.len(), 1, "the tick persisted the route");
    // still no scans: the next ticks flip the device out of range
    core.tick_at(t0 + 11_000);
    core.tick_at(t0 + 26_000);
    let d = wait_for(&c, 2);
    assert!(
        d.iter()
            .flat_map(|d| &d.changed)
            .any(|x| x.key == 0xAA0000000001 && !x.in_range)
    );
    assert!(d.windows(2).all(|w| w[0].seq < w[1].seq));
    // paused: the tick commits nothing and emits no delta
    core.pause_recording_at(t0 + 27_000).unwrap();
    let n = wait_for(&c, 3).len();
    core.tick_at(t0 + 99_000);
    std::thread::sleep(std::time::Duration::from_millis(30));
    assert_eq!(c.deltas.lock().unwrap().len(), n);
}

/// §2 cancellation: dropping a query's future skips it when queued and stops it in flight (its own cancel flag,
/// checked by the progress handler), never touching another query.
#[test]
fn dropping_a_query_cancels_it() {
    use std::sync::atomic::{AtomicUsize, Ordering};
    let (core, _dir, _c) = fresh_core(1_761_000_000_000);
    let endless =
        "WITH RECURSIVE r(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM r) SELECT count(*) FROM r";
    let started = Arc::new(AtomicUsize::new(0));
    let s = started.clone();
    let running = core.read(move |c, _| {
        s.fetch_add(1, Ordering::SeqCst);
        Ok(c.query_row(endless, [], |r| r.get::<_, i64>(0))?)
    });
    let ran = Arc::new(AtomicUsize::new(0));
    let r = ran.clone();
    let queued = core.read(move |_, _| {
        r.fetch_add(1, Ordering::SeqCst);
        Ok(())
    });
    for _ in 0..200 {
        if started.load(Ordering::SeqCst) > 0 {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    drop(queued); // not started yet: skipped
    let t = std::time::Instant::now();
    drop(running); // in flight: interrupted
    let after = block(core.device_order(ALL)).unwrap();
    assert!(keys(&after).is_empty());
    assert!(
        t.elapsed() < std::time::Duration::from_secs(2),
        "the endless query was interrupted"
    );
    assert_eq!(
        ran.load(Ordering::SeqCst),
        0,
        "a dropped query that had not started never ran"
    );
    // the race that hung CI: an endless query dropped at any moment, also between the reader's look at its reply
    // and its first step, always stops, and its cancel flag never reaches the next query
    for n in 0..200u64 {
        let endless_again =
            core.read(move |c, _| Ok(c.query_row(endless, [], |r| r.get::<_, i64>(0))?));
        if n % 3 == 1 {
            std::thread::sleep(std::time::Duration::from_micros(n * 7));
        }
        drop(endless_again);
        let v =
            block(core.read(move |c, _| {
                Ok(c.query_row("SELECT ?1", [n as i64], |r| r.get::<_, i64>(0))?)
            }))
            .unwrap();
        assert_eq!(v, n as i64);
    }
    assert!(
        t.elapsed() < std::time::Duration::from_secs(20),
        "every dropped query stopped"
    );
}

/// A failed tick commit, pause or stop keeps everything and the recording goes on; the next attempt stores it.
#[test]
fn failed_tick_pause_and_stop_commits_are_retried() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(
        vec![obs("AA:00:00:00:00:01", -60, t0 + 1_000, 1, "A")],
        t0 + 1_000,
    )
    .unwrap();
    core.fail_next_commit();
    core.tick_at(t0 + 6_000);
    assert_eq!(core.pending_len(), 1, "the failed tick kept the batch");
    core.fail_next_commit();
    assert!(core.pause_recording_at(t0 + 7_000).is_err());
    assert_eq!(
        core.recording_state().unwrap().phase,
        RecordingPhase::Recording
    );
    core.fail_next_commit();
    assert!(core.stop_recording_at(t0 + 8_000).is_err());
    assert_eq!(
        core.recording_state().unwrap().session_id.as_deref(),
        Some(id.as_str())
    );
    assert_eq!(core.pending_len(), 1);
    assert_eq!(core.stop_recording_at(t0 + 9_000).unwrap(), id);
    let n: i64 = core.with_writer_conn(|c| {
        c.query_row("SELECT sum(sightings) FROM sighting_minutes", [], |r| {
            r.get(0)
        })
        .unwrap()
    });
    assert_eq!(n, 1);
    for _ in 0..200 {
        if c.events
            .lock()
            .unwrap()
            .iter()
            .filter(|e| matches!(e, CoreEvent::StorageWarning { .. }))
            .count()
            >= 3
        {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    let warnings = c
        .events
        .lock()
        .unwrap()
        .iter()
        .filter(|e| matches!(e, CoreEvent::StorageWarning { .. }))
        .count();
    assert_eq!(warnings, 3);
}

/// A fixed-seed interleaving of ingest, fixes, ticks, injected commit failures, start/pause/resume/stop and
/// queries. Invariants: every accepted observation is stored exactly once, in its session, under its own
/// payload; queries never fail; delta seqs grow within a session; nothing stays pending after the last stop.
#[test]
fn randomized_interleavings_keep_the_invariants() {
    for seed in [1u64, 7, 42, 2026] {
        randomized(seed);
    }
}

fn randomized(seed: u64) {
    let mut state = seed;
    let mut rnd = move |n: u64| {
        state = state
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        (state >> 33) % n
    };
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    // six devices, two advertisement variants each
    let names = ["Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot"];
    let mut now = t0;
    let mut nanos = 0u64;
    let mut session: Option<String> = None;
    let mut paused = false;
    // (session, addr, raw) -> accepted count
    let mut expected: HashMap<(String, i64, Vec<u8>), i64> = HashMap::new();
    for _ in 0..400 {
        now += 1 + rnd(3_000) as i64;
        nanos += 1_000_000 + rnd(3_000_000_000);
        match rnd(100) {
            0..=44 => {
                let d = rnd(6) as usize;
                let name = format!("{}{}", names[d], if rnd(2) == 0 { "" } else { "-2" });
                let addr = format!("AA:00:00:00:00:0{}", d + 1);
                let o = obs(&addr, -40 - rnd(50) as i16, now, nanos, &name);
                let raw = o.raw.clone();
                let ack = core.ingest_scans_at(vec![o], now).unwrap();
                if ack.accepted == 1 {
                    let s = session.clone().expect("accepted only while recording");
                    *expected
                        .entry((s, 0xAA00_0000_0000 + d as i64 + 1, raw))
                        .or_default() += 1;
                } else {
                    assert!(session.is_none() || paused);
                }
            }
            45..=54 => core.ingest_location_at(fix_at(now, nanos)),
            55..=69 => core.tick_at(now),
            70..=74 => core.fail_next_commit(),
            75..=79 => {
                if session.is_none() {
                    session = Some(core.start_recording_at(env(), now).unwrap());
                    paused = false;
                }
            }
            80..=84 => match core.pause_recording_at(now) {
                Ok(()) => paused = session.is_some(),
                Err(_) => assert!(
                    session.is_some() || core.recording_state().unwrap().session_id.is_none()
                ),
            },
            85..=87 => {
                if core.resume_recording_at(now).is_ok() {
                    paused = false;
                }
            }
            88..=91 => {
                if core.stop_recording_at(now).is_ok() {
                    session = None;
                    paused = false;
                }
            }
            _ => {
                let scope = match &session {
                    Some(s) => Scope {
                        sessions: SessionScope::Sessions {
                            ids: vec![s.clone()],
                        },
                        filter: None,
                    },
                    None => ALL,
                };
                let order = keys(&block(core.device_order(scope.clone())).unwrap());
                block(core.device_rows(scope.clone(), order)).unwrap();
                block(core.category_counts(scope, DeviceFilter::default())).unwrap();
            }
        }
    }
    if session.is_some() {
        // an injected failure fails one commit only, so the second stop finishes
        let stopped = (0..3).any(|_| {
            now += 1_000;
            core.stop_recording_at(now).is_ok()
        });
        assert!(stopped, "seed {seed}: the last stop never finished");
    }
    assert_eq!(core.pending_len(), 0);
    let stored: HashMap<(String, i64, Vec<u8>), i64> = core.with_writer_conn(|conn| {
        let rows: Vec<(String, i64, i64, i64)> = conn
            .prepare(
                "SELECT s.uuid, m.addr, m.payload_id, m.sightings FROM sighting_minutes m
                 JOIN sessions s ON s.id = m.session_id",
            )
            .unwrap()
            .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap();
        let mut out = HashMap::new();
        for (s, addr, payload, n) in rows {
            let raw = super::store::payload_raw(conn, payload).unwrap();
            *out.entry((s, addr, raw)).or_default() += n;
        }
        out
    });
    assert_eq!(
        stored, expected,
        "seed {seed}: every accepted observation exactly once under its own payload"
    );
    let deltas = wait_for(&c, 0);
    let mut last: HashMap<String, u64> = HashMap::new();
    for d in deltas {
        let prev = last.insert(d.session_id.clone(), d.seq).unwrap_or(0);
        assert!(d.seq > prev, "seed {seed}: seq {} after {prev}", d.seq);
    }
}

/// Waits until an event matching `f` arrived; returns every event so far.
fn wait_event(c: &Collect, f: impl Fn(&CoreEvent) -> bool) -> Vec<CoreEvent> {
    for _ in 0..200 {
        if c.events.lock().unwrap().iter().any(&f) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(10));
    }
    c.events.lock().unwrap().clone()
}

fn stored_sightings(core: &Core) -> i64 {
    core.with_writer_conn(|c| {
        c.query_row(
            "SELECT coalesce(sum(sightings), 0) FROM sighting_minutes",
            [],
            |r| r.get(0),
        )
        .unwrap()
    })
}

fn batch(n: usize, at: i64) -> Vec<ScanObservationInput> {
    (0..n)
        .map(|i| {
            obs(
                &format!("AA:00:00:00:{:02X}:{:02X}", i / 256, i % 256),
                -60,
                at,
                1,
                "A",
            )
        })
        .collect()
}

/// SPEC R-10: a commit that always fails for lack of space stops writing and pauses; memory stays bounded and
/// ingest gets a typed error; after space comes back, resume records again and nothing committed was lost.
#[test]
fn a_full_disk_pauses_drops_and_resumes() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(batch(10, t0 + 1_000), t0 + 1_000)
        .unwrap();
    core.tick_at(t0 + 5_000);
    assert_eq!(stored_sightings(&core), 10);
    core.fail_commits(Some(CoreError::StorageFull { free_bytes: 0 }));
    core.ingest_scans_at(batch(7, t0 + 6_000), t0 + 6_000)
        .unwrap();
    core.tick_at(t0 + 10_000);
    let state = core.recording_state().unwrap();
    assert_eq!(state.phase, RecordingPhase::Paused);
    assert!(state.paused_for_storage);
    assert_eq!(
        state.session_id.as_deref(),
        Some(id.as_str()),
        "the session stays open"
    );
    assert_eq!(
        core.pending_len(),
        0,
        "the rows that could not be stored are dropped"
    );
    let events = wait_event(&c, |e| matches!(e, CoreEvent::StorageFull { .. }));
    assert!(
        events.iter().any(|e| matches!(
            e,
            CoreEvent::StorageFull {
                dropped_scans: 7,
                ..
            }
        )),
        "{events:?}"
    );
    // while paused for storage every batch is refused, typed, and nothing is buffered
    for i in 0..50 {
        let at = t0 + 11_000 + i;
        assert!(matches!(
            core.ingest_scans_at(batch(64, at), at),
            Err(CoreError::StorageFull { .. })
        ));
    }
    assert_eq!(core.pending_len(), 0);
    // resume needs the free space back above the floor
    assert!(matches!(
        core.resume_recording_free_at(t0 + 20_000, Some(1_000)),
        Err(CoreError::StorageFull { free_bytes: 1_000 })
    ));
    assert!(core.recording_state().unwrap().paused_for_storage);
    core.fail_commits(None);
    core.resume_recording_free_at(t0 + 21_000, Some(10_000_000_000))
        .unwrap();
    let state = core.recording_state().unwrap();
    assert_eq!(state.phase, RecordingPhase::Recording);
    assert!(!state.paused_for_storage);
    core.ingest_scans_at(batch(3, t0 + 22_000), t0 + 22_000)
        .unwrap();
    assert_eq!(core.stop_recording_at(t0 + 23_000).unwrap(), id);
    assert_eq!(
        stored_sightings(&core),
        13,
        "the first 10 and the 3 after resume"
    );
}

/// SPEC R-10: below the 200 MB floor the tick stops writing and pauses, without a commit.
#[test]
fn the_free_space_floor_pauses_without_writing() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(batch(4, t0 + 1_000), t0 + 1_000)
        .unwrap();
    core.tick_free_at(t0 + 5_000, Some(199_999_999));
    assert!(core.recording_state().unwrap().paused_for_storage);
    assert_eq!(stored_sightings(&core), 0);
    let events = wait_event(&c, |e| matches!(e, CoreEvent::StorageFull { .. }));
    assert!(events.iter().any(|e| matches!(
        e,
        CoreEvent::StorageFull {
            free_bytes: 199_999_999,
            dropped_scans: 4
        }
    )));
    // a user pause is not a storage pause: its resume needs no free space
    core.resume_recording_free_at(t0 + 6_000, Some(300_000_000))
        .unwrap();
    core.pause_recording_at(t0 + 7_000).unwrap();
    assert!(!core.recording_state().unwrap().paused_for_storage);
    core.resume_recording_free_at(t0 + 8_000, Some(1)).unwrap();
}

/// #71: commits that keep failing (not for space) back off and the pending buffer stays capped; past the cap
/// ingest is refused with Backpressure, and the retained rows commit once storage works again.
#[test]
fn repeated_commit_failures_keep_pending_bounded() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    core.start_recording_at(env(), t0).unwrap();
    core.fail_commits(Some(CoreError::Db {
        detail: "disk I/O error".into(),
    }));
    let cap = super::machine::PENDING_CAP.rows as usize;
    let mut accepted = 0usize;
    let mut refused = 0usize;
    for i in 0..(cap / 64 + 20) as i64 {
        let at = t0 + 5_000 + i;
        match core.ingest_scans_at(batch(64, at), at) {
            Ok(ack) => accepted += ack.accepted as usize,
            Err(CoreError::Backpressure) => refused += 1,
            Err(e) => panic!("{e}"),
        }
        assert!(core.pending_len() <= cap + 64, "pending stays capped");
    }
    assert!(refused > 0, "ingest is refused past the cap");
    assert_eq!(core.pending_len(), accepted);
    // backoff: an attempt failed at t0 + 5 s, so ticks before the retry time do not run the transaction again
    let warnings = |c: &Collect| {
        c.events
            .lock()
            .unwrap()
            .iter()
            .filter(|e| matches!(e, CoreEvent::StorageWarning { .. }))
            .count()
    };
    let before = warnings(&c);
    core.tick_at(t0 + 9_000);
    assert_eq!(warnings(&c), before);
    core.fail_commits(None);
    core.tick_at(t0 + 60_000);
    assert_eq!(core.pending_len(), 0);
    assert_eq!(stored_sightings(&core), accepted as i64);
}

fn write_file(path: &std::path::Path, len: usize) {
    std::fs::create_dir_all(path.parent().unwrap()).unwrap();
    std::fs::write(path, vec![7u8; len]).unwrap();
}

/// SPEC R-10 cleanup: both actions free space, report it beforehand, and never touch files not yet uploaded.
#[test]
fn cleanup_frees_space_and_keeps_unuploaded_files() {
    let t0 = 1_761_000_000_000i64;
    let (core, dir, _c) = fresh_core(t0);
    let root = dir.path();
    let pending = [
        root.join("pending/scans/v3/2025/10/20/a.parquet"),
        root.join("pending/gatt/v1/2025/10/20/b.json"),
    ];
    for p in &pending {
        write_file(p, 1_000);
    }
    write_file(
        &root.join("archive/sessions/s/scans/v3/2025/10/20/c.parquet"),
        30_000,
    );
    write_file(&root.join("archive/gatt/v1/2025/10/20/d.json"), 2_000);
    // an old session with many devices, then the current one
    let old = core.start_recording_at(env(), t0).unwrap();
    for i in 0..40 {
        let at = t0 + 1_000 + i;
        let mut b = batch(64, at);
        for (j, o) in b.iter_mut().enumerate() {
            o.addr = format!("BB:00:00:00:{:02X}:{:02X}", i, j);
            o.raw.extend((i as u16 * 64 + j as u16).to_be_bytes());
        }
        core.ingest_scans_at(b, at).unwrap();
    }
    core.stop_recording_at(t0 + 10_000).unwrap();
    let current = core.start_recording_at(env(), t0 + 20_000).unwrap();
    core.ingest_scans_at(batch(5, t0 + 21_000), t0 + 21_000)
        .unwrap();
    core.tick_at(t0 + 26_000);

    let r = block(core.reclaimable()).unwrap();
    assert_eq!(r.uploaded_raw_bytes, 32_000);
    assert!(r.local_database_bytes > 0);
    assert_eq!(block(core.delete_uploaded_raw()).unwrap(), 32_000);
    assert!(!root.join("archive/gatt/v1/2025/10/20/d.json").exists());
    assert_eq!(block(core.reclaimable()).unwrap().uploaded_raw_bytes, 0);

    let freed = block(core.clear_local_database()).unwrap();
    assert!(freed > 0, "the vacuum shrank local.db");
    for p in &pending {
        assert!(p.exists(), "{} survives", p.display());
    }
    let ids: Vec<String> = core.with_writer_conn(|c| {
        c.prepare("SELECT uuid FROM sessions")
            .unwrap()
            .query_map([], |r| r.get(0))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap()
    });
    assert_eq!(ids, vec![current.clone()]);
    assert!(!ids.contains(&old));
    assert!(block(core.sessions()).unwrap().is_empty());
    assert_eq!(stored_sightings(&core), 5, "the current session is kept");
    // the recording goes on: new payloads after the cache reset commit under their own ids
    let mut o = obs("CC:00:00:00:00:01", -50, t0 + 27_000, 2, "New");
    o.raw.push(1);
    core.ingest_scans_at(vec![o], t0 + 27_000).unwrap();
    assert_eq!(core.stop_recording_at(t0 + 28_000).unwrap(), current);
    assert_eq!(stored_sightings(&core), 6);
    core.with_writer_conn(|c| {
        let n: i64 = c
            .query_row("SELECT count(*) FROM payloads", [], |r| r.get(0))
            .unwrap();
        assert!(n <= 2, "orphan payloads are gone ({n})");
    });
}

/// SPEC V-3 (600c2de): a session that stopped with 0 devices keeps its route and frame when the scope is exactly that
/// session; every other scope (All, several sessions, a filter, the session list) still leaves it out.
#[test]
fn an_empty_session_shows_only_on_its_own() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, _c) = fresh_core(t0);
    // a session with a device, then one with only a route
    let full = core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(
        vec![obs("AA:00:00:00:00:01", -60, t0 + 1_000, 1, "A")],
        t0 + 1_000,
    )
    .unwrap();
    core.stop_recording_at(t0 + 2_000).unwrap();
    let t1 = t0 + 10_000;
    let empty = core.start_recording_at(env(), t1).unwrap();
    for k in 0..4i64 {
        core.ingest_location_at(LocationFix {
            lat: 32.88 + k as f64 * 0.001,
            lon: -117.234,
            accuracy_meters: 5.0,
            observed_at_ms: t1 + 1_000 * (k + 1),
            elapsed_realtime_nanos: 1_000_000_000 * (k as u64 + 1),
        });
    }
    core.tick_at(t1 + 6_000);
    core.stop_recording_at(t1 + 7_000).unwrap();
    let one = |id: &str, filter: Option<SessionFilter>| Scope {
        sessions: SessionScope::Sessions {
            ids: vec![id.to_owned()],
        },
        filter,
    };

    let v = block(core.session_view(one(&empty, None))).unwrap();
    assert_eq!(v.session_ids, vec![empty.clone()]);
    assert_eq!(v.device_count, 0);
    assert_eq!(v.route.len(), 4, "its route");
    let frame = v.frame.expect("its frame");
    assert!(frame.south <= 32.88 && frame.north >= 32.883);
    assert!(!v.routes_faint, "one route with Start and End (M-8)");
    assert!(keys(&block(core.device_order(one(&empty, None))).unwrap()).is_empty());
    let routes = block(core.routes(one(&empty, None), None, 15.0)).unwrap();
    assert!(routes.contains("LineString"));

    // every other scope keeps the rule
    let all = block(core.session_view(ALL)).unwrap();
    assert!(!all.session_ids.contains(&empty));
    assert!(all.session_ids.contains(&full));
    let both = Scope {
        sessions: SessionScope::Sessions {
            ids: vec![empty.clone(), full.clone()],
        },
        filter: None,
    };
    assert_eq!(
        block(core.session_view(both)).unwrap().session_ids,
        vec![full.clone()]
    );
    let filtered = one(&empty, Some(SessionFilter::default()));
    assert!(
        block(core.session_view(filtered))
            .unwrap()
            .session_ids
            .is_empty()
    );
    let listed: Vec<String> = block(core.sessions())
        .unwrap()
        .into_iter()
        .map(|s| s.id)
        .collect();
    assert_eq!(listed, vec![full.clone()]);
    assert_eq!(block(core.all_sessions_summary()).unwrap().session_count, 1);
}

fn dev(i: usize, rssi: i16, at: i64) -> ScanObservationInput {
    obs(
        &format!("AA:00:00:00:{:02X}:{:02X}", i / 256, i % 256),
        rssi,
        at,
        at as u64,
        "D",
    )
}

fn rate(core: &Core, id: &str) -> DiscoveryRate {
    block(core.discovery_rate(id.to_owned())).unwrap()
}

fn bins(r: &DiscoveryRate) -> Vec<(i64, u32)> {
    r.bins.iter().map(|b| (b.start_ms, b.new_devices)).collect()
}

/// R-4a: standing still, everything nearby is found early and the later bins are 0 (missing); the deltas carry the
/// changed bins and the runs.
#[test]
fn a_stationary_session_decays_to_zero_new() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    for i in 0..8 {
        core.ingest_scans_at(
            vec![dev(i, -60, t0 + 1_000 + i as i64 * 1_000)],
            t0 + 1_000 + i as i64 * 1_000,
        )
        .unwrap();
    }
    // the same eight devices for five minutes
    let mut t = t0 + 10_000;
    while t < t0 + 300_000 {
        let batch: Vec<_> = (0..8).map(|i| dev(i, -60, t)).collect();
        core.ingest_scans_at(batch, t).unwrap();
        core.tick_at(t + 1);
        t += 5_000;
    }
    let r = rate(&core, &id);
    assert_eq!(r.bin_ms, 5_000);
    assert_eq!(
        r.runs,
        vec![DiscoveryRun {
            start_ms: t0,
            end_ms: None
        }]
    );
    // the eight first sightings at +1..+8 s fill the first two 5 s bins; every later bin is 0, so missing
    assert_eq!(
        bins(&r),
        vec![(t0, 4), (t0 + 5_000, 4)],
        "every later bin is 0, so missing"
    );
    assert!(r.seq > 0, "live: the delta seq of the snapshot");
    // the deltas: the first carries the bin, none after repeats it unchanged
    let deltas = wait_for(&c, 2);
    let with: Vec<_> = deltas.iter().filter_map(|d| d.discovery.as_ref()).collect();
    assert!(!with.is_empty());
    assert_eq!(
        applied(&deltas, &id).0,
        bins(&r),
        "the deltas carry the bins"
    );
    assert!(with.iter().all(|u| u.runs == r.runs));
}

/// R-4a: a pause ends a run; the next run counts its bins from its own start; a restart rebuilds the same rate.
#[test]
fn a_pause_breaks_the_runs_and_a_restart_rebuilds_them() {
    let t0 = 1_761_000_000_000i64;
    let dir = tempfile::tempdir().unwrap();
    let open = |now: i64| {
        Core::open_at(
            config(dir.path()),
            CoreAssets {
                wiki_bundle_json: resource("fake-bundle.json"),
                sig_numbers_tsv: String::new(),
            },
            now,
        )
        .unwrap()
    };
    let core = open(t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(vec![dev(1, -60, t0 + 5_000)], t0 + 5_000)
        .unwrap();
    core.pause_recording_at(t0 + 60_000).unwrap();
    core.resume_recording_at(t0 + 120_000).unwrap();
    core.ingest_scans_at(vec![dev(2, -60, t0 + 125_000)], t0 + 125_000)
        .unwrap();
    core.ingest_scans_at(vec![dev(3, -60, t0 + 160_000)], t0 + 160_000)
        .unwrap();
    core.tick_at(t0 + 165_000);
    let live = rate(&core, &id);
    assert_eq!(
        live.runs,
        vec![
            DiscoveryRun {
                start_ms: t0,
                end_ms: Some(t0 + 60_000)
            },
            DiscoveryRun {
                start_ms: t0 + 120_000,
                end_ms: None
            },
        ],
        "the paused minute lies between the runs"
    );
    assert_eq!(
        bins(&live),
        vec![(t0 + 5_000, 1), (t0 + 125_000, 1), (t0 + 160_000, 1)]
    );
    // killed mid-recording: the restart rebuilds the bins from local.db; the session is interrupted, its run ends
    drop(core);
    let core = open(t0 + 200_000);
    let after = rate(&core, &id);
    assert_eq!(bins(&after), bins(&live));
    assert_eq!(after.runs.first(), live.runs.first());
    assert!(
        after.runs.last().unwrap().end_ms.is_some(),
        "an interrupted session has no open run"
    );
    assert_eq!(after.seq, 0, "not live");
    // a stopped session reads the same after another restart
    let other = core.start_recording_at(env(), t0 + 300_000).unwrap();
    core.ingest_scans_at(vec![dev(4, -60, t0 + 301_000)], t0 + 301_000)
        .unwrap();
    core.stop_recording_at(t0 + 340_000).unwrap();
    let stopped = rate(&core, &other);
    drop(core);
    let core = open(t0 + 400_000);
    assert_eq!(rate(&core, &other), stopped);
    assert_eq!(bins(&stopped), vec![(t0 + 300_000, 1)]);
    assert_eq!(
        stopped.runs,
        vec![DiscoveryRun {
            start_ms: t0 + 300_000,
            end_ms: Some(t0 + 340_000)
        }]
    );
    // an unknown session has nothing
    let none = rate(&core, "nope");
    assert!(none.bins.is_empty() && none.runs.is_empty());
}

/// R-4a randomized: whatever the interleaving of new and known devices, pauses and ticks, every session's bins add up
/// to its distinct device count, and every bin starts on its run's 5 s grid.
#[test]
fn randomized_discovery_bins_sum_to_the_device_count() {
    for seed in [3u64, 11, 2026] {
        let mut state = seed;
        let mut rnd = move |n: u64| {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            (state >> 33) % n
        };
        let t0 = 1_761_000_000_000i64;
        let (core, _dir, _c) = fresh_core(t0);
        let mut now = t0;
        let mut sessions = Vec::new();
        let mut paused = false;
        let mut next_dev = 0usize;
        for _ in 0..6 {
            let id = core.start_recording_at(env(), now).unwrap();
            for _ in 0..120 {
                now += 1 + rnd(8_000) as i64;
                match rnd(10) {
                    0 => {
                        if paused {
                            core.resume_recording_at(now).unwrap();
                        } else {
                            core.pause_recording_at(now).unwrap();
                        }
                        paused = !paused;
                    }
                    1 => core.tick_at(now),
                    _ => {
                        let i = if rnd(3) == 0 && next_dev > 0 {
                            rnd(next_dev as u64) as usize
                        } else {
                            next_dev += 1;
                            next_dev - 1
                        };
                        core.ingest_scans_at(vec![dev(i, -60, now)], now).unwrap();
                    }
                }
            }
            core.stop_recording_at(now).unwrap();
            paused = false;
            sessions.push(id);
        }
        for id in &sessions {
            let r = rate(&core, id);
            let count: i64 = core.with_writer_conn({
                let id = id.clone();
                move |c| {
                    c.query_row(
                        "SELECT device_count_shown FROM sessions WHERE uuid = ?1",
                        [id],
                        |r| r.get(0),
                    )
                    .unwrap()
                }
            });
            let sum: u32 = r.bins.iter().map(|b| b.new_devices).sum();
            assert_eq!(
                i64::from(sum),
                count,
                "seed {seed}: the bins add up to the session's devices"
            );
            for b in &r.bins {
                assert!(b.new_devices > 0, "sparse: no empty bins");
                assert!(
                    r.runs.iter().any(|run| b.start_ms >= run.start_ms
                        && (b.start_ms - run.start_ms) % 5_000 == 0
                        && run.end_ms.is_none_or(|e| b.start_ms <= e)),
                    "seed {seed}: bin {} is on a run's grid",
                    b.start_ms
                );
            }
            for w in r.runs.windows(2) {
                assert!(
                    w[0].end_ms.unwrap() <= w[1].start_ms,
                    "runs are ordered and disjoint"
                );
            }
        }
    }
}

/// D-10: while live, `device_chart` is the device's last minute at 1 s resolution (no samples while paused); the
/// focused key's chart rides on the deltas; rows and deltas carry no sparklines (C-5/C-6); after Stop it is `None`.
#[test]
fn the_live_chart_follows_the_last_minute() {
    let t0 = 1_761_000_000_000i64; // a whole second
    let (core, _dir, c) = fresh_core(t0);
    let key = 0xAA00_0000_0001i64;
    let chart = |now: i64| block(core.device_chart_at(key, now)).unwrap();
    assert_eq!(chart(t0), None, "not live");
    let id = core.start_recording_at(env(), t0).unwrap();
    assert_eq!(chart(t0), None, "not part of the session yet");
    for (s, rssi) in [(1, -50), (2, -60), (2, -45), (5, -70)] {
        core.ingest_scans_at(
            vec![obs(
                "AA:00:00:00:00:01",
                rssi,
                t0 + s * 1_000 + 100,
                s as u64,
                "A",
            )],
            t0 + s * 1_000 + 100,
        )
        .unwrap();
    }
    let at = t0 + 5_500;
    let ch = chart(at).expect("live");
    assert_eq!((ch.key, ch.end_ms, ch.samples.len()), (key, at, 60));
    // buckets 55..59 are seconds 1..5: max per second, gaps between
    assert_eq!(&ch.samples[55..], &[50, 45, 0, 0, 70]);
    assert_eq!(ch.rssi, Some(-70));
    // paused: ingest is ignored, so the paused seconds are gaps
    core.pause_recording_at(t0 + 6_000).unwrap();
    core.ingest_scans_at(
        vec![obs("AA:00:00:00:00:01", -40, t0 + 7_000, 7, "A")],
        t0 + 7_000,
    )
    .unwrap();
    core.resume_recording_at(t0 + 20_000).unwrap();
    core.ingest_scans_at(
        vec![obs("AA:00:00:00:00:01", -55, t0 + 20_100, 20, "A")],
        t0 + 20_100,
    )
    .unwrap();
    let ch = chart(t0 + 20_500).unwrap();
    assert_eq!(ch.samples.iter().filter(|b| **b != 0).count(), 4);
    assert_eq!(ch.samples[59], 55);
    // seconds 1, 2, 5 and 20 are buckets 40, 41, 44 and 59; the paused seconds 6..19 (45..58) are gaps
    assert_eq!(
        (ch.samples[40], ch.samples[41], ch.samples[44]),
        (50, 45, 70)
    );
    assert!(
        ch.samples[45..59].iter().all(|b| *b == 0),
        "nothing while paused"
    );
    // at 61.5 s the window is seconds 2..=61: second 1 just left it
    let ch = chart(t0 + 61_500).unwrap();
    assert_eq!(ch.samples.iter().filter(|b| **b != 0).count(), 3);
    assert_eq!(ch.samples[0], 45, "second 2 is the oldest bucket");
    assert_eq!(ch.rssi, None, "stale");
    // the focused chart rides on the deltas; no sparklines anywhere
    core.set_live_focus(Some(key)).unwrap();
    core.ingest_scans_at(
        vec![
            obs("AA:00:00:00:00:01", -52, t0 + 62_000, 62, "A"),
            obs("AA:00:00:00:00:02", -66, t0 + 62_000, 62, "B"),
        ],
        t0 + 62_000,
    )
    .unwrap();
    core.tick_at(t0 + 66_000);
    // ct-notify delivers asynchronously: wait for the delta that carries the chart, not just any delta
    for _ in 0..400 {
        if c.deltas.lock().unwrap().iter().any(|d| d.chart.is_some()) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    let deltas = c.deltas.lock().unwrap().clone();
    let last = deltas
        .iter()
        .rev()
        .find(|d| d.chart.is_some())
        .expect("a delta with the focused chart");
    let ch = last.chart.as_ref().expect("the focused chart");
    assert_eq!((ch.key, ch.end_ms), (key, last.at_ms));
    let bucket = |sec: i64, end_ms: i64| (sec - (end_ms / 1000 - (t0 / 1000) - 59)) as usize;
    assert_eq!(ch.samples[bucket(62, ch.end_ms)], 52);
    assert!(last.appended.iter().all(|r| r.spark.is_none()));
    assert!(
        deltas
            .iter()
            .all(|d| d.changed.iter().all(|l| l.spark.is_empty()))
    );
    let scope = Scope {
        sessions: SessionScope::Sessions {
            ids: vec![id.clone()],
        },
        filter: None,
    };
    let order = keys(&block(core.device_order(scope.clone())).unwrap());
    assert!(
        block(core.device_rows(scope, order))
            .unwrap()
            .iter()
            .all(|r| r.spark.is_none())
    );
    // another device keeps its own ring
    let other = block(core.device_chart_at(0xAA00_0000_0002, t0 + 66_000))
        .unwrap()
        .unwrap();
    assert_eq!(other.samples[55], 66);
    core.stop_recording_at(t0 + 67_000).unwrap();
    assert_eq!(chart(t0 + 67_000), None, "stopped: not live");
}

/// An unnamed Apple device (company field 76, no name): the display filter hides it.
fn apple(i: usize, at: i64) -> ScanObservationInput {
    let mut o = dev(i, -60, at);
    o.raw = vec![2, 1, 6, 4, 0xFF, 0x4C, 0x00, 0x10];
    o.local_name = None;
    o
}

/// The deltas' discovery parts applied in order to an empty snapshot (a total of 0 removes the bin).
fn applied(deltas: &[LiveDelta], session: &str) -> (Vec<(i64, u32)>, Vec<DiscoveryRun>) {
    let mut bins = BTreeMap::new();
    let mut runs = Vec::new();
    for d in deltas.iter().filter(|d| d.session_id == session) {
        if let Some(u) = &d.discovery {
            for b in &u.bins {
                match b.new_devices {
                    0 => bins.remove(&b.start_ms),
                    n => bins.insert(b.start_ms, n),
                };
            }
            runs = u.runs.clone();
        }
    }
    (bins.into_iter().collect(), runs)
}

/// The deltas once the one with `seq` has arrived.
fn deltas_through(c: &Collect, seq: u64) -> Vec<LiveDelta> {
    for _ in 0..400 {
        if c.deltas.lock().unwrap().iter().any(|d| d.seq >= seq) {
            break;
        }
        std::thread::sleep(std::time::Duration::from_millis(5));
    }
    c.deltas.lock().unwrap().clone()
}

fn hide_apple(core: &Core, hide: bool) {
    core.set_display_filter(DisplayFilter {
        hide_unnamed_apple: hide,
    })
    .unwrap();
}

/// #112 review: an unnamed Apple device counts only while the display filter shows it; turning the filter on
/// mid-recording empties its bin, and the deltas send that bin as 0.
#[test]
fn hidden_devices_leave_the_rate_and_their_bin_goes_out_as_zero() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    hide_apple(&core, false);
    let id = core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(vec![apple(1, t0 + 1_000)], t0 + 1_000)
        .unwrap();
    core.ingest_scans_at(vec![dev(2, -60, t0 + 40_000)], t0 + 40_000)
        .unwrap();
    core.tick_at(t0 + 45_000);
    assert_eq!(
        bins(&rate(&core, &id)),
        vec![(t0, 1), (t0 + 40_000, 1)],
        "shown: counted"
    );
    hide_apple(&core, true);
    let fresh = rate(&core, &id);
    assert_eq!(bins(&fresh), vec![(t0 + 40_000, 1)], "hidden: not counted");
    // the next delta tells the UI that bin t0 is now 0
    core.ingest_scans_at(vec![dev(2, -60, t0 + 50_000)], t0 + 50_000)
        .unwrap();
    core.tick_at(t0 + 55_000);
    let deltas = deltas_through(&c, rate(&core, &id).seq);
    assert!(
        deltas
            .iter()
            .filter_map(|d| d.discovery.as_ref())
            .any(|u| u.bins.contains(&DiscoveryBin {
                start_ms: t0,
                new_devices: 0
            })),
        "the emptied bin is sent as 0"
    );
    assert_eq!(applied(&deltas, &id), (bins(&fresh), fresh.runs.clone()));
}

/// #112 review: a late first sighting moves a device to an earlier bin; the later one, now empty, goes out as 0, so
/// the device is not counted twice.
#[test]
fn a_late_first_sighting_moves_the_device_and_empties_its_bin() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, c) = fresh_core(t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    core.ingest_scans_at(vec![dev(5, -60, t0 + 40_000)], t0 + 40_000)
        .unwrap();
    core.tick_at(t0 + 45_000);
    assert_eq!(bins(&rate(&core, &id)), vec![(t0 + 40_000, 1)]);
    // the same device, scanned earlier, arrives late
    core.ingest_scans_at(vec![dev(5, -60, t0 + 10_000)], t0 + 46_000)
        .unwrap();
    core.tick_at(t0 + 51_000);
    let fresh = rate(&core, &id);
    assert_eq!(bins(&fresh), vec![(t0 + 10_000, 1)]);
    let deltas = deltas_through(&c, fresh.seq);
    let (applied_bins, _) = applied(&deltas, &id);
    assert_eq!(
        applied_bins,
        vec![(t0 + 10_000, 1)],
        "counted once, in the earlier bin"
    );
}

/// #112 review, randomized: whatever happens (new, known and late devices, hidden ones, filter flips, pauses), the
/// empty snapshot plus every delta's discovery part equals a fresh read.
#[test]
fn randomized_snapshot_plus_deltas_equals_a_fresh_read() {
    for seed in [5u64, 17, 2027] {
        let mut state = seed;
        let mut rnd = move |n: u64| {
            state = state
                .wrapping_mul(6_364_136_223_846_793_005)
                .wrapping_add(1_442_695_040_888_963_407);
            (state >> 33) % n
        };
        let t0 = 1_761_000_000_000i64;
        let (core, _dir, c) = fresh_core(t0);
        let id = core.start_recording_at(env(), t0).unwrap();
        let mut now = t0;
        let mut paused = false;
        let mut hidden = true;
        let mut next = 0usize;
        // named devices that were alone in their bin: (device, first sighting)
        let mut solo: Vec<(usize, i64)> = Vec::new();
        for _ in 0..300 {
            now += 1 + rnd(6_000) as i64;
            match rnd(20) {
                0 => {
                    if paused {
                        core.resume_recording_at(now).unwrap();
                    } else {
                        core.pause_recording_at(now).unwrap();
                    }
                    paused = !paused;
                }
                // a filter flip empties the bins that hold only unnamed Apple devices
                1..=2 => {
                    hidden = !hidden;
                    hide_apple(&core, hidden);
                }
                3..=4 => core.tick_at(now),
                // a bin of its own: jump past the current bin, one new device, committed soon
                5..=8 => {
                    now += 30_000 + rnd(15_000) as i64;
                    next += 1;
                    if rnd(2) == 0 {
                        core.ingest_scans_at(vec![apple(next, now)], now).unwrap();
                    } else {
                        core.ingest_scans_at(vec![dev(next, -60, now)], now)
                            .unwrap();
                        solo.push((next, now));
                    }
                    now += 5_001;
                    core.tick_at(now);
                }
                // a late sighting moves a lone device to an earlier bin and empties its own
                9..=11 if !solo.is_empty() => {
                    let (i, first) = solo.swap_remove(rnd(solo.len() as u64) as usize);
                    let at = first - 30_000 - rnd(30_000) as i64;
                    core.ingest_scans_at(vec![dev(i, -60, at)], now).unwrap();
                }
                12..=13 if next > 0 => {
                    let at = now - rnd(90_000) as i64;
                    let i = rnd(next as u64) as usize;
                    core.ingest_scans_at(vec![dev(i, -60, at)], now).unwrap();
                }
                _ => {
                    next += 1;
                    core.ingest_scans_at(vec![dev(next, -60, now)], now)
                        .unwrap();
                }
            }
        }
        if paused {
            now += 1_000;
            core.resume_recording_at(now).unwrap();
        }
        // one more commit carries whatever changed since the last delta
        now += 6_000;
        core.ingest_scans_at(vec![dev(next + 1, -60, now)], now)
            .unwrap();
        core.tick_at(now + 6_000);
        let fresh = rate(&core, &id);
        let deltas = deltas_through(&c, fresh.seq);
        assert_eq!(
            applied(&deltas, &id),
            (bins(&fresh), fresh.runs.clone()),
            "seed {seed}: snapshot + deltas = a fresh read"
        );
        let zeros = deltas
            .iter()
            .filter_map(|d| d.discovery.as_ref())
            .flat_map(|u| u.bins.iter())
            .filter(|b| b.new_devices == 0)
            .count();
        assert!(
            zeros >= 3,
            "seed {seed}: the generator empties whole bins ({zeros} sent as 0)"
        );
    }
}

/// #111 follow-up: a dropped query's cancel flag never reaches the next one, even a long one the progress handler
/// checks many times.
#[test]
fn a_cancel_never_reaches_a_long_next_query() {
    let (core, _dir, _c) = fresh_core(1_761_000_000_000);
    let endless =
        "WITH RECURSIVE r(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM r) SELECT count(*) FROM r";
    let long = "WITH RECURSIVE r(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM r WHERE i < 200000) SELECT count(*) FROM r";
    for n in 0..20u64 {
        let dropped = core.read(move |c, _| Ok(c.query_row(endless, [], |r| r.get::<_, i64>(0))?));
        if n % 2 == 1 {
            std::thread::sleep(std::time::Duration::from_millis(2));
        }
        drop(dropped);
        let v = block(core.read(move |c, _| Ok(c.query_row(long, [], |r| r.get::<_, i64>(0))?)))
            .expect("the next query is not cancelled");
        assert_eq!(v, 200_000);
    }
}

/// #111 follow-up: the reader gets its new snapshot before Stop replies, so a query sent right after sees the session
/// no longer live (no chart, a frozen view).
#[test]
fn queries_after_stop_see_it() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, _c) = fresh_core(t0);
    let key = 0xAA00_0000_0001i64;
    for k in 0..20i64 {
        let at = t0 + k * 100_000;
        let id = core.start_recording_at(env(), at).unwrap();
        core.ingest_scans_at(vec![dev(1, -60, at + 1_000)], at + 1_000)
            .unwrap();
        assert!(
            block(core.device_chart_at(key, at + 1_500))
                .unwrap()
                .is_some()
        );
        core.stop_recording_at(at + 2_000).unwrap();
        assert_eq!(
            block(core.device_chart_at(key, at + 2_000)).unwrap(),
            None,
            "round {k}"
        );
        let scope = Scope {
            sessions: SessionScope::Sessions { ids: vec![id] },
            filter: None,
        };
        assert!(!block(core.session_view(scope)).unwrap().live, "round {k}");
    }
}

/// Owner bug (51cce33): while recording, the device sheet of a device on the live map was blank, so `device(scope,
/// key)` gave nothing for the live session. Every key the live map or rows can show resolves to a detail.
#[test]
fn a_live_device_has_a_detail() {
    let t0 = 1_761_000_000_000i64;
    let (core, _dir, _c) = fresh_core(t0);
    let id = core.start_recording_at(env(), t0).unwrap();
    let key = 0xAA00_0000_0001i64;
    let live = Scope {
        sessions: SessionScope::Sessions {
            ids: vec![id.clone()],
        },
        filter: None,
    };
    core.ingest_scans_at(
        vec![obs("AA:00:00:00:00:01", -60, t0 + 1_000, 1, "A")],
        t0 + 1_000,
    )
    .unwrap();
    core.tick_at(t0 + 6_000);
    for scope in [live.clone(), ALL] {
        let d = block(core.device(scope.clone(), key)).unwrap();
        assert!(d.is_some(), "committed, {scope:?}");
    }
    // the sheet's D-10 chart: the device's last minute
    let chart = block(core.device_chart_at(key, t0 + 6_000))
        .unwrap()
        .expect("a live chart");
    assert_eq!(chart.samples.len(), 60);
    assert!(chart.samples.iter().any(|b| *b != 0));
}
