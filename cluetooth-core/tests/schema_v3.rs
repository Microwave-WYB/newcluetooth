use std::fs::File;

use cluetooth_core::{
    CoreError, ScanEnvironment, SchemaV3Row, inspect_schema_v2_parquet, write_schema_v3_parquet,
};
use polars::prelude::*;
use polars_parquet::arrow::read::read_metadata;
use polars_parquet::parquet::compression::Compression;
use tempfile::tempdir;

const PAYLOAD_ID: &str = "0195c920-7c00-7abc-8def-0123456789ab";
type RowMutation = Box<dyn Fn(&mut SchemaV3Row)>;

const V3_COLUMNS: [&str; 18] = [
    "addr",
    "rssi",
    "scanned_at",
    "raw",
    "local_name",
    "tx_power",
    "is_connectable",
    "lat",
    "lon",
    "accuracy",
    "is_legacy",
    "primary_phy",
    "secondary_phy",
    "adv_sid",
    "adv_tx_power",
    "periodic_interval",
    "data_status",
    "addr_type",
];

fn environment() -> ScanEnvironment {
    ScanEnvironment {
        device_model: "Pixel 8".to_owned(),
        android_version: "15 (API 35)".to_owned(),
        app_version: "0.1.0-debug".to_owned(),
        le_extended_advertising_supported: true,
        le_coded_phy_supported: false,
        le_max_advertising_data_length: 1650,
        scan_phy: "all_supported".to_owned(),
        scan_legacy_only: false,
    }
}

fn extended_row() -> SchemaV3Row {
    SchemaV3Row {
        addr: "AA:BB:CC:DD:EE:FF".to_owned(),
        rssi: Some(-47),
        scanned_at_ms: 1_741_435_200_123,
        // Longest extended advertisement; the first AD structure fills the rest.
        raw: (0..1650).map(|index| (index % 251) as u8).collect(),
        local_name: Some("sensor".to_owned()),
        tx_power: Some(-12),
        is_connectable: Some(true),
        lat: Some(32.8801),
        lon: Some(-117.234),
        accuracy: Some(3.25),
        is_legacy: Some(false),
        primary_phy: Some(3),
        secondary_phy: Some(2),
        adv_sid: Some(15),
        adv_tx_power: Some(-127),
        periodic_interval: Some(65_535),
        data_status: Some(2),
        addr_type: Some(1),
    }
}

fn legacy_row_with_nulls() -> SchemaV3Row {
    SchemaV3Row {
        addr: "00:11:22:33:44:55".to_owned(),
        rssi: None,
        scanned_at_ms: 1_741_435_201_999,
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

#[test]
fn round_trip_preserves_every_value_null_and_a_1650_byte_raw() {
    let directory = tempdir().unwrap();
    let path = directory.path().join(format!("{PAYLOAD_ID}.parquet"));
    let rows = vec![extended_row(), legacy_row_with_nulls()];

    let inspection = write_schema_v3_parquet(
        path.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        environment(),
        rows.clone(),
    )
    .unwrap();
    assert_eq!(inspection.schema_version, "v3");
    assert_eq!(inspection.row_count, 2);
    assert_eq!(inspection.column_names, V3_COLUMNS);
    assert_eq!(inspection.environment, Some(environment()));

    let reread = inspect_schema_v2_parquet(path.to_string_lossy().into_owned()).unwrap();
    assert_eq!(reread, inspection);

    let metadata = read_metadata(&mut File::open(&path).unwrap()).unwrap();
    assert!(metadata.row_groups.iter().all(|group| {
        group
            .parquet_columns()
            .iter()
            .all(|column| column.compression() == Compression::Zstd)
    }));

    let frame = ParquetReader::new(File::open(&path).unwrap())
        .finish()
        .unwrap();
    let names: Vec<&str> = frame
        .get_column_names()
        .into_iter()
        .map(|name| name.as_str())
        .collect();
    assert_eq!(names, V3_COLUMNS);
    assert_eq!(
        frame.column("is_legacy").unwrap().dtype(),
        &DataType::Boolean
    );
    for name in [
        "primary_phy",
        "secondary_phy",
        "adv_sid",
        "adv_tx_power",
        "periodic_interval",
        "data_status",
        "addr_type",
    ] {
        assert_eq!(
            frame.column(name).unwrap().dtype(),
            &DataType::Int32,
            "{name}"
        );
        assert_eq!(frame.column(name).unwrap().null_count(), 1, "{name}");
    }
    assert_eq!(frame.column("is_legacy").unwrap().null_count(), 1);

    let raw = frame.column("raw").unwrap().binary().unwrap();
    assert_eq!(raw.get(0), Some(rows[0].raw.as_slice()));
    assert_eq!(raw.get(0).unwrap().len(), 1650);
    assert_eq!(raw.get(1), Some(rows[1].raw.as_slice()));
    let int = |name: &str| frame.column(name).unwrap().i32().unwrap().clone();
    assert_eq!(int("primary_phy").get(0), Some(3));
    assert_eq!(int("secondary_phy").get(0), Some(2));
    assert_eq!(int("adv_sid").get(0), Some(15));
    assert_eq!(int("adv_tx_power").get(0), Some(-127));
    assert_eq!(int("periodic_interval").get(0), Some(65_535));
    assert_eq!(int("data_status").get(0), Some(2));
    assert_eq!(int("addr_type").get(0), Some(1));
    assert_eq!(int("primary_phy").get(1), None);
    let is_legacy = frame.column("is_legacy").unwrap().bool().unwrap().clone();
    assert_eq!((is_legacy.get(0), is_legacy.get(1)), (Some(false), None));
}

#[test]
fn footer_carries_device_and_scanner_environment_once() {
    let directory = tempdir().unwrap();
    let path = directory.path().join(format!("{PAYLOAD_ID}.parquet"));
    write_schema_v3_parquet(
        path.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        environment(),
        vec![legacy_row_with_nulls()],
    )
    .unwrap();

    let metadata = read_metadata(&mut File::open(&path).unwrap()).unwrap();
    let footer = metadata.key_value_metadata().as_ref().unwrap();
    let value = |key: &str| {
        let matches: Vec<_> = footer.iter().filter(|entry| entry.key == key).collect();
        assert_eq!(matches.len(), 1, "{key}");
        matches[0].value.clone().unwrap()
    };
    assert_eq!(value("cluetooth.payload_schema"), "v3");
    assert_eq!(value("cluetooth.payload_id"), PAYLOAD_ID);
    assert_eq!(value("cluetooth.device_model"), "Pixel 8");
    assert_eq!(value("cluetooth.android_version"), "15 (API 35)");
    assert_eq!(value("cluetooth.app_version"), "0.1.0-debug");
    assert_eq!(value("cluetooth.le_extended_advertising_supported"), "true");
    assert_eq!(value("cluetooth.le_coded_phy_supported"), "false");
    assert_eq!(value("cluetooth.le_max_advertising_data_length"), "1650");
    assert_eq!(value("cluetooth.scan_phy"), "all_supported");
    assert_eq!(value("cluetooth.scan_legacy_only"), "false");
}

#[test]
fn invalid_environment_is_rejected_before_anything_is_written() {
    let directory = tempdir().unwrap();
    let path = directory.path().join(format!("{PAYLOAD_ID}.parquet"));
    let mut variants = Vec::new();
    for mutate in [
        Box::new(|value: &mut ScanEnvironment| value.device_model.clear())
            as Box<dyn Fn(&mut ScanEnvironment)>,
        Box::new(|value| value.app_version = "x".repeat(257)),
        Box::new(|value| value.scan_phy = "coded".to_owned()),
    ] {
        let mut value = environment();
        mutate(&mut value);
        variants.push(value);
    }
    for value in variants {
        let error = write_schema_v3_parquet(
            path.to_string_lossy().into_owned(),
            PAYLOAD_ID.to_owned(),
            value,
            vec![legacy_row_with_nulls()],
        )
        .unwrap_err();
        assert!(matches!(error, CoreError::InvalidPayload { .. }));
    }
    assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
}

#[test]
fn producer_validation_rejects_platform_sentinels_and_out_of_range_values() {
    let mutations: Vec<(&str, RowMutation)> = vec![
        ("raw too long", Box::new(|row| row.raw = vec![0; 1651])),
        ("primary_phy 2M", Box::new(|row| row.primary_phy = Some(2))),
        (
            "primary_phy unused",
            Box::new(|row| row.primary_phy = Some(0)),
        ),
        (
            "secondary_phy 4",
            Box::new(|row| row.secondary_phy = Some(4)),
        ),
        ("sid not present", Box::new(|row| row.adv_sid = Some(255))),
        ("sid negative", Box::new(|row| row.adv_sid = Some(-1))),
        (
            "tx power not present",
            Box::new(|row| row.adv_tx_power = Some(127)),
        ),
        (
            "interval zero",
            Box::new(|row| row.periodic_interval = Some(0)),
        ),
        (
            "interval too large",
            Box::new(|row| row.periodic_interval = Some(65_536)),
        ),
        ("data status 1", Box::new(|row| row.data_status = Some(1))),
        (
            "addr type unknown",
            Box::new(|row| row.addr_type = Some(65_535)),
        ),
        (
            "lowercase mac",
            Box::new(|row| row.addr = "aa:bb:cc:dd:ee:ff".to_owned()),
        ),
        ("lat without lon", Box::new(|row| row.lon = None)),
    ];
    let directory = tempdir().unwrap();
    for (name, mutate) in mutations {
        let mut row = extended_row();
        mutate(&mut row);
        let error = write_schema_v3_parquet(
            directory
                .path()
                .join("payload.parquet")
                .to_string_lossy()
                .into_owned(),
            PAYLOAD_ID.to_owned(),
            environment(),
            vec![row],
        )
        .unwrap_err();
        assert!(
            matches!(error, CoreError::InvalidPayload { .. }),
            "{name}: {error:?}"
        );
    }
    assert_eq!(std::fs::read_dir(directory.path()).unwrap().count(), 0);
}

#[test]
fn inspection_rejects_v3_files_missing_or_corrupting_footer_environment() {
    let directory = tempdir().unwrap();
    let path = directory.path().join("complete.parquet");
    write_schema_v3_parquet(
        path.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        environment(),
        vec![extended_row()],
    )
    .unwrap();
    let mut frame = ParquetReader::new(File::open(&path).unwrap())
        .finish()
        .unwrap();

    for (name, entries) in [
        (
            "no environment",
            vec![
                ("cluetooth.payload_schema", "v3"),
                ("cluetooth.payload_id", PAYLOAD_ID),
            ],
        ),
        (
            "bad boolean",
            vec![
                ("cluetooth.payload_schema", "v3"),
                ("cluetooth.payload_id", PAYLOAD_ID),
                ("cluetooth.device_model", "Pixel 8"),
                ("cluetooth.android_version", "15"),
                ("cluetooth.app_version", "1"),
                ("cluetooth.le_extended_advertising_supported", "yes"),
                ("cluetooth.le_coded_phy_supported", "true"),
                ("cluetooth.le_max_advertising_data_length", "1650"),
                ("cluetooth.scan_phy", "1m"),
                ("cluetooth.scan_legacy_only", "false"),
            ],
        ),
    ] {
        let broken = directory.path().join(format!("{name}.parquet"));
        let metadata = polars::io::parquet::write::KeyValueMetadata::from_static(
            entries
                .into_iter()
                .map(|(key, value)| (key.to_owned(), value.to_owned()))
                .collect(),
        );
        let mut file = File::create(&broken).unwrap();
        ParquetWriter::new(&mut file)
            .with_key_value_metadata(Some(metadata))
            .finish(&mut frame)
            .unwrap();
        let error = inspect_schema_v2_parquet(broken.to_string_lossy().into_owned()).unwrap_err();
        assert!(matches!(error, CoreError::InvalidPayload { .. }), "{name}");
    }
}
