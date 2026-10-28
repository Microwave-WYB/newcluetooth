//! Local storage of `gatt/v1` discovery objects.
//!
//! Kotlin serializes one JSON object per manual GATT discovery; this module
//! validates it, keeps it under `pending/gatt/v1/...` until the shared upload
//! path has sent it sealed to `gatt/v1/YYYY/MM/DD/<uuidv7>.json.encrypted`, and
//! then keeps the plaintext under `archive/gatt/v1/...` for later viewing. The
//! plaintext bytes are stored exactly as received.

use std::fs;
use std::path::{Component, Path, PathBuf};

use serde_json::Value;

use crate::CoreError;
use crate::core::{remove_file_if_exists, visit_regular_files, write_atomic_state_file};
use crate::payload_identity::PayloadIdentity;

pub const GATT_SCHEMA: &str = "gatt/v1";
const MAX_GATT_OBJECT_BYTES: usize = 8 * 1024 * 1024;
const OUTCOMES: [&str; 4] = ["complete", "partial", "failed", "cancelled"];
const STAGES: [&str; 5] = ["connect", "mtu", "discover", "read", "disconnect"];
const READ_STATUSES: [&str; 6] = [
    "ok",
    "insufficient_authentication",
    "insufficient_encryption",
    "read_not_permitted",
    "timeout",
    "not_read",
];

/// One locally stored discovery, pending upload or archived after it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct GattFile {
    pub id: String,
    pub local_path: PathBuf,
    pub object_path: String,
    pub created_at_ms: i64,
    pub size_bytes: u64,
    pub archived: bool,
}

/// A stored GATT discovery as shown to the platform layer.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct GattDiscoveryFile {
    pub discovery_id: String,
    pub local_path: String,
    pub object_path: String,
    pub created_at_ms: i64,
    pub size_bytes: u64,
    /// True once the sealed object reached storage; the plaintext copy stays.
    pub uploaded: bool,
}

impl From<&GattFile> for GattDiscoveryFile {
    fn from(file: &GattFile) -> Self {
        Self {
            discovery_id: file.id.clone(),
            local_path: file.local_path.to_string_lossy().into_owned(),
            object_path: file.object_path.clone(),
            created_at_ms: file.created_at_ms,
            size_bytes: file.size_bytes,
            uploaded: file.archived,
        }
    }
}

fn date_directory(identity: &PayloadIdentity) -> PathBuf {
    PathBuf::from("gatt")
        .join("v1")
        .join(format!("{:04}", identity.year))
        .join(format!("{:02}", identity.month))
        .join(format!("{:02}", identity.day))
}

pub(crate) fn object_path(identity: &PayloadIdentity) -> String {
    format!(
        "gatt/v1/{:04}/{:02}/{:02}/{}.json.encrypted",
        identity.year, identity.month, identity.day, identity.payload_id
    )
}

fn pending_relative_path(identity: &PayloadIdentity) -> PathBuf {
    PathBuf::from("pending")
        .join(date_directory(identity))
        .join(format!("{}.json", identity.payload_id))
}

fn archive_relative_path(identity: &PayloadIdentity) -> PathBuf {
    PathBuf::from("archive")
        .join(date_directory(identity))
        .join(format!("{}.json", identity.payload_id))
}

/// Checks the producer-side contract of a `gatt/v1` object and returns its ID.
pub(crate) fn validate_gatt_json(bytes: &[u8]) -> Result<String, CoreError> {
    if bytes.is_empty() || bytes.len() > MAX_GATT_OBJECT_BYTES {
        return Err(CoreError::invalid(format!(
            "gatt/v1 object must be 1 to {MAX_GATT_OBJECT_BYTES} bytes"
        )));
    }
    let value: Value = serde_json::from_slice(bytes)
        .map_err(|error| CoreError::invalid(format!("gatt/v1 object is not JSON: {error}")))?;
    let root = object(&value, "gatt/v1 object")?;
    if root.get("schema").and_then(Value::as_str) != Some(GATT_SCHEMA) {
        return Err(CoreError::invalid("gatt/v1 schema must be \"gatt/v1\""));
    }
    let id = string(root, "id")?;
    PayloadIdentity::parse(id)?;
    match root.get("session_id") {
        Some(Value::String(session_id)) => {
            PayloadIdentity::parse(session_id)?;
        }
        Some(Value::Null) | None => {}
        Some(_) => return Err(CoreError::invalid("gatt/v1 session_id must be a string")),
    }
    string(root, "app_version")?;
    let device = object_field(root, "device")?;
    string(device, "model")?;
    string(device, "android")?;

    let target = object_field(root, "target")?;
    if !crate::payload::is_canonical_mac(string(target, "addr")?) {
        return Err(CoreError::invalid(
            "gatt/v1 target.addr must be canonical uppercase XX:XX:XX:XX:XX:XX",
        ));
    }
    if let Some(trigger) = target.get("trigger_adv").filter(|value| !value.is_null()) {
        let trigger = object(trigger, "gatt/v1 target.trigger_adv")?;
        string(trigger, "raw")?;
        timestamp(trigger, "scanned_at")?;
    }

    timestamp(root, "started_at")?;
    timestamp(root, "ended_at")?;
    match root.get("location") {
        Some(Value::Null) | None => {}
        Some(location) => {
            let location = object(location, "gatt/v1 location")?;
            let lat = number(location, "lat")?;
            let lon = number(location, "lon")?;
            let accuracy = number(location, "accuracy")?;
            if !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lon) || accuracy < 0.0
            {
                return Err(CoreError::invalid("gatt/v1 location is out of range"));
            }
        }
    }
    one_of(root, "outcome", &OUTCOMES)?;
    one_of(root, "stage_reached", &STAGES)?;

    let services = root
        .get("services")
        .and_then(Value::as_array)
        .ok_or_else(|| CoreError::invalid("gatt/v1 services must be an array"))?;
    for service in services {
        let service = object(service, "gatt/v1 service")?;
        string(service, "uuid")?;
        for characteristic in array(service, "characteristics")? {
            let characteristic = object(characteristic, "gatt/v1 characteristic")?;
            string(characteristic, "uuid")?;
            array(characteristic, "properties")?;
            read_value(object_field(characteristic, "value")?)?;
            for descriptor in array(characteristic, "descriptors")? {
                let descriptor = object(descriptor, "gatt/v1 descriptor")?;
                string(descriptor, "uuid")?;
                read_value(object_field(descriptor, "value")?)?;
            }
        }
    }
    Ok(id.to_owned())
}

type Fields = serde_json::Map<String, Value>;

fn object<'a>(value: &'a Value, what: &str) -> Result<&'a Fields, CoreError> {
    value
        .as_object()
        .ok_or_else(|| CoreError::invalid(format!("{what} must be a JSON object")))
}

fn object_field<'a>(fields: &'a Fields, key: &str) -> Result<&'a Fields, CoreError> {
    fields
        .get(key)
        .ok_or_else(|| CoreError::invalid(format!("gatt/v1 {key} is required")))
        .and_then(|value| object(value, &format!("gatt/v1 {key}")))
}

fn array<'a>(fields: &'a Fields, key: &str) -> Result<&'a Vec<Value>, CoreError> {
    fields
        .get(key)
        .and_then(Value::as_array)
        .ok_or_else(|| CoreError::invalid(format!("gatt/v1 {key} must be an array")))
}

fn string<'a>(fields: &'a Fields, key: &str) -> Result<&'a str, CoreError> {
    fields
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| CoreError::invalid(format!("gatt/v1 {key} must be a non-empty string")))
}

fn number(fields: &Fields, key: &str) -> Result<f64, CoreError> {
    fields
        .get(key)
        .and_then(Value::as_f64)
        .filter(|value| value.is_finite())
        .ok_or_else(|| CoreError::invalid(format!("gatt/v1 {key} must be a finite number")))
}

fn one_of(fields: &Fields, key: &str, allowed: &[&str]) -> Result<(), CoreError> {
    let value = string(fields, key)?;
    if allowed.contains(&value) {
        Ok(())
    } else {
        Err(CoreError::invalid(format!(
            "gatt/v1 {key} must be one of {allowed:?}"
        )))
    }
}

/// `YYYY-MM-DDTHH:MM:SS.mmmZ`, always UTC with millisecond precision.
fn timestamp(fields: &Fields, key: &str) -> Result<(), CoreError> {
    let value = string(fields, key)?;
    let bytes = value.as_bytes();
    let well_formed = bytes.len() == 24
        && bytes.iter().enumerate().all(|(index, byte)| match index {
            4 | 7 => *byte == b'-',
            10 => *byte == b'T',
            13 | 16 => *byte == b':',
            19 => *byte == b'.',
            23 => *byte == b'Z',
            _ => byte.is_ascii_digit(),
        });
    if well_formed {
        Ok(())
    } else {
        Err(CoreError::invalid(format!(
            "gatt/v1 {key} must be a UTC YYYY-MM-DDTHH:MM:SS.mmmZ timestamp"
        )))
    }
}

/// `{ "hex": "..", "status": ".." }` of a characteristic or descriptor read.
fn read_value(value: &Fields) -> Result<(), CoreError> {
    let status = string(value, "status")?;
    let known = READ_STATUSES.contains(&status)
        || status
            .strip_prefix("error:")
            .is_some_and(|code| !code.is_empty() && code.bytes().all(|b| b.is_ascii_digit()));
    if !known {
        return Err(CoreError::invalid(format!(
            "gatt/v1 value status {status:?} is not defined"
        )));
    }
    match value.get("hex") {
        Some(Value::String(hex)) => {
            if hex.len() % 2 != 0
                || !hex
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || matches!(byte, b'a'..=b'f'))
            {
                return Err(CoreError::invalid(
                    "gatt/v1 value hex must be lowercase hexadecimal",
                ));
            }
            Ok(())
        }
        Some(Value::Null) | None if status != "ok" => Ok(()),
        _ => Err(CoreError::invalid(
            "gatt/v1 value hex is required exactly when status is ok",
        )),
    }
}

/// Validates and durably stores one discovery. Saving the same bytes again is
/// idempotent; different bytes for an existing ID are refused.
pub(crate) fn save(root: &Path, bytes: &[u8]) -> Result<GattFile, CoreError> {
    let id = validate_gatt_json(bytes)?;
    let identity = PayloadIdentity::parse(&id)?;
    for relative in [
        archive_relative_path(&identity),
        pending_relative_path(&identity),
    ] {
        let path = root.join(&relative);
        if path.exists() {
            if fs::read(&path).map_err(CoreError::io)? != bytes {
                return Err(CoreError::upload_state(format!(
                    "gatt discovery {id} already exists with different contents"
                )));
            }
            return file_for(root, &identity, &relative, relative.starts_with("archive"));
        }
    }
    let relative = pending_relative_path(&identity);
    write_atomic_state_file(&root.join(&relative), bytes)?;
    file_for(root, &identity, &relative, false)
}

fn file_for(
    root: &Path,
    identity: &PayloadIdentity,
    relative: &Path,
    archived: bool,
) -> Result<GattFile, CoreError> {
    let path = root.join(relative);
    Ok(GattFile {
        id: identity.payload_id.clone(),
        object_path: object_path(identity),
        created_at_ms: identity.created_at_ms(),
        size_bytes: fs::metadata(&path).map_err(CoreError::io)?.len(),
        local_path: path,
        archived,
    })
}

/// Moves an uploaded discovery's plaintext to the archive (idempotent).
pub(crate) fn archive(root: &Path, file: &GattFile) -> Result<(), CoreError> {
    let identity = PayloadIdentity::parse(&file.id)?;
    let destination = root.join(archive_relative_path(&identity));
    let parent = destination.parent().expect("archive path has a parent");
    fs::create_dir_all(parent).map_err(CoreError::io)?;
    if destination.exists() {
        remove_file_if_exists(&file.local_path)?;
    } else {
        fs::rename(&file.local_path, &destination).map_err(CoreError::io)?;
        fs::File::open(parent)
            .and_then(|directory| directory.sync_all())
            .map_err(CoreError::io)?;
    }
    remove_file_if_exists(&root.join("upload-cache").join(&file.object_path))
}

/// Finds valid pending and archived discoveries; anything else is counted.
pub(crate) fn discover(root: &Path) -> Result<(Vec<GattFile>, Vec<GattFile>, u64), CoreError> {
    let mut pending = Vec::new();
    let mut archived = Vec::new();
    let mut invalid = 0_u64;
    for (area, files) in [("pending", &mut pending), ("archive", &mut archived)] {
        visit_regular_files(&root.join(area).join("gatt"), &mut |path| {
            if path.extension().and_then(|extension| extension.to_str()) != Some("json") {
                return Ok(());
            }
            match validate_stored(root, area, path) {
                Ok(file) => files.push(file),
                Err(_) => invalid += 1,
            }
            Ok(())
        })?;
        files.sort_by(|left, right| left.object_path.cmp(&right.object_path));
    }
    Ok((pending, archived, invalid))
}

fn validate_stored(root: &Path, area: &str, path: &Path) -> Result<GattFile, CoreError> {
    let relative = path
        .strip_prefix(root)
        .map_err(|_| CoreError::invalid("gatt path escapes the core root"))?;
    if relative
        .components()
        .any(|component| !matches!(component, Component::Normal(_)))
    {
        return Err(CoreError::invalid("gatt path contains traversal"));
    }
    let id = path
        .file_stem()
        .and_then(|name| name.to_str())
        .ok_or_else(|| CoreError::invalid("gatt file name is not UTF-8"))?;
    let identity = PayloadIdentity::parse(id)?;
    let expected = if area == "pending" {
        pending_relative_path(&identity)
    } else {
        archive_relative_path(&identity)
    };
    if relative != expected {
        return Err(CoreError::invalid(
            "gatt file path does not match its UUIDv7 identity",
        ));
    }
    if validate_gatt_json(&fs::read(path).map_err(CoreError::io)?)? != id {
        return Err(CoreError::invalid("gatt file ID does not match its path"));
    }
    file_for(root, &identity, relative, area == "archive")
}
