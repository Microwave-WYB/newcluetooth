//! GATT in the v2 core (`docs/v2-api.md` §2.7, D-13): a gatt/v1 object as a [`GattView`], its rows in
//! `gatt_discoveries`/`gatt_attributes`, and the device's last stored result.

use rusqlite::{Connection, OptionalExtension, Transaction, params};
use serde_json::Value;
use sha2::{Digest, Sha256};

use super::catalog::Catalog;
use super::error::CoreError;
use super::keys::key_of;
use super::types::*;

fn invalid(detail: impl Into<String>) -> CoreError {
    CoreError::InvalidPayload {
        detail: detail.into(),
    }
}

/// `YYYY-MM-DDTHH:MM:SS.mmmZ` (the gatt/v1 timestamp form, already validated) → epoch ms.
pub(crate) fn parse_ms(text: &str) -> Option<i64> {
    let b = text.as_bytes();
    if b.len() != 24 || b.get(23) != Some(&b'Z') {
        return None;
    }
    let num = |r: std::ops::Range<usize>| text.get(r)?.parse::<i64>().ok();
    let (y, m, d) = (num(0..4)?, num(5..7)?, num(8..10)?);
    let (hh, mm, ss, ms) = (num(11..13)?, num(14..16)?, num(17..19)?, num(20..23)?);
    // days from civil (Howard Hinnant)
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(((days * 24 + hh) * 60 + mm) * 60_000 + ss * 1000 + ms)
}

const CHARACTERISTICS: &[(&str, &str)] = &[
    ("2A00", "Device Name"),
    ("2A01", "Appearance"),
    ("2A04", "Peripheral Preferred Connection Parameters"),
    ("2A05", "Service Changed"),
    ("2A19", "Battery Level"),
    ("2A23", "System ID"),
    ("2A24", "Model Number String"),
    ("2A25", "Serial Number String"),
    ("2A26", "Firmware Revision String"),
    ("2A27", "Hardware Revision String"),
    ("2A28", "Software Revision String"),
    ("2A29", "Manufacturer Name String"),
    ("2A50", "PnP ID"),
    ("2AA6", "Central Address Resolution"),
];

fn hex_bytes(hex: &str) -> Option<Vec<u8>> {
    (0..hex.len())
        .step_by(2)
        .map(|i| {
            hex.get(i..i + 2)
                .and_then(|h| u8::from_str_radix(h, 16).ok())
        })
        .collect()
}

/// §2.7 display-only value: Battery Level "78 %", Appearance from the SIG table, printable UTF-8, else hex.
fn value_text(catalog: &Catalog, uuid: &str, hex: &str) -> String {
    let Some(bytes) = hex_bytes(hex) else {
        return hex.to_owned();
    };
    match (uuid, bytes.as_slice()) {
        ("2A19", [level]) => return format!("{level} %"),
        ("2A01", [a, b]) => {
            if let Some(name) = catalog.appearance_name(u16::from_le_bytes([*a, *b])) {
                return name;
            }
        }
        _ => {}
    }
    match std::str::from_utf8(&bytes) {
        Ok(text) if !text.is_empty() && !text.chars().any(char::is_control) => text.to_owned(),
        _ => hex.to_owned(),
    }
}

fn s<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key)?.as_str()
}

fn i(v: &Value, key: &str) -> Option<i64> {
    v.get(key)?.as_i64()
}

/// gatt/v1 status codes the failure table uses (Android's, plus the GATT engine's own negative ones).
const STATUS_SUCCESS: i64 = 0;
const STATUS_TIMED_OUT: i64 = -1;
/// Android's generic `GATT_ERROR`, for a failure that carries no status.
const STATUS_GATT_ERROR: i32 = 133;
/// `GATT_INTERNAL_ERROR`: the engine itself failed mid-discovery (`:platform-api`, #99). It is a known status, so
/// it is `Other(-6)` like any other.
#[cfg(test)]
const STATUS_INTERNAL_ERROR: i32 = -6;

/// The §2.7 failure table, the same as `:platform`'s (#99). A known status (Android's 133, 62, 19, …, or an
/// engine code such as `GATT_INTERNAL_ERROR` −6) is `Other(status)`, so the UI can name it; `ConnectionFailed` and
/// `DisconnectedUnexpectedly` remain only when no status is known. `None` for a complete run.
fn failure(o: &Value, outcome: &str) -> Option<GattFailure> {
    let null = Value::Null;
    let error = o.get("error").unwrap_or(&null);
    let disconnect = o.get("disconnect").unwrap_or(&null);
    let initiator = s(disconnect, "initiator");
    // the error's status, else the disconnect's; success is no status
    let status = i(error, "status")
        .or_else(|| i(disconnect, "status"))
        .filter(|s| *s != STATUS_SUCCESS);
    Some(match outcome {
        "complete" => return None,
        "cancelled" => GattFailure::Cancelled,
        _ if initiator == Some("timeout") || status == Some(STATUS_TIMED_OUT) => {
            GattFailure::TimedOut
        }
        _ => match status {
            Some(status) => GattFailure::Other {
                status: i32::try_from(status).unwrap_or(STATUS_GATT_ERROR),
            },
            None if s(error, "stage") == Some("connect") => GattFailure::ConnectionFailed,
            None if initiator == Some("remote") => GattFailure::DisconnectedUnexpectedly,
            None => GattFailure::Other {
                status: STATUS_GATT_ERROR,
            },
        },
    })
}

/// The §2.7 view of one gatt/v1 object, plus its outcome.
pub(crate) fn view(catalog: &Catalog, json: &str) -> Result<(GattView, String), CoreError> {
    let o: Value = serde_json::from_str(json).map_err(|e| invalid(e.to_string()))?;
    let id = s(&o, "id").ok_or_else(|| invalid("gatt/v1 id is missing"))?;
    let addr = o
        .get("target")
        .and_then(|t| s(t, "addr"))
        .ok_or_else(|| invalid("target.addr is missing"))?;
    let key = key_of(addr).ok_or_else(|| invalid("target.addr is not a MAC"))?;
    let outcome = s(&o, "outcome")
        .ok_or_else(|| invalid("outcome is missing"))?
        .to_owned();
    let started_ms = s(&o, "started_at").and_then(parse_ms).unwrap_or(0);
    let null = Value::Null;
    let timings = o.get("timings_ms").unwrap_or(&null);
    let raw_services = o
        .get("services")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut readable = 0u32;
    let mut read = 0u32;
    let services: Vec<GattServiceView> = raw_services
        .iter()
        .map(|sv| {
            let uuid = s(sv, "uuid").unwrap_or_default().to_owned();
            let characteristics = sv
                .get("characteristics")
                .and_then(Value::as_array)
                .map(|cs| {
                    cs.iter()
                        .map(|c| {
                            let cu = s(c, "uuid").unwrap_or_default().to_owned();
                            let properties: Vec<String> = c
                                .get("properties")
                                .and_then(Value::as_array)
                                .map(|p| {
                                    p.iter()
                                        .filter_map(|x| x.as_str().map(str::to_owned))
                                        .collect()
                                })
                                .unwrap_or_default();
                            if properties.iter().any(|p| p == "read") {
                                readable += 1;
                            }
                            let value = c.get("value").unwrap_or(&null);
                            let hex = (s(value, "status") == Some("ok"))
                                .then(|| s(value, "hex"))
                                .flatten();
                            if hex.is_some() {
                                read += 1;
                            }
                            GattCharView {
                                name: CHARACTERISTICS
                                    .iter()
                                    .find(|(u, _)| *u == cu)
                                    .map(|(_, n)| (*n).to_owned()),
                                value_text: hex.map(|h| value_text(catalog, &cu, h)),
                                uuid: cu,
                                properties,
                            }
                        })
                        .collect()
                })
                .unwrap_or_default();
            GattServiceView {
                name: u16::from_str_radix(&uuid, 16)
                    .ok()
                    .filter(|_| uuid.len() == 4)
                    .and_then(|u| catalog.uuid16_name(u)),
                uuid,
                characteristics,
            }
        })
        .collect();
    let error = o.get("error").unwrap_or(&null);
    let failure = failure(&o, &outcome);
    let failed_at = match s(error, "stage").or_else(|| s(&o, "stage_reached")) {
        Some("connect" | "mtu") => 0,
        Some("discover") => 1,
        Some("read") => 2,
        _ => 3,
    };
    let ms = |k: &str| i(timings, k).map(|v| v as u32);
    let connect_ms = match (ms("connect"), ms("mtu")) {
        (None, None) => None,
        (a, b) => Some(a.unwrap_or(0) + b.unwrap_or(0)),
    };
    let step = |kind, duration_ms, services, read, readable| GattStep {
        kind,
        state: GattStepState::Done,
        duration_ms,
        services,
        read,
        readable,
        failure: None,
    };
    let all = vec![
        step(GattStepKind::Connecting, connect_ms, None, None, None),
        step(
            GattStepKind::DiscoveringServices,
            ms("discover"),
            Some(services.len() as u32),
            None,
            None,
        ),
        step(
            GattStepKind::ReadingCharacteristics,
            ms("read"),
            None,
            Some(read),
            Some(readable),
        ),
        step(GattStepKind::Disconnected, None, None, None, None),
    ];
    let steps = match failure {
        None => all,
        Some(f) => {
            let mut steps: Vec<GattStep> = all.into_iter().take(failed_at + 1).collect();
            if let Some(last) = steps.last_mut() {
                last.state = GattStepState::Failed;
                last.failure = Some(f);
            }
            steps
        }
    };
    Ok((
        GattView {
            discovery_id: id.to_owned(),
            key,
            started_ms,
            ok: outcome == "complete",
            steps,
            // D-13.6: the services show only after a complete run
            services: if outcome == "complete" {
                services
            } else {
                Vec::new()
            },
        },
        outcome,
    ))
}

/// Writes one validated discovery and its attributes (idempotent by id).
pub(crate) fn store(tx: &Transaction<'_>, json: &str) -> Result<(), CoreError> {
    let o: Value = serde_json::from_str(json).map_err(|e| invalid(e.to_string()))?;
    let id = s(&o, "id").unwrap_or_default();
    let exists: Option<i64> = tx
        .query_row(
            "SELECT id FROM gatt_discoveries WHERE uuid = ?1",
            [id],
            |r| r.get(0),
        )
        .optional()?;
    if exists.is_some() {
        return Ok(());
    }
    let null = Value::Null;
    let target = o.get("target").unwrap_or(&null);
    let key = s(target, "addr")
        .and_then(key_of)
        .ok_or_else(|| invalid("target.addr is not a MAC"))?;
    let session: Option<i64> = match s(&o, "session_id") {
        Some(uuid) => tx
            .query_row("SELECT id FROM sessions WHERE uuid = ?1", [uuid], |r| {
                r.get(0)
            })
            .optional()?,
        None => None,
    };
    let trigger = target.get("trigger_adv").unwrap_or(&null);
    let trigger_payload: Option<i64> = match s(trigger, "raw").and_then(hex_bytes) {
        Some(raw) => {
            let sha: [u8; 32] = Sha256::digest(&raw).into();
            tx.query_row(
                "SELECT id FROM payloads WHERE sha256 = ?1",
                [&sha[..]],
                |r| r.get(0),
            )
            .optional()?
        }
        None => None,
    };
    let (location, error, disconnect, phy, timings) = (
        o.get("location").unwrap_or(&null),
        o.get("error").unwrap_or(&null),
        o.get("disconnect").unwrap_or(&null),
        o.get("phy").unwrap_or(&null),
        o.get("timings_ms").unwrap_or(&null),
    );
    let f = |v: &Value, k: &str| v.get(k).and_then(Value::as_f64);
    tx.execute(
        "INSERT INTO gatt_discoveries (uuid, session_id, addr, addr_type, trigger_payload_id, trigger_rssi,
           started_ms, ended_ms, lat, lon, accuracy, outcome, stage_reached, error_stage, error_status, error_name,
           disconnect_initiator, disconnect_status, mtu, phy_tx, phy_rx, connect_ms, mtu_ms, discover_ms, read_ms,
           profile_hash, raw)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20, ?21,
           ?22, ?23, ?24, ?25, ?26, ?27)",
        params![
            id,
            session,
            key,
            s(target, "addr_type"),
            trigger_payload,
            i(trigger, "rssi"),
            s(&o, "started_at").and_then(parse_ms).unwrap_or(0),
            s(&o, "ended_at").and_then(parse_ms),
            f(location, "lat"),
            f(location, "lon"),
            f(location, "accuracy"),
            s(&o, "outcome").unwrap_or_default(),
            s(&o, "stage_reached").unwrap_or_default(),
            s(error, "stage"),
            i(error, "status"),
            s(error, "name"),
            s(disconnect, "initiator"),
            i(disconnect, "status"),
            i(&o, "mtu"),
            i(phy, "tx"),
            i(phy, "rx"),
            i(timings, "connect"),
            i(timings, "mtu"),
            i(timings, "discover"),
            i(timings, "read"),
            s(&o, "profile_hash"),
            json
        ],
    )?;
    let discovery = tx.last_insert_rowid();
    let mut next = 0i64;
    let mut insert = |parent: Option<i64>, kind: &str, a: &Value| -> Result<i64, CoreError> {
        next += 1;
        let value = a.get("value").unwrap_or(&Value::Null);
        tx.prepare_cached(
            "INSERT INTO gatt_attributes (discovery_id, attribute_id, parent_attribute_id, kind, uuid, is_primary,
               instance, properties, value, read_status) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
        )?
        .execute(params![
            discovery,
            next,
            parent,
            kind,
            s(a, "uuid").unwrap_or_default(),
            a.get("primary").and_then(Value::as_bool),
            i(a, "instance"),
            a.get("properties").and_then(Value::as_array).map(|p| {
                p.iter().filter_map(Value::as_str).collect::<Vec<_>>().join(",")
            }),
            s(value, "hex").and_then(hex_bytes),
            s(value, "status")
        ])?;
        Ok(next)
    };
    for sv in o
        .get("services")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let service = insert(None, "service", sv)?;
        for c in sv
            .get("characteristics")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
        {
            let characteristic = insert(Some(service), "characteristic", c)?;
            for d in c
                .get("descriptors")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
            {
                insert(Some(characteristic), "descriptor", d)?;
            }
        }
    }
    Ok(())
}

/// D-13.7: the device's last stored discovery that was not cancelled.
pub(crate) fn latest(
    conn: &Connection,
    catalog: &Catalog,
    key: i64,
) -> Result<Option<GattView>, CoreError> {
    let raw: Option<String> = conn
        .query_row(
            "SELECT raw FROM gatt_discoveries WHERE addr = ?1 AND outcome <> 'cancelled'
             ORDER BY started_ms DESC, id DESC LIMIT 1",
            [key],
            |r| r.get(0),
        )
        .optional()?;
    raw.map(|json| view(catalog, &json).map(|v| v.0))
        .transpose()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(outcome: &str, error: Value, disconnect: Value) -> Option<GattFailure> {
        failure(
            &serde_json::json!({"outcome": outcome, "error": error, "disconnect": disconnect}),
            outcome,
        )
    }

    /// #99 in the core: a stored run keeps its status; the named failures only when none is known.
    #[test]
    fn a_known_status_is_carried() {
        use serde_json::json;
        let other = |status| Some(GattFailure::Other { status });
        // a failed connect with Android's code, and a remote disconnect with its code
        assert_eq!(
            run(
                "failed",
                json!({"stage": "connect", "status": 62, "name": "x"}),
                Value::Null
            ),
            other(62)
        );
        assert_eq!(
            run(
                "failed",
                json!({"stage": "connect", "status": 133, "name": "GATT_ERROR"}),
                Value::Null
            ),
            other(133)
        );
        assert_eq!(
            run(
                "partial",
                Value::Null,
                json!({"initiator": "remote", "status": 19})
            ),
            other(19)
        );
        // the error's status wins over the disconnect's
        assert_eq!(
            run(
                "partial",
                json!({"stage": "read", "status": 8, "name": "x"}),
                json!({"initiator": "remote", "status": 19})
            ),
            other(8)
        );
        // an engine crash keeps its own code
        assert_eq!(
            run(
                "failed",
                json!({"stage": "read", "status": STATUS_INTERNAL_ERROR, "name": "INTERNAL_ERROR"}),
                Value::Null
            ),
            other(-6)
        );
        // no known status (absent or success): the named failures
        assert_eq!(
            run(
                "partial",
                Value::Null,
                json!({"initiator": "remote", "status": null})
            ),
            Some(GattFailure::DisconnectedUnexpectedly)
        );
        assert_eq!(
            run(
                "partial",
                Value::Null,
                json!({"initiator": "remote", "status": 0})
            ),
            Some(GattFailure::DisconnectedUnexpectedly)
        );
        assert_eq!(
            run(
                "failed",
                json!({"stage": "connect", "status": 0, "name": "x"}),
                Value::Null
            ),
            Some(GattFailure::ConnectionFailed)
        );
        assert_eq!(
            run("failed", Value::Null, Value::Null),
            other(STATUS_GATT_ERROR)
        );
        // unchanged: timeouts, cancelled, complete
        assert_eq!(
            run(
                "failed",
                json!({"stage": "connect", "status": -1, "name": "TIMEOUT"}),
                json!({"initiator": "timeout", "status": null})
            ),
            Some(GattFailure::TimedOut)
        );
        assert_eq!(
            run(
                "partial",
                Value::Null,
                json!({"initiator": "timeout", "status": 19})
            ),
            Some(GattFailure::TimedOut)
        );
        assert_eq!(
            run("cancelled", Value::Null, Value::Null),
            Some(GattFailure::Cancelled)
        );
        assert_eq!(run("complete", Value::Null, Value::Null), None);
    }

    #[test]
    fn parses_gatt_timestamps() {
        assert_eq!(parse_ms("1970-01-01T00:00:00.000Z"), Some(0));
        assert_eq!(
            parse_ms("2025-03-25T00:00:01.000Z"),
            Some(1_742_860_801_000)
        );
        assert_eq!(
            parse_ms("2024-02-29T12:34:56.789Z"),
            Some(1_709_210_096_789)
        );
        assert_eq!(parse_ms("2025-03-25 00:00:01"), None);
    }
}
