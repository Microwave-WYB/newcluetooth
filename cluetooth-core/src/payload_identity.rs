use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use uuid::{Uuid, Variant};

use crate::CoreError;

pub const OBJECT_ROOT: &str = "scans/v2";
pub const PENDING_ROOT: &str = "pending";
pub const ARCHIVE_ROOT: &str = "archive";
const UUID_V7_TIMESTAMP_MAX: u64 = (1_u64 << 48) - 1;

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct PayloadIdentity {
    pub payload_id: String,
    pub year: i32,
    pub month: u32,
    pub day: u32,
}

impl PayloadIdentity {
    pub(crate) fn from_uuid(uuid: Uuid) -> Result<Self, CoreError> {
        validate_uuid_v7(uuid)?;
        let timestamp_ms = uuid_v7_timestamp_ms(uuid);
        let (year, month, day) = utc_date_from_timestamp_ms(timestamp_ms)?;
        Ok(Self {
            payload_id: uuid.hyphenated().to_string(),
            year,
            month,
            day,
        })
    }

    pub(crate) fn parse(payload_id: &str) -> Result<Self, CoreError> {
        let uuid = parse_canonical_uuid_v7(payload_id)?;
        Self::from_uuid(uuid)
    }

    pub(crate) fn object_path(&self) -> String {
        format!(
            "{OBJECT_ROOT}/{:04}/{:02}/{:02}/{}.parquet.encrypted",
            self.year, self.month, self.day, self.payload_id
        )
    }

    pub(crate) fn created_at_ms(&self) -> i64 {
        uuid_v7_timestamp_ms(parse_canonical_uuid_v7(&self.payload_id).expect("validated identity"))
            as i64
    }

    pub(crate) fn pending_relative_path(&self) -> PathBuf {
        PathBuf::from(PENDING_ROOT)
            .join("scans")
            .join("v2")
            .join(format!("{:04}", self.year))
            .join(format!("{:02}", self.month))
            .join(format!("{:02}", self.day))
            .join(format!("{}.parquet", self.payload_id))
    }

    pub(crate) fn archive_relative_path(&self, session_id: &str) -> PathBuf {
        PathBuf::from(ARCHIVE_ROOT)
            .join("sessions")
            .join(session_id)
            .join("scans")
            .join("v2")
            .join(format!("{:04}", self.year))
            .join(format!("{:02}", self.month))
            .join(format!("{:02}", self.day))
            .join(format!("{}.parquet", self.payload_id))
    }
}

pub(crate) fn system_timestamp_ms() -> Result<u64, CoreError> {
    let duration = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|error| CoreError::clock(format!("system clock is before Unix epoch: {error}")))?;
    u64::try_from(duration.as_millis())
        .map_err(|_| CoreError::clock("system timestamp does not fit in u64 milliseconds"))
}

pub(crate) fn generate_uuid_v7(timestamp_ms: u64, random: [u8; 10]) -> Result<Uuid, CoreError> {
    if timestamp_ms > UUID_V7_TIMESTAMP_MAX {
        return Err(CoreError::clock(
            "timestamp exceeds the 48-bit UUIDv7 millisecond range",
        ));
    }

    let mut bytes = [0_u8; 16];
    let timestamp_bytes = timestamp_ms.to_be_bytes();
    bytes[..6].copy_from_slice(&timestamp_bytes[2..]);
    bytes[6..].copy_from_slice(&random);
    bytes[6] = (bytes[6] & 0x0f) | 0x70;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let uuid = Uuid::from_bytes(bytes);
    validate_uuid_v7(uuid)?;
    Ok(uuid)
}

pub(crate) fn parse_canonical_uuid_v7(payload_id: &str) -> Result<Uuid, CoreError> {
    let uuid = Uuid::parse_str(payload_id)
        .map_err(|error| CoreError::invalid(format!("invalid payload UUID: {error}")))?;
    validate_uuid_v7(uuid)?;
    if uuid.hyphenated().to_string() != payload_id {
        return Err(CoreError::invalid(
            "payload ID must use canonical lowercase hyphenated form",
        ));
    }
    Ok(uuid)
}

pub(crate) fn uuid_v7_timestamp_ms(uuid: Uuid) -> u64 {
    let bytes = uuid.as_bytes();
    u64::from_be_bytes([
        0, 0, bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5],
    ])
}

pub(crate) fn validate_pending_relative_path(
    relative_path: &Path,
) -> Result<PayloadIdentity, CoreError> {
    if relative_path.is_absolute()
        || relative_path
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
    {
        return Err(CoreError::invalid(
            "pending payload path must be relative and contain no traversal",
        ));
    }

    let parts: Vec<String> = relative_path
        .components()
        .map(|component| component.as_os_str().to_string_lossy().into_owned())
        .collect();
    if parts.len() != 7 || parts[0] != PENDING_ROOT || parts[1] != "scans" || parts[2] != "v2" {
        return Err(CoreError::invalid(
            "pending payload path must match pending/scans/v2/YYYY/MM/DD/<uuid>.parquet",
        ));
    }

    let year = parse_date_part(&parts[3], 4, "year")? as i32;
    let month = parse_date_part(&parts[4], 2, "month")?;
    let day = parse_date_part(&parts[5], 2, "day")?;
    validate_date(year, month, day)?;
    let payload_id = parts[6]
        .strip_suffix(".parquet")
        .ok_or_else(|| CoreError::invalid("pending payload must end with .parquet"))?;
    let identity = PayloadIdentity::parse(payload_id)?;
    if (identity.year, identity.month, identity.day) != (year, month, day) {
        return Err(CoreError::invalid(format!(
            "payload UUID date {:04}/{:02}/{:02} does not match path {year:04}/{month:02}/{day:02}",
            identity.year, identity.month, identity.day
        )));
    }
    Ok(identity)
}

pub(crate) fn validate_archive_relative_path(
    relative_path: &Path,
) -> Result<(String, PayloadIdentity), CoreError> {
    if relative_path.is_absolute()
        || relative_path
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
    {
        return Err(CoreError::invalid(
            "archive payload path must contain no traversal",
        ));
    }
    let parts: Vec<String> = relative_path
        .components()
        .map(|component| component.as_os_str().to_string_lossy().into_owned())
        .collect();
    if parts.len() != 9
        || parts[0] != ARCHIVE_ROOT
        || parts[1] != "sessions"
        || parts[3] != "scans"
        || parts[4] != "v2"
    {
        return Err(CoreError::invalid(
            "archive payload path must match archive/sessions/<session>/scans/v2/YYYY/MM/DD/<uuid>.parquet",
        ));
    }
    parse_canonical_uuid_v7(&parts[2])?;
    let pending = PathBuf::from(PENDING_ROOT)
        .join("scans")
        .join("v2")
        .join(&parts[5])
        .join(&parts[6])
        .join(&parts[7])
        .join(&parts[8]);
    let identity = validate_pending_relative_path(&pending)?;
    Ok((parts[2].clone(), identity))
}

fn validate_uuid_v7(uuid: Uuid) -> Result<(), CoreError> {
    if uuid.get_version_num() != 7 || uuid.get_variant() != Variant::RFC4122 {
        return Err(CoreError::invalid("payload ID must be an RFC 4122 UUIDv7"));
    }
    Ok(())
}

fn parse_date_part(value: &str, width: usize, name: &str) -> Result<u32, CoreError> {
    if value.len() != width || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(CoreError::invalid(format!(
            "payload path {name} must contain exactly {width} digits"
        )));
    }
    value
        .parse()
        .map_err(|error| CoreError::invalid(format!("invalid payload path {name}: {error}")))
}

fn utc_date_from_timestamp_ms(timestamp_ms: u64) -> Result<(i32, u32, u32), CoreError> {
    let days = i64::try_from(timestamp_ms / 86_400_000)
        .map_err(|_| CoreError::clock("timestamp day count is out of range"))?;
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1_460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let mut year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_prime = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_prime + 2) / 5 + 1;
    let month = month_prime + if month_prime < 10 { 3 } else { -9 };
    year += i64::from(month <= 2);
    let year = i32::try_from(year).map_err(|_| CoreError::clock("UTC year is out of range"))?;
    if !(0..=9_999).contains(&year) {
        return Err(CoreError::clock(
            "UUIDv7 UTC year cannot be represented by the four-digit payload path",
        ));
    }
    Ok((year, month as u32, day as u32))
}

fn validate_date(year: i32, month: u32, day: u32) -> Result<(), CoreError> {
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let max_day = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if leap => 29,
        2 => 28,
        _ => 0,
    };
    if day == 0 || day > max_day {
        return Err(CoreError::invalid(format!(
            "invalid UTC payload date {year:04}/{month:02}/{day:02}"
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::{
        PayloadIdentity, generate_uuid_v7, parse_canonical_uuid_v7, validate_pending_relative_path,
    };

    #[test]
    fn deterministic_uuid_v7_and_utc_boundaries() {
        let before_midnight = generate_uuid_v7(1_742_860_799_999, [0x55; 10]).unwrap();
        let midnight = generate_uuid_v7(1_742_860_800_000, [0xaa; 10]).unwrap();
        assert_eq!(
            before_midnight.hyphenated().to_string(),
            "0195ca99-4fff-7555-9555-555555555555"
        );
        assert_eq!(
            PayloadIdentity::from_uuid(before_midnight)
                .unwrap()
                .object_path(),
            "scans/v2/2025/03/24/0195ca99-4fff-7555-9555-555555555555.parquet.encrypted"
        );
        assert_eq!(
            PayloadIdentity::from_uuid(midnight).unwrap().object_path(),
            "scans/v2/2025/03/25/0195ca99-5000-7aaa-aaaa-aaaaaaaaaaaa.parquet.encrypted"
        );
    }

    #[test]
    fn strict_uuid_and_path_validation_rejects_malformed_traversal_and_date_mismatch() {
        for invalid in [
            "0195CB93-5C00-7AAA-AAAA-AAAAAAAAAAAA",
            "0195cb935c007aaaaaaaaaaaaaaaaaaa",
            "550e8400-e29b-41d4-a716-446655440000",
        ] {
            assert!(parse_canonical_uuid_v7(invalid).is_err(), "{invalid}");
        }
        for invalid in [
            "../pending/scans/v2/2025/03/25/0195cb93-5c00-7aaa-aaaa-aaaaaaaaaaaa.parquet",
            "pending/scans/v2/2025/03/24/0195cb93-5c00-7aaa-aaaa-aaaaaaaaaaaa.parquet",
            "pending/scans/v2/2025/02/30/0195cb93-5c00-7aaa-aaaa-aaaaaaaaaaaa.parquet",
            "pending/scans/v2/2025/03/25/not-a-uuid.parquet",
        ] {
            assert!(
                validate_pending_relative_path(Path::new(invalid)).is_err(),
                "{invalid}"
            );
        }
    }
}
