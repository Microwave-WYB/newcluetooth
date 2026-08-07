use std::fs::{self, File, OpenOptions};
use std::io::{Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

use polars::io::parquet::write::KeyValueMetadata;
use polars::prelude::{DataFrame, ParquetCompression, ParquetReader, ParquetWriter, SerReader};
use serde::Serialize;
use sha2::{Digest, Sha256};

use crate::CoreError;
use crate::payload::{PayloadFile, SchemaV2Row, rows_to_frame, validate_frame_schema};
use crate::session::ScanSession;

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum ExportFormat {
    Jsonl,
    Parquet,
}

#[derive(Clone, Debug, uniffi::Record)]
pub struct LegacySessionRows {
    pub session_id: String,
    pub started_at_ms: i64,
    pub ended_at_ms: Option<i64>,
    pub rows: Vec<SchemaV2Row>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct PreparedExport {
    pub export_id: String,
    pub local_path: String,
    pub suggested_file_name: String,
    pub file_count: u64,
    pub size_bytes: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum DeleteSessionResult {
    DeletedUploadedLocalCopy,
    DeletedUnuploadedData,
    NothingLocalToDelete,
}

pub(crate) fn write_session_export(
    root: &Path,
    export_id: &str,
    session_id: &str,
    format: ExportFormat,
    payloads: &[PayloadFile],
) -> Result<PreparedExport, CoreError> {
    let extension = match format {
        ExportFormat::Jsonl => "jsonl",
        ExportFormat::Parquet => "parquet",
    };
    let suggested = format!("cluetooth-session-{session_id}.{extension}");
    let final_path = export_path(root, export_id, &suggested)?;
    let parent = final_path.parent().expect("export path has parent");
    fs::create_dir_all(parent).map_err(CoreError::io)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cluetooth-export-")
        .suffix(".tmp")
        .tempfile_in(parent)
        .map_err(CoreError::io)?
        .into_temp_path();
    let frames = read_ordered_frames(payloads)?;
    match format {
        ExportFormat::Jsonl => write_jsonl(&temporary, &frames)?,
        ExportFormat::Parquet => write_combined_parquet(&temporary, session_id, frames)?,
    }
    temporary
        .persist(&final_path)
        .map_err(|error| CoreError::io(error.error))?;
    sync_parent(parent)?;
    Ok(PreparedExport {
        export_id: export_id.to_owned(),
        local_path: final_path.to_string_lossy().into_owned(),
        suggested_file_name: suggested,
        file_count: 1,
        size_bytes: fs::metadata(&final_path).map_err(CoreError::io)?.len(),
    })
}

pub(crate) fn write_legacy_session_export(
    root: &Path,
    export_id: &str,
    session: &LegacySessionRows,
    format: ExportFormat,
) -> Result<PreparedExport, CoreError> {
    let extension = match format {
        ExportFormat::Jsonl => "jsonl",
        ExportFormat::Parquet => "parquet",
    };
    let suggested = format!(
        "cluetooth-legacy-session-{}.{}",
        safe_session_name(&session.session_id),
        extension
    );
    let final_path = export_path(root, export_id, &suggested)?;
    let parent = final_path.parent().expect("export path has parent");
    fs::create_dir_all(parent).map_err(CoreError::io)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cluetooth-export-")
        .suffix(".tmp")
        .tempfile_in(parent)
        .map_err(CoreError::io)?
        .into_temp_path();
    let frames = vec![rows_to_frame(session.rows.clone())?];
    match format {
        ExportFormat::Jsonl => write_jsonl(&temporary, &frames)?,
        ExportFormat::Parquet => write_combined_parquet(&temporary, &session.session_id, frames)?,
    }
    temporary
        .persist(&final_path)
        .map_err(|error| CoreError::io(error.error))?;
    sync_parent(parent)?;
    Ok(PreparedExport {
        export_id: export_id.to_owned(),
        local_path: final_path.to_string_lossy().into_owned(),
        suggested_file_name: suggested,
        file_count: 1,
        size_bytes: fs::metadata(&final_path).map_err(CoreError::io)?.len(),
    })
}

pub(crate) fn write_full_export(
    root: &Path,
    export_id: &str,
    format: ExportFormat,
    sessions: &[ScanSession],
    payloads: &[PayloadFile],
    legacy_sessions: &[LegacySessionRows],
) -> Result<PreparedExport, CoreError> {
    let suggested = match format {
        ExportFormat::Jsonl => "cluetooth-scan-sessions-jsonl.zip",
        ExportFormat::Parquet => "cluetooth-scan-sessions-parquet.zip",
    }
    .to_owned();
    let final_path = export_path(root, export_id, &suggested)?;
    let parent = final_path.parent().expect("export path has parent");
    fs::create_dir_all(parent).map_err(CoreError::io)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cluetooth-export-")
        .suffix(".tmp")
        .tempfile_in(parent)
        .map_err(CoreError::io)?
        .into_temp_path();
    let staging = tempfile::tempdir_in(parent).map_err(CoreError::io)?;
    let extension = match format {
        ExportFormat::Jsonl => "jsonl",
        ExportFormat::Parquet => "parquet",
    };
    let mut built = Vec::new();
    for session in sessions
        .iter()
        .filter(|session| session.retained_local_bytes > 0)
    {
        let session_payloads: Vec<_> = payloads
            .iter()
            .filter(|payload| {
                payload.session_id.as_deref().unwrap_or(&payload.payload_id) == session.session_id
            })
            .cloned()
            .collect();
        let safe_id = safe_session_name(&session.session_id);
        let name = format!("sessions/{safe_id}.{extension}");
        let path = staging.path().join(format!("{safe_id}.{extension}"));
        let frames = read_ordered_frames(&session_payloads)?;
        match format {
            ExportFormat::Jsonl => write_jsonl(&path, &frames)?,
            ExportFormat::Parquet => write_combined_parquet(&path, &session.session_id, frames)?,
        }
        let bytes = fs::read(&path).map_err(CoreError::io)?;
        built.push((
            session.started_at_ms,
            name,
            bytes,
            session.session_id.clone(),
            session.ended_at_ms,
            format!("{:?}", session.status).to_lowercase(),
            session.observation_count,
        ));
    }
    for session in legacy_sessions {
        let safe_id = safe_session_name(&session.session_id);
        let name = format!("sessions/{safe_id}.{extension}");
        let path = staging.path().join(format!("{safe_id}.{extension}"));
        let frames = vec![rows_to_frame(session.rows.clone())?];
        match format {
            ExportFormat::Jsonl => write_jsonl(&path, &frames)?,
            ExportFormat::Parquet => write_combined_parquet(&path, &session.session_id, frames)?,
        }
        let bytes = fs::read(&path).map_err(CoreError::io)?;
        built.push((
            session.started_at_ms,
            name,
            bytes,
            session.session_id.clone(),
            session.ended_at_ms,
            "legacy".to_owned(),
            session.rows.len() as u64,
        ));
    }
    built.sort_by(|left, right| left.0.cmp(&right.0).then_with(|| left.3.cmp(&right.3)));
    let mut entries = Vec::new();
    let mut manifest = Vec::new();
    for (started_at_ms, name, bytes, session_id, ended_at_ms, status, observation_count) in built {
        manifest.push(ManifestSession {
            session_id,
            started_at_ms,
            ended_at_ms,
            status,
            observation_count,
            export_filename: name.clone(),
            byte_size: bytes.len() as u64,
            sha256: hex::encode(Sha256::digest(&bytes)),
        });
        entries.push((name, bytes));
    }
    let manifest_bytes = serde_json::to_vec_pretty(&Manifest {
        version: 1,
        sessions: manifest,
    })
    .map_err(CoreError::io)?;
    entries.push(("manifest.json".to_owned(), manifest_bytes));
    write_stored_zip(&temporary, &entries)?;
    temporary
        .persist(&final_path)
        .map_err(|error| CoreError::io(error.error))?;
    sync_parent(parent)?;
    Ok(PreparedExport {
        export_id: export_id.to_owned(),
        local_path: final_path.to_string_lossy().into_owned(),
        suggested_file_name: suggested,
        file_count: (entries.len().saturating_sub(1)) as u64,
        size_bytes: fs::metadata(&final_path).map_err(CoreError::io)?.len(),
    })
}

pub(crate) fn acknowledge_export(root: &Path, export_id: &str) -> Result<(), CoreError> {
    let directory = root.join("export-temp").join(export_id);
    if directory.exists() {
        fs::remove_dir_all(directory).map_err(CoreError::io)?;
    }
    Ok(())
}

fn safe_session_name(session_id: &str) -> String {
    let safe: String = session_id
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '-' | '_') {
                character
            } else {
                '_'
            }
        })
        .collect();
    if safe.is_empty() {
        "legacy".to_owned()
    } else {
        safe
    }
}

fn export_path(root: &Path, export_id: &str, suggested: &str) -> Result<PathBuf, CoreError> {
    crate::payload_identity::parse_canonical_uuid_v7(export_id)?;
    if suggested.contains('/') || suggested.contains('\\') || suggested == "." || suggested == ".."
    {
        return Err(CoreError::invalid("unsafe export file name"));
    }
    Ok(root.join("export-temp").join(export_id).join(suggested))
}

fn read_ordered_frames(payloads: &[PayloadFile]) -> Result<Vec<DataFrame>, CoreError> {
    let mut payloads = payloads.to_vec();
    payloads.sort_by_key(|payload| (payload.created_at_ms, payload.payload_id.clone()));
    payloads
        .into_iter()
        .map(|payload| {
            let frame = ParquetReader::new(File::open(payload.local_path).map_err(CoreError::io)?)
                .finish()
                .map_err(CoreError::parquet)?;
            validate_frame_schema(&frame)?;
            Ok(frame)
        })
        .collect()
}

fn write_jsonl(path: &Path, frames: &[DataFrame]) -> Result<(), CoreError> {
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(path)
        .map_err(CoreError::io)?;
    for frame in frames {
        let addr = frame
            .column("addr")
            .map_err(CoreError::parquet)?
            .str()
            .map_err(CoreError::parquet)?;
        let rssi = frame
            .column("rssi")
            .map_err(CoreError::parquet)?
            .i32()
            .map_err(CoreError::parquet)?;
        let time = frame
            .column("scanned_at")
            .map_err(CoreError::parquet)?
            .datetime()
            .map_err(CoreError::parquet)?;
        let raw = frame
            .column("raw")
            .map_err(CoreError::parquet)?
            .binary()
            .map_err(CoreError::parquet)?;
        let local_name = frame
            .column("local_name")
            .map_err(CoreError::parquet)?
            .str()
            .map_err(CoreError::parquet)?;
        let tx_power = frame
            .column("tx_power")
            .map_err(CoreError::parquet)?
            .i32()
            .map_err(CoreError::parquet)?;
        let connectable = frame
            .column("is_connectable")
            .map_err(CoreError::parquet)?
            .bool()
            .map_err(CoreError::parquet)?;
        let lat = frame
            .column("lat")
            .map_err(CoreError::parquet)?
            .f64()
            .map_err(CoreError::parquet)?;
        let lon = frame
            .column("lon")
            .map_err(CoreError::parquet)?
            .f64()
            .map_err(CoreError::parquet)?;
        let accuracy = frame
            .column("accuracy")
            .map_err(CoreError::parquet)?
            .f32()
            .map_err(CoreError::parquet)?;
        for index in 0..frame.height() {
            let value = serde_json::json!({
                "addr": addr.get(index).expect("validated addr"),
                "rssi": rssi.get(index),
                "scanned_at": format_timestamp_ms(time.phys.get(index).expect("validated timestamp")),
                "raw": hex::encode(raw.get(index).expect("validated raw")),
                "local_name": local_name.get(index),
                "tx_power": tx_power.get(index),
                "is_connectable": connectable.get(index),
                "lat": finite_optional(lat.get(index))?,
                "lon": finite_optional(lon.get(index))?,
                "accuracy": finite_optional(accuracy.get(index).map(f64::from))?,
            });
            serde_json::to_writer(&mut file, &value).map_err(CoreError::io)?;
            file.write_all(b"\n").map_err(CoreError::io)?;
        }
    }
    file.sync_all().map_err(CoreError::io)
}

fn finite_optional(value: Option<f64>) -> Result<Option<f64>, CoreError> {
    if value.is_some_and(|number| !number.is_finite()) {
        return Err(CoreError::invalid(
            "JSONL export cannot represent a non-finite number",
        ));
    }
    Ok(value)
}

fn write_combined_parquet(
    path: &Path,
    session_id: &str,
    frames: Vec<DataFrame>,
) -> Result<(), CoreError> {
    let mut combined = if let Some(first) = frames.first() {
        first.clone()
    } else {
        return Err(CoreError::upload_state(
            "session has no retained observations to export",
        ));
    };
    for frame in frames.iter().skip(1) {
        combined.vstack_mut(frame).map_err(CoreError::parquet)?;
    }
    validate_frame_schema(&combined)?;
    let metadata = KeyValueMetadata::from_static(vec![
        ("cluetooth.export".to_owned(), "scan_session".to_owned()),
        (
            "cluetooth.scan_session_id".to_owned(),
            session_id.to_owned(),
        ),
    ]);
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .write(true)
        .open(path)
        .map_err(CoreError::io)?;
    ParquetWriter::new(&mut file)
        .with_compression(ParquetCompression::Zstd(None))
        .with_key_value_metadata(Some(metadata))
        .finish(&mut combined)
        .map_err(CoreError::parquet)?;
    file.sync_all().map_err(CoreError::io)
}

fn format_timestamp_ms(timestamp_ms: i64) -> String {
    let days = timestamp_ms.div_euclid(86_400_000);
    let day_ms = timestamp_ms.rem_euclid(86_400_000);
    let (year, month, day) = civil_from_days(days);
    let hour = day_ms / 3_600_000;
    let minute = day_ms % 3_600_000 / 60_000;
    let second = day_ms % 60_000 / 1_000;
    let millis = day_ms % 1_000;
    format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}.{millis:03}Z")
}

fn civil_from_days(days: i64) -> (i64, i64, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let mut year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = mp + if mp < 10 { 3 } else { -9 };
    year += i64::from(month <= 2);
    (year, month, day)
}

#[derive(Serialize)]
struct Manifest {
    version: u32,
    sessions: Vec<ManifestSession>,
}
#[derive(Serialize)]
struct ManifestSession {
    session_id: String,
    started_at_ms: i64,
    ended_at_ms: Option<i64>,
    status: String,
    observation_count: u64,
    export_filename: String,
    byte_size: u64,
    sha256: String,
}

fn write_stored_zip(path: &Path, entries: &[(String, Vec<u8>)]) -> Result<(), CoreError> {
    let mut file = OpenOptions::new()
        .create(true)
        .truncate(true)
        .read(true)
        .write(true)
        .open(path)
        .map_err(CoreError::io)?;
    let mut central = Vec::new();
    for (name, bytes) in entries {
        if name.starts_with('/') || name.contains("..") || name.contains('\\') {
            return Err(CoreError::invalid("unsafe ZIP entry name"));
        }
        let name_bytes = name.as_bytes();
        let size = u32::try_from(bytes.len())
            .map_err(|_| CoreError::invalid("ZIP entry exceeds 4 GiB"))?;
        let offset = u32::try_from(file.stream_position().map_err(CoreError::io)?)
            .map_err(|_| CoreError::invalid("ZIP exceeds classic ZIP offset range"))?;
        let crc = crc32fast::hash(bytes);
        write_u32(&mut file, 0x04034b50)?;
        write_u16(&mut file, 20)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u32(&mut file, crc)?;
        write_u32(&mut file, size)?;
        write_u32(&mut file, size)?;
        write_u16(&mut file, name_bytes.len() as u16)?;
        write_u16(&mut file, 0)?;
        file.write_all(name_bytes).map_err(CoreError::io)?;
        file.write_all(bytes).map_err(CoreError::io)?;
        central.push((name_bytes.to_vec(), crc, size, offset));
    }
    let central_start = file.stream_position().map_err(CoreError::io)?;
    for (name, crc, size, offset) in &central {
        write_u32(&mut file, 0x02014b50)?;
        write_u16(&mut file, 20)?;
        write_u16(&mut file, 20)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u32(&mut file, *crc)?;
        write_u32(&mut file, *size)?;
        write_u32(&mut file, *size)?;
        write_u16(&mut file, name.len() as u16)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u16(&mut file, 0)?;
        write_u32(&mut file, 0)?;
        write_u32(&mut file, *offset)?;
        file.write_all(name).map_err(CoreError::io)?;
    }
    let central_end = file.stream_position().map_err(CoreError::io)?;
    let count =
        u16::try_from(central.len()).map_err(|_| CoreError::invalid("too many ZIP entries"))?;
    write_u32(&mut file, 0x06054b50)?;
    write_u16(&mut file, 0)?;
    write_u16(&mut file, 0)?;
    write_u16(&mut file, count)?;
    write_u16(&mut file, count)?;
    write_u32(&mut file, (central_end - central_start) as u32)?;
    write_u32(&mut file, central_start as u32)?;
    write_u16(&mut file, 0)?;
    file.flush().map_err(CoreError::io)?;
    file.seek(SeekFrom::Start(0)).map_err(CoreError::io)?;
    file.sync_all().map_err(CoreError::io)
}

fn write_u16(writer: &mut File, value: u16) -> Result<(), CoreError> {
    writer
        .write_all(&value.to_le_bytes())
        .map_err(CoreError::io)
}
fn write_u32(writer: &mut File, value: u32) -> Result<(), CoreError> {
    writer
        .write_all(&value.to_le_bytes())
        .map_err(CoreError::io)
}
fn sync_parent(parent: &Path) -> Result<(), CoreError> {
    File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(CoreError::io)
}

#[cfg(test)]
mod tests {
    use super::{civil_from_days, format_timestamp_ms};
    #[test]
    fn export_timestamp_is_stable_utc_milliseconds() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        assert_eq!(
            format_timestamp_ms(1_741_435_200_123),
            "2025-03-08T12:00:00.123Z"
        );
    }
}
