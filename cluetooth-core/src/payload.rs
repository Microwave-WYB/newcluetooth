use std::ffi::OsStr;
use std::fs::{File, OpenOptions};
use std::io::{Seek, SeekFrom};
use std::path::Path;

use crate::CoreError;
use crate::owned_path::OwnedDirectory;
use crate::payload_identity::{PayloadIdentity, ScanSchema, parse_canonical_uuid_v7};
use polars::io::parquet::write::KeyValueMetadata;
use polars::prelude::*;
use polars_parquet::arrow::read::read_metadata;

pub const PAYLOAD_SCHEMA_METADATA_KEY: &str = "cluetooth.payload_schema";
pub const PAYLOAD_ID_METADATA_KEY: &str = "cluetooth.payload_id";
pub const SCAN_SESSION_ID_METADATA_KEY: &str = "cluetooth.scan_session_id";
pub const DEVICE_MODEL_METADATA_KEY: &str = "cluetooth.device_model";
pub const ANDROID_RELEASE_METADATA_KEY: &str = "cluetooth.android_release";
pub const ANDROID_SDK_METADATA_KEY: &str = "cluetooth.android_sdk";
pub const APP_VERSION_METADATA_KEY: &str = "cluetooth.app_version";
pub const CAP_EXT_ADV_METADATA_KEY: &str = "cluetooth.cap_ext_adv";
pub const CAP_CODED_PHY_METADATA_KEY: &str = "cluetooth.cap_coded_phy";
pub const CAP_MAX_ADV_LEN_METADATA_KEY: &str = "cluetooth.cap_max_adv_len";
pub const SCAN_PHY_METADATA_KEY: &str = "cluetooth.scan.phy";
pub const SCAN_LEGACY_ONLY_METADATA_KEY: &str = "cluetooth.scan.legacy_only";
/// Longest advertisement a producer may record (extended advertising maximum).
pub const MAX_RAW_BYTES: usize = 1650;
const MAX_ENVIRONMENT_STRING_BYTES: usize = 256;
pub const SCAN_PHY_ALL_SUPPORTED: &str = "all_supported";
pub const SCAN_PHY_1M: &str = "1m";
pub const SCHEMA_V2_COLUMNS: [&str; 10] = [
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
];

pub const SCHEMA_V3_COLUMNS: [&str; 18] = [
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

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SchemaV2Row {
    pub addr: String,
    pub rssi: Option<i32>,
    pub scanned_at_ms: i64,
    pub raw: Vec<u8>,
    pub local_name: Option<String>,
    pub tx_power: Option<i32>,
    pub is_connectable: Option<bool>,
    pub lat: Option<f64>,
    pub lon: Option<f64>,
    pub accuracy: Option<f32>,
}

/// The ten v2 columns followed by the Android `ScanResult` fields of schema v3.
#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct SchemaV3Row {
    pub addr: String,
    pub rssi: Option<i32>,
    pub scanned_at_ms: i64,
    pub raw: Vec<u8>,
    pub local_name: Option<String>,
    pub tx_power: Option<i32>,
    pub is_connectable: Option<bool>,
    pub lat: Option<f64>,
    pub lon: Option<f64>,
    pub accuracy: Option<f32>,
    pub is_legacy: Option<bool>,
    pub primary_phy: Option<i32>,
    pub secondary_phy: Option<i32>,
    pub adv_sid: Option<i32>,
    pub adv_tx_power: Option<i32>,
    pub periodic_interval: Option<i32>,
    pub data_status: Option<i32>,
    pub addr_type: Option<i32>,
}

impl From<SchemaV2Row> for SchemaV3Row {
    fn from(row: SchemaV2Row) -> Self {
        Self {
            addr: row.addr,
            rssi: row.rssi,
            scanned_at_ms: row.scanned_at_ms,
            raw: row.raw,
            local_name: row.local_name,
            tx_power: row.tx_power,
            is_connectable: row.is_connectable,
            lat: row.lat,
            lon: row.lon,
            accuracy: row.accuracy,
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
}

/// File-level facts a v3 reader needs to interpret absent patterns: what the
/// device could scan and what the scanner was asked to do while the file was
/// written. Stored once per file in the Parquet footer.
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize, uniffi::Record)]
pub struct ScanEnvironment {
    pub device_model: String,
    /// `Build.VERSION.RELEASE`, e.g. `15`.
    pub android_release: String,
    /// `Build.VERSION.SDK_INT`.
    pub android_sdk: u32,
    pub app_version: String,
    pub cap_ext_adv: bool,
    pub cap_coded_phy: bool,
    pub cap_max_adv_len: u32,
    /// `all_supported` (`PHY_LE_ALL_SUPPORTED`, Coded PHY where supported) or `1m`.
    pub scan_phy: String,
    /// True when the scan was restricted to legacy PDUs (`setLegacy(true)`).
    pub scan_legacy_only: bool,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PayloadInspection {
    pub payload_id: String,
    pub scan_session_id: Option<String>,
    pub schema_version: String,
    pub row_count: u64,
    pub column_names: Vec<String>,
    /// Present exactly for schema v3.
    pub environment: Option<ScanEnvironment>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PayloadFile {
    pub payload_id: String,
    pub session_id: Option<String>,
    pub local_path: String,
    pub object_path: String,
    pub created_at_ms: i64,
    pub row_count: u64,
    pub size_bytes: u64,
    pub archived: bool,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PreparedUpload {
    pub payload_id: String,
    pub ciphertext_path: String,
    pub object_path: String,
    pub plaintext_size_bytes: u64,
    pub ciphertext_size_bytes: u64,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PendingUpload {
    pub payload_id: String,
    pub local_path: String,
    pub object_path: String,
    pub row_count: u64,
    pub size_bytes: u64,
}

impl From<&PayloadFile> for PendingUpload {
    fn from(payload: &PayloadFile) -> Self {
        Self {
            payload_id: payload.payload_id.clone(),
            local_path: payload.local_path.clone(),
            object_path: payload.object_path.clone(),
            row_count: payload.row_count,
            size_bytes: payload.size_bytes,
        }
    }
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct PayloadResult {
    pub sealed: Option<PayloadFile>,
}

/// Writes a schema-v2 file. Kept for fixtures and compatibility tests: the core
/// itself only produces v3 files.
pub fn write_schema_v2_parquet_impl(
    path: &Path,
    payload_id: &str,
    rows: Vec<SchemaV2Row>,
) -> Result<PayloadInspection, CoreError> {
    write_parquet_for_session_impl(
        path,
        ScanSchema::V2,
        payload_id,
        None,
        None,
        rows.into_iter().map(Into::into).collect(),
    )
}

pub fn write_schema_v3_parquet_impl(
    path: &Path,
    payload_id: &str,
    environment: &ScanEnvironment,
    rows: Vec<SchemaV3Row>,
) -> Result<PayloadInspection, CoreError> {
    write_parquet_for_session_impl(
        path,
        ScanSchema::V3,
        payload_id,
        None,
        Some(environment),
        rows,
    )
}

pub(crate) fn write_parquet_for_session_impl(
    path: &Path,
    schema: ScanSchema,
    payload_id: &str,
    scan_session_id: Option<&str>,
    environment: Option<&ScanEnvironment>,
    rows: Vec<SchemaV3Row>,
) -> Result<PayloadInspection, CoreError> {
    validate_payload_id(payload_id)?;
    if let Some(session_id) = scan_session_id {
        validate_payload_id(session_id)?;
    }
    let mut frame = rows_to_frame(schema, rows)?;
    validate_frame_schema(schema, &frame)?;
    let parent = existing_parent(path)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cluetooth-payload-")
        .suffix(".tmp")
        .tempfile_in(parent)
        .map_err(CoreError::io)?
        .into_temp_path();
    let inspection = {
        let mut file = OpenOptions::new()
            .read(true)
            .write(true)
            .truncate(true)
            .open(&temporary)
            .map_err(CoreError::io)?;
        write_and_inspect(
            &mut file,
            schema,
            payload_id,
            scan_session_id,
            environment,
            &mut frame,
        )?
    };
    temporary
        .persist(path)
        .map_err(|error| CoreError::io(error.error))?;
    File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(CoreError::io)?;
    Ok(inspection)
}

pub(crate) fn write_parquet_owned(
    directory: &OwnedDirectory,
    target: &OsStr,
    identity: &PayloadIdentity,
    scan_session_id: Option<&str>,
    environment: Option<&ScanEnvironment>,
    rows: Vec<SchemaV3Row>,
) -> Result<PayloadInspection, CoreError> {
    let payload_id = identity.payload_id.as_str();
    validate_payload_id(payload_id)?;
    if let Some(session_id) = scan_session_id {
        validate_payload_id(session_id)?;
    }
    let mut frame = rows_to_frame(identity.schema, rows)?;
    validate_frame_schema(identity.schema, &frame)?;
    let (temporary, mut file) = directory.create_temporary()?;
    let result = (|| {
        let inspection = write_and_inspect(
            &mut file,
            identity.schema,
            payload_id,
            scan_session_id,
            environment,
            &mut frame,
        )?;
        drop(file);
        directory.publish_noreplace(&temporary, target)?;
        Ok(inspection)
    })();
    if result.is_err() {
        let _ = directory.remove_temporary(&temporary);
    }
    result
}

fn write_and_inspect(
    file: &mut File,
    schema: ScanSchema,
    payload_id: &str,
    scan_session_id: Option<&str>,
    environment: Option<&ScanEnvironment>,
    frame: &mut DataFrame,
) -> Result<PayloadInspection, CoreError> {
    let mut metadata_values = vec![
        (
            PAYLOAD_SCHEMA_METADATA_KEY.to_owned(),
            schema.as_str().to_owned(),
        ),
        (PAYLOAD_ID_METADATA_KEY.to_owned(), payload_id.to_owned()),
    ];
    if let Some(session_id) = scan_session_id {
        metadata_values.push((
            SCAN_SESSION_ID_METADATA_KEY.to_owned(),
            session_id.to_owned(),
        ));
    }
    match (schema, environment) {
        (ScanSchema::V2, None) => {}
        (ScanSchema::V2, Some(_)) => {
            return Err(CoreError::invalid(
                "schema v2 payloads carry no scan environment",
            ));
        }
        (ScanSchema::V3, Some(environment)) => {
            validate_environment(environment)?;
            metadata_values.extend(environment_metadata(environment));
        }
        (ScanSchema::V3, None) => {
            return Err(CoreError::invalid(
                "schema v3 payloads require the scan environment footer",
            ));
        }
    }
    let metadata = KeyValueMetadata::from_static(metadata_values);
    ParquetWriter::new(&mut *file)
        .with_compression(ParquetCompression::Zstd(None))
        .with_key_value_metadata(Some(metadata))
        .finish(frame)
        .map_err(CoreError::parquet)?;
    file.sync_all().map_err(CoreError::io)?;
    file.seek(SeekFrom::Start(0)).map_err(CoreError::io)?;
    inspect_schema_v2_parquet_file(file)
}

fn environment_metadata(environment: &ScanEnvironment) -> Vec<(String, String)> {
    vec![
        (
            DEVICE_MODEL_METADATA_KEY.to_owned(),
            environment.device_model.clone(),
        ),
        (
            ANDROID_RELEASE_METADATA_KEY.to_owned(),
            environment.android_release.clone(),
        ),
        (
            ANDROID_SDK_METADATA_KEY.to_owned(),
            environment.android_sdk.to_string(),
        ),
        (
            APP_VERSION_METADATA_KEY.to_owned(),
            environment.app_version.clone(),
        ),
        (
            CAP_EXT_ADV_METADATA_KEY.to_owned(),
            environment.cap_ext_adv.to_string(),
        ),
        (
            CAP_CODED_PHY_METADATA_KEY.to_owned(),
            environment.cap_coded_phy.to_string(),
        ),
        (
            CAP_MAX_ADV_LEN_METADATA_KEY.to_owned(),
            environment.cap_max_adv_len.to_string(),
        ),
        (
            SCAN_PHY_METADATA_KEY.to_owned(),
            environment.scan_phy.clone(),
        ),
        (
            SCAN_LEGACY_ONLY_METADATA_KEY.to_owned(),
            environment.scan_legacy_only.to_string(),
        ),
    ]
}

pub(crate) fn validate_environment(environment: &ScanEnvironment) -> Result<(), CoreError> {
    for (name, value) in [
        ("device_model", &environment.device_model),
        ("android_release", &environment.android_release),
        ("app_version", &environment.app_version),
    ] {
        if value.is_empty() || value.len() > MAX_ENVIRONMENT_STRING_BYTES {
            return Err(CoreError::invalid(format!(
                "scan environment {name} must be 1 to {MAX_ENVIRONMENT_STRING_BYTES} bytes"
            )));
        }
    }
    if ![SCAN_PHY_ALL_SUPPORTED, SCAN_PHY_1M].contains(&environment.scan_phy.as_str()) {
        return Err(CoreError::invalid(format!(
            "scan environment scan_phy must be {SCAN_PHY_ALL_SUPPORTED} or {SCAN_PHY_1M}"
        )));
    }
    Ok(())
}

fn read_environment(
    key_values: &[polars_parquet::parquet::metadata::KeyValue],
) -> Result<ScanEnvironment, CoreError> {
    fn parse<T: std::str::FromStr>(key: &str, value: &str) -> Result<T, CoreError> {
        value.parse().map_err(|_| {
            CoreError::invalid(format!("footer key {key} has an invalid value {value:?}"))
        })
    }
    let environment = ScanEnvironment {
        device_model: metadata_value(key_values, DEVICE_MODEL_METADATA_KEY)?,
        android_release: metadata_value(key_values, ANDROID_RELEASE_METADATA_KEY)?,
        android_sdk: parse(
            ANDROID_SDK_METADATA_KEY,
            &metadata_value(key_values, ANDROID_SDK_METADATA_KEY)?,
        )?,
        app_version: metadata_value(key_values, APP_VERSION_METADATA_KEY)?,
        cap_ext_adv: parse(
            CAP_EXT_ADV_METADATA_KEY,
            &metadata_value(key_values, CAP_EXT_ADV_METADATA_KEY)?,
        )?,
        cap_coded_phy: parse(
            CAP_CODED_PHY_METADATA_KEY,
            &metadata_value(key_values, CAP_CODED_PHY_METADATA_KEY)?,
        )?,
        cap_max_adv_len: parse(
            CAP_MAX_ADV_LEN_METADATA_KEY,
            &metadata_value(key_values, CAP_MAX_ADV_LEN_METADATA_KEY)?,
        )?,
        scan_phy: metadata_value(key_values, SCAN_PHY_METADATA_KEY)?,
        scan_legacy_only: parse(
            SCAN_LEGACY_ONLY_METADATA_KEY,
            &metadata_value(key_values, SCAN_LEGACY_ONLY_METADATA_KEY)?,
        )?,
    };
    validate_environment(&environment)?;
    Ok(environment)
}

pub fn inspect_schema_v2_parquet_impl(path: &Path) -> Result<PayloadInspection, CoreError> {
    let mut file = File::open(path).map_err(CoreError::io)?;
    inspect_schema_v2_parquet_file(&mut file)
}

/// Validates a pending, archived or freshly written scan file of either schema
/// generation and returns what its footer and frame say about it.
pub(crate) fn inspect_schema_v2_parquet_file(
    file: &mut File,
) -> Result<PayloadInspection, CoreError> {
    file.seek(SeekFrom::Start(0)).map_err(CoreError::io)?;
    let metadata = read_metadata(file).map_err(CoreError::parquet)?;
    let key_values = metadata
        .key_value_metadata()
        .as_ref()
        .ok_or_else(|| CoreError::invalid("required Parquet footer metadata is absent"))?;

    let schema_version = metadata_value(key_values, PAYLOAD_SCHEMA_METADATA_KEY)?;
    let Some(schema) = ScanSchema::from_path_segment(&schema_version) else {
        return Err(CoreError::invalid(format!(
            "expected {PAYLOAD_SCHEMA_METADATA_KEY} v2 or v3, got {schema_version}"
        )));
    };
    let payload_id = metadata_value(key_values, PAYLOAD_ID_METADATA_KEY)?;
    validate_payload_id(&payload_id)?;
    let scan_session_id = optional_metadata_value(key_values, SCAN_SESSION_ID_METADATA_KEY)?;
    if let Some(session_id) = &scan_session_id {
        validate_payload_id(session_id)?;
    }
    let environment = match schema {
        ScanSchema::V2 => None,
        ScanSchema::V3 => Some(read_environment(key_values)?),
    };

    file.seek(SeekFrom::Start(0)).map_err(CoreError::io)?;
    let frame = ParquetReader::new(file.try_clone().map_err(CoreError::io)?)
        .finish()
        .map_err(CoreError::parquet)?;
    validate_frame_schema(schema, &frame)?;

    Ok(PayloadInspection {
        payload_id,
        scan_session_id,
        schema_version,
        row_count: frame.height() as u64,
        column_names: schema_columns(schema)
            .iter()
            .map(ToString::to_string)
            .collect(),
        environment,
    })
}

fn schema_columns(schema: ScanSchema) -> &'static [&'static str] {
    match schema {
        ScanSchema::V2 => &SCHEMA_V2_COLUMNS,
        ScanSchema::V3 => &SCHEMA_V3_COLUMNS,
    }
}

/// Builds the frame of one schema generation. A v2 frame is the v3 frame
/// without the appended columns; it refuses rows that carry v3 values.
pub(crate) fn rows_to_frame(
    schema: ScanSchema,
    rows: Vec<SchemaV3Row>,
) -> Result<DataFrame, CoreError> {
    validate_rows(&rows)?;
    if schema == ScanSchema::V2 && rows.iter().any(has_v3_values) {
        return Err(CoreError::invalid(
            "schema v2 rows cannot carry schema v3 values",
        ));
    }
    let addr: Vec<&str> = rows.iter().map(|row| row.addr.as_str()).collect();
    let rssi: Vec<Option<i32>> = rows.iter().map(|row| row.rssi).collect();
    let scanned_at: Vec<i64> = rows.iter().map(|row| row.scanned_at_ms).collect();
    let raw: Vec<&[u8]> = rows.iter().map(|row| row.raw.as_slice()).collect();
    let local_name: Vec<Option<&str>> = rows.iter().map(|row| row.local_name.as_deref()).collect();
    let tx_power: Vec<Option<i32>> = rows.iter().map(|row| row.tx_power).collect();
    let is_connectable: Vec<Option<bool>> = rows.iter().map(|row| row.is_connectable).collect();
    let lat: Vec<Option<f64>> = rows.iter().map(|row| row.lat).collect();
    let lon: Vec<Option<f64>> = rows.iter().map(|row| row.lon).collect();
    let accuracy: Vec<Option<f32>> = rows.iter().map(|row| row.accuracy).collect();

    let timestamp = Series::new("scanned_at".into(), scanned_at)
        .cast(&DataType::Datetime(
            TimeUnit::Milliseconds,
            Some(TimeZone::UTC),
        ))
        .map_err(CoreError::parquet)?;

    let mut columns: Vec<Column> = vec![
        Series::new("addr".into(), addr).into(),
        Series::new("rssi".into(), rssi).into(),
        timestamp.into(),
        Series::new("raw".into(), raw).into(),
        Series::new("local_name".into(), local_name).into(),
        Series::new("tx_power".into(), tx_power).into(),
        Series::new("is_connectable".into(), is_connectable).into(),
        Series::new("lat".into(), lat).into(),
        Series::new("lon".into(), lon).into(),
        Series::new("accuracy".into(), accuracy).into(),
    ];
    if schema == ScanSchema::V3 {
        let is_legacy: Vec<Option<bool>> = rows.iter().map(|row| row.is_legacy).collect();
        columns.push(Series::new("is_legacy".into(), is_legacy).into());
        for (name, select) in V3_INT_COLUMNS {
            let values: Vec<Option<i32>> = rows.iter().map(select).collect();
            columns.push(Series::new(name.into(), values).into());
        }
    }
    DataFrame::new(rows.len(), columns).map_err(CoreError::parquet)
}

type IntSelector = fn(&SchemaV3Row) -> Option<i32>;

/// The appended nullable Int32 columns of v3 after `is_legacy`, in file order.
const V3_INT_COLUMNS: [(&str, IntSelector); 7] = [
    ("primary_phy", |row| row.primary_phy),
    ("secondary_phy", |row| row.secondary_phy),
    ("adv_sid", |row| row.adv_sid),
    ("adv_tx_power", |row| row.adv_tx_power),
    ("periodic_interval", |row| row.periodic_interval),
    ("data_status", |row| row.data_status),
    ("addr_type", |row| row.addr_type),
];

fn has_v3_values(row: &SchemaV3Row) -> bool {
    row.is_legacy.is_some()
        || V3_INT_COLUMNS
            .iter()
            .any(|(_, select)| select(row).is_some())
}

/// Upgrades a v2 frame to the v3 column set (all appended columns null) so that
/// exports can combine payloads of both generations.
pub(crate) fn frame_to_v3(frame: DataFrame) -> Result<DataFrame, CoreError> {
    let height = frame.height();
    let mut frame = frame;
    let additions: [(&str, DataType); 8] = [
        ("is_legacy", DataType::Boolean),
        ("primary_phy", DataType::Int32),
        ("secondary_phy", DataType::Int32),
        ("adv_sid", DataType::Int32),
        ("adv_tx_power", DataType::Int32),
        ("periodic_interval", DataType::Int32),
        ("data_status", DataType::Int32),
        ("addr_type", DataType::Int32),
    ];
    for (name, dtype) in additions {
        let column = Column::full_null(name.into(), height, &dtype);
        frame.with_column(column).map_err(CoreError::parquet)?;
    }
    Ok(frame)
}

pub(crate) fn validate_frame_schema(
    schema: ScanSchema,
    frame: &DataFrame,
) -> Result<(), CoreError> {
    let expected_names = schema_columns(schema);
    let actual_names: Vec<&str> = frame
        .get_column_names()
        .into_iter()
        .map(|name| name.as_str())
        .collect();
    if actual_names != expected_names {
        return Err(CoreError::invalid(format!(
            "expected ordered columns {expected_names:?}, got {actual_names:?}"
        )));
    }

    let mut expected_types = vec![
        DataType::String,
        DataType::Int32,
        DataType::Datetime(TimeUnit::Milliseconds, Some(TimeZone::UTC)),
        DataType::Binary,
        DataType::String,
        DataType::Int32,
        DataType::Boolean,
        DataType::Float64,
        DataType::Float64,
        DataType::Float32,
    ];
    if schema == ScanSchema::V3 {
        expected_types.push(DataType::Boolean);
        expected_types.extend(std::iter::repeat_n(DataType::Int32, 7));
    }
    let actual_types: Vec<DataType> = frame
        .columns()
        .iter()
        .map(|column| column.dtype().clone())
        .collect();
    if actual_types != expected_types {
        return Err(CoreError::invalid(format!(
            "expected schema types {expected_types:?}, got {actual_types:?}"
        )));
    }

    for required_column in ["addr", "scanned_at", "raw"] {
        let null_count = frame
            .column(required_column)
            .map_err(CoreError::parquet)?
            .null_count();
        if null_count != 0 {
            return Err(CoreError::invalid(format!(
                "required column {required_column} contains {null_count} null value(s)"
            )));
        }
    }
    Ok(())
}

pub(crate) fn validate_rows(rows: &[SchemaV3Row]) -> Result<(), CoreError> {
    for (index, row) in rows.iter().enumerate() {
        if !is_canonical_mac(&row.addr) {
            return Err(CoreError::invalid(format!(
                "row {index} addr must be canonical uppercase XX:XX:XX:XX:XX:XX"
            )));
        }
        if row.raw.len() > MAX_RAW_BYTES {
            return Err(CoreError::invalid(format!(
                "row {index} raw exceeds the {MAX_RAW_BYTES}-byte advertisement ceiling"
            )));
        }
        for (name, value) in [("rssi", row.rssi), ("tx_power", row.tx_power)] {
            if value.is_some_and(|number| i16::try_from(number).is_err()) {
                return Err(CoreError::invalid(format!(
                    "row {index} {name} is outside signed-smallint range"
                )));
            }
        }
        // Android platform constants; sentinel values (SID 255, TX power 127,
        // interval 0) must have been mapped to null by the producer.
        for (name, value, valid) in [
            ("primary_phy", row.primary_phy, &[1, 3][..]),
            ("secondary_phy", row.secondary_phy, &[0, 1, 2, 3][..]),
            ("data_status", row.data_status, &[0, 2][..]),
            ("addr_type", row.addr_type, &[0, 1][..]),
        ] {
            if value.is_some_and(|number| !valid.contains(&number)) {
                return Err(CoreError::invalid(format!(
                    "row {index} {name} must be one of {valid:?}"
                )));
            }
        }
        for (name, value, range) in [
            ("adv_sid", row.adv_sid, 0..=15),
            ("adv_tx_power", row.adv_tx_power, -127..=126),
            ("periodic_interval", row.periodic_interval, 1..=65_535),
        ] {
            if value.is_some_and(|number| !range.contains(&number)) {
                return Err(CoreError::invalid(format!(
                    "row {index} {name} must be within {range:?}"
                )));
            }
        }
        if row.lat.is_some() != row.lon.is_some() {
            return Err(CoreError::invalid(format!(
                "row {index} lat and lon must be present together"
            )));
        }
        if let Some(lat) = row.lat
            && (!lat.is_finite() || !(-90.0..=90.0).contains(&lat))
        {
            return Err(CoreError::invalid(format!(
                "row {index} lat must be finite and between -90 and 90"
            )));
        }
        if let Some(lon) = row.lon
            && (!lon.is_finite() || !(-180.0..=180.0).contains(&lon))
        {
            return Err(CoreError::invalid(format!(
                "row {index} lon must be finite and between -180 and 180"
            )));
        }
        if row.accuracy.is_some() && row.lat.is_none() {
            return Err(CoreError::invalid(format!(
                "row {index} accuracy requires coordinates"
            )));
        }
        if let Some(accuracy) = row.accuracy
            && (!accuracy.is_finite() || accuracy < 0.0)
        {
            return Err(CoreError::invalid(format!(
                "row {index} accuracy must be finite and nonnegative"
            )));
        }
    }
    Ok(())
}

pub(crate) fn is_canonical_mac(value: &str) -> bool {
    value.len() == 17
        && value.as_bytes().iter().enumerate().all(|(index, byte)| {
            if matches!(index, 2 | 5 | 8 | 11 | 14) {
                *byte == b':'
            } else {
                byte.is_ascii_digit() || matches!(*byte, b'A'..=b'F')
            }
        })
}

fn existing_parent(path: &Path) -> Result<&Path, CoreError> {
    if path.file_name().is_none() {
        return Err(CoreError::invalid(
            "payload output path must include a file name",
        ));
    }

    let parent = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty());
    let parent = parent.unwrap_or_else(|| Path::new("."));
    if !parent.is_dir() {
        return Err(CoreError::io(format!(
            "payload output parent directory does not exist: {}",
            parent.display()
        )));
    }
    Ok(parent)
}

fn metadata_value(
    key_values: &[polars_parquet::parquet::metadata::KeyValue],
    key: &str,
) -> Result<String, CoreError> {
    let matches: Vec<_> = key_values.iter().filter(|item| item.key == key).collect();
    if matches.len() != 1 {
        return Err(CoreError::invalid(format!(
            "required footer key {key} must occur exactly once"
        )));
    }
    matches[0]
        .value
        .clone()
        .ok_or_else(|| CoreError::invalid(format!("required footer key {key} has no value")))
}

fn optional_metadata_value(
    key_values: &[polars_parquet::parquet::metadata::KeyValue],
    key: &str,
) -> Result<Option<String>, CoreError> {
    let matches: Vec<_> = key_values.iter().filter(|item| item.key == key).collect();
    if matches.len() > 1 {
        return Err(CoreError::invalid(format!(
            "optional footer key {key} must occur at most once"
        )));
    }
    matches
        .first()
        .map(|item| {
            item.value.clone().ok_or_else(|| {
                CoreError::invalid(format!("optional footer key {key} has no value"))
            })
        })
        .transpose()
}

fn validate_payload_id(payload_id: &str) -> Result<(), CoreError> {
    parse_canonical_uuid_v7(payload_id).map(|_| ())
}
