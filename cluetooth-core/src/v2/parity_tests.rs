//! Parity with `:core-fake`: the data.js world is recorded through the real ingest path, then the fake's query
//! contracts run against the real core. The data comes from `scanner/core-fake/src/test/resources/`:
//! `data-js-fixture.json` (dumped from data.js) and `fake-bundle.json` (rules that reproduce data.js's products).

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::sync::Arc;

use serde_json::Value as Json;

use super::core::Core;
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
        recipient_public_key: vec![0; 32],
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
    // The [P]/[V] futures of this slice complete on the first poll.
    let waker = std::task::Waker::noop();
    let mut cx = std::task::Context::from_waker(waker);
    let mut f = std::pin::pin!(f);
    match f.as_mut().poll(&mut cx) {
        std::task::Poll::Ready(v) => v,
        std::task::Poll::Pending => panic!("query future did not complete"),
    }
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

/// L-2: newest first, the empty session (r4) never appears.
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
    assert!(
        block(w.core.session_view(scope(&w, "r4")))
            .unwrap()
            .session_ids
            .is_empty()
    );
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
    w.core.set_display_filter(DisplayFilter {
        hide_unnamed_apple: false,
    });
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
    w.core.with_writer_conn(|conn| {
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
    core.set_listener(Some(c.clone()));
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
