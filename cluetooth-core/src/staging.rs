use std::fs::{self, File, OpenOptions};
use std::io::{ErrorKind, Read, Seek, Write};
use std::path::{Path, PathBuf};

use crc32fast::hash as crc32;
use uuid::Uuid;

use crate::config::{
    MAX_CONVERTED_BATCH_ESTIMATED_BYTES, MAX_PAYLOAD_MAX_ESTIMATED_BYTES, MAX_PAYLOAD_MAX_ROWS,
};
use crate::payload::{SchemaV2Row, inspect_schema_v2_parquet_impl, validate_rows};
use crate::payload_identity::{PayloadIdentity, parse_canonical_uuid_v7};
use crate::{CoreError, core::estimate_row_bytes};

const STAGING_DIRECTORY: &str = "active-payload";
const MANIFEST_FILE: &str = "manifest.bin";
const WAL_FILE: &str = "rows.wal";
const MANIFEST_MAGIC: &[u8; 8] = b"CLTHACT2";
const WAL_MAGIC: &[u8; 4] = b"CTW2";
const FORMAT_VERSION: u32 = 2;
const SCHEMA_VERSION: u32 = 2;
const MANIFEST_BYTES: usize = 77;
const FRAME_HEADER_BYTES: usize = 24;
const FRAME_TRAILER_BYTES: usize = 8;
const ENCODED_ROW_OVERHEAD_OVER_ESTIMATE: u64 = 6;
const MAX_FRAME_BYTES: usize =
    (MAX_CONVERTED_BATCH_ESTIMATED_BYTES + ENCODED_ROW_OVERHEAD_OVER_ESTIMATE) as usize;
const MAX_RECOVERED_ROWS: u64 = MAX_PAYLOAD_MAX_ROWS as u64 + 64;
const MAX_RECOVERED_ESTIMATED_BYTES: u64 =
    MAX_PAYLOAD_MAX_ESTIMATED_BYTES + MAX_CONVERTED_BATCH_ESTIMATED_BYTES;
const MAX_WAL_BYTES: u64 = MAX_RECOVERED_ESTIMATED_BYTES
    + MAX_RECOVERED_ROWS
        * ((FRAME_HEADER_BYTES + FRAME_TRAILER_BYTES) as u64 + ENCODED_ROW_OVERHEAD_OVER_ESTIMATE);

#[derive(Clone, Debug)]
pub(crate) struct StageManifest {
    pub identity: PayloadIdentity,
    pub session_id: Option<String>,
    pub started_at_ms: u64,
    pub estimated_bytes: u64,
    pub staged_row_count: u64,
}

#[derive(Debug)]
pub(crate) struct RecoveredStage {
    pub manifest: StageManifest,
    pub rows: Vec<SchemaV2Row>,
}

pub(crate) fn stage_rows(
    root: &Path,
    identity: &PayloadIdentity,
    session_id: Option<&str>,
    started_at_ms: u64,
    estimated_bytes: u64,
    rows: &[SchemaV2Row],
    staged_row_count: usize,
) -> Result<usize, CoreError> {
    if staged_row_count > rows.len() {
        return Err(CoreError::invalid(
            "active staged row count exceeds active rows",
        ));
    }
    validate_rows(rows)?;
    if rows.len() as u64 > MAX_RECOVERED_ROWS || estimated_bytes > MAX_RECOVERED_ESTIMATED_BYTES {
        return Err(CoreError::invalid(
            "active payload exceeds recoverable staging bounds",
        ));
    }
    let directory = ensure_staging_directory(root)?;
    let manifest_path = directory.join(MANIFEST_FILE);
    let wal_path = directory.join(WAL_FILE);
    reject_symlink_or_non_file(&manifest_path)?;
    reject_symlink_or_non_file(&wal_path)?;

    let durable_row_count = if manifest_path.exists() {
        let current = read_manifest(&manifest_path)?;
        if current.identity != *identity
            || current.session_id.as_deref() != session_id
            || current.started_at_ms != started_at_ms
        {
            return Err(CoreError::invalid(
                "active payload manifest does not match in-memory identity or session",
            ));
        }
        if current.staged_row_count == 0 && !wal_path.exists() {
            0
        } else {
            let recovered = recover(root)?.ok_or_else(|| {
                CoreError::invalid("active payload staging disappeared during checkpoint")
            })?;
            if recovered.manifest.identity != *identity
                || recovered.manifest.session_id.as_deref() != session_id
                || recovered.manifest.started_at_ms != started_at_ms
                || recovered.rows.len() < staged_row_count
                || recovered.rows.len() > rows.len()
                || recovered.rows != rows[..recovered.rows.len()]
            {
                return Err(CoreError::invalid(
                    "durable active WAL does not match the exact in-memory row prefix",
                ));
            }
            recovered.rows.len()
        }
    } else {
        if staged_row_count != 0 || wal_path.exists() {
            return Err(CoreError::invalid(
                "active payload WAL exists without its manifest",
            ));
        }
        write_manifest_atomically(
            &manifest_path,
            &StageManifest {
                identity: identity.clone(),
                session_id: session_id.map(ToOwned::to_owned),
                started_at_ms,
                estimated_bytes: 0,
                staged_row_count: 0,
            },
        )?;
        0
    };

    if durable_row_count < rows.len() {
        let mut wal = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&wal_path)
            .map_err(CoreError::io)?;
        for row in &rows[durable_row_count..] {
            write_frame(&mut wal, row)?;
        }
        wal.sync_all().map_err(CoreError::io)?;
        sync_directory(&directory)?;
    }

    let calculated_bytes = rows.iter().fold(0_u64, |total, row| {
        total.saturating_add(estimate_row_bytes(row))
    });
    if calculated_bytes != estimated_bytes {
        return Err(CoreError::invalid(
            "active payload byte estimate does not match active rows",
        ));
    }
    write_manifest_atomically(
        &manifest_path,
        &StageManifest {
            identity: identity.clone(),
            session_id: session_id.map(ToOwned::to_owned),
            started_at_ms,
            estimated_bytes,
            staged_row_count: rows.len() as u64,
        },
    )?;
    Ok(rows.len())
}

pub(crate) fn recover(root: &Path) -> Result<Option<RecoveredStage>, CoreError> {
    let directory = root.join(STAGING_DIRECTORY);
    let manifest_path = directory.join(MANIFEST_FILE);
    let wal_path = directory.join(WAL_FILE);
    reject_staging_directory(&directory)?;
    reject_symlink_or_non_file(&manifest_path)?;
    reject_symlink_or_non_file(&wal_path)?;

    if !manifest_path.exists() {
        if wal_path.exists() {
            return Err(CoreError::invalid(
                "active payload WAL exists without its manifest",
            ));
        }
        return Ok(None);
    }
    let manifest = read_manifest(&manifest_path)?;
    if !wal_path.exists() {
        if manifest.staged_row_count == 0 {
            cleanup(root)?;
            return Ok(None);
        }
        let published = root.join(manifest.identity.pending_relative_path());
        if published.exists() {
            let inspection = inspect_schema_v2_parquet_impl(&published)?;
            if inspection.payload_id == manifest.identity.payload_id
                && inspection.scan_session_id == manifest.session_id
                && inspection.row_count == manifest.staged_row_count
            {
                cleanup(root)?;
                return Ok(None);
            }
        }
        return Err(CoreError::invalid(
            "active payload manifest claims rows absent from its WAL",
        ));
    }

    let (rows, truncated_tail) = read_wal(&wal_path)?;
    if rows.is_empty() && manifest.staged_row_count == 0 {
        cleanup(root)?;
        return Ok(None);
    }
    if (rows.len() as u64) > MAX_RECOVERED_ROWS {
        return Err(CoreError::invalid(
            "active payload WAL exceeds the bounded emergency row ceiling",
        ));
    }
    validate_rows(&rows)?;
    if (rows.len() as u64) < manifest.staged_row_count {
        return Err(CoreError::invalid(
            "active payload manifest claims more rows than its durable WAL",
        ));
    }
    let manifest_prefix_bytes = rows[..manifest.staged_row_count as usize]
        .iter()
        .fold(0_u64, |total, row| {
            total.saturating_add(estimate_row_bytes(row))
        });
    if manifest_prefix_bytes != manifest.estimated_bytes {
        return Err(CoreError::invalid(
            "active payload manifest byte estimate does not match its WAL prefix",
        ));
    }
    let recovered_bytes = rows.iter().fold(0_u64, |total, row| {
        total.saturating_add(estimate_row_bytes(row))
    });
    let manifest_count = manifest.staged_row_count;
    let manifest_bytes = manifest.estimated_bytes;
    let repaired = StageManifest {
        estimated_bytes: recovered_bytes,
        staged_row_count: rows.len() as u64,
        ..manifest
    };
    if truncated_tail
        || repaired.staged_row_count != manifest_count
        || repaired.estimated_bytes != manifest_bytes
    {
        write_manifest_atomically(&manifest_path, &repaired)?;
    }
    Ok(Some(RecoveredStage {
        manifest: repaired,
        rows,
    }))
}

pub(crate) fn cleanup(root: &Path) -> Result<(), CoreError> {
    let directory = root.join(STAGING_DIRECTORY);
    reject_staging_directory(&directory)?;
    let wal_path = directory.join(WAL_FILE);
    let manifest_path = directory.join(MANIFEST_FILE);
    remove_file_if_exists(&wal_path)?;
    if directory.exists() {
        sync_directory(&directory)?;
    }
    remove_file_if_exists(&manifest_path)?;
    if directory.exists() {
        sync_directory(&directory)?;
        match fs::remove_dir(&directory) {
            Ok(()) => sync_directory(root)?,
            Err(error) if error.kind() == ErrorKind::NotFound => {}
            Err(error) => return Err(CoreError::io(error)),
        }
    }
    Ok(())
}

fn ensure_staging_directory(root: &Path) -> Result<PathBuf, CoreError> {
    let directory = root.join(STAGING_DIRECTORY);
    reject_staging_directory(&directory)?;
    if !directory.exists() {
        fs::create_dir(&directory).map_err(CoreError::io)?;
        sync_directory(root)?;
    }
    Ok(directory)
}

fn reject_staging_directory(path: &Path) -> Result<(), CoreError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => Err(
            CoreError::invalid("active payload staging path must be a real directory"),
        ),
        Ok(_) => Ok(()),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(()),
        Err(error) => Err(CoreError::io(error)),
    }
}

fn reject_symlink_or_non_file(path: &Path) -> Result<(), CoreError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => Err(
            CoreError::invalid("active payload staging file must be a regular file"),
        ),
        Ok(_) => Ok(()),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(()),
        Err(error) => Err(CoreError::io(error)),
    }
}

fn sync_directory(path: &Path) -> Result<(), CoreError> {
    File::open(path)
        .and_then(|directory| directory.sync_all())
        .map_err(CoreError::io)
}

fn remove_file_if_exists(path: &Path) -> Result<(), CoreError> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(()),
        Err(error) => Err(CoreError::io(error)),
    }
}

fn write_manifest_atomically(path: &Path, manifest: &StageManifest) -> Result<(), CoreError> {
    let parent = path
        .parent()
        .ok_or_else(|| CoreError::invalid("active manifest path has no parent"))?;
    let bytes = encode_manifest(manifest)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cluetooth-active-")
        .suffix(".tmp")
        .tempfile_in(parent)
        .map_err(CoreError::io)?
        .into_temp_path();
    {
        let mut file = OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(&temporary)
            .map_err(CoreError::io)?;
        file.write_all(&bytes).map_err(CoreError::io)?;
        file.sync_all().map_err(CoreError::io)?;
    }
    temporary
        .persist(path)
        .map_err(|error| CoreError::io(error.error))?;
    sync_directory(parent)
}

fn encode_manifest(manifest: &StageManifest) -> Result<Vec<u8>, CoreError> {
    let payload_uuid = parse_canonical_uuid_v7(&manifest.identity.payload_id)?;
    let session_uuid = manifest
        .session_id
        .as_deref()
        .map(parse_canonical_uuid_v7)
        .transpose()?;
    let mut bytes = Vec::with_capacity(77);
    bytes.extend_from_slice(MANIFEST_MAGIC);
    put_u32(&mut bytes, FORMAT_VERSION);
    put_u32(&mut bytes, SCHEMA_VERSION);
    bytes.extend_from_slice(payload_uuid.as_bytes());
    bytes.push(u8::from(session_uuid.is_some()));
    bytes.extend_from_slice(session_uuid.unwrap_or_else(Uuid::nil).as_bytes());
    put_u64(&mut bytes, manifest.started_at_ms);
    put_u64(&mut bytes, manifest.estimated_bytes);
    put_u64(&mut bytes, manifest.staged_row_count);
    let checksum = crc32(&bytes);
    put_u32(&mut bytes, checksum);
    Ok(bytes)
}

fn read_manifest(path: &Path) -> Result<StageManifest, CoreError> {
    let mut file = File::open(path).map_err(CoreError::io)?;
    if file.metadata().map_err(CoreError::io)?.len() != MANIFEST_BYTES as u64 {
        return Err(CoreError::invalid(
            "active payload manifest has an invalid bounded length",
        ));
    }
    let mut bytes = [0_u8; MANIFEST_BYTES];
    file.read_exact(&mut bytes).map_err(CoreError::io)?;
    let (contents, checksum_bytes) = bytes.split_at(bytes.len() - 4);
    let expected = u32::from_le_bytes(checksum_bytes.try_into().expect("four checksum bytes"));
    if crc32(contents) != expected {
        return Err(CoreError::invalid(
            "active payload manifest checksum mismatch",
        ));
    }
    let mut cursor = Cursor::new(contents);
    if cursor.take(8)? != MANIFEST_MAGIC {
        return Err(CoreError::invalid("active payload manifest magic mismatch"));
    }
    if cursor.u32()? != FORMAT_VERSION || cursor.u32()? != SCHEMA_VERSION {
        return Err(CoreError::invalid(
            "unsupported active payload format or schema version",
        ));
    }
    let payload_uuid = Uuid::from_bytes(cursor.array_16()?);
    let identity = PayloadIdentity::from_uuid(payload_uuid)?;
    let session_present = cursor.u8()?;
    let session_uuid = Uuid::from_bytes(cursor.array_16()?);
    let session_id = match session_present {
        0 if session_uuid.is_nil() => None,
        1 => {
            parse_canonical_uuid_v7(&session_uuid.hyphenated().to_string())?;
            Some(session_uuid.hyphenated().to_string())
        }
        _ => {
            return Err(CoreError::invalid(
                "active manifest session encoding is invalid",
            ));
        }
    };
    let started_at_ms = cursor.u64()?;
    let estimated_bytes = cursor.u64()?;
    let staged_row_count = cursor.u64()?;
    if cursor.remaining() != 0
        || staged_row_count > MAX_RECOVERED_ROWS
        || estimated_bytes > MAX_RECOVERED_ESTIMATED_BYTES
    {
        return Err(CoreError::invalid(
            "active payload manifest bounds are invalid",
        ));
    }
    Ok(StageManifest {
        identity,
        session_id,
        started_at_ms,
        estimated_bytes,
        staged_row_count,
    })
}

fn write_frame(file: &mut File, row: &SchemaV2Row) -> Result<(), CoreError> {
    let payload = encode_row(row)?;
    if payload.is_empty() || payload.len() > MAX_FRAME_BYTES {
        return Err(CoreError::invalid(
            "active WAL row frame exceeds its bounded length",
        ));
    }
    let length = payload.len() as u32;
    let mut header = Vec::with_capacity(FRAME_HEADER_BYTES);
    header.extend_from_slice(WAL_MAGIC);
    put_u32(&mut header, FORMAT_VERSION);
    put_u32(&mut header, length);
    put_u32(&mut header, 1);
    put_u32(&mut header, crc32(&payload));
    let header_checksum = crc32(&header);
    put_u32(&mut header, header_checksum);

    let mut authenticated = Vec::with_capacity(FRAME_HEADER_BYTES + payload.len() + 4);
    authenticated.extend_from_slice(&header);
    authenticated.extend_from_slice(&payload);
    put_u32(&mut authenticated, length);
    let frame_checksum = crc32(&authenticated);
    file.write_all(&authenticated).map_err(CoreError::io)?;
    file.write_all(&frame_checksum.to_le_bytes())
        .map_err(CoreError::io)
}

fn read_wal(path: &Path) -> Result<(Vec<SchemaV2Row>, bool), CoreError> {
    if fs::metadata(path).map_err(CoreError::io)?.len() > MAX_WAL_BYTES {
        return Err(CoreError::invalid(
            "active payload WAL exceeds its bounded byte ceiling",
        ));
    }
    let mut file = OpenOptions::new()
        .read(true)
        .write(true)
        .open(path)
        .map_err(CoreError::io)?;
    let mut rows = Vec::new();
    let mut truncated = false;
    loop {
        let frame_start = file.stream_position().map_err(CoreError::io)?;
        let mut header = [0_u8; FRAME_HEADER_BYTES];
        let header_read = read_until_eof(&mut file, &mut header)?;
        if header_read == 0 {
            break;
        }
        if header_read < FRAME_HEADER_BYTES {
            truncate_torn_tail(&mut file, frame_start)?;
            truncated = true;
            break;
        }
        let expected_header_crc =
            u32::from_le_bytes(header[20..24].try_into().expect("four checksum bytes"));
        if crc32(&header[..20]) != expected_header_crc {
            return Err(CoreError::invalid(
                "corrupt complete active WAL frame header checksum",
            ));
        }
        if &header[..4] != WAL_MAGIC
            || u32::from_le_bytes(header[4..8].try_into().expect("four version bytes"))
                != FORMAT_VERSION
            || u32::from_le_bytes(header[12..16].try_into().expect("four row-count bytes")) != 1
        {
            return Err(CoreError::invalid(
                "corrupt complete active WAL frame header",
            ));
        }
        let length =
            u32::from_le_bytes(header[8..12].try_into().expect("four length bytes")) as usize;
        if length == 0 || length > MAX_FRAME_BYTES {
            return Err(CoreError::invalid(
                "corrupt complete active WAL frame length",
            ));
        }
        let expected_payload_crc =
            u32::from_le_bytes(header[16..20].try_into().expect("four checksum bytes"));
        let mut payload = vec![0_u8; length];
        if read_until_eof(&mut file, &mut payload)? < length {
            truncate_torn_tail(&mut file, frame_start)?;
            truncated = true;
            break;
        }
        let mut trailer = [0_u8; FRAME_TRAILER_BYTES];
        if read_until_eof(&mut file, &mut trailer)? < FRAME_TRAILER_BYTES {
            truncate_torn_tail(&mut file, frame_start)?;
            truncated = true;
            break;
        }
        if crc32(&payload) != expected_payload_crc {
            return Err(CoreError::invalid(
                "corrupt complete active WAL frame payload checksum",
            ));
        }
        let trailer_length =
            u32::from_le_bytes(trailer[..4].try_into().expect("four trailer length bytes"));
        let expected_frame_crc =
            u32::from_le_bytes(trailer[4..].try_into().expect("four frame checksum bytes"));
        let mut authenticated = Vec::with_capacity(FRAME_HEADER_BYTES + payload.len() + 4);
        authenticated.extend_from_slice(&header);
        authenticated.extend_from_slice(&payload);
        authenticated.extend_from_slice(&trailer[..4]);
        if trailer_length as usize != length || crc32(&authenticated) != expected_frame_crc {
            return Err(CoreError::invalid(
                "corrupt complete active WAL frame trailer",
            ));
        }
        rows.push(decode_row(&payload)?);
        if (rows.len() as u64) > MAX_RECOVERED_ROWS {
            return Err(CoreError::invalid(
                "active payload WAL exceeds the bounded emergency row ceiling",
            ));
        }
    }
    if truncated && let Some(parent) = path.parent() {
        sync_directory(parent)?;
    }
    Ok((rows, truncated))
}

fn truncate_torn_tail(file: &mut File, frame_start: u64) -> Result<(), CoreError> {
    file.set_len(frame_start).map_err(CoreError::io)?;
    file.sync_all().map_err(CoreError::io)
}

fn read_until_eof(file: &mut File, output: &mut [u8]) -> Result<usize, CoreError> {
    let mut read = 0;
    while read < output.len() {
        match file.read(&mut output[read..]) {
            Ok(0) => break,
            Ok(count) => read += count,
            Err(error) if error.kind() == ErrorKind::Interrupted => {}
            Err(error) => return Err(CoreError::io(error)),
        }
    }
    Ok(read)
}

fn encode_row(row: &SchemaV2Row) -> Result<Vec<u8>, CoreError> {
    let mut bytes = Vec::with_capacity(row.raw.len() + row.addr.len() + 96);
    put_string(&mut bytes, &row.addr)?;
    put_option_i32(&mut bytes, row.rssi);
    bytes.extend_from_slice(&row.scanned_at_ms.to_le_bytes());
    put_bytes(&mut bytes, &row.raw)?;
    put_option_string(&mut bytes, row.local_name.as_deref())?;
    put_option_i32(&mut bytes, row.tx_power);
    bytes.push(match row.is_connectable {
        None => 0,
        Some(false) => 1,
        Some(true) => 2,
    });
    put_option_f64(&mut bytes, row.lat);
    put_option_f64(&mut bytes, row.lon);
    put_option_f32(&mut bytes, row.accuracy);
    Ok(bytes)
}

fn decode_row(bytes: &[u8]) -> Result<SchemaV2Row, CoreError> {
    let mut cursor = Cursor::new(bytes);
    let row = SchemaV2Row {
        addr: cursor.string()?,
        rssi: cursor.option_i32()?,
        scanned_at_ms: cursor.i64()?,
        raw: cursor.bytes()?,
        local_name: cursor.option_string()?,
        tx_power: cursor.option_i32()?,
        is_connectable: match cursor.u8()? {
            0 => None,
            1 => Some(false),
            2 => Some(true),
            _ => return Err(CoreError::invalid("active WAL bool tag is invalid")),
        },
        lat: cursor.option_f64()?,
        lon: cursor.option_f64()?,
        accuracy: cursor.option_f32()?,
    };
    if cursor.remaining() != 0 {
        return Err(CoreError::invalid("active WAL row contains trailing bytes"));
    }
    Ok(row)
}

fn put_u32(output: &mut Vec<u8>, value: u32) {
    output.extend_from_slice(&value.to_le_bytes());
}

fn put_u64(output: &mut Vec<u8>, value: u64) {
    output.extend_from_slice(&value.to_le_bytes());
}

fn put_bytes(output: &mut Vec<u8>, value: &[u8]) -> Result<(), CoreError> {
    let length = u32::try_from(value.len())
        .map_err(|_| CoreError::invalid("active WAL byte field exceeds u32"))?;
    put_u32(output, length);
    output.extend_from_slice(value);
    Ok(())
}

fn put_string(output: &mut Vec<u8>, value: &str) -> Result<(), CoreError> {
    put_bytes(output, value.as_bytes())
}

fn put_option_string(output: &mut Vec<u8>, value: Option<&str>) -> Result<(), CoreError> {
    output.push(u8::from(value.is_some()));
    if let Some(value) = value {
        put_string(output, value)?;
    }
    Ok(())
}

fn put_option_i32(output: &mut Vec<u8>, value: Option<i32>) {
    output.push(u8::from(value.is_some()));
    if let Some(value) = value {
        output.extend_from_slice(&value.to_le_bytes());
    }
}

fn put_option_f64(output: &mut Vec<u8>, value: Option<f64>) {
    output.push(u8::from(value.is_some()));
    if let Some(value) = value {
        output.extend_from_slice(&value.to_bits().to_le_bytes());
    }
}

fn put_option_f32(output: &mut Vec<u8>, value: Option<f32>) {
    output.push(u8::from(value.is_some()));
    if let Some(value) = value {
        output.extend_from_slice(&value.to_bits().to_le_bytes());
    }
}

struct Cursor<'a> {
    bytes: &'a [u8],
    offset: usize,
}

impl<'a> Cursor<'a> {
    fn new(bytes: &'a [u8]) -> Self {
        Self { bytes, offset: 0 }
    }

    fn remaining(&self) -> usize {
        self.bytes.len().saturating_sub(self.offset)
    }

    fn take(&mut self, length: usize) -> Result<&'a [u8], CoreError> {
        let end = self
            .offset
            .checked_add(length)
            .filter(|end| *end <= self.bytes.len())
            .ok_or_else(|| CoreError::invalid("active payload binary field is truncated"))?;
        let value = &self.bytes[self.offset..end];
        self.offset = end;
        Ok(value)
    }

    fn u8(&mut self) -> Result<u8, CoreError> {
        Ok(self.take(1)?[0])
    }

    fn u32(&mut self) -> Result<u32, CoreError> {
        Ok(u32::from_le_bytes(
            self.take(4)?.try_into().expect("four bytes"),
        ))
    }

    fn u64(&mut self) -> Result<u64, CoreError> {
        Ok(u64::from_le_bytes(
            self.take(8)?.try_into().expect("eight bytes"),
        ))
    }

    fn i64(&mut self) -> Result<i64, CoreError> {
        Ok(i64::from_le_bytes(
            self.take(8)?.try_into().expect("eight bytes"),
        ))
    }

    fn array_16(&mut self) -> Result<[u8; 16], CoreError> {
        Ok(self.take(16)?.try_into().expect("sixteen bytes"))
    }

    fn bytes(&mut self) -> Result<Vec<u8>, CoreError> {
        let length = self.u32()? as usize;
        if length > MAX_FRAME_BYTES || length > self.remaining() {
            return Err(CoreError::invalid(
                "active WAL byte field length is invalid",
            ));
        }
        Ok(self.take(length)?.to_vec())
    }

    fn string(&mut self) -> Result<String, CoreError> {
        String::from_utf8(self.bytes()?)
            .map_err(|_| CoreError::invalid("active WAL string is not UTF-8"))
    }

    fn option_string(&mut self) -> Result<Option<String>, CoreError> {
        match self.u8()? {
            0 => Ok(None),
            1 => self.string().map(Some),
            _ => Err(CoreError::invalid("active WAL option tag is invalid")),
        }
    }

    fn option_i32(&mut self) -> Result<Option<i32>, CoreError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(i32::from_le_bytes(
                self.take(4)?.try_into().expect("four bytes"),
            ))),
            _ => Err(CoreError::invalid("active WAL option tag is invalid")),
        }
    }

    fn option_f64(&mut self) -> Result<Option<f64>, CoreError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(f64::from_bits(u64::from_le_bytes(
                self.take(8)?.try_into().expect("eight bytes"),
            )))),
            _ => Err(CoreError::invalid("active WAL option tag is invalid")),
        }
    }

    fn option_f32(&mut self) -> Result<Option<f32>, CoreError> {
        match self.u8()? {
            0 => Ok(None),
            1 => Ok(Some(f32::from_bits(u32::from_le_bytes(
                self.take(4)?.try_into().expect("four bytes"),
            )))),
            _ => Err(CoreError::invalid("active WAL option tag is invalid")),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::fs::{self, OpenOptions};
    use std::io::Write;

    use super::*;
    use crate::payload_identity::generate_uuid_v7;

    fn row(index: i64) -> SchemaV2Row {
        SchemaV2Row {
            addr: "AA:BB:CC:DD:EE:FF".to_owned(),
            rssi: Some(-50),
            scanned_at_ms: index,
            raw: vec![0, 0xff, index as u8],
            local_name: Some("sensor".to_owned()),
            tx_power: Some(-8),
            is_connectable: Some(true),
            lat: Some(32.0),
            lon: Some(-117.0),
            accuracy: Some(4.0),
        }
    }

    fn identity() -> PayloadIdentity {
        PayloadIdentity::from_uuid(generate_uuid_v7(1_742_860_800_000, [0x55; 10]).unwrap())
            .unwrap()
    }

    fn stage(root: &Path, rows: &[SchemaV2Row]) {
        let estimated = rows.iter().map(estimate_row_bytes).sum();
        stage_rows(
            root,
            &identity(),
            None,
            1_742_860_799_000,
            estimated,
            rows,
            0,
        )
        .unwrap();
    }

    #[test]
    fn incomplete_final_header_payload_or_trailer_is_truncated_without_losing_complete_rows() {
        let frame_directory = tempfile::tempdir().unwrap();
        let frame_path = frame_directory.path().join("frame");
        let mut frame_file = File::create(&frame_path).unwrap();
        write_frame(&mut frame_file, &row(2)).unwrap();
        drop(frame_file);
        let frame = fs::read(frame_path).unwrap();
        let payload_length = u32::from_le_bytes(frame[8..12].try_into().unwrap()) as usize;

        for partial_length in [
            5_usize,
            FRAME_HEADER_BYTES + payload_length / 2,
            FRAME_HEADER_BYTES + payload_length + 4,
        ] {
            let directory = tempfile::tempdir().unwrap();
            stage(directory.path(), &[row(1)]);
            let wal = directory.path().join(STAGING_DIRECTORY).join(WAL_FILE);
            let durable_length = fs::metadata(&wal).unwrap().len();
            OpenOptions::new()
                .append(true)
                .open(&wal)
                .unwrap()
                .write_all(&frame[..partial_length])
                .unwrap();

            let recovered = recover(directory.path()).unwrap().unwrap();
            assert_eq!(recovered.rows, vec![row(1)]);
            assert_eq!(fs::metadata(wal).unwrap().len(), durable_length);
        }
    }

    #[test]
    fn corrupt_complete_frame_is_rejected_and_retained() {
        for (name, corrupt) in [
            ("magic", 0_usize),
            ("version", 4),
            ("length-down", 8),
            ("length-up", 9),
            ("row-count", 12),
            ("payload-checksum", 16),
            ("header-checksum", 20),
            ("payload", FRAME_HEADER_BYTES),
        ] {
            let directory = tempfile::tempdir().unwrap();
            stage(directory.path(), &[row(1)]);
            let wal = directory.path().join(STAGING_DIRECTORY).join(WAL_FILE);
            let mut bytes = fs::read(&wal).unwrap();
            bytes[corrupt] ^= if name == "length-up" { 0x40 } else { 0x01 };
            fs::write(&wal, &bytes).unwrap();

            let error = recover(directory.path()).unwrap_err();
            assert!(
                error.to_string().contains("corrupt complete"),
                "{name}: {error}"
            );
            assert_eq!(fs::read(wal).unwrap(), bytes, "{name}");
        }

        for trailer_offset in [0_usize, 4] {
            let directory = tempfile::tempdir().unwrap();
            stage(directory.path(), &[row(1)]);
            let wal = directory.path().join(STAGING_DIRECTORY).join(WAL_FILE);
            let mut bytes = fs::read(&wal).unwrap();
            let payload_length = u32::from_le_bytes(bytes[8..12].try_into().unwrap()) as usize;
            bytes[FRAME_HEADER_BYTES + payload_length + trailer_offset] ^= 1;
            fs::write(&wal, &bytes).unwrap();
            let error = recover(directory.path()).unwrap_err();
            assert!(error.to_string().contains("trailer"));
            assert_eq!(fs::read(wal).unwrap(), bytes);
        }
    }

    #[test]
    fn zero_row_empty_or_partial_first_wal_is_cleaned_as_no_active_payload() {
        for partial in [Vec::new(), b"CTW2\x02".to_vec()] {
            let directory = tempfile::tempdir().unwrap();
            stage(directory.path(), &[]);
            let staging = directory.path().join(STAGING_DIRECTORY);
            fs::write(staging.join(WAL_FILE), partial).unwrap();

            assert!(recover(directory.path()).unwrap().is_none());
            assert!(!staging.exists());
        }
    }

    #[test]
    fn maximum_admitted_batch_estimate_round_trips_through_recovery() {
        let directory = tempfile::tempdir().unwrap();
        let per_row_fixed = crate::config::INPUT_FIXED_ESTIMATE_BYTES + 17;
        let raw_per_row = ((crate::config::MAX_INPUT_BATCH_ESTIMATED_BYTES
            - crate::config::MAX_INPUT_BATCH_ROWS as u64 * per_row_fixed)
            / crate::config::MAX_INPUT_BATCH_ROWS as u64) as usize;
        let rows: Vec<_> = (0..crate::config::MAX_INPUT_BATCH_ROWS)
            .map(|index| {
                let mut value = row(index as i64);
                value.local_name = None;
                value.raw = vec![index as u8; raw_per_row];
                value
            })
            .collect();
        let estimated = rows.iter().map(estimate_row_bytes).sum::<u64>();
        assert_eq!(
            MAX_RECOVERED_ROWS,
            crate::config::MAX_PAYLOAD_MAX_ROWS as u64 + crate::config::MAX_INPUT_BATCH_ROWS as u64,
        );
        assert_eq!(
            MAX_RECOVERED_ESTIMATED_BYTES,
            crate::config::MAX_PAYLOAD_MAX_ESTIMATED_BYTES + MAX_CONVERTED_BATCH_ESTIMATED_BYTES,
        );
        assert!(estimated <= MAX_CONVERTED_BATCH_ESTIMATED_BYTES);
        stage(directory.path(), &rows);
        let recovered = recover(directory.path()).unwrap().unwrap();
        assert_eq!(recovered.rows, rows);
        assert_eq!(recovered.manifest.estimated_bytes, estimated);
    }

    #[test]
    fn manifest_cannot_claim_rows_or_estimates_missing_from_wal() {
        let directory = tempfile::tempdir().unwrap();
        let rows = [row(1), row(2)];
        stage(directory.path(), &rows);
        let manifest_path = directory.path().join(STAGING_DIRECTORY).join(MANIFEST_FILE);
        let mut manifest = read_manifest(&manifest_path).unwrap();
        manifest.staged_row_count += 1;
        write_manifest_atomically(&manifest_path, &manifest).unwrap();
        assert!(recover(directory.path()).is_err());

        manifest.staged_row_count -= 1;
        manifest.estimated_bytes += 1;
        write_manifest_atomically(&manifest_path, &manifest).unwrap();
        assert!(recover(directory.path()).is_err());
    }

    #[test]
    fn wal_ahead_of_manifest_is_recovered_and_manifest_is_repaired() {
        let directory = tempfile::tempdir().unwrap();
        let rows = [row(1), row(2)];
        stage(directory.path(), &rows[..1]);
        let wal = directory.path().join(STAGING_DIRECTORY).join(WAL_FILE);
        let mut file = OpenOptions::new().append(true).open(&wal).unwrap();
        write_frame(&mut file, &rows[1]).unwrap();
        file.sync_all().unwrap();

        let estimated = rows.iter().map(estimate_row_bytes).sum::<u64>();
        assert_eq!(
            stage_rows(
                directory.path(),
                &identity(),
                None,
                1_742_860_799_000,
                estimated,
                &rows,
                1,
            )
            .unwrap(),
            2
        );
        let recovered = recover(directory.path()).unwrap().unwrap();
        assert_eq!(recovered.rows, rows);
        assert_eq!(recovered.manifest.staged_row_count, 2);
        assert_eq!(recovered.manifest.estimated_bytes, estimated);
    }
}
