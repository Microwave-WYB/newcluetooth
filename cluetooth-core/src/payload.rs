use std::ffi::OsStr;
use std::fs::{File, OpenOptions};
use std::io::{Seek, SeekFrom};
use std::path::Path;

use crate::CoreError;
use crate::owned_path::OwnedDirectory;
use crate::payload_identity::parse_canonical_uuid_v7;
use polars::io::parquet::write::KeyValueMetadata;
use polars::prelude::*;
use polars_parquet::arrow::read::read_metadata;

pub const PAYLOAD_SCHEMA_VERSION: &str = "v2";
pub const PAYLOAD_SCHEMA_METADATA_KEY: &str = "cluetooth.payload_schema";
pub const PAYLOAD_ID_METADATA_KEY: &str = "cluetooth.payload_id";
pub const SCAN_SESSION_ID_METADATA_KEY: &str = "cluetooth.scan_session_id";
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

#[derive(Clone, Debug, uniffi::Record)]
pub struct PayloadInspection {
    pub payload_id: String,
    pub scan_session_id: Option<String>,
    pub schema_version: String,
    pub row_count: u64,
    pub column_names: Vec<String>,
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

pub fn write_schema_v2_parquet_impl(
    path: &Path,
    payload_id: &str,
    rows: Vec<SchemaV2Row>,
) -> Result<PayloadInspection, CoreError> {
    write_schema_v2_parquet_for_session_impl(path, payload_id, None, rows)
}

pub(crate) fn write_schema_v2_parquet_for_session_impl(
    path: &Path,
    payload_id: &str,
    scan_session_id: Option<&str>,
    rows: Vec<SchemaV2Row>,
) -> Result<PayloadInspection, CoreError> {
    validate_payload_id(payload_id)?;
    if let Some(session_id) = scan_session_id {
        validate_payload_id(session_id)?;
    }
    let mut frame = rows_to_frame(rows)?;
    validate_frame_schema(&frame)?;
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
        write_and_inspect(&mut file, payload_id, scan_session_id, &mut frame)?
    };
    temporary
        .persist(path)
        .map_err(|error| CoreError::io(error.error))?;
    File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(CoreError::io)?;
    Ok(inspection)
}

pub(crate) fn write_schema_v2_parquet_owned(
    directory: &OwnedDirectory,
    target: &OsStr,
    payload_id: &str,
    scan_session_id: Option<&str>,
    rows: Vec<SchemaV2Row>,
) -> Result<PayloadInspection, CoreError> {
    validate_payload_id(payload_id)?;
    if let Some(session_id) = scan_session_id {
        validate_payload_id(session_id)?;
    }
    let mut frame = rows_to_frame(rows)?;
    validate_frame_schema(&frame)?;
    let (temporary, mut file) = directory.create_temporary()?;
    let result = (|| {
        let inspection = write_and_inspect(&mut file, payload_id, scan_session_id, &mut frame)?;
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
    payload_id: &str,
    scan_session_id: Option<&str>,
    frame: &mut DataFrame,
) -> Result<PayloadInspection, CoreError> {
    let mut metadata_values = vec![
        (
            PAYLOAD_SCHEMA_METADATA_KEY.to_owned(),
            PAYLOAD_SCHEMA_VERSION.to_owned(),
        ),
        (PAYLOAD_ID_METADATA_KEY.to_owned(), payload_id.to_owned()),
    ];
    if let Some(session_id) = scan_session_id {
        metadata_values.push((
            SCAN_SESSION_ID_METADATA_KEY.to_owned(),
            session_id.to_owned(),
        ));
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

pub fn inspect_schema_v2_parquet_impl(path: &Path) -> Result<PayloadInspection, CoreError> {
    let mut file = File::open(path).map_err(CoreError::io)?;
    inspect_schema_v2_parquet_file(&mut file)
}

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
    if schema_version != PAYLOAD_SCHEMA_VERSION {
        return Err(CoreError::invalid(format!(
            "expected {PAYLOAD_SCHEMA_METADATA_KEY}={PAYLOAD_SCHEMA_VERSION}, got {schema_version}"
        )));
    }
    let payload_id = metadata_value(key_values, PAYLOAD_ID_METADATA_KEY)?;
    validate_payload_id(&payload_id)?;
    let scan_session_id = optional_metadata_value(key_values, SCAN_SESSION_ID_METADATA_KEY)?;
    if let Some(session_id) = &scan_session_id {
        validate_payload_id(session_id)?;
    }

    file.seek(SeekFrom::Start(0)).map_err(CoreError::io)?;
    let frame = ParquetReader::new(file.try_clone().map_err(CoreError::io)?)
        .finish()
        .map_err(CoreError::parquet)?;
    validate_frame_schema(&frame)?;

    Ok(PayloadInspection {
        payload_id,
        scan_session_id,
        schema_version,
        row_count: frame.height() as u64,
        column_names: SCHEMA_V2_COLUMNS.iter().map(ToString::to_string).collect(),
    })
}

pub(crate) fn rows_to_frame(rows: Vec<SchemaV2Row>) -> Result<DataFrame, CoreError> {
    validate_rows(&rows)?;
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

    DataFrame::new(
        rows.len(),
        vec![
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
        ],
    )
    .map_err(CoreError::parquet)
}

pub(crate) fn validate_frame_schema(frame: &DataFrame) -> Result<(), CoreError> {
    let actual_names: Vec<&str> = frame
        .get_column_names()
        .into_iter()
        .map(|name| name.as_str())
        .collect();
    if actual_names != SCHEMA_V2_COLUMNS {
        return Err(CoreError::invalid(format!(
            "expected ordered columns {SCHEMA_V2_COLUMNS:?}, got {actual_names:?}"
        )));
    }

    let expected_types = [
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

pub(crate) fn validate_rows(rows: &[SchemaV2Row]) -> Result<(), CoreError> {
    for (index, row) in rows.iter().enumerate() {
        if !is_canonical_mac(&row.addr) {
            return Err(CoreError::invalid(format!(
                "row {index} addr must be canonical uppercase XX:XX:XX:XX:XX:XX"
            )));
        }
        for (name, value) in [("rssi", row.rssi), ("tx_power", row.tx_power)] {
            if value.is_some_and(|number| i16::try_from(number).is_err()) {
                return Err(CoreError::invalid(format!(
                    "row {index} {name} is outside signed-smallint range"
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
