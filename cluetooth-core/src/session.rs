use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

use polars::prelude::{ParquetReader, SerReader};
use serde::{Deserialize, Serialize};

use crate::CoreError;
use crate::payload::{PayloadFile, SchemaV2Row};
use crate::payload_identity::parse_canonical_uuid_v7;

type ClusterSignature = (Vec<u8>, Vec<i32>);
type ClusterAggregate = (
    u64,
    HashSet<String>,
    HashSet<(String, Vec<u8>)>,
    Vec<SessionRoutePoint>,
);

pub(crate) const ACTIVE_ROUTE_POINT_LIMIT: usize = 2_048;
pub(crate) const COMPLETED_ROUTE_POINT_LIMIT: usize = 512;

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, uniffi::Enum)]
pub enum ScanSessionStatus {
    Active,
    Completed,
    Interrupted,
    Legacy,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum SessionUploadState {
    Pending,
    Uploaded,
    Failed,
    Empty,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, uniffi::Record)]
pub struct SessionRoutePoint {
    pub observed_at_ms: i64,
    pub lat: f64,
    pub lon: f64,
    pub accuracy_meters: f64,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct AdvertisementCluster {
    pub cluster_id: String,
    pub adv_types: Vec<u8>,
    pub field_lengths: Vec<i32>,
    pub observation_count: u64,
    pub unique_mac_count: u64,
    pub exact_payload_count: u64,
    pub observation_points: Vec<SessionRoutePoint>,
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ScanSession {
    pub session_id: String,
    pub status: ScanSessionStatus,
    pub started_at_ms: i64,
    pub ended_at_ms: Option<i64>,
    pub last_event_at_ms: i64,
    pub observation_count: u64,
    pub unique_mac_count: u64,
    pub exact_payload_count: u64,
    pub retained_local_bytes: u64,
    pub route_points: Vec<SessionRoutePoint>,
    pub distance_meters: f64,
    pub average_accuracy_meters: Option<f64>,
    pub upload_state: SessionUploadState,
    pub diagnostic: Option<String>,
    pub clusters: Vec<AdvertisementCluster>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub(crate) struct SessionMetadata {
    pub session_id: String,
    pub status: ScanSessionStatus,
    pub started_at_ms: i64,
    pub ended_at_ms: Option<i64>,
    pub last_event_at_ms: i64,
    pub route_points: Vec<SessionRoutePoint>,
}

impl SessionMetadata {
    pub(crate) fn new(session_id: String, started_at_ms: i64) -> Self {
        Self {
            session_id,
            status: ScanSessionStatus::Active,
            started_at_ms,
            ended_at_ms: None,
            last_event_at_ms: started_at_ms,
            route_points: Vec::new(),
        }
    }

    pub(crate) fn push_route_fix(&mut self, point: SessionRoutePoint) -> bool {
        if !point.lat.is_finite()
            || !point.lon.is_finite()
            || !point.accuracy_meters.is_finite()
            || !(-90.0..=90.0).contains(&point.lat)
            || !(-180.0..=180.0).contains(&point.lon)
            || !(0.0..=10_000.0).contains(&point.accuracy_meters)
        {
            return false;
        }
        if self.route_points.last().is_some_and(|last| {
            last.lat == point.lat
                && last.lon == point.lon
                && last.accuracy_meters == point.accuracy_meters
        }) {
            self.last_event_at_ms = self.last_event_at_ms.max(point.observed_at_ms);
            return false;
        }
        self.last_event_at_ms = self.last_event_at_ms.max(point.observed_at_ms);
        self.route_points.push(point);
        if self.route_points.len() > ACTIVE_ROUTE_POINT_LIMIT {
            self.route_points = simplify_points(&self.route_points, ACTIVE_ROUTE_POINT_LIMIT / 2);
        }
        true
    }

    pub(crate) fn finish(&mut self, ended_at_ms: i64, status: ScanSessionStatus) {
        self.status = status;
        self.ended_at_ms = Some(ended_at_ms.max(self.started_at_ms));
        self.last_event_at_ms = self.last_event_at_ms.max(ended_at_ms);
        self.route_points = simplify_points(&self.route_points, COMPLETED_ROUTE_POINT_LIMIT);
    }
}

pub(crate) fn session_state_path(root: &Path, session_id: &str) -> PathBuf {
    root.join("sessions").join(format!("{session_id}.json"))
}

pub(crate) fn write_session_metadata(
    root: &Path,
    metadata: &SessionMetadata,
) -> Result<(), CoreError> {
    parse_canonical_uuid_v7(&metadata.session_id)?;
    let path = session_state_path(root, &metadata.session_id);
    let parent = path.parent().expect("session state path has a parent");
    fs::create_dir_all(parent).map_err(CoreError::io)?;
    let contents = serde_json::to_vec(metadata).map_err(CoreError::io)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cluetooth-session-")
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
        file.write_all(&contents).map_err(CoreError::io)?;
        file.sync_all().map_err(CoreError::io)?;
    }
    temporary
        .persist(&path)
        .map_err(|error| CoreError::io(error.error))?;
    File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(CoreError::io)
}

pub(crate) fn discover_session_metadata(
    root: &Path,
) -> Result<BTreeMap<String, SessionMetadata>, CoreError> {
    let mut sessions = BTreeMap::new();
    let directory = root.join("sessions");
    if !directory.exists() {
        return Ok(sessions);
    }
    for entry in fs::read_dir(directory).map_err(CoreError::io)? {
        let entry = entry.map_err(CoreError::io)?;
        if !entry.file_type().map_err(CoreError::io)?.is_file()
            || entry.path().extension().and_then(|value| value.to_str()) != Some("json")
        {
            continue;
        }
        let metadata: SessionMetadata = match fs::read(entry.path())
            .map_err(CoreError::io)
            .and_then(|bytes| serde_json::from_slice(&bytes).map_err(CoreError::io))
        {
            Ok(value) => value,
            Err(_) => continue,
        };
        if parse_canonical_uuid_v7(&metadata.session_id).is_ok()
            && entry.path().file_stem().and_then(|value| value.to_str())
                == Some(metadata.session_id.as_str())
        {
            sessions.insert(metadata.session_id.clone(), metadata);
        }
    }
    Ok(sessions)
}

pub(crate) fn build_session_summaries(
    metadata: &BTreeMap<String, SessionMetadata>,
    payloads: &[PayloadFile],
    failed_ids: &HashSet<String>,
    active_session_id: Option<&str>,
    active_rows: &[SchemaV2Row],
) -> Result<Vec<ScanSession>, CoreError> {
    let mut grouped: BTreeMap<String, Vec<&PayloadFile>> = BTreeMap::new();
    for payload in payloads {
        let session_id = payload
            .session_id
            .clone()
            .unwrap_or_else(|| payload.payload_id.clone());
        grouped.entry(session_id).or_default().push(payload);
    }
    let mut ids: HashSet<String> = metadata.keys().cloned().collect();
    ids.extend(grouped.keys().cloned());
    let mut summaries = Vec::new();
    for session_id in ids {
        let chunks = grouped.remove(&session_id).unwrap_or_default();
        let inferred_created = chunks
            .iter()
            .map(|chunk| chunk.created_at_ms)
            .min()
            .unwrap_or(0);
        let inferred_last = chunks
            .iter()
            .map(|chunk| chunk.created_at_ms)
            .max()
            .unwrap_or(inferred_created);
        let fallback = SessionMetadata {
            session_id: session_id.clone(),
            status: ScanSessionStatus::Interrupted,
            started_at_ms: inferred_created,
            ended_at_ms: Some(inferred_last),
            last_event_at_ms: inferred_last,
            route_points: Vec::new(),
        };
        let state = metadata.get(&session_id).unwrap_or(&fallback);
        let session_active_rows = if active_session_id == Some(session_id.as_str()) {
            active_rows
        } else {
            &[]
        };
        let aggregate = aggregate_payloads(&chunks, session_active_rows)?;
        let has_failed = chunks
            .iter()
            .any(|chunk| failed_ids.contains(&chunk.payload_id));
        let archived = chunks.iter().filter(|chunk| chunk.archived).count();
        let upload_state = if chunks.is_empty() {
            SessionUploadState::Empty
        } else if has_failed {
            SessionUploadState::Failed
        } else if archived == chunks.len() {
            SessionUploadState::Uploaded
        } else {
            SessionUploadState::Pending
        };
        let average_accuracy_meters = (!state.route_points.is_empty()).then(|| {
            state
                .route_points
                .iter()
                .map(|point| point.accuracy_meters)
                .sum::<f64>()
                / state.route_points.len() as f64
        });
        summaries.push(ScanSession {
            session_id,
            status: state.status.clone(),
            started_at_ms: state.started_at_ms,
            ended_at_ms: state.ended_at_ms,
            last_event_at_ms: state
                .last_event_at_ms
                .max(aggregate.last_observation_at_ms.unwrap_or(0)),
            observation_count: aggregate.observation_count,
            unique_mac_count: aggregate.macs.len() as u64,
            exact_payload_count: aggregate.exact_payloads.len() as u64,
            retained_local_bytes: chunks.iter().map(|chunk| chunk.size_bytes).sum(),
            route_points: state.route_points.clone(),
            distance_meters: route_distance(&state.route_points),
            average_accuracy_meters,
            upload_state,
            diagnostic: has_failed.then(|| "Upload failed; retry is available".to_owned()),
            clusters: aggregate.clusters,
        });
    }
    summaries.sort_by_key(|session| (session.started_at_ms, session.session_id.clone()));
    Ok(summaries)
}

struct Aggregate {
    observation_count: u64,
    macs: HashSet<String>,
    exact_payloads: HashSet<(String, Vec<u8>)>,
    last_observation_at_ms: Option<i64>,
    clusters: Vec<AdvertisementCluster>,
}

fn aggregate_payloads(
    chunks: &[&PayloadFile],
    active_rows: &[SchemaV2Row],
) -> Result<Aggregate, CoreError> {
    let mut chunks = chunks.to_vec();
    chunks.sort_by_key(|chunk| (chunk.created_at_ms, chunk.payload_id.clone()));
    let mut observation_count = 0_u64;
    let mut macs = HashSet::new();
    let mut exact_payloads = HashSet::new();
    let mut last_observation_at_ms: Option<i64> = None;
    let mut cluster_data: HashMap<ClusterSignature, ClusterAggregate> = HashMap::new();
    for chunk in chunks {
        let frame = ParquetReader::new(File::open(&chunk.local_path).map_err(CoreError::io)?)
            .finish()
            .map_err(CoreError::parquet)?;
        let addresses = frame
            .column("addr")
            .map_err(CoreError::parquet)?
            .str()
            .map_err(CoreError::parquet)?;
        let raws = frame
            .column("raw")
            .map_err(CoreError::parquet)?
            .binary()
            .map_err(CoreError::parquet)?;
        let times = frame
            .column("scanned_at")
            .map_err(CoreError::parquet)?
            .datetime()
            .map_err(CoreError::parquet)?;
        let latitudes = frame
            .column("lat")
            .map_err(CoreError::parquet)?
            .f64()
            .map_err(CoreError::parquet)?;
        let longitudes = frame
            .column("lon")
            .map_err(CoreError::parquet)?
            .f64()
            .map_err(CoreError::parquet)?;
        let accuracies = frame
            .column("accuracy")
            .map_err(CoreError::parquet)?
            .f32()
            .map_err(CoreError::parquet)?;
        for index in 0..frame.height() {
            let addr = addresses
                .get(index)
                .expect("validated required addr")
                .to_owned();
            let raw = raws.get(index).expect("validated required raw").to_vec();
            observation_count += 1;
            macs.insert(addr.clone());
            exact_payloads.insert((addr.clone(), raw.clone()));
            if let Some(time) = times.phys.get(index) {
                last_observation_at_ms =
                    Some(last_observation_at_ms.map_or(time, |value| value.max(time)));
            }
            let signature = advertisement_signature(&raw);
            let cluster = cluster_data
                .entry(signature)
                .or_insert_with(|| (0, HashSet::new(), HashSet::new(), Vec::new()));
            cluster.0 += 1;
            cluster.1.insert(addr.clone());
            cluster.2.insert((addr, raw));
            if cluster.3.len() < COMPLETED_ROUTE_POINT_LIMIT
                && let (Some(lat), Some(lon)) = (latitudes.get(index), longitudes.get(index))
            {
                cluster.3.push(SessionRoutePoint {
                    observed_at_ms: times.phys.get(index).unwrap_or(0),
                    lat,
                    lon,
                    accuracy_meters: f64::from(accuracies.get(index).unwrap_or(0.0)),
                });
            }
        }
    }
    for row in active_rows {
        observation_count += 1;
        macs.insert(row.addr.clone());
        exact_payloads.insert((row.addr.clone(), row.raw.clone()));
        last_observation_at_ms = Some(
            last_observation_at_ms.map_or(row.scanned_at_ms, |value| value.max(row.scanned_at_ms)),
        );
        let cluster = cluster_data
            .entry(advertisement_signature(&row.raw))
            .or_insert_with(|| (0, HashSet::new(), HashSet::new(), Vec::new()));
        cluster.0 += 1;
        cluster.1.insert(row.addr.clone());
        cluster.2.insert((row.addr.clone(), row.raw.clone()));
        if cluster.3.len() < COMPLETED_ROUTE_POINT_LIMIT
            && let (Some(lat), Some(lon)) = (row.lat, row.lon)
        {
            cluster.3.push(SessionRoutePoint {
                observed_at_ms: row.scanned_at_ms,
                lat,
                lon,
                accuracy_meters: f64::from(row.accuracy.unwrap_or(0.0)),
            });
        }
    }
    let mut clusters: Vec<_> = cluster_data
        .into_iter()
        .map(|((types, lengths), data)| {
            let cluster_id = signature_id(&types, &lengths);
            AdvertisementCluster {
                cluster_id,
                adv_types: types,
                field_lengths: lengths,
                observation_count: data.0,
                unique_mac_count: data.1.len() as u64,
                exact_payload_count: data.2.len() as u64,
                observation_points: data.3,
            }
        })
        .collect();
    clusters.sort_by(|left, right| {
        right
            .observation_count
            .cmp(&left.observation_count)
            .then_with(|| left.cluster_id.cmp(&right.cluster_id))
    });
    Ok(Aggregate {
        observation_count,
        macs,
        exact_payloads,
        last_observation_at_ms,
        clusters,
    })
}

pub(crate) fn advertisement_signature(raw: &[u8]) -> (Vec<u8>, Vec<i32>) {
    let mut types = Vec::new();
    let mut lengths = Vec::new();
    let mut offset = 0_usize;
    while offset < raw.len() {
        let declared = raw[offset] as usize;
        if declared == 0 || offset.saturating_add(declared).saturating_add(1) > raw.len() {
            break;
        }
        let ad_type = raw[offset + 1];
        types.push(ad_type);
        lengths.push(if matches!(ad_type, 0x08 | 0x09) {
            -1
        } else {
            declared as i32
        });
        offset += declared + 1;
    }
    (types, lengths)
}

fn signature_id(types: &[u8], lengths: &[i32]) -> String {
    let type_text = types
        .iter()
        .map(|value| format!("{value:02x}"))
        .collect::<Vec<_>>()
        .join("-");
    let length_text = lengths
        .iter()
        .map(ToString::to_string)
        .collect::<Vec<_>>()
        .join("-");
    format!("{type_text}:{length_text}")
}

fn simplify_points(points: &[SessionRoutePoint], maximum: usize) -> Vec<SessionRoutePoint> {
    if points.len() <= maximum || maximum < 2 {
        return points.to_vec();
    }
    (0..maximum)
        .map(|index| {
            let source = index * (points.len() - 1) / (maximum - 1);
            points[source].clone()
        })
        .collect()
}

fn route_distance(points: &[SessionRoutePoint]) -> f64 {
    points
        .windows(2)
        .map(|pair| haversine_meters(&pair[0], &pair[1]))
        .sum()
}

fn haversine_meters(left: &SessionRoutePoint, right: &SessionRoutePoint) -> f64 {
    let lat1 = left.lat.to_radians();
    let lat2 = right.lat.to_radians();
    let delta_lat = (right.lat - left.lat).to_radians();
    let delta_lon = (right.lon - left.lon).to_radians();
    let a =
        (delta_lat / 2.0).sin().powi(2) + lat1.cos() * lat2.cos() * (delta_lon / 2.0).sin().powi(2);
    6_371_000.0 * 2.0 * a.sqrt().atan2((1.0 - a).sqrt())
}

#[cfg(test)]
mod tests {
    use super::{
        ACTIVE_ROUTE_POINT_LIMIT, COMPLETED_ROUTE_POINT_LIMIT, SessionMetadata, SessionRoutePoint,
        advertisement_signature,
    };
    use crate::session::ScanSessionStatus;

    #[test]
    fn signature_preserves_order_duplicates_and_normalizes_only_names() {
        let raw = [2, 1, 6, 4, 9, b'a', b'b', b'c', 2, 1, 4, 3, 8, b'x', b'y'];
        assert_eq!(
            advertisement_signature(&raw),
            (vec![1, 9, 1, 8], vec![2, -1, 2, -1])
        );
    }

    #[test]
    fn route_rejects_invalid_deduplicates_and_is_bounded() {
        let mut session =
            SessionMetadata::new("0195c920-7c00-7abc-8def-0123456789ab".to_owned(), 1);
        assert!(!session.push_route_fix(SessionRoutePoint {
            observed_at_ms: 2,
            lat: 100.0,
            lon: 0.0,
            accuracy_meters: 1.0
        }));
        for index in 0..(ACTIVE_ROUTE_POINT_LIMIT + 20) {
            session.push_route_fix(SessionRoutePoint {
                observed_at_ms: index as i64 + 2,
                lat: 32.0 + index as f64 / 100_000.0,
                lon: -117.0,
                accuracy_meters: 3.0,
            });
        }
        assert!(session.route_points.len() <= ACTIVE_ROUTE_POINT_LIMIT);
        let last = session.route_points.last().unwrap().clone();
        assert!(!session.push_route_fix(last));
        session.finish(9_999, ScanSessionStatus::Completed);
        assert!(session.route_points.len() <= COMPLETED_ROUTE_POINT_LIMIT);
    }
}
