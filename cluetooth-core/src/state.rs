#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct CoreState {
    pub total_observations: u64,
    pub observations_with_location: u64,
    pub active_payload_rows: u64,
    pub active_payload_estimated_bytes: u64,
    pub pending_upload_count: u64,
    pub invalid_pending_payload_count: u64,
    pub prepared_upload_count: u64,
    pub failed_upload_count: u64,
    pub last_upload_error: Option<String>,
    pub recent_location_fix_count: u64,
    pub has_location: bool,
    pub latest_observation_at_ms: Option<i64>,
    pub latest_local_name: Option<String>,
}

impl CoreState {
    pub(crate) fn empty() -> Self {
        Self {
            total_observations: 0,
            observations_with_location: 0,
            active_payload_rows: 0,
            active_payload_estimated_bytes: 0,
            pending_upload_count: 0,
            invalid_pending_payload_count: 0,
            prepared_upload_count: 0,
            failed_upload_count: 0,
            last_upload_error: None,
            recent_location_fix_count: 0,
            has_location: false,
            latest_observation_at_ms: None,
            latest_local_name: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, uniffi::Enum)]
pub enum CoreEffect {
    ScheduleUpload,
    CancelUpload,
    StorageWarning { message: String },
    PersistenceDegraded { message: String },
}

#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct CoreUpdate {
    pub state: CoreState,
    pub effects: Vec<CoreEffect>,
}

impl CoreUpdate {
    pub(crate) fn state_only(state: CoreState) -> Self {
        Self {
            state,
            effects: Vec::new(),
        }
    }
}
