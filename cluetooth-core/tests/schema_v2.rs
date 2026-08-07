use std::fs::{self, File};

use cluetooth_core::{
    CoreError, SchemaV2Row, api_version, inspect_schema_v2_parquet, write_schema_v2_parquet,
};
use polars::io::parquet::write::KeyValueMetadata;
use polars::prelude::*;
use polars_parquet::arrow::read::read_metadata;
use polars_parquet::parquet::compression::Compression;
use tempfile::tempdir;

const PAYLOAD_ID: &str = "0195c920-7c00-7abc-8def-0123456789ab";
type RowMutation = Box<dyn Fn(&mut SchemaV2Row)>;

fn representative_rows() -> Vec<SchemaV2Row> {
    vec![
        SchemaV2Row {
            addr: "AA:BB:CC:DD:EE:FF".to_owned(),
            rssi: Some(-47),
            scanned_at_ms: 1_741_435_200_123,
            raw: vec![0x02, 0x01, 0x06, 0x05, 0xff, 0x00, 0x80, 0xfe],
            local_name: Some("sensor".to_owned()),
            tx_power: Some(-12),
            is_connectable: Some(true),
            lat: Some(32.8801),
            lon: Some(-117.234),
            accuracy: Some(3.25),
        },
        SchemaV2Row {
            addr: "00:11:22:33:44:55".to_owned(),
            rssi: None,
            scanned_at_ms: 1_741_435_201_999,
            raw: vec![0x00, 0xff, 0x10, 0x00],
            local_name: None,
            tx_power: None,
            is_connectable: None,
            lat: None,
            lon: None,
            accuracy: None,
        },
    ]
}

fn write_external_file_with_required_null(path: &std::path::Path, null_column: &str) {
    let addr = vec![
        (null_column != "addr").then_some("AA:BB:CC:DD:EE:FF"),
        Some("00:11:22:33:44:55"),
    ];
    let scanned_at = vec![
        (null_column != "scanned_at").then_some(1_741_435_200_123_i64),
        Some(1_741_435_201_999_i64),
    ];
    let raw: Vec<Option<&[u8]>> = vec![
        (null_column != "raw").then_some(&[0x02, 0x01, 0x06][..]),
        Some(&[0x00, 0xff][..]),
    ];
    let timestamp = Series::new("scanned_at".into(), scanned_at)
        .cast(&DataType::Datetime(
            TimeUnit::Milliseconds,
            Some(TimeZone::UTC),
        ))
        .unwrap();
    let mut frame = DataFrame::new(
        2,
        vec![
            Series::new("addr".into(), addr).into(),
            Series::new("rssi".into(), vec![Some(-47_i32), None]).into(),
            timestamp.into(),
            Series::new("raw".into(), raw).into(),
            Series::new("local_name".into(), vec![Some("sensor"), None]).into(),
            Series::new("tx_power".into(), vec![Some(-12_i32), None]).into(),
            Series::new("is_connectable".into(), vec![Some(true), None]).into(),
            Series::new("lat".into(), vec![Some(32.8801_f64), None]).into(),
            Series::new("lon".into(), vec![Some(-117.234_f64), None]).into(),
            Series::new("accuracy".into(), vec![Some(3.25_f32), None]).into(),
        ],
    )
    .unwrap();
    let metadata = KeyValueMetadata::from_static(vec![
        ("cluetooth.payload_schema".to_owned(), "v2".to_owned()),
        ("cluetooth.payload_id".to_owned(), PAYLOAD_ID.to_owned()),
    ]);
    ParquetWriter::new(File::create(path).unwrap())
        .with_compression(ParquetCompression::Zstd(None))
        .with_key_value_metadata(Some(metadata))
        .finish(&mut frame)
        .unwrap();
}

fn assert_required_null_rejected(column: &str) {
    let directory = tempdir().unwrap();
    let path = directory.path().join(format!("null-{column}.parquet"));
    write_external_file_with_required_null(&path, column);

    let error = inspect_schema_v2_parquet(path.to_string_lossy().into_owned()).unwrap_err();
    match error {
        CoreError::InvalidPayload { detail } => assert_eq!(
            detail,
            format!("required column {column} contains 1 null value(s)")
        ),
        other => panic!("expected required-null validation error, got {other:?}"),
    }
}

#[test]
fn ffi_api_version_is_stable() {
    assert_eq!(api_version(), 6);
}

#[test]
fn atomically_replaces_path_with_real_zstd_parquet_and_exact_contract() {
    let directory = tempdir().unwrap();
    let path = directory.path().join(format!("{PAYLOAD_ID}.parquet"));
    fs::write(&path, b"previous complete payload").unwrap();

    let inspection = write_schema_v2_parquet(
        path.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        representative_rows(),
    )
    .unwrap();
    assert_eq!(inspection.payload_id, PAYLOAD_ID);
    assert_eq!(inspection.schema_version, "v2");
    assert_eq!(inspection.row_count, 2);
    assert_eq!(
        inspection.column_names,
        [
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
        ]
    );
    assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 1);

    let mut metadata_file = File::open(&path).unwrap();
    let metadata = read_metadata(&mut metadata_file).unwrap();
    let footer = metadata.key_value_metadata().as_ref().unwrap();
    assert!(footer.iter().any(|entry| {
        entry.key == "cluetooth.payload_schema" && entry.value.as_deref() == Some("v2")
    }));
    assert!(footer.iter().any(|entry| {
        entry.key == "cluetooth.payload_id" && entry.value.as_deref() == Some(PAYLOAD_ID)
    }));
    assert!(metadata.row_groups.iter().all(|group| {
        group
            .parquet_columns()
            .iter()
            .all(|column| column.compression() == Compression::Zstd)
    }));

    let frame = ParquetReader::new(File::open(&path).unwrap())
        .finish()
        .unwrap();
    let column_names: Vec<&str> = frame
        .get_column_names()
        .into_iter()
        .map(|name| name.as_str())
        .collect();
    assert_eq!(
        column_names,
        [
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
        ]
    );
    assert_eq!(frame.column("addr").unwrap().dtype(), &DataType::String);
    assert_eq!(frame.column("rssi").unwrap().dtype(), &DataType::Int32);
    assert_eq!(
        frame.column("scanned_at").unwrap().dtype(),
        &DataType::Datetime(TimeUnit::Milliseconds, Some(TimeZone::UTC))
    );
    assert_eq!(frame.column("raw").unwrap().dtype(), &DataType::Binary);
    assert_eq!(frame.column("tx_power").unwrap().dtype(), &DataType::Int32);
    assert_eq!(
        frame.column("accuracy").unwrap().dtype(),
        &DataType::Float32
    );
    assert_eq!(frame.column("addr").unwrap().null_count(), 0);
    assert_eq!(frame.column("scanned_at").unwrap().null_count(), 0);
    assert_eq!(frame.column("raw").unwrap().null_count(), 0);

    let raw = frame.column("raw").unwrap().binary().unwrap();
    assert_eq!(
        raw.get(0),
        Some(&[0x02, 0x01, 0x06, 0x05, 0xff, 0x00, 0x80, 0xfe][..])
    );
    assert_eq!(raw.get(1), Some(&[0x00, 0xff, 0x10, 0x00][..]));
    assert_eq!(frame.column("rssi").unwrap().null_count(), 1);
    assert_eq!(frame.column("accuracy").unwrap().null_count(), 1);

    let selected = frame
        .lazy()
        .select([col("raw"), col("scanned_at")])
        .collect()
        .unwrap();
    assert_eq!(selected.shape(), (2, 2));

    let inspected = inspect_schema_v2_parquet(path.to_string_lossy().into_owned()).unwrap();
    assert_eq!(inspected.payload_id, PAYLOAD_ID);
    assert_eq!(inspected.row_count, 2);
}

#[test]
fn inspection_rejects_external_file_with_null_addr() {
    assert_required_null_rejected("addr");
}

#[test]
fn inspection_rejects_external_file_with_null_scanned_at() {
    assert_required_null_rejected("scanned_at");
}

#[test]
fn inspection_rejects_external_file_with_null_raw() {
    assert_required_null_rejected("raw");
}

#[test]
fn producer_rejects_noncanonical_and_out_of_database_range_values() {
    let directory = tempdir().unwrap();
    let cases: Vec<(&str, RowMutation)> = vec![
        (
            "canonical uppercase",
            Box::new(|row| row.addr = "aa:bb:cc:dd:ee:ff".to_owned()),
        ),
        ("signed-smallint", Box::new(|row| row.rssi = Some(40_000))),
        ("present together", Box::new(|row| row.lon = None)),
        (
            "lat must be finite",
            Box::new(|row| row.lat = Some(f64::NAN)),
        ),
        (
            "lon must be finite",
            Box::new(|row| row.lon = Some(f64::INFINITY)),
        ),
        (
            "accuracy requires",
            Box::new(|row| {
                row.lat = None;
                row.lon = None;
                row.accuracy = Some(1.0);
            }),
        ),
        (
            "accuracy must be finite",
            Box::new(|row| row.accuracy = Some(f32::NAN)),
        ),
        (
            "accuracy must be finite",
            Box::new(|row| row.accuracy = Some(-1.0)),
        ),
    ];
    for (index, (message, mutate)) in cases.into_iter().enumerate() {
        let mut rows = representative_rows();
        mutate(&mut rows[0]);
        let path = directory.path().join(format!("invalid-{index}.parquet"));
        let error = write_schema_v2_parquet(
            path.to_string_lossy().into_owned(),
            PAYLOAD_ID.to_owned(),
            rows,
        )
        .unwrap_err();
        assert!(error.to_string().contains(message), "{error}");
        assert!(!path.exists());
    }
}

#[test]
fn pre_write_validation_failure_creates_no_parent_or_output_artifacts() {
    let directory = tempdir().unwrap();
    let missing_parent = directory.path().join("not-created");
    let path = missing_parent.join("invalid.parquet");
    let error = write_schema_v2_parquet(
        path.to_string_lossy().into_owned(),
        "550E8400-E29B-41D4-A716-446655440000".to_owned(),
        representative_rows(),
    )
    .unwrap_err();

    assert!(matches!(error, CoreError::InvalidPayload { .. }));
    assert!(!missing_parent.exists());
    assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 0);
}

#[test]
fn valid_write_requires_an_existing_parent_without_creating_it() {
    let directory = tempdir().unwrap();
    let missing_parent = directory.path().join("not-created");
    let path = missing_parent.join(format!("{PAYLOAD_ID}.parquet"));
    let error = write_schema_v2_parquet(
        path.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        representative_rows(),
    )
    .unwrap_err();

    assert!(matches!(error, CoreError::Io { .. }));
    assert!(!missing_parent.exists());
    assert_eq!(fs::read_dir(directory.path()).unwrap().count(), 0);
}

#[test]
fn rename_failure_cleans_the_same_directory_temporary_file() {
    let directory = tempdir().unwrap();
    let destination = directory.path().join("destination-is-a-directory.parquet");
    fs::create_dir(&destination).unwrap();

    let error = write_schema_v2_parquet(
        destination.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        representative_rows(),
    )
    .unwrap_err();

    assert!(matches!(error, CoreError::Io { .. }));
    let entries: Vec<_> = fs::read_dir(directory.path())
        .unwrap()
        .map(|entry| entry.unwrap().file_name())
        .collect();
    assert_eq!(entries, [destination.file_name().unwrap()]);
    assert!(destination.is_dir());
}
