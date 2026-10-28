//! Fixtures written by the real producer code, shared with the sync side
//! (`test-fixtures/`, see its README). The checked-in scan file must equal what the
//! producer writes today, and every checked-in `gatt/v1` object must satisfy the
//! producer contract. Set `CLUETOOTH_UPDATE_FIXTURES=1` to rewrite the Parquet file.

use std::fs::{self, File};
use std::path::{Path, PathBuf};

use cluetooth_core::{
    ScanEnvironment, SchemaV3Row, inspect_schema_v2_parquet, validate_gatt_json,
    write_schema_v3_parquet,
};
use polars::prelude::*;
use tempfile::tempdir;

const PAYLOAD_ID: &str = "0195ca99-5000-7002-8000-000000000001";
const SESSION_ID: &str = "0195ca99-4fff-7555-9555-555555555555";

fn fixtures_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../test-fixtures")
}

fn scan_fixture_path() -> PathBuf {
    fixtures_root().join(format!("scans-v3/2025/03/25/{PAYLOAD_ID}.parquet"))
}

fn environment() -> ScanEnvironment {
    ScanEnvironment {
        device_model: "Pixel 8".to_owned(),
        android_release: "15".to_owned(),
        android_sdk: 35,
        app_version: "0.1.0-debug".to_owned(),
        cap_ext_adv: true,
        cap_coded_phy: true,
        cap_max_adv_len: 1650,
        scan_phy: "all_supported".to_owned(),
        scan_legacy_only: false,
    }
}

fn row() -> SchemaV3Row {
    SchemaV3Row {
        addr: "AA:BB:CC:DD:EE:FF".to_owned(),
        rssi: None,
        scanned_at_ms: 1_742_860_800_000,
        raw: vec![0x02, 0x01, 0x06],
        local_name: None,
        tx_power: None,
        is_connectable: None,
        lat: None,
        lon: None,
        accuracy: None,
        is_legacy: None,
        primary_phy: None,
        secondary_phy: None,
        adv_sid: None,
        adv_tx_power: None,
        periodic_interval: None,
        data_status: None,
        addr_type: None,
    }
}

/// Representative sightings: a long Coded-PHY extended advertisement, a legacy
/// advertisement with a name and a location, a truncated extended one, and one
/// where the platform reported nothing but the mandatory columns.
fn rows() -> Vec<SchemaV3Row> {
    let mut long_raw = vec![0x02, 0x01, 0x06, 0xff, 0xff, 0xff];
    long_raw.resize(1650, 0);
    long_raw[3] = 0xfe; // AD length byte declaring the rest of the payload
    vec![
        SchemaV3Row {
            rssi: Some(-97),
            raw: long_raw,
            is_connectable: Some(false),
            is_legacy: Some(false),
            primary_phy: Some(3),
            secondary_phy: Some(3),
            adv_sid: Some(15),
            adv_tx_power: Some(-12),
            periodic_interval: Some(480),
            data_status: Some(0),
            addr_type: Some(1),
            lat: Some(32.8801),
            lon: Some(-117.234),
            accuracy: Some(6.5),
            ..row()
        },
        SchemaV3Row {
            addr: "00:11:22:33:44:55".to_owned(),
            rssi: Some(-48),
            scanned_at_ms: 1_742_860_801_250,
            raw: vec![
                0x02, 0x01, 0x06, 0x07, 0x09, b's', b'e', b'n', b's', b'o', b'r', 0x02, 0x0a, 0xf4,
            ],
            local_name: Some("sensor".to_owned()),
            tx_power: Some(-12),
            is_connectable: Some(true),
            is_legacy: Some(true),
            primary_phy: Some(1),
            secondary_phy: Some(0),
            data_status: Some(0),
            addr_type: Some(0),
            lat: Some(32.8802),
            lon: Some(-117.2341),
            accuracy: Some(3.25),
            ..row()
        },
        SchemaV3Row {
            addr: "11:22:33:44:55:66".to_owned(),
            rssi: Some(-81),
            scanned_at_ms: 1_742_860_802_500,
            raw: vec![0x03, 0xff, 0x4c, 0x00],
            is_legacy: Some(false),
            primary_phy: Some(1),
            secondary_phy: Some(2),
            adv_sid: Some(0),
            data_status: Some(2),
            ..row()
        },
        row(),
    ]
}

fn frame(path: &Path) -> DataFrame {
    ParquetReader::new(File::open(path).unwrap())
        .finish()
        .unwrap()
}

#[test]
fn scan_v3_fixture_is_what_the_producer_writes() {
    let directory = tempdir().unwrap();
    let written = directory.path().join(format!("{PAYLOAD_ID}.parquet"));
    let inspection = write_schema_v3_parquet(
        written.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        Some(SESSION_ID.to_owned()),
        environment(),
        rows(),
    )
    .unwrap();
    assert_eq!(inspection.schema_version, "v3");
    assert_eq!(inspection.scan_session_id.as_deref(), Some(SESSION_ID));
    assert_eq!(inspection.row_count, 4);

    let checked_in = scan_fixture_path();
    if std::env::var_os("CLUETOOTH_UPDATE_FIXTURES").is_some() {
        fs::create_dir_all(checked_in.parent().unwrap()).unwrap();
        fs::copy(&written, &checked_in).unwrap();
    }
    let fixture = inspect_schema_v2_parquet(checked_in.to_string_lossy().into_owned())
        .expect("fixture is missing or invalid; rerun with CLUETOOTH_UPDATE_FIXTURES=1");
    assert_eq!(fixture, inspection);
    assert!(
        frame(&checked_in).equals_missing(&frame(&written)),
        "fixture differs from the producer output; rerun with CLUETOOTH_UPDATE_FIXTURES=1"
    );
}

#[test]
fn every_gatt_fixture_satisfies_the_producer_contract_and_its_path() {
    let root = fixtures_root().join("gatt-v1");
    let mut checked = 0;
    let mut stack = vec![root];
    while let Some(directory) = stack.pop() {
        for entry in fs::read_dir(directory).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                stack.push(path);
                continue;
            }
            if path.extension().and_then(|e| e.to_str()) != Some("json") {
                continue;
            }
            let json = fs::read_to_string(&path).unwrap();
            let id = validate_gatt_json(json).unwrap_or_else(|error| panic!("{path:?}: {error}"));
            assert_eq!(path.file_stem().unwrap().to_str().unwrap(), id);
            // The object path date is the UTC date of the UUIDv7.
            assert!(path.to_string_lossy().contains("/2025/03/25/"), "{path:?}");
            checked += 1;
        }
    }
    assert!(
        checked >= 9,
        "expected the golden and engine fixtures, found {checked}"
    );
}
