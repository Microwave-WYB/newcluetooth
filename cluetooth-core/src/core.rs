use std::collections::{BTreeMap, HashSet};
use std::fs::{self, File, OpenOptions};
use std::io::{Seek, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};

use crate::config::{
    CoreConfig, INPUT_FIXED_ESTIMATE_BYTES, MAX_INPUT_BATCH_ESTIMATED_BYTES, MAX_INPUT_BATCH_ROWS,
    MAX_PAYLOAD_MAX_AGE_MS, MAX_PAYLOAD_MAX_ESTIMATED_BYTES, MAX_PAYLOAD_MAX_ROWS,
    MAX_RECENT_LOCATION_CAPACITY, ROW_FIXED_ESTIMATE_BYTES,
};
use crate::encryption::{
    SEALED_BOX_OVERHEAD_BYTES, encrypt_file_atomically, validate_recipient_public_key,
};
use crate::export::{
    DeleteSessionResult, ExportFormat, LegacySessionRows, PreparedExport,
    acknowledge_export as cleanup_export, write_full_export, write_legacy_session_export,
    write_session_export,
};
use crate::location::{LocationFix, RecentLocationFixes};
use crate::owned_path::{OwnedSyncStep, ensure_owned_directory, prove_owned_publication_durable};
use crate::payload::{
    PayloadFile, PayloadResult, PendingUpload, PreparedUpload, SchemaV2Row,
    inspect_schema_v2_parquet_file, inspect_schema_v2_parquet_impl, rows_to_frame,
    write_schema_v2_parquet_owned,
};
use crate::payload_identity::{
    PayloadIdentity, generate_uuid_v7, parse_canonical_uuid_v7, system_timestamp_ms,
    validate_archive_relative_path, validate_pending_relative_path,
};
use crate::scan::{ScanObservationInput, sanitize_local_name};
use crate::session::{
    ScanSession, ScanSessionStatus, SessionMetadata, SessionRoutePoint, build_session_summaries,
    discover_session_metadata, session_state_path, write_session_metadata,
};
use crate::state::{CoreEffect, CoreState, CoreUpdate};
use crate::{API_VERSION, CoreError};
use polars::prelude::{ParquetReader, SerReader};

trait PayloadRuntime: Send + Sync {
    fn now_ms(&self) -> Result<u64, CoreError>;
    fn random_bytes(&self) -> Result<[u8; 10], CoreError>;
    fn before_owned_sync(&self, _step: OwnedSyncStep) -> Result<(), CoreError> {
        Ok(())
    }
}

struct SystemPayloadRuntime;

impl PayloadRuntime for SystemPayloadRuntime {
    fn now_ms(&self) -> Result<u64, CoreError> {
        system_timestamp_ms()
    }

    fn random_bytes(&self) -> Result<[u8; 10], CoreError> {
        let mut random = [0_u8; 10];
        getrandom::fill(&mut random).map_err(CoreError::clock)?;
        Ok(random)
    }
}

#[derive(Debug, Default)]
struct ActivePayload {
    rows: Vec<SchemaV2Row>,
    estimated_bytes: u64,
    started_at_ms: Option<u64>,
    identity: Option<PayloadIdentity>,
    session_id: Option<String>,
    staged_row_count: usize,
    seal_failed: bool,
}

#[derive(Debug)]
struct CoreInner {
    locations: RecentLocationFixes,
    active: ActivePayload,
    pending: Vec<PayloadFile>,
    archived: Vec<PayloadFile>,
    sessions: BTreeMap<String, SessionMetadata>,
    active_session_id: Option<String>,
    state: CoreState,
}

#[derive(uniffi::Object)]
pub struct CluetoothCore {
    data_directory: PathBuf,
    config: CoreConfig,
    recipient_public_key: [u8; 32],
    runtime: Arc<dyn PayloadRuntime>,
    inner: Mutex<CoreInner>,
}

impl std::fmt::Debug for CluetoothCore {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("CluetoothCore")
            .field("data_directory", &self.data_directory)
            .field("config", &self.config)
            .finish_non_exhaustive()
    }
}

#[uniffi::export]
impl CluetoothCore {
    #[uniffi::constructor]
    pub fn open(data_directory: String, config: CoreConfig) -> Result<Arc<Self>, CoreError> {
        Self::open_with_runtime(
            PathBuf::from(data_directory),
            config,
            Arc::new(SystemPayloadRuntime),
        )
    }

    pub fn api_version(&self) -> u32 {
        API_VERSION
    }

    pub fn state(&self) -> CoreState {
        self.lock_inner().state.clone()
    }

    pub fn start_scan_session(&self) -> Result<String, CoreError> {
        let mut inner = self.lock_inner();
        if let Some(session_id) = &inner.active_session_id {
            return Ok(session_id.clone());
        }
        let now_ms = self.runtime.now_ms()?;
        let uuid = generate_uuid_v7(now_ms, self.runtime.random_bytes()?)?;
        let session_id = uuid.hyphenated().to_string();
        let metadata = SessionMetadata::new(session_id.clone(), now_ms as i64);
        write_session_metadata(&self.data_directory, &metadata)?;
        inner.sessions.insert(session_id.clone(), metadata);
        inner.active_session_id = Some(session_id.clone());
        Ok(session_id)
    }

    pub fn finish_scan_session(&self) -> Result<CoreUpdate, CoreError> {
        let mut inner = self.lock_inner();
        // Read the clock before sealing so a returned error still means that no
        // finish-time payload was committed.
        let ended_at_ms = if inner.active_session_id.is_some() {
            Some(self.runtime.now_ms()? as i64)
        } else {
            None
        };
        let sealed = self.seal_locked(&mut inner)?.is_some();
        let mut effects = Vec::new();
        if sealed {
            effects.push(CoreEffect::ScheduleUpload);
        }
        if let Some(session_id) = inner.active_session_id.clone() {
            if let Some(session) = inner.sessions.get_mut(&session_id) {
                session.finish(
                    ended_at_ms.expect("active sessions have a finish timestamp"),
                    ScanSessionStatus::Completed,
                );
                if let Err(error) = write_session_metadata(&self.data_directory, session) {
                    effects.push(CoreEffect::StorageWarning {
                        message: error.to_string(),
                    });
                }
            }
            inner.active_session_id = None;
        }
        if let Err(error) = refresh_upload_state(&self.data_directory, &mut inner) {
            effects.push(CoreEffect::StorageWarning {
                message: error.to_string(),
            });
        }
        Ok(CoreUpdate {
            state: inner.state.clone(),
            effects,
        })
    }

    pub fn scan_sessions(&self) -> Result<Vec<ScanSession>, CoreError> {
        let inner = self.lock_inner();
        let failed_ids = discover_failed_ids(&self.data_directory)?;
        let payloads: Vec<_> = inner
            .pending
            .iter()
            .chain(&inner.archived)
            .cloned()
            .collect();
        build_session_summaries(
            &inner.sessions,
            &payloads,
            &failed_ids,
            inner.active_session_id.as_deref(),
            &inner.active.rows,
        )
    }

    pub fn prepare_session_export(
        &self,
        session_id: String,
        format: ExportFormat,
    ) -> Result<PreparedExport, CoreError> {
        parse_canonical_uuid_v7(&session_id)?;
        let inner = self.lock_inner();
        let payloads: Vec<_> = inner
            .pending
            .iter()
            .chain(&inner.archived)
            .filter(|payload| {
                payload.session_id.as_deref().unwrap_or(&payload.payload_id) == session_id
            })
            .cloned()
            .collect();
        if payloads.is_empty() {
            return Err(CoreError::upload_state(
                "session has no retained observations to export",
            ));
        }
        let export_id = generate_uuid_v7(self.runtime.now_ms()?, self.runtime.random_bytes()?)?
            .hyphenated()
            .to_string();
        write_session_export(
            &self.data_directory,
            &export_id,
            &session_id,
            format,
            &payloads,
        )
    }

    pub fn prepare_legacy_session_export(
        &self,
        session: LegacySessionRows,
        format: ExportFormat,
    ) -> Result<PreparedExport, CoreError> {
        let export_id = generate_uuid_v7(self.runtime.now_ms()?, self.runtime.random_bytes()?)?
            .hyphenated()
            .to_string();
        write_legacy_session_export(&self.data_directory, &export_id, &session, format)
    }

    pub fn prepare_full_export(
        &self,
        format: ExportFormat,
        legacy_sessions: Vec<LegacySessionRows>,
    ) -> Result<PreparedExport, CoreError> {
        let inner = self.lock_inner();
        let failed_ids = discover_failed_ids(&self.data_directory)?;
        let payloads: Vec<_> = inner
            .pending
            .iter()
            .chain(&inner.archived)
            .cloned()
            .collect();
        let sessions = build_session_summaries(&inner.sessions, &payloads, &failed_ids, None, &[])?;
        if sessions
            .iter()
            .all(|session| session.retained_local_bytes == 0)
            && legacy_sessions.is_empty()
        {
            return Err(CoreError::upload_state(
                "no retained sessions are available to export",
            ));
        }
        let export_id = generate_uuid_v7(self.runtime.now_ms()?, self.runtime.random_bytes()?)?
            .hyphenated()
            .to_string();
        write_full_export(
            &self.data_directory,
            &export_id,
            format,
            &sessions,
            &payloads,
            &legacy_sessions,
        )
    }

    pub fn acknowledge_export(&self, export_id: String) -> Result<(), CoreError> {
        cleanup_export(&self.data_directory, &export_id)
    }

    pub fn delete_scan_session(
        &self,
        session_id: String,
        allow_unuploaded_data_loss: bool,
    ) -> Result<DeleteSessionResult, CoreError> {
        parse_canonical_uuid_v7(&session_id)?;
        let mut inner = self.lock_inner();
        if inner.active_session_id.as_deref() == Some(&session_id) {
            return Err(CoreError::upload_state(
                "active session must be stopped before deletion",
            ));
        }
        let payloads: Vec<_> = inner
            .pending
            .iter()
            .chain(&inner.archived)
            .filter(|payload| {
                payload.session_id.as_deref().unwrap_or(&payload.payload_id) == session_id
            })
            .cloned()
            .collect();
        let has_unuploaded = payloads.iter().any(|payload| !payload.archived);
        if has_unuploaded && !allow_unuploaded_data_loss {
            return Err(CoreError::upload_state(
                "session contains data that has not uploaded; explicit destructive confirmation is required",
            ));
        }
        if payloads.is_empty() {
            remove_file_if_exists(&session_state_path(&self.data_directory, &session_id))?;
            inner.sessions.remove(&session_id);
            return Ok(DeleteSessionResult::NothingLocalToDelete);
        }
        for payload in &payloads {
            remove_file_if_exists(Path::new(&payload.local_path))?;
            remove_file_if_exists(&self.ciphertext_path(payload))?;
            remove_file_if_exists(&failure_marker_path(
                &self.data_directory,
                &payload.payload_id,
            ))?;
        }
        remove_file_if_exists(&session_state_path(&self.data_directory, &session_id))?;
        inner.sessions.remove(&session_id);
        reconcile_pending_state(&self.data_directory, &mut inner)?;
        Ok(if has_unuploaded {
            DeleteSessionResult::DeletedUnuploadedData
        } else {
            DeleteSessionResult::DeletedUploadedLocalCopy
        })
    }

    pub fn refresh(&self) -> CoreUpdate {
        let mut inner = self.lock_inner();
        match reconcile_pending_state(&self.data_directory, &mut inner) {
            Ok(()) => CoreUpdate::state_only(inner.state.clone()),
            Err(error) => CoreUpdate {
                state: inner.state.clone(),
                effects: vec![CoreEffect::StorageWarning {
                    message: error.to_string(),
                }],
            },
        }
    }

    pub fn update_location(&self, fix: LocationFix) -> CoreUpdate {
        let mut inner = self.lock_inner();
        if !fix.is_usable() {
            return CoreUpdate {
                state: inner.state.clone(),
                effects: vec![CoreEffect::StorageWarning {
                    message: "Ignored invalid location fix".to_owned(),
                }],
            };
        }
        inner.locations.push(fix.clone());
        inner.state.recent_location_fix_count = inner.locations.len() as u64;
        inner.state.has_location = true;
        if let Some(session_id) = inner.active_session_id.clone()
            && let Some(session) = inner.sessions.get_mut(&session_id)
            && session.push_route_fix(SessionRoutePoint {
                observed_at_ms: fix.observed_at_ms,
                lat: fix.lat,
                lon: fix.lon,
                accuracy_meters: fix.accuracy_meters,
            })
            && let Err(error) = write_session_metadata(&self.data_directory, session)
        {
            return CoreUpdate {
                state: inner.state.clone(),
                effects: vec![CoreEffect::StorageWarning {
                    message: error.to_string(),
                }],
            };
        }
        CoreUpdate::state_only(inner.state.clone())
    }

    pub fn clear_location(&self) -> CoreUpdate {
        let mut inner = self.lock_inner();
        inner.locations.clear();
        inner.state.recent_location_fix_count = 0;
        inner.state.has_location = false;
        CoreUpdate::state_only(inner.state.clone())
    }

    pub fn record_observations(
        &self,
        observations: Vec<ScanObservationInput>,
    ) -> Result<CoreUpdate, CoreError> {
        if observations.is_empty() {
            return Ok(CoreUpdate::state_only(self.state()));
        }
        validate_input_batch(&observations)?;

        let now_ms = self.runtime.now_ms()?;
        let mut inner = self.lock_inner();
        let recovered_before_append = if inner.active.seal_failed
            || (!inner.active.rows.is_empty() && inner.active.session_id != inner.active_session_id)
        {
            match self.seal_locked(&mut inner) {
                Ok(sealed) => sealed.is_some(),
                Err(error) => {
                    inner.active.seal_failed = true;
                    return Err(error);
                }
            }
        } else {
            false
        };
        if inner.active.started_at_ms.is_none() {
            let active_session_id = inner.active_session_id.clone();
            inner.active.started_at_ms = Some(now_ms);
            inner.active.session_id = active_session_id;
        }

        // Persist the only fallible per-session metadata follow-up before the
        // observation batch is accepted into active rows. After the append below,
        // this method returns a committed update even when automatic publication
        // degrades, so callers never have an ambiguous retry boundary.
        let latest_batch_observation_at_ms = observations
            .last()
            .map_or(now_ms as i64, |observation| observation.scanned_at_ms);
        if let Some(session_id) = inner.active_session_id.clone()
            && let Some(session) = inner.sessions.get(&session_id)
        {
            let mut updated = session.clone();
            updated.last_event_at_ms = updated.last_event_at_ms.max(latest_batch_observation_at_ms);
            write_session_metadata(&self.data_directory, &updated)?;
            inner.sessions.insert(session_id, updated);
        }

        for mut input in observations {
            let location = inner
                .locations
                .most_recent_preceding(input.elapsed_realtime_nanos)
                .cloned();
            let local_name = sanitize_local_name(input.local_name.take());
            input.local_name.clone_from(&local_name);
            inner.state.total_observations += 1;
            if location.is_some() {
                inner.state.observations_with_location += 1;
            }
            inner.state.latest_observation_at_ms = Some(input.scanned_at_ms);
            if let Some(name) = &local_name {
                inner.state.latest_local_name = Some(name.clone());
            }

            let row = SchemaV2Row {
                addr: input.addr,
                rssi: input.rssi.map(i32::from),
                scanned_at_ms: input.scanned_at_ms,
                raw: input.raw,
                local_name,
                tx_power: input.tx_power.map(i32::from),
                is_connectable: input.is_connectable,
                lat: location.as_ref().map(|fix| fix.lat),
                lon: location.as_ref().map(|fix| fix.lon),
                accuracy: location.as_ref().map(|fix| fix.accuracy_meters as f32),
            };
            inner.active.estimated_bytes = inner
                .active
                .estimated_bytes
                .saturating_add(estimate_row_bytes(&row));
            inner.active.rows.push(row);
        }
        update_active_state(&mut inner);
        if self.should_rotate(&inner.active, now_ms) {
            return match self.seal_locked(&mut inner) {
                Ok(sealed) => {
                    let mut update = CoreUpdate::state_only(inner.state.clone());
                    if recovered_before_append || sealed.is_some() {
                        update.effects.push(CoreEffect::ScheduleUpload);
                    }
                    Ok(update)
                }
                Err(error) => {
                    inner.active.seal_failed = true;
                    let mut update = CoreUpdate::state_only(inner.state.clone());
                    update.effects.push(CoreEffect::PersistenceDegraded {
                        message: error.to_string(),
                    });
                    Ok(update)
                }
            };
        }
        let mut update = CoreUpdate::state_only(inner.state.clone());
        if recovered_before_append {
            update.effects.push(CoreEffect::ScheduleUpload);
        }
        Ok(update)
    }

    /// Durably checkpoints all unstaged active rows and seals only when a normal
    /// size, age, or emergency-row threshold is due.
    pub fn checkpoint_active_payload(&self) -> Result<PayloadResult, CoreError> {
        let now_ms = self.runtime.now_ms()?;
        let mut inner = self.lock_inner();
        if inner.active.seal_failed {
            return match self.seal_locked(&mut inner) {
                Ok(sealed) => Ok(PayloadResult { sealed }),
                Err(error) => {
                    inner.active.seal_failed = true;
                    Err(error)
                }
            };
        }
        self.stage_active_locked(&mut inner)?;
        if self.should_rotate(&inner.active, now_ms) {
            match self.seal_locked(&mut inner) {
                Ok(sealed) => Ok(PayloadResult { sealed }),
                Err(error) => {
                    inner.active.seal_failed = true;
                    Err(error)
                }
            }
        } else {
            Ok(PayloadResult { sealed: None })
        }
    }

    pub fn flush_payload(&self) -> Result<PayloadResult, CoreError> {
        let mut inner = self.lock_inner();
        match self.seal_locked(&mut inner) {
            Ok(sealed) => Ok(PayloadResult { sealed }),
            Err(error) => {
                inner.active.seal_failed = true;
                Err(error)
            }
        }
    }

    pub fn pending_uploads(&self) -> Vec<PendingUpload> {
        self.lock_inner()
            .pending
            .iter()
            .map(PendingUpload::from)
            .collect()
    }

    pub fn prepare_upload(&self, payload_id: String) -> Result<PreparedUpload, CoreError> {
        let mut inner = self.lock_inner();
        let payload = find_pending(&inner, &payload_id)?.clone();
        let plaintext_path = Path::new(&payload.local_path);
        let inspection = inspect_schema_v2_parquet_impl(plaintext_path)?;
        let current_size = fs::metadata(plaintext_path).map_err(CoreError::io)?.len();
        if inspection.payload_id != payload.payload_id
            || inspection.row_count != payload.row_count
            || current_size != payload.size_bytes
        {
            return Err(CoreError::upload_state(
                "pending payload changed after validation",
            ));
        }
        let ciphertext_path = self.ciphertext_path(&payload);
        // A cache entry cannot be authenticated without the recipient secret key.
        // Always regenerate from the validated pending plaintext and current key;
        // atomic replacement preserves the stable remote object path across retries.
        let ciphertext_size_bytes =
            encrypt_file_atomically(plaintext_path, &ciphertext_path, &self.recipient_public_key)?;
        refresh_upload_state(&self.data_directory, &mut inner)?;
        Ok(PreparedUpload {
            payload_id: payload.payload_id,
            ciphertext_path: ciphertext_path.to_string_lossy().into_owned(),
            object_path: payload.object_path,
            plaintext_size_bytes: payload.size_bytes,
            ciphertext_size_bytes,
        })
    }

    pub fn mark_upload_succeeded(&self, payload_id: String) -> Result<CoreUpdate, CoreError> {
        parse_canonical_uuid_v7(&payload_id)?;
        let mut inner = self.lock_inner();
        let marker = success_marker_path(&self.data_directory, &payload_id);
        if !marker.exists() {
            find_pending(&inner, &payload_id)?;
            write_atomic_state_file(&marker, b"succeeded\n")?;
        }
        if let Some(payload) = inner
            .pending
            .iter()
            .find(|payload| payload.payload_id == payload_id)
            .cloned()
        {
            archive_payload(&self.data_directory, &payload)?;
            remove_file_if_exists(&self.ciphertext_path(&payload))?;
        }
        remove_file_if_exists(&failure_marker_path(&self.data_directory, &payload_id))?;
        reconcile_pending_state(&self.data_directory, &mut inner)?;
        Ok(CoreUpdate::state_only(inner.state.clone()))
    }

    pub fn mark_upload_failed(
        &self,
        payload_id: String,
        message: String,
    ) -> Result<CoreUpdate, CoreError> {
        const MAX_DIAGNOSTIC_BYTES: usize = 512;
        let mut inner = self.lock_inner();
        let payload = find_pending(&inner, &payload_id)?.clone();
        remove_file_if_exists(&self.ciphertext_path(&payload))?;
        let bounded = truncate_utf8_bytes(&message, MAX_DIAGNOSTIC_BYTES).to_owned();
        write_atomic_state_file(
            &failure_marker_path(&self.data_directory, &payload_id),
            bounded.as_bytes(),
        )?;
        refresh_upload_state(&self.data_directory, &mut inner)?;
        inner.state.last_upload_error = Some(bounded);
        Ok(CoreUpdate::state_only(inner.state.clone()))
    }
}

impl CluetoothCore {
    fn open_with_runtime(
        data_directory: PathBuf,
        config: CoreConfig,
        runtime: Arc<dyn PayloadRuntime>,
    ) -> Result<Arc<Self>, CoreError> {
        validate_config(&config)?;
        let recipient_public_key = validate_recipient_public_key(&config.recipient_public_key)?;
        fs::create_dir_all(&data_directory).map_err(CoreError::io)?;
        cleanup_temporary_files(&data_directory)?;
        let recovered_stage = crate::staging::recover(&data_directory)?;
        let recovered_active = recovered_stage.is_some();
        let active =
            recovered_stage.map_or_else(ActivePayload::default, |recovered| ActivePayload {
                estimated_bytes: recovered.manifest.estimated_bytes,
                started_at_ms: Some(recovered.manifest.started_at_ms),
                identity: Some(recovered.manifest.identity),
                session_id: recovered.manifest.session_id,
                staged_row_count: recovered.rows.len(),
                rows: recovered.rows,
                seal_failed: false,
            });
        let (pending, invalid_pending_payload_count) = discover_pending(&data_directory)?;
        let (archived, _) = discover_archive(&data_directory)?;
        let mut sessions = discover_session_metadata(&data_directory)?;
        if sessions
            .values()
            .any(|session| session.status == ScanSessionStatus::Active)
        {
            let interrupted_at = runtime.now_ms()? as i64;
            for session in sessions
                .values_mut()
                .filter(|session| session.status == ScanSessionStatus::Active)
            {
                session.finish(interrupted_at, ScanSessionStatus::Interrupted);
                write_session_metadata(&data_directory, session)?;
            }
        }
        let mut state = CoreState::empty();
        state.pending_upload_count = pending.len() as u64;
        state.invalid_pending_payload_count = invalid_pending_payload_count;
        let mut inner = CoreInner {
            locations: RecentLocationFixes::new(
                config.recent_location_capacity as usize,
                config.location_max_age_ms,
            ),
            active,
            pending,
            archived,
            sessions,
            active_session_id: None,
            state,
        };
        update_active_state(&mut inner);
        reconcile_upload_state(&data_directory, &mut inner)?;
        let core = Arc::new(Self {
            data_directory,
            config: config.clone(),
            recipient_public_key,
            runtime,
            inner: Mutex::new(inner),
        });
        if recovered_active {
            let mut inner = core.lock_inner();
            core.seal_locked(&mut inner)?;
            reconcile_upload_state(&core.data_directory, &mut inner)?;
        }
        Ok(core)
    }

    fn should_rotate(&self, active: &ActivePayload, now_ms: u64) -> bool {
        active.rows.len() >= self.config.payload_max_rows as usize
            || active.estimated_bytes >= self.config.payload_max_estimated_bytes
            || active.started_at_ms.is_some_and(|started| {
                now_ms.saturating_sub(started) >= self.config.payload_max_age_ms
            })
    }

    fn stage_active_locked(&self, inner: &mut CoreInner) -> Result<(), CoreError> {
        if inner.active.rows.is_empty() {
            return Ok(());
        }
        if inner.active.identity.is_none() {
            let uuid = generate_uuid_v7(self.runtime.now_ms()?, self.runtime.random_bytes()?)?;
            inner.active.identity = Some(PayloadIdentity::from_uuid(uuid)?);
        }
        let started_at_ms = inner
            .active
            .started_at_ms
            .ok_or_else(|| CoreError::invalid("active payload has rows without a start time"))?;
        inner.active.staged_row_count = crate::staging::stage_rows(
            &self.data_directory,
            inner.active.identity.as_ref().expect("identity assigned"),
            inner.active.session_id.as_deref(),
            started_at_ms,
            inner.active.estimated_bytes,
            &inner.active.rows,
            inner.active.staged_row_count,
        )?;
        inner.active.seal_failed = false;
        Ok(())
    }

    fn seal_locked(&self, inner: &mut CoreInner) -> Result<Option<PayloadFile>, CoreError> {
        if inner.active.rows.is_empty() {
            return Ok(None);
        }
        // Stage first even when a final pathname exists. If any generated pending
        // component is hostile, rejection must retain the accepted WAL.
        self.stage_active_locked(inner)?;

        let identity = inner.active.identity.as_ref().expect("identity assigned");
        let relative_path = identity.pending_relative_path();
        let final_path = self.data_directory.join(&relative_path);
        let relative_parent = relative_path.parent().expect("payload path has parent");
        let target = relative_path
            .file_name()
            .expect("payload path has file name");
        let before_sync = |step| self.runtime.before_owned_sync(step);
        let owned_parent =
            ensure_owned_directory(&self.data_directory, relative_parent, &before_sync)?;
        if let Some(mut existing) = owned_parent.open_regular(target)? {
            if let Some(payload) = recover_exact_published_payload(
                &mut existing,
                &final_path,
                identity,
                &inner.active.rows,
            )? && payload.session_id == inner.active.session_id
            {
                prove_owned_publication_durable(
                    &self.data_directory,
                    relative_parent,
                    target,
                    &existing,
                    &before_sync,
                )?;
                crate::staging::cleanup(&self.data_directory)?;
                finish_seal(inner, payload.clone());
                return Ok(Some(payload));
            }
            return Err(CoreError::io(format!(
                "refusing to replace existing non-matching pending payload {}",
                final_path.display()
            )));
        }

        let inspection = write_schema_v2_parquet_owned(
            &owned_parent,
            target,
            &identity.payload_id,
            inner.active.session_id.as_deref(),
            inner.active.rows.clone(),
        )?;
        if inspection.payload_id != identity.payload_id {
            return Err(CoreError::invalid(
                "written payload footer identity changed",
            ));
        }
        let published = owned_parent
            .open_regular(target)?
            .ok_or_else(|| CoreError::invalid("published payload disappeared"))?;
        let size_bytes = published.metadata().map_err(CoreError::io)?.len();
        prove_owned_publication_durable(
            &self.data_directory,
            relative_parent,
            target,
            &published,
            &before_sync,
        )?;
        let payload = PayloadFile {
            payload_id: identity.payload_id.clone(),
            session_id: inspection.scan_session_id,
            local_path: final_path.to_string_lossy().into_owned(),
            object_path: identity.object_path(),
            created_at_ms: identity.created_at_ms(),
            row_count: inspection.row_count,
            size_bytes,
            archived: false,
        };
        crate::staging::cleanup(&self.data_directory)?;
        finish_seal(inner, payload.clone());
        Ok(Some(payload))
    }

    fn ciphertext_path(&self, payload: &PayloadFile) -> PathBuf {
        self.data_directory
            .join("upload-cache")
            .join(&payload.object_path)
    }

    fn lock_inner(&self) -> MutexGuard<'_, CoreInner> {
        self.inner
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }
}

fn recover_exact_published_payload(
    file: &mut File,
    path: &Path,
    identity: &PayloadIdentity,
    active_rows: &[SchemaV2Row],
) -> Result<Option<PayloadFile>, CoreError> {
    let inspection = match inspect_schema_v2_parquet_file(file) {
        Ok(inspection) => inspection,
        Err(_) => return Ok(None),
    };
    if inspection.payload_id != identity.payload_id
        || inspection.row_count != active_rows.len() as u64
    {
        return Ok(None);
    }
    file.rewind().map_err(CoreError::io)?;
    let published = ParquetReader::new(file.try_clone().map_err(CoreError::io)?)
        .finish()
        .map_err(CoreError::parquet)?;
    let expected = rows_to_frame(active_rows.to_vec())?;
    if !published.equals_missing(&expected) {
        return Ok(None);
    }
    Ok(Some(PayloadFile {
        payload_id: identity.payload_id.clone(),
        session_id: inspection.scan_session_id,
        local_path: path.to_string_lossy().into_owned(),
        object_path: identity.object_path(),
        created_at_ms: identity.created_at_ms(),
        row_count: inspection.row_count,
        size_bytes: file.metadata().map_err(CoreError::io)?.len(),
        archived: false,
    }))
}

fn finish_seal(inner: &mut CoreInner, payload: PayloadFile) {
    if !inner
        .pending
        .iter()
        .any(|pending| pending.payload_id == payload.payload_id)
    {
        inner.pending.push(payload);
        inner
            .pending
            .sort_by(|left, right| left.object_path.cmp(&right.object_path));
    }
    inner.active = ActivePayload::default();
    inner.state.pending_upload_count = inner.pending.len() as u64;
    update_active_state(inner);
}

fn validate_config(config: &CoreConfig) -> Result<(), CoreError> {
    if config.recent_location_capacity == 0
        || config.recent_location_capacity > MAX_RECENT_LOCATION_CAPACITY
    {
        return Err(CoreError::invalid_config(format!(
            "recent_location_capacity must be between 1 and {MAX_RECENT_LOCATION_CAPACITY}",
        )));
    }
    if config.payload_max_rows == 0 || config.payload_max_rows > MAX_PAYLOAD_MAX_ROWS {
        return Err(CoreError::invalid_config(format!(
            "payload_max_rows must be between 1 and {MAX_PAYLOAD_MAX_ROWS}",
        )));
    }
    if config.payload_max_estimated_bytes == 0
        || config.payload_max_estimated_bytes > MAX_PAYLOAD_MAX_ESTIMATED_BYTES
    {
        return Err(CoreError::invalid_config(format!(
            "payload_max_estimated_bytes must be between 1 and \
             {MAX_PAYLOAD_MAX_ESTIMATED_BYTES}",
        )));
    }
    if config.payload_max_age_ms == 0 || config.payload_max_age_ms > MAX_PAYLOAD_MAX_AGE_MS {
        return Err(CoreError::invalid_config(format!(
            "payload_max_age_ms must be between 1 and {MAX_PAYLOAD_MAX_AGE_MS}",
        )));
    }
    Ok(())
}

fn truncate_utf8_bytes(message: &str, maximum_bytes: usize) -> &str {
    if message.len() <= maximum_bytes {
        return message;
    }
    let mut end = maximum_bytes;
    while !message.is_char_boundary(end) {
        end -= 1;
    }
    &message[..end]
}

fn validate_input_batch(observations: &[ScanObservationInput]) -> Result<(), CoreError> {
    if let Some(invalid) = observations
        .iter()
        .find(|observation| !crate::payload::is_canonical_mac(&observation.addr))
    {
        return Err(CoreError::invalid(format!(
            "scan address {} must be canonical uppercase XX:XX:XX:XX:XX:XX",
            invalid.addr,
        )));
    }
    if observations.len() > MAX_INPUT_BATCH_ROWS {
        return Err(CoreError::input_batch_too_large(format!(
            "{} rows exceeds the documented {MAX_INPUT_BATCH_ROWS}-row maximum",
            observations.len()
        )));
    }
    let estimated_bytes = observations.iter().fold(0_u64, |total, observation| {
        total.saturating_add(estimate_input_bytes(observation))
    });
    if estimated_bytes > MAX_INPUT_BATCH_ESTIMATED_BYTES {
        return Err(CoreError::input_batch_too_large(format!(
            "estimated {estimated_bytes} bytes exceeds the documented \
             {MAX_INPUT_BATCH_ESTIMATED_BYTES}-byte maximum"
        )));
    }
    Ok(())
}

fn estimate_input_bytes(observation: &ScanObservationInput) -> u64 {
    INPUT_FIXED_ESTIMATE_BYTES
        .saturating_add(observation.addr.len() as u64)
        .saturating_add(observation.raw.len() as u64)
        .saturating_add(observation.local_name.as_ref().map_or(0, |name| name.len()) as u64)
}

pub(crate) fn estimate_row_bytes(row: &SchemaV2Row) -> u64 {
    ROW_FIXED_ESTIMATE_BYTES
        .saturating_add(row.addr.len() as u64)
        .saturating_add(row.raw.len() as u64)
        .saturating_add(row.local_name.as_ref().map_or(0, |name| name.len()) as u64)
}

fn update_active_state(inner: &mut CoreInner) {
    inner.state.active_payload_rows = inner.active.rows.len() as u64;
    inner.state.active_payload_estimated_bytes = inner.active.estimated_bytes;
}

fn find_pending<'a>(inner: &'a CoreInner, payload_id: &str) -> Result<&'a PayloadFile, CoreError> {
    inner
        .pending
        .iter()
        .find(|payload| payload.payload_id == payload_id)
        .ok_or_else(|| CoreError::upload_state(format!("unknown pending payload ID {payload_id}")))
}

fn success_marker_path(root: &Path, payload_id: &str) -> PathBuf {
    root.join("upload-state/succeeded").join(payload_id)
}

fn failure_marker_path(root: &Path, payload_id: &str) -> PathBuf {
    root.join("upload-state/failed")
        .join(format!("{payload_id}.txt"))
}

fn write_atomic_state_file(path: &Path, contents: &[u8]) -> Result<(), CoreError> {
    let parent = path
        .parent()
        .ok_or_else(|| CoreError::upload_state("state path has no parent"))?;
    fs::create_dir_all(parent).map_err(CoreError::io)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cluetooth-state-")
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
        file.write_all(contents).map_err(CoreError::io)?;
        file.sync_all().map_err(CoreError::io)?;
    }
    temporary
        .persist(path)
        .map_err(|error| CoreError::io(error.error))?;
    File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(CoreError::io)
}

fn remove_file_if_exists(path: &Path) -> Result<(), CoreError> {
    match fs::remove_file(path) {
        Ok(()) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(CoreError::io(error)),
    }
}

fn archive_payload(root: &Path, payload: &PayloadFile) -> Result<(), CoreError> {
    let identity = PayloadIdentity::parse(&payload.payload_id)?;
    let session_id = payload.session_id.as_deref().unwrap_or(&payload.payload_id);
    let destination = root.join(identity.archive_relative_path(session_id));
    let parent = destination
        .parent()
        .expect("archive payload path has a parent");
    fs::create_dir_all(parent).map_err(CoreError::io)?;
    if destination.exists() {
        let inspection = inspect_schema_v2_parquet_impl(&destination)?;
        if inspection.payload_id != payload.payload_id
            || inspection
                .scan_session_id
                .as_deref()
                .unwrap_or(&inspection.payload_id)
                != session_id
        {
            return Err(CoreError::upload_state(
                "existing archive payload does not match success identity",
            ));
        }
        remove_file_if_exists(Path::new(&payload.local_path))?;
    } else {
        fs::rename(&payload.local_path, &destination).map_err(CoreError::io)?;
        File::open(parent)
            .and_then(|directory| directory.sync_all())
            .map_err(CoreError::io)?;
    }
    remove_file_if_exists(&root.join("upload-cache").join(&payload.object_path))?;
    remove_file_if_exists(&failure_marker_path(root, &payload.payload_id))
}

fn reconcile_pending_state(root: &Path, inner: &mut CoreInner) -> Result<(), CoreError> {
    let (pending, invalid_pending) = discover_pending(root)?;
    let (archived, invalid_archive) = discover_archive(root)?;
    inner.pending = pending;
    inner.archived = archived;
    inner.sessions = discover_session_metadata(root)?;
    inner.state.invalid_pending_payload_count = invalid_pending + invalid_archive;
    reconcile_upload_state(root, inner)
}

fn reconcile_upload_state(root: &Path, inner: &mut CoreInner) -> Result<(), CoreError> {
    let succeeded_root = root.join("upload-state/succeeded");
    let mut succeeded_ids = Vec::new();
    visit_regular_files(&succeeded_root, &mut |path| {
        if let Some(payload_id) = path.file_name().and_then(|name| name.to_str())
            && crate::payload_identity::parse_canonical_uuid_v7(payload_id).is_ok()
        {
            succeeded_ids.push(payload_id.to_owned());
        }
        Ok(())
    })?;
    for payload_id in &succeeded_ids {
        if let Some(payload) = inner
            .pending
            .iter()
            .find(|payload| &payload.payload_id == payload_id)
            .cloned()
        {
            archive_payload(root, &payload)?;
        }
    }
    inner
        .pending
        .retain(|payload| !succeeded_ids.contains(&payload.payload_id));
    inner.archived = discover_archive(root)?.0;

    let expected_ciphertexts: std::collections::HashSet<PathBuf> = inner
        .pending
        .iter()
        .map(|payload| root.join("upload-cache").join(&payload.object_path))
        .collect();
    let upload_cache = root.join("upload-cache");
    visit_regular_files(&upload_cache, &mut |path| {
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("");
        let owned_temporary = name.starts_with(".cluetooth-upload-") && name.ends_with(".tmp");
        let orphan_ciphertext =
            name.ends_with(".parquet.encrypted") && !expected_ciphertexts.contains(path);
        if owned_temporary || orphan_ciphertext {
            remove_file_if_exists(path)?;
        }
        Ok(())
    })?;

    let pending_ids: std::collections::HashSet<&str> = inner
        .pending
        .iter()
        .map(|payload| payload.payload_id.as_str())
        .collect();
    visit_regular_files(&root.join("upload-state/failed"), &mut |path| {
        let payload_id = path
            .file_stem()
            .and_then(|name| name.to_str())
            .unwrap_or("");
        if !pending_ids.contains(payload_id) {
            remove_file_if_exists(path)?;
        }
        Ok(())
    })?;

    let mut prepared = 0_u64;
    let mut failed = 0_u64;
    let mut last_error = None;
    for payload in &inner.pending {
        let ciphertext = root.join("upload-cache").join(&payload.object_path);
        let expected_size = payload.size_bytes.saturating_add(SEALED_BOX_OVERHEAD_BYTES);
        if fs::metadata(&ciphertext)
            .ok()
            .map(|metadata| metadata.len())
            == Some(expected_size)
        {
            prepared += 1;
        } else if ciphertext.exists() {
            remove_file_if_exists(&ciphertext)?;
        }
        let failure = failure_marker_path(root, &payload.payload_id);
        if failure.exists() {
            failed += 1;
            last_error = fs::read_to_string(failure).ok();
        }
    }
    inner.state.pending_upload_count = inner.pending.len() as u64;
    inner.state.prepared_upload_count = prepared;
    inner.state.failed_upload_count = failed;
    inner.state.last_upload_error = last_error;
    Ok(())
}

fn discover_failed_ids(root: &Path) -> Result<HashSet<String>, CoreError> {
    let mut failed = HashSet::new();
    visit_regular_files(&root.join("upload-state/failed"), &mut |path| {
        if let Some(payload_id) = path.file_stem().and_then(|name| name.to_str())
            && parse_canonical_uuid_v7(payload_id).is_ok()
        {
            failed.insert(payload_id.to_owned());
        }
        Ok(())
    })?;
    Ok(failed)
}

fn refresh_upload_state(root: &Path, inner: &mut CoreInner) -> Result<(), CoreError> {
    reconcile_upload_state(root, inner)
}

fn cleanup_temporary_files(root: &Path) -> Result<(), CoreError> {
    let export_root = root.join("export-temp");
    if let Ok(metadata) = fs::symlink_metadata(&export_root) {
        if metadata.file_type().is_symlink() || metadata.is_file() {
            fs::remove_file(&export_root).map_err(CoreError::io)?;
        } else if metadata.is_dir() {
            fs::remove_dir_all(&export_root).map_err(CoreError::io)?;
        }
    }
    visit_regular_files(root, &mut |path| {
        let name = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("");
        if (name.starts_with(".cluetooth-payload-")
            || name.starts_with(".cluetooth-upload-")
            || name.starts_with(".cluetooth-state-")
            || name.starts_with(".cluetooth-session-")
            || name.starts_with(".cluetooth-export-")
            || name.starts_with(".cluetooth-active-"))
            && name.ends_with(".tmp")
        {
            fs::remove_file(path).map_err(CoreError::io)?;
        }
        Ok(())
    })
}

fn discover_pending(root: &Path) -> Result<(Vec<PayloadFile>, u64), CoreError> {
    let pending_root = root.join("pending");
    if !pending_root.exists() {
        return Ok((Vec::new(), 0));
    }
    let mut pending = Vec::new();
    let mut invalid = 0_u64;
    visit_regular_files(&pending_root, &mut |path| {
        if path.extension().and_then(|extension| extension.to_str()) != Some("parquet") {
            return Ok(());
        }
        let relative = match path.strip_prefix(root) {
            Ok(relative) => relative,
            Err(_) => {
                invalid += 1;
                return Ok(());
            }
        };
        let identity = match validate_pending_relative_path(relative) {
            Ok(identity) => identity,
            Err(_) => {
                invalid += 1;
                return Ok(());
            }
        };
        let inspection = match inspect_schema_v2_parquet_impl(path) {
            Ok(inspection) => inspection,
            Err(_) => {
                invalid += 1;
                return Ok(());
            }
        };
        if inspection.payload_id != identity.payload_id {
            invalid += 1;
            return Ok(());
        }
        pending.push(PayloadFile {
            payload_id: identity.payload_id.clone(),
            session_id: inspection.scan_session_id,
            local_path: path.to_string_lossy().into_owned(),
            object_path: identity.object_path(),
            created_at_ms: identity.created_at_ms(),
            row_count: inspection.row_count,
            size_bytes: fs::metadata(path).map_err(CoreError::io)?.len(),
            archived: false,
        });
        Ok(())
    })?;
    pending.sort_by(|left, right| left.object_path.cmp(&right.object_path));
    Ok((pending, invalid))
}

fn discover_archive(root: &Path) -> Result<(Vec<PayloadFile>, u64), CoreError> {
    let archive_root = root.join("archive");
    let mut archived = Vec::new();
    let mut invalid = 0_u64;
    visit_regular_files(&archive_root, &mut |path| {
        if path.extension().and_then(|extension| extension.to_str()) != Some("parquet") {
            return Ok(());
        }
        let relative = path.strip_prefix(root).map_err(CoreError::io)?;
        let (session_id, identity) = match validate_archive_relative_path(relative) {
            Ok(value) => value,
            Err(_) => {
                invalid += 1;
                return Ok(());
            }
        };
        let inspection = match inspect_schema_v2_parquet_impl(path) {
            Ok(value) => value,
            Err(_) => {
                invalid += 1;
                return Ok(());
            }
        };
        if inspection.payload_id != identity.payload_id
            || inspection
                .scan_session_id
                .as_deref()
                .unwrap_or(&inspection.payload_id)
                != session_id
        {
            invalid += 1;
            return Ok(());
        }
        archived.push(PayloadFile {
            payload_id: identity.payload_id.clone(),
            session_id: inspection.scan_session_id.or(Some(session_id)),
            local_path: path.to_string_lossy().into_owned(),
            object_path: identity.object_path(),
            created_at_ms: identity.created_at_ms(),
            row_count: inspection.row_count,
            size_bytes: fs::metadata(path).map_err(CoreError::io)?.len(),
            archived: true,
        });
        Ok(())
    })?;
    archived.sort_by_key(|payload| (payload.created_at_ms, payload.payload_id.clone()));
    Ok((archived, invalid))
}

fn visit_regular_files(
    root: &Path,
    visitor: &mut impl FnMut(&Path) -> Result<(), CoreError>,
) -> Result<(), CoreError> {
    if !root.exists() {
        return Ok(());
    }
    for entry in fs::read_dir(root).map_err(CoreError::io)? {
        let entry = entry.map_err(CoreError::io)?;
        let file_type = entry.file_type().map_err(CoreError::io)?;
        if file_type.is_dir() {
            visit_regular_files(&entry.path(), visitor)?;
        } else if file_type.is_file() {
            visitor(&entry.path())?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::fs::{self, File};
    use std::path::{Path, PathBuf};
    use std::sync::{Arc, Mutex};

    use crypto_box::SecretKey;
    use polars::prelude::*;
    use tempfile::TempDir;

    use super::{ActivePayload, CluetoothCore, PayloadRuntime};
    use crate::CoreError;
    use crate::config::CoreConfig;
    use crate::location::LocationFix;
    use crate::owned_path::OwnedSyncStep;
    use crate::scan::ScanObservationInput;

    struct DeterministicRuntime {
        times: Mutex<VecDeque<u64>>,
        random: [u8; 10],
    }

    impl PayloadRuntime for DeterministicRuntime {
        fn now_ms(&self) -> Result<u64, CoreError> {
            let mut times = self.times.lock().unwrap();
            Ok(if times.len() > 1 {
                times.pop_front().unwrap()
            } else {
                *times.front().unwrap()
            })
        }

        fn random_bytes(&self) -> Result<[u8; 10], CoreError> {
            Ok(self.random)
        }
    }

    struct FaultRuntime {
        times: Mutex<VecDeque<u64>>,
        random: [u8; 10],
        fault: Mutex<Option<OwnedSyncStep>>,
        steps: Mutex<Vec<OwnedSyncStep>>,
    }

    impl FaultRuntime {
        fn set_fault(&self, step: OwnedSyncStep) {
            *self.fault.lock().unwrap() = Some(step);
        }

        fn take_steps(&self) -> Vec<OwnedSyncStep> {
            std::mem::take(&mut *self.steps.lock().unwrap())
        }
    }

    impl PayloadRuntime for FaultRuntime {
        fn now_ms(&self) -> Result<u64, CoreError> {
            let mut times = self.times.lock().unwrap();
            Ok(if times.len() > 1 {
                times.pop_front().unwrap()
            } else {
                *times.front().unwrap()
            })
        }

        fn random_bytes(&self) -> Result<[u8; 10], CoreError> {
            Ok(self.random)
        }

        fn before_owned_sync(&self, step: OwnedSyncStep) -> Result<(), CoreError> {
            self.steps.lock().unwrap().push(step.clone());
            let mut fault = self.fault.lock().unwrap();
            if fault.as_ref() == Some(&step) {
                fault.take();
                return Err(CoreError::io(format!(
                    "injected owned sync failure at {step:?}"
                )));
            }
            Ok(())
        }
    }

    fn config(rows: u32) -> CoreConfig {
        CoreConfig {
            location_max_age_ms: 5_000,
            recent_location_capacity: 3,
            payload_max_rows: rows,
            payload_max_estimated_bytes: 10 * 1024 * 1024,
            payload_max_age_ms: 30_000,
            recipient_public_key: crate::config::CoreConfig::default().recipient_public_key,
        }
    }

    fn open(rows: u32, times: Vec<u64>) -> (TempDir, Arc<CluetoothCore>) {
        let directory = tempfile::tempdir().unwrap();
        let core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(rows),
            Arc::new(DeterministicRuntime {
                times: Mutex::new(times.into_iter().collect()),
                random: [0x55; 10],
            }),
        )
        .unwrap();
        (directory, core)
    }

    fn open_with_fault_runtime() -> (TempDir, Arc<CluetoothCore>, Arc<FaultRuntime>) {
        let directory = tempfile::tempdir().unwrap();
        let runtime = Arc::new(FaultRuntime {
            times: Mutex::new([1_742_860_800_000, 1_742_860_800_001].into_iter().collect()),
            random: [0x66; 10],
            fault: Mutex::new(None),
            steps: Mutex::new(Vec::new()),
        });
        let core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(100_000),
            runtime.clone(),
        )
        .unwrap();
        (directory, core, runtime)
    }

    fn publication_proof_steps(
        relative_parent: &Path,
        target: &std::ffi::OsStr,
    ) -> Vec<OwnedSyncStep> {
        let mut steps = vec![OwnedSyncStep::PublishedFile(relative_parent.join(target))];
        let mut directory = relative_parent.to_owned();
        loop {
            steps.push(OwnedSyncStep::PublishedDirectory(directory.clone()));
            if !directory.pop() {
                break;
            }
        }
        steps
    }

    fn observation(index: i64) -> ScanObservationInput {
        ScanObservationInput {
            addr: "AA:BB:CC:DD:EE:FF".to_owned(),
            rssi: Some(-50),
            scanned_at_ms: 1_741_478_400_000 + index,
            elapsed_realtime_nanos: index as u64 + 1_000,
            raw: vec![0, 0xff, index as u8],
            local_name: Some("na\0me".to_owned()),
            tx_power: Some(-8),
            is_connectable: Some(true),
        }
    }

    #[test]
    fn automatic_and_explicit_rotation_preserve_exact_rows_and_stable_identity() {
        let (_directory, core) = open(
            2,
            vec![1_742_860_799_999, 1_742_860_800_000, 1_742_860_800_001],
        );
        core.update_location(LocationFix {
            lat: 32.0,
            lon: -117.0,
            accuracy_meters: 4.5,
            observed_at_ms: 0,
            elapsed_realtime_nanos: 1,
        });
        let update = core
            .record_observations(vec![observation(1), observation(2)])
            .unwrap();
        assert_eq!(update.state.active_payload_rows, 0);
        assert_eq!(update.state.pending_upload_count, 1);
        assert_eq!(update.effects, vec![crate::CoreEffect::ScheduleUpload]);

        let pending = core.pending_uploads();
        assert_eq!(pending.len(), 1);
        assert_eq!(
            pending[0].payload_id,
            "0195ca99-5000-7555-9555-555555555555"
        );
        assert_eq!(
            pending[0].object_path,
            "scans/v2/2025/03/25/0195ca99-5000-7555-9555-555555555555.parquet.encrypted"
        );
        let frame = ParquetReader::new(fs::File::open(&pending[0].local_path).unwrap())
            .finish()
            .unwrap();
        assert_eq!(frame.height(), 2);
        assert_eq!(
            frame.column("raw").unwrap().binary().unwrap().get(1),
            Some(&[0, 0xff, 2][..])
        );
        assert_eq!(
            frame.column("local_name").unwrap().str().unwrap().get(0),
            Some("name")
        );
        assert_eq!(
            frame.column("lat").unwrap().f64().unwrap().get(0),
            Some(32.0)
        );

        assert!(core.flush_payload().unwrap().sealed.is_none());
        core.record_observations(vec![observation(3)]).unwrap();
        assert_eq!(core.state().active_payload_rows, 1);
        let second = core.flush_payload().unwrap().sealed.unwrap();
        assert_eq!(second.row_count, 1);
        assert_ne!(
            second.payload_id, pending[0].payload_id,
            "test runtime random/time must differ for multiple rotations"
        );
    }

    #[test]
    fn byte_and_time_thresholds_rotate_without_unbounded_retention() {
        let directory = tempfile::tempdir().unwrap();
        let mut byte_config = config(10_000);
        byte_config.payload_max_estimated_bytes = 1;
        let byte_core = CluetoothCore::open_with_runtime(
            directory.path().join("bytes"),
            byte_config,
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_000, 1_742_860_800_001].into()),
                random: [0x11; 10],
            }),
        )
        .unwrap();
        byte_core.record_observations(vec![observation(1)]).unwrap();
        assert_eq!(byte_core.state().active_payload_rows, 0);
        assert_eq!(byte_core.state().pending_upload_count, 1);

        let mut time_config = config(10_000);
        time_config.payload_max_age_ms = 1;
        let time_core = CluetoothCore::open_with_runtime(
            directory.path().join("time"),
            time_config,
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_000, 1_742_860_800_001, 1_742_860_800_002].into()),
                random: [0x22; 10],
            }),
        )
        .unwrap();
        time_core.record_observations(vec![observation(1)]).unwrap();
        assert_eq!(time_core.state().active_payload_rows, 1);
        time_core.record_observations(vec![observation(2)]).unwrap();
        assert_eq!(time_core.state().active_payload_rows, 0);
        assert_eq!(time_core.pending_uploads()[0].row_count, 2);

        let checkpoint_core = CluetoothCore::open_with_runtime(
            directory.path().join("checkpoint-time"),
            CoreConfig::default(),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_000, 1_742_861_100_000, 1_742_861_100_001].into()),
                random: [0x33; 10],
            }),
        )
        .unwrap();
        checkpoint_core
            .record_observations(vec![observation(3)])
            .unwrap();
        assert!(
            checkpoint_core
                .checkpoint_active_payload()
                .unwrap()
                .sealed
                .is_some()
        );
        assert_eq!(checkpoint_core.pending_uploads()[0].row_count, 1);
    }

    #[test]
    fn automatic_failure_retains_batch_and_backpressures_new_observations() {
        let directory = tempfile::tempdir().unwrap();
        let collision = directory
            .path()
            .join("pending/scans/v2/2025/03/25")
            .join("0195ca99-5000-7555-9555-555555555555.parquet");
        fs::create_dir_all(collision.parent().unwrap()).unwrap();
        fs::write(&collision, b"collision").unwrap();
        let core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(1),
            Arc::new(DeterministicRuntime {
                times: Mutex::new(
                    [
                        1_742_860_799_999,
                        1_742_860_800_000,
                        1_742_860_800_001,
                        1_742_860_800_002,
                    ]
                    .into(),
                ),
                random: [0x55; 10],
            }),
        )
        .unwrap();

        let update = core.record_observations(vec![observation(1)]).unwrap();
        assert_eq!(update.state.active_payload_rows, 1);
        assert!(matches!(
            update.effects.as_slice(),
            [crate::CoreEffect::PersistenceDegraded { .. }]
        ));
        assert!(core.record_observations(vec![observation(2)]).is_err());
        assert_eq!(core.state().total_observations, 1);
        assert_eq!(core.state().active_payload_rows, 1);

        fs::remove_file(collision).unwrap();
        core.record_observations(vec![observation(2)]).unwrap();
        assert_eq!(core.state().total_observations, 2);
        assert_eq!(core.state().active_payload_rows, 0);
        assert_eq!(core.state().pending_upload_count, 2);
    }

    #[test]
    fn failed_atomic_publication_retains_rows_and_reuses_rotation_identity() {
        let (directory, core) = open(10, vec![1_742_860_799_999, 1_742_860_800_000]);
        core.record_observations(vec![observation(1)]).unwrap();
        let expected_id = "0195ca99-5000-7555-9555-555555555555";
        let collision = directory
            .path()
            .join("pending/scans/v2/2025/03/25")
            .join(format!("{expected_id}.parquet"));
        fs::create_dir_all(collision.parent().unwrap()).unwrap();
        fs::write(&collision, b"existing").unwrap();

        assert!(core.flush_payload().is_err());
        assert_eq!(core.state().active_payload_rows, 1);
        assert!(core.pending_uploads().is_empty());
        fs::remove_file(&collision).unwrap();
        crate::write_schema_v2_parquet(
            collision.to_string_lossy().into_owned(),
            expected_id.to_owned(),
            vec![crate::SchemaV2Row {
                addr: "AA:BB:CC:DD:EE:FF".to_owned(),
                rssi: Some(-50),
                scanned_at_ms: 1_741_478_400_001,
                raw: vec![0, 0xff, 1],
                local_name: Some("name".to_owned()),
                tx_power: Some(-8),
                is_connectable: Some(true),
                lat: None,
                lon: None,
                accuracy: None,
            }],
        )
        .unwrap();

        let sealed = core.flush_payload().unwrap().sealed.unwrap();
        assert_eq!(sealed.payload_id, expected_id);
        assert_eq!(sealed.row_count, 1);
        assert_eq!(core.state().active_payload_rows, 0);
    }

    #[test]
    fn zero_rows_publish_nothing_and_restart_discovers_pending() {
        let (directory, core) = open(1, vec![1_742_860_799_999, 1_742_860_800_000]);
        assert!(core.flush_payload().unwrap().sealed.is_none());
        core.record_observations(vec![observation(1)]).unwrap();
        let expected = core.pending_uploads();
        drop(core);

        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(1),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_741_478_400_001].into()),
                random: [0xaa; 10],
            }),
        )
        .unwrap();
        assert_eq!(reopened.pending_uploads(), expected);
        assert_eq!(reopened.state().pending_upload_count, 1);
        assert_eq!(reopened.state().active_payload_rows, 0);
    }

    #[test]
    fn path_correct_corrupt_footer_mismatch_and_temporary_files_are_quarantined() {
        let (directory, core) = open(1, vec![1_742_860_799_999, 1_742_860_800_000]);
        core.record_observations(vec![observation(1)]).unwrap();
        let valid = core.pending_uploads()[0].local_path.clone();
        drop(core);

        let invalid_dir = directory.path().join("pending/scans/v2/2025/03/24");
        fs::create_dir_all(&invalid_dir).unwrap();
        let corrupt_id = "0195c920-7c00-7abc-8def-0123456789ab";
        fs::write(invalid_dir.join(format!("{corrupt_id}.parquet")), b"bad").unwrap();
        let path_id = "0195c920-7c00-7abc-8def-0123456789ac";
        let footer_id = "0195c920-7c00-7abc-8def-0123456789ad";
        crate::write_schema_v2_parquet(
            invalid_dir
                .join(format!("{path_id}.parquet"))
                .to_string_lossy()
                .into_owned(),
            footer_id.to_owned(),
            vec![crate::SchemaV2Row {
                addr: "AA:BB:CC:DD:EE:FF".to_owned(),
                rssi: None,
                scanned_at_ms: 1_741_435_200_000,
                raw: vec![1],
                local_name: None,
                tx_power: None,
                is_connectable: None,
                lat: None,
                lon: None,
                accuracy: None,
            }],
        )
        .unwrap();
        let temporary = invalid_dir.join(".cluetooth-payload-leftover.tmp");
        fs::write(&temporary, b"partial").unwrap();

        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(1),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_741_478_400_002].into()),
                random: [0xaa; 10],
            }),
        )
        .unwrap();
        assert_eq!(reopened.pending_uploads().len(), 1);
        assert_eq!(reopened.pending_uploads()[0].local_path, valid);
        assert_eq!(reopened.state().invalid_pending_payload_count, 2);
        assert!(invalid_dir.join(format!("{corrupt_id}.parquet")).exists());
        assert!(invalid_dir.join(format!("{path_id}.parquet")).exists());
        assert!(!temporary.exists());
    }

    #[test]
    fn oversized_ffi_batches_are_rejected_before_any_state_mutation() {
        let (_directory, core) = open(10_000, vec![1_742_860_800_000]);
        let row_error = core
            .record_observations((0..65).map(observation).collect())
            .unwrap_err();
        assert!(matches!(row_error, CoreError::InputBatchTooLarge { .. }));
        assert_eq!(core.state().total_observations, 0);
        assert_eq!(core.state().active_payload_rows, 0);

        let mut huge = observation(1);
        huge.raw = vec![0xaa; crate::config::MAX_INPUT_BATCH_ESTIMATED_BYTES as usize];
        let byte_error = core.record_observations(vec![huge]).unwrap_err();
        assert!(matches!(byte_error, CoreError::InputBatchTooLarge { .. }));
        assert_eq!(core.state().total_observations, 0);
        assert_eq!(core.state().active_payload_estimated_bytes, 0);
    }

    #[test]
    fn rotation_is_batch_granular_with_bounded_row_overshoot() {
        let (_directory, core) = open(10, vec![1_742_860_800_000, 1_742_860_800_001]);
        core.record_observations((0..9).map(observation).collect())
            .unwrap();
        core.record_observations((9..15).map(observation).collect())
            .unwrap();
        assert_eq!(core.pending_uploads()[0].row_count, 15);
        assert_eq!(core.state().active_payload_rows, 0);
    }

    #[test]
    fn upload_prepare_retry_failure_ack_and_reopen_are_durable_and_idempotent() {
        let directory = tempfile::tempdir().unwrap();
        let secret = SecretKey::from([7_u8; 32]);
        let mut upload_config = config(1);
        upload_config.recipient_public_key = secret.public_key().as_bytes().to_vec();
        let core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            upload_config.clone(),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_000, 1_742_860_800_001].into()),
                random: [0x44; 10],
            }),
        )
        .unwrap();
        core.record_observations(vec![observation(1)]).unwrap();
        let pending = core.pending_uploads()[0].clone();
        let plaintext = fs::read(&pending.local_path).unwrap();

        let prepared = core.prepare_upload(pending.payload_id.clone()).unwrap();
        assert_eq!(prepared.object_path, pending.object_path);
        assert_eq!(prepared.ciphertext_size_bytes, pending.size_bytes + 48);
        assert_eq!(
            secret
                .unseal(&fs::read(&prepared.ciphertext_path).unwrap())
                .unwrap(),
            plaintext
        );
        assert_eq!(core.state().prepared_upload_count, 1);

        core.mark_upload_failed(pending.payload_id.clone(), "🙂".repeat(200))
            .unwrap();
        assert!(Path::new(&pending.local_path).exists());
        assert!(!Path::new(&prepared.ciphertext_path).exists());
        assert_eq!(core.state().failed_upload_count, 1);
        let diagnostic = core.state().last_upload_error.unwrap();
        assert!(diagnostic.len() <= 512);
        assert_eq!(diagnostic.len(), 512);
        fs::create_dir_all(Path::new(&prepared.ciphertext_path).parent().unwrap()).unwrap();
        fs::write(
            &prepared.ciphertext_path,
            vec![0xa5; prepared.ciphertext_size_bytes as usize],
        )
        .unwrap();
        let retried = core.prepare_upload(pending.payload_id.clone()).unwrap();
        assert_eq!(
            secret
                .unseal(&fs::read(&retried.ciphertext_path).unwrap())
                .unwrap(),
            plaintext
        );
        assert_eq!(retried.object_path, prepared.object_path);
        drop(core);

        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            upload_config,
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_002].into()),
                random: [0x55; 10],
            }),
        )
        .unwrap();
        assert_eq!(reopened.pending_uploads().len(), 1);
        let after_remote_success_crash =
            reopened.prepare_upload(pending.payload_id.clone()).unwrap();
        assert_eq!(after_remote_success_crash.object_path, retried.object_path);
        reopened
            .mark_upload_succeeded(pending.payload_id.clone())
            .unwrap();
        assert!(!Path::new(&pending.local_path).exists());
        assert!(!Path::new(&retried.ciphertext_path).exists());
        assert!(reopened.pending_uploads().is_empty());
        reopened
            .mark_upload_succeeded(pending.payload_id.clone())
            .unwrap();
        assert!(
            reopened
                .mark_upload_succeeded("0195c920-7c00-7abc-8def-0123456789ab".to_owned())
                .is_err()
        );
    }

    #[test]
    fn prepare_regenerates_ciphertext_when_recipient_key_changes() {
        let directory = tempfile::tempdir().unwrap();
        let first_secret = SecretKey::from([7_u8; 32]);
        let second_secret = SecretKey::from([8_u8; 32]);
        let mut first_config = config(1);
        first_config.recipient_public_key = first_secret.public_key().as_bytes().to_vec();
        let core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            first_config,
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_000, 1_742_860_800_001].into()),
                random: [0x44; 10],
            }),
        )
        .unwrap();
        core.record_observations(vec![observation(1)]).unwrap();
        let pending = core.pending_uploads()[0].clone();
        let plaintext = fs::read(&pending.local_path).unwrap();
        let first = core.prepare_upload(pending.payload_id.clone()).unwrap();
        let first_ciphertext = fs::read(&first.ciphertext_path).unwrap();
        assert_eq!(first_secret.unseal(&first_ciphertext).unwrap(), plaintext);
        drop(core);

        let mut second_config = config(1);
        second_config.recipient_public_key = second_secret.public_key().as_bytes().to_vec();
        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            second_config,
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_002].into()),
                random: [0x55; 10],
            }),
        )
        .unwrap();
        let second = reopened.prepare_upload(pending.payload_id).unwrap();
        assert_eq!(second.object_path, first.object_path);
        let second_ciphertext = fs::read(second.ciphertext_path).unwrap();
        assert_eq!(second_secret.unseal(&second_ciphertext).unwrap(), plaintext);
        assert!(first_secret.unseal(&second_ciphertext).is_err());
    }

    #[test]
    fn open_rejects_known_low_order_recipient_keys() {
        let directory = tempfile::tempdir().unwrap();
        let mut one = vec![0_u8; 32];
        one[0] = 1;
        for (index, key) in [vec![0_u8; 32], one].into_iter().enumerate() {
            let mut invalid = config(1);
            invalid.recipient_public_key = key;
            assert!(
                CluetoothCore::open(
                    directory
                        .path()
                        .join(index.to_string())
                        .to_string_lossy()
                        .into(),
                    invalid,
                )
                .is_err()
            );
        }
        assert!(
            CluetoothCore::open(
                directory.path().join("valid").to_string_lossy().into(),
                config(1),
            )
            .is_ok()
        );
    }

    #[test]
    fn rotation_configuration_rejects_effectively_unbounded_retention() {
        let directory = tempfile::tempdir().unwrap();
        for invalid in [
            {
                let mut value = config(1);
                value.payload_max_rows = crate::config::MAX_PAYLOAD_MAX_ROWS + 1;
                value
            },
            {
                let mut value = config(1);
                value.payload_max_estimated_bytes =
                    crate::config::MAX_PAYLOAD_MAX_ESTIMATED_BYTES + 1;
                value
            },
            {
                let mut value = config(1);
                value.payload_max_age_ms = crate::config::MAX_PAYLOAD_MAX_AGE_MS + 1;
                value
            },
        ] {
            assert!(
                CluetoothCore::open(directory.path().to_string_lossy().into(), invalid).is_err()
            );
        }
    }

    #[test]
    fn reopen_removes_orphan_ciphertext() {
        let directory = tempfile::tempdir().unwrap();
        let orphan = directory.path().join(
            "upload-cache/scans/v2/2025/03/24/\
             0195c920-7c00-7abc-8def-0123456789ab.parquet.encrypted",
        );
        fs::create_dir_all(orphan.parent().unwrap()).unwrap();
        fs::write(&orphan, b"orphan").unwrap();
        let _core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(1),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_000].into()),
                random: [0x55; 10],
            }),
        )
        .unwrap();
        assert!(!orphan.exists());
    }

    #[test]
    fn successful_rotations_do_not_retain_an_unbounded_observation_shadow() {
        let times = (0..200).map(|offset| 1_742_860_800_000 + offset).collect();
        let (_directory, core) = open(10, times);
        for index in 0..100 {
            core.record_observations(vec![observation(index)]).unwrap();
        }
        assert_eq!(core.state().active_payload_rows, 0);
        assert_eq!(core.state().pending_upload_count, 10);
        assert_eq!(core.state().total_observations, 100);
    }

    #[test]
    fn user_session_is_idempotent_spans_rotations_and_finishes_with_summary() {
        let (_directory, core) = open(
            1,
            (0..20).map(|offset| 1_742_860_800_000 + offset).collect(),
        );
        let session_id = core.start_scan_session().unwrap();
        assert_eq!(core.start_scan_session().unwrap(), session_id);
        core.update_location(LocationFix {
            lat: 32.0,
            lon: -117.0,
            accuracy_meters: 3.0,
            observed_at_ms: 1_742_860_800_010,
            elapsed_realtime_nanos: 10,
        });
        core.update_location(LocationFix {
            lat: 32.0001,
            lon: -117.0001,
            accuracy_meters: 4.0,
            observed_at_ms: 1_742_860_800_020,
            elapsed_realtime_nanos: 20,
        });
        core.record_observations(vec![observation(1)]).unwrap();
        core.record_observations(vec![observation(2)]).unwrap();
        assert!(core.pending_uploads().iter().all(|payload| {
            crate::inspect_schema_v2_parquet(payload.local_path.clone())
                .unwrap()
                .scan_session_id
                .as_deref()
                == Some(session_id.as_str())
        }));
        core.finish_scan_session().unwrap();
        let summary = core.scan_sessions().unwrap().remove(0);
        assert_eq!(summary.session_id, session_id);
        assert_eq!(summary.status, crate::ScanSessionStatus::Completed);
        assert_eq!(summary.observation_count, 2);
        assert_eq!(summary.unique_mac_count, 1);
        assert_eq!(summary.exact_payload_count, 2);
        assert_eq!(summary.route_points.len(), 2);
        assert!(summary.distance_meters > 0.0);
        assert_eq!(summary.clusters.len(), 1);
    }

    #[test]
    fn finish_session_schedules_upload_only_when_it_seals_rows() {
        let (_directory, core) = open(
            10,
            (0..10).map(|offset| 1_742_860_800_000 + offset).collect(),
        );
        core.start_scan_session().unwrap();
        core.record_observations(vec![observation(1)]).unwrap();

        let update = core.finish_scan_session().unwrap();

        assert_eq!(update.effects, vec![crate::CoreEffect::ScheduleUpload]);
        assert_eq!(update.state.pending_upload_count, 1);
    }

    #[test]
    fn finish_empty_session_does_not_schedule_upload() {
        let (_directory, core) = open(10, vec![1_742_860_800_000, 1_742_860_800_001]);
        core.start_scan_session().unwrap();

        let update = core.finish_scan_session().unwrap();

        assert!(update.effects.is_empty());
        assert_eq!(update.state.pending_upload_count, 0);
    }

    #[test]
    fn reopen_marks_open_session_interrupted() {
        let (directory, core) = open(10, vec![1_742_860_800_000]);
        let session_id = core.start_scan_session().unwrap();
        drop(core);
        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(10),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_900_000].into()),
                random: [0x66; 10],
            }),
        )
        .unwrap();
        let sessions = reopened.scan_sessions().unwrap();
        assert_eq!(sessions[0].session_id, session_id);
        assert_eq!(sessions[0].status, crate::ScanSessionStatus::Interrupted);
        assert_eq!(sessions[0].ended_at_ms, Some(1_742_860_900_000));
    }

    #[test]
    fn success_marker_reconciles_to_archive_and_uploaded_delete_keeps_cloud_tombstone() {
        let (directory, core) = open(
            1,
            (0..10).map(|offset| 1_742_860_800_000 + offset).collect(),
        );
        let session_id = core.start_scan_session().unwrap();
        core.record_observations(vec![observation(1)]).unwrap();
        core.finish_scan_session().unwrap();
        let payload = core.pending_uploads()[0].clone();
        super::write_atomic_state_file(
            &super::success_marker_path(directory.path(), &payload.payload_id),
            b"succeeded\n",
        )
        .unwrap();
        drop(core);
        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(1),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_900_000].into()),
                random: [0x77; 10],
            }),
        )
        .unwrap();
        assert!(reopened.pending_uploads().is_empty());
        let archived = reopened.scan_sessions().unwrap();
        assert_eq!(
            archived[0].upload_state,
            crate::SessionUploadState::Uploaded
        );
        assert_eq!(archived[0].observation_count, 1);
        reopened
            .mark_upload_succeeded(payload.payload_id.clone())
            .unwrap();
        assert_eq!(
            reopened.delete_scan_session(session_id, false).unwrap(),
            crate::DeleteSessionResult::DeletedUploadedLocalCopy,
        );
        assert!(super::success_marker_path(directory.path(), &payload.payload_id).exists());
        assert!(reopened.scan_sessions().unwrap().is_empty());
    }

    #[test]
    fn pending_delete_requires_destructive_confirmation_and_exports_exact_raw() {
        let (_directory, core) = open(
            1,
            (0..30).map(|offset| 1_742_860_800_000 + offset).collect(),
        );
        let session_id = core.start_scan_session().unwrap();
        core.record_observations(vec![observation(1)]).unwrap();
        core.finish_scan_session().unwrap();
        assert!(core.delete_scan_session(session_id.clone(), false).is_err());

        let jsonl = core
            .prepare_session_export(session_id.clone(), crate::ExportFormat::Jsonl)
            .unwrap();
        let text = fs::read_to_string(&jsonl.local_path).unwrap();
        assert!(text.contains("\"raw\":\"00ff01\""));
        assert!(text.ends_with('\n'));
        core.acknowledge_export(jsonl.export_id).unwrap();
        assert!(!Path::new(&jsonl.local_path).exists());

        let parquet = core
            .prepare_session_export(session_id.clone(), crate::ExportFormat::Parquet)
            .unwrap();
        let frame = ParquetReader::new(fs::File::open(&parquet.local_path).unwrap())
            .finish()
            .unwrap();
        assert_eq!(frame.height(), 1);
        assert_eq!(
            frame.column("raw").unwrap().binary().unwrap().get(0),
            Some(&[0, 0xff, 1][..])
        );
        core.acknowledge_export(parquet.export_id).unwrap();

        let legacy = crate::LegacySessionRows {
            session_id: "legacy-test".to_owned(),
            started_at_ms: 1_741_478_400_001,
            ended_at_ms: Some(1_741_478_400_001),
            rows: vec![crate::SchemaV2Row {
                addr: "AA:BB:CC:DD:EE:FF".to_owned(),
                rssi: Some(-50),
                scanned_at_ms: 1_741_478_400_001,
                raw: vec![0, 0xff, 1],
                local_name: None,
                tx_power: None,
                is_connectable: None,
                lat: None,
                lon: None,
                accuracy: None,
            }],
        };
        let legacy_parquet = core
            .prepare_legacy_session_export(legacy.clone(), crate::ExportFormat::Parquet)
            .unwrap();
        let legacy_frame = ParquetReader::new(fs::File::open(&legacy_parquet.local_path).unwrap())
            .finish()
            .unwrap();
        assert_eq!(legacy_frame.height(), 1);
        core.acknowledge_export(legacy_parquet.export_id).unwrap();

        let full = core
            .prepare_full_export(crate::ExportFormat::Jsonl, vec![legacy.clone()])
            .unwrap();
        let bytes = fs::read(&full.local_path).unwrap();
        assert!(
            bytes
                .windows(b"manifest.json".len())
                .any(|window| window == b"manifest.json")
        );
        assert!(
            bytes
                .windows(session_id.len())
                .any(|window| window == session_id.as_bytes())
        );
        core.acknowledge_export(full.export_id).unwrap();
        let full_parquet = core
            .prepare_full_export(crate::ExportFormat::Parquet, vec![legacy])
            .unwrap();
        let full_parquet_bytes = fs::read(&full_parquet.local_path).unwrap();
        assert!(
            full_parquet_bytes
                .windows(b".parquet".len())
                .any(|window| window == b".parquet")
        );
        core.acknowledge_export(full_parquet.export_id).unwrap();

        assert_eq!(
            core.delete_scan_session(session_id, true).unwrap(),
            crate::DeleteSessionResult::DeletedUnuploadedData,
        );
    }

    #[test]
    fn reopen_cleans_orphan_export_temporary_tree() {
        let directory = tempfile::tempdir().unwrap();
        let orphan = directory.path().join("export-temp/orphan/file.zip");
        fs::create_dir_all(orphan.parent().unwrap()).unwrap();
        fs::write(&orphan, b"partial").unwrap();
        let _core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(1),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_000].into()),
                random: [0x55; 10],
            }),
        )
        .unwrap();
        assert!(!directory.path().join("export-temp").exists());
    }

    #[test]
    fn repeated_checkpoints_are_idempotent_and_restart_finalizes_original_session() {
        let directory = tempfile::tempdir().unwrap();
        let runtime = Arc::new(DeterministicRuntime {
            times: Mutex::new(
                [
                    1_742_860_800_000,
                    1_742_860_800_001,
                    1_742_860_800_002,
                    1_742_860_800_003,
                    1_742_860_800_004,
                ]
                .into(),
            ),
            random: [0x66; 10],
        });
        let core =
            CluetoothCore::open_with_runtime(directory.path().to_owned(), config(100_000), runtime)
                .unwrap();
        let session_id = core.start_scan_session().unwrap();
        core.record_observations(vec![observation(1), observation(2)])
            .unwrap();

        assert!(core.checkpoint_active_payload().unwrap().sealed.is_none());
        let staged = crate::staging::recover(directory.path()).unwrap().unwrap();
        let payload_id = staged.manifest.identity.payload_id.clone();
        let wal_length = fs::metadata(directory.path().join("active-payload/rows.wal"))
            .unwrap()
            .len();
        assert_eq!(
            staged.manifest.session_id.as_deref(),
            Some(session_id.as_str())
        );
        assert!(core.checkpoint_active_payload().unwrap().sealed.is_none());
        assert_eq!(
            fs::metadata(directory.path().join("active-payload/rows.wal"))
                .unwrap()
                .len(),
            wal_length
        );
        assert!(core.pending_uploads().is_empty());
        drop(core);

        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(100_000),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_900_000].into()),
                random: [0x77; 10],
            }),
        )
        .unwrap();
        let pending = reopened.pending_uploads();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].payload_id, payload_id);
        assert_eq!(pending[0].row_count, 2);
        assert!(!directory.path().join("active-payload").exists());
        let inspection = crate::inspect_schema_v2_parquet(pending[0].local_path.clone()).unwrap();
        assert_eq!(
            inspection.scan_session_id.as_deref(),
            Some(session_id.as_str())
        );
        let session = reopened
            .scan_sessions()
            .unwrap()
            .into_iter()
            .find(|session| session.session_id == session_id)
            .unwrap();
        assert_eq!(session.status, crate::ScanSessionStatus::Interrupted);
    }

    #[test]
    fn zero_row_recovery_never_contaminates_a_new_session_or_payload_identity() {
        for partial_wal in [Some(Vec::new()), Some(b"CTW2\x02".to_vec())] {
            let directory = tempfile::tempdir().unwrap();
            let old_identity = crate::payload_identity::PayloadIdentity::from_uuid(
                crate::payload_identity::generate_uuid_v7(1_742_860_800_000, [0x11; 10]).unwrap(),
            )
            .unwrap();
            let old_session =
                crate::payload_identity::generate_uuid_v7(1_742_860_800_001, [0x22; 10])
                    .unwrap()
                    .hyphenated()
                    .to_string();
            crate::staging::stage_rows(
                directory.path(),
                &old_identity,
                Some(&old_session),
                1_742_860_800_002,
                0,
                &[],
                0,
            )
            .unwrap();
            if let Some(bytes) = partial_wal {
                fs::write(directory.path().join("active-payload/rows.wal"), bytes).unwrap();
            }

            let reopened = CluetoothCore::open_with_runtime(
                directory.path().to_owned(),
                config(100_000),
                Arc::new(DeterministicRuntime {
                    times: Mutex::new(
                        [
                            1_742_860_900_000,
                            1_742_860_900_001,
                            1_742_860_900_002,
                            1_742_860_900_003,
                        ]
                        .into(),
                    ),
                    random: [0x77; 10],
                }),
            )
            .unwrap();
            assert!(!directory.path().join("active-payload").exists());
            let new_session = reopened.start_scan_session().unwrap();
            assert_ne!(new_session, old_session);
            reopened.record_observations(vec![observation(9)]).unwrap();
            let sealed = reopened.flush_payload().unwrap().sealed.unwrap();
            assert_ne!(sealed.payload_id, old_identity.payload_id);
            assert_eq!(sealed.session_id.as_deref(), Some(new_session.as_str()));
        }
    }

    #[cfg(unix)]
    #[test]
    fn pending_symlinks_at_root_intermediate_and_final_never_escape_or_clean_wal() {
        use std::os::unix::fs::symlink;

        for attack in ["pending", "scans", "final"] {
            let directory = tempfile::tempdir().unwrap();
            let outside = tempfile::tempdir().unwrap();
            let core = CluetoothCore::open_with_runtime(
                directory.path().to_owned(),
                config(100_000),
                Arc::new(DeterministicRuntime {
                    times: Mutex::new(
                        [1_742_860_800_000, 1_742_860_800_001, 1_742_860_800_002].into(),
                    ),
                    random: [0x55; 10],
                }),
            )
            .unwrap();
            core.record_observations(vec![observation(1)]).unwrap();
            core.checkpoint_active_payload().unwrap();
            let staged = crate::staging::recover(directory.path()).unwrap().unwrap();
            let relative = staged.manifest.identity.pending_relative_path();
            let final_path = directory.path().join(&relative);
            match attack {
                "pending" => symlink(outside.path(), directory.path().join("pending")).unwrap(),
                "scans" => {
                    fs::create_dir(directory.path().join("pending")).unwrap();
                    symlink(outside.path(), directory.path().join("pending/scans")).unwrap();
                }
                "final" => {
                    fs::create_dir_all(final_path.parent().unwrap()).unwrap();
                    symlink(outside.path().join("escaped.parquet"), &final_path).unwrap();
                }
                _ => unreachable!(),
            }

            assert!(core.flush_payload().is_err(), "{attack}");
            assert!(directory.path().join("active-payload/rows.wal").exists());
            assert!(fs::read_dir(outside.path()).unwrap().next().is_none());
        }
    }

    #[test]
    fn exact_default_rotation_boundaries_are_inclusive() {
        let directory = tempfile::tempdir().unwrap();
        let core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            CoreConfig::default(),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_800_000].into()),
                random: [0x55; 10],
            }),
        )
        .unwrap();
        let mut active = ActivePayload {
            rows: vec![observation_row(); 99_999],
            estimated_bytes: 10 * 1024 * 1024 - 1,
            started_at_ms: Some(1_000),
            ..ActivePayload::default()
        };
        assert!(!core.should_rotate(&active, 300_999));
        assert!(core.should_rotate(&active, 301_000));
        active.started_at_ms = Some(1_000_000);
        active.estimated_bytes = 10 * 1024 * 1024;
        assert!(core.should_rotate(&active, 1_000_000));
        active.estimated_bytes -= 1;
        active.rows.push(observation_row());
        assert_eq!(active.rows.len(), 100_000);
        assert!(core.should_rotate(&active, 1_000_000));
    }

    #[test]
    fn active_staging_paths_never_enter_upload_export_or_session_models() {
        let (directory, core) = open(
            100_000,
            (0..20).map(|offset| 1_742_860_800_000 + offset).collect(),
        );
        let session_id = core.start_scan_session().unwrap();
        core.record_observations(vec![observation(1)]).unwrap();
        core.flush_payload().unwrap();
        core.record_observations(vec![observation(2)]).unwrap();
        core.checkpoint_active_payload().unwrap();
        assert!(directory.path().join("active-payload/rows.wal").exists());
        assert!(core.pending_uploads().iter().all(|payload| {
            !payload.local_path.contains("active-payload")
                && !payload.object_path.contains("active-payload")
        }));

        let session_export = core
            .prepare_session_export(session_id, crate::ExportFormat::Jsonl)
            .unwrap();
        let session_bytes = fs::read(&session_export.local_path).unwrap();
        assert!(
            !session_bytes
                .windows(14)
                .any(|window| window == b"active-payload")
        );
        assert!(!session_bytes.windows(8).any(|window| window == b"rows.wal"));
        let full = core
            .prepare_full_export(crate::ExportFormat::Jsonl, vec![])
            .unwrap();
        let full_bytes = fs::read(&full.local_path).unwrap();
        assert!(
            !full_bytes
                .windows(14)
                .any(|window| window == b"active-payload")
        );
        assert!(
            !full_bytes
                .windows(12)
                .any(|window| window == b"manifest.bin")
        );
        let models = format!("{:?}", core.scan_sessions().unwrap());
        assert!(!models.contains("active-payload"));
        assert!(!models.contains("rows.wal"));
    }

    #[test]
    fn fallible_session_metadata_precondition_cannot_create_ambiguous_acceptance() {
        let (directory, core) = open(
            100_000,
            vec![1_742_860_800_000, 1_742_860_800_001, 1_742_860_800_002],
        );
        let session_id = core.start_scan_session().unwrap();
        let metadata = crate::session::session_state_path(directory.path(), &session_id);
        fs::remove_file(&metadata).unwrap();
        fs::create_dir(&metadata).unwrap();

        assert!(core.record_observations(vec![observation(1)]).is_err());
        assert_eq!(core.state().total_observations, 0);
        assert_eq!(core.state().active_payload_rows, 0);

        fs::remove_dir(&metadata).unwrap();
        core.record_observations(vec![observation(1)]).unwrap();
        assert_eq!(core.state().total_observations, 1);
        assert_eq!(core.state().active_payload_rows, 1);
    }

    #[test]
    fn staged_prefix_and_later_append_recover_in_exact_order_without_shadow() {
        let directory = tempfile::tempdir().unwrap();
        let core = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(100_000),
            Arc::new(DeterministicRuntime {
                times: Mutex::new((1_742_860_800_000..1_742_860_800_010).collect()),
                random: [0x66; 10],
            }),
        )
        .unwrap();
        core.record_observations(vec![observation(1), observation(2)])
            .unwrap();
        core.checkpoint_active_payload().unwrap();
        core.record_observations(vec![observation(3), observation(4)])
            .unwrap();
        core.checkpoint_active_payload().unwrap();
        drop(core);

        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(100_000),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_900_000].into()),
                random: [0x77; 10],
            }),
        )
        .unwrap();
        assert_eq!(reopened.pending_uploads().len(), 1);
        let frame =
            ParquetReader::new(File::open(&reopened.pending_uploads()[0].local_path).unwrap())
                .finish()
                .unwrap();
        assert_eq!(
            (0..frame.height())
                .map(|index| frame
                    .column("raw")
                    .unwrap()
                    .binary()
                    .unwrap()
                    .get(index)
                    .unwrap()[2])
                .collect::<Vec<_>>(),
            vec![1, 2, 3, 4]
        );
        assert_eq!(reopened.state().active_payload_rows, 0);
    }

    fn observation_row() -> crate::SchemaV2Row {
        crate::SchemaV2Row {
            addr: "AA:BB:CC:DD:EE:FF".to_owned(),
            rssi: None,
            scanned_at_ms: 0,
            raw: vec![],
            local_name: None,
            tx_power: None,
            is_connectable: None,
            lat: None,
            lon: None,
            accuracy: None,
        }
    }

    #[test]
    fn rename_success_then_leaf_fsync_failure_retries_exact_file_and_full_ancestry() {
        let (directory, core, runtime) = open_with_fault_runtime();
        core.record_observations(vec![observation(1), observation(2)])
            .unwrap();
        core.checkpoint_active_payload().unwrap();
        let staged = crate::staging::recover(directory.path()).unwrap().unwrap();
        let relative_path = staged.manifest.identity.pending_relative_path();
        let relative_parent = relative_path.parent().unwrap();
        let target = relative_path.file_name().unwrap();
        runtime.set_fault(OwnedSyncStep::PublishedDirectory(
            relative_parent.to_owned(),
        ));

        assert!(core.flush_payload().is_err());
        assert!(directory.path().join("active-payload/rows.wal").exists());
        assert!(directory.path().join(&relative_path).exists());

        let payload_id = staged.manifest.identity.payload_id;
        runtime.take_steps();
        drop(core);
        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(100_000),
            runtime.clone(),
        )
        .unwrap();
        assert_eq!(
            runtime.take_steps(),
            publication_proof_steps(relative_parent, target)
        );
        let pending = reopened.pending_uploads();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].payload_id, payload_id);
        assert!(!directory.path().join("active-payload").exists());
        let frame = ParquetReader::new(File::open(&pending[0].local_path).unwrap())
            .finish()
            .unwrap();
        assert_eq!(frame.height(), 2);
    }

    #[test]
    fn mkdir_success_then_parent_fsync_failure_retries_existing_tree_and_publishes_once() {
        let (directory, core, runtime) = open_with_fault_runtime();
        core.record_observations(vec![observation(1)]).unwrap();
        core.checkpoint_active_payload().unwrap();
        let staged = crate::staging::recover(directory.path()).unwrap().unwrap();
        let relative_path = staged.manifest.identity.pending_relative_path();
        runtime.set_fault(OwnedSyncStep::CreatedParent(PathBuf::from("pending/scans")));

        assert!(core.flush_payload().is_err());
        assert!(directory.path().join("active-payload/rows.wal").exists());
        assert!(directory.path().join("pending/scans/v2").is_dir());
        assert!(!directory.path().join(&relative_path).exists());

        runtime.take_steps();
        let sealed = core.flush_payload().unwrap().sealed.unwrap();
        let target = relative_path.file_name().unwrap();
        let proof = publication_proof_steps(relative_path.parent().unwrap(), target);
        let retry_steps = runtime.take_steps();
        assert_eq!(&retry_steps[retry_steps.len() - proof.len()..], proof);
        let pending = core.pending_uploads();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].payload_id, sealed.payload_id);
        assert!(!directory.path().join("active-payload").exists());
    }

    #[test]
    fn every_publication_proof_failure_preserves_wal_until_complete_leaf_to_root_retry() {
        let identity = crate::payload_identity::PayloadIdentity::from_uuid(
            crate::payload_identity::generate_uuid_v7(1_742_860_800_001, [0x66; 10]).unwrap(),
        )
        .unwrap();
        let relative_path = identity.pending_relative_path();
        let relative_parent = relative_path.parent().unwrap();
        let target = relative_path.file_name().unwrap();
        let proof = publication_proof_steps(relative_parent, target);

        for fault in &proof {
            let (directory, core, runtime) = open_with_fault_runtime();
            core.record_observations(vec![observation(1)]).unwrap();
            core.checkpoint_active_payload().unwrap();
            runtime.set_fault(fault.clone());

            assert!(core.flush_payload().is_err(), "{fault:?}");
            assert!(
                directory.path().join("active-payload/rows.wal").exists(),
                "{fault:?}"
            );
            runtime.take_steps();
            assert!(core.flush_payload().unwrap().sealed.is_some(), "{fault:?}");
            assert_eq!(runtime.take_steps(), proof, "{fault:?}");
            assert_eq!(core.pending_uploads().len(), 1, "{fault:?}");
            assert!(
                !directory.path().join("active-payload").exists(),
                "{fault:?}"
            );
        }
    }

    #[test]
    fn recovery_after_publication_before_wal_cleanup_does_not_duplicate_payload() {
        let (directory, core) = open(
            100_000,
            vec![1_742_860_800_000, 1_742_860_800_001, 1_742_860_800_002],
        );
        core.record_observations(vec![observation(1), observation(2)])
            .unwrap();
        core.checkpoint_active_payload().unwrap();
        let staged = crate::staging::recover(directory.path()).unwrap().unwrap();
        let final_path = directory
            .path()
            .join(staged.manifest.identity.pending_relative_path());
        fs::create_dir_all(final_path.parent().unwrap()).unwrap();
        crate::payload::write_schema_v2_parquet_for_session_impl(
            &final_path,
            &staged.manifest.identity.payload_id,
            staged.manifest.session_id.as_deref(),
            staged.rows,
        )
        .unwrap();
        let payload_id = staged.manifest.identity.payload_id;
        drop(core);

        let reopened = CluetoothCore::open_with_runtime(
            directory.path().to_owned(),
            config(100_000),
            Arc::new(DeterministicRuntime {
                times: Mutex::new([1_742_860_900_000].into()),
                random: [0x77; 10],
            }),
        )
        .unwrap();
        assert_eq!(reopened.pending_uploads().len(), 1);
        assert_eq!(reopened.pending_uploads()[0].payload_id, payload_id);
        assert_eq!(reopened.pending_uploads()[0].row_count, 2);
        assert!(!directory.path().join("active-payload").exists());
    }
}
