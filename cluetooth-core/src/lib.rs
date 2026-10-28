mod config;
mod core;
mod encryption;
mod error;
mod export;
mod gatt;
mod location;
mod matcher;
mod owned_path;
mod payload;
mod payload_identity;
mod scan;
mod session;
mod staging;
mod state;

use std::path::Path;

pub use config::{CoreConfig, default_core_config};
pub use core::CluetoothCore;
pub use error::CoreError;
pub use export::{DeleteSessionResult, ExportFormat, LegacySessionRows, PreparedExport};
pub use gatt::GattDiscoveryFile;
pub use location::LocationFix;
pub use matcher::{
    AddrContext, AddrType, AdvAddressType, Compiled, MatcherError, Node, RuleMatcher, Structure,
    compile, evaluate_rule, regex_ok, structures,
};
pub use payload::{
    PayloadFile, PayloadInspection, PayloadResult, PendingUpload, PreparedUpload, ScanEnvironment,
    SchemaV2Row, SchemaV3Row,
};
pub use scan::ScanObservationInput;
pub use session::{
    AdvertisementCluster, ScanSession, ScanSessionStatus, SessionRoutePoint, SessionUploadState,
};
pub use state::{CoreEffect, CoreState, CoreUpdate};

/// Checks one `gatt/v1` object against the producer contract without storing it.
pub fn validate_gatt_json(json: String) -> Result<String, CoreError> {
    gatt::validate_gatt_json(json.as_bytes())
}

pub const API_VERSION: u32 = 7;

#[uniffi::export]
pub fn api_version() -> u32 {
    API_VERSION
}

#[uniffi::export]
pub fn write_schema_v2_parquet(
    path: String,
    payload_id: String,
    rows: Vec<SchemaV2Row>,
) -> Result<PayloadInspection, CoreError> {
    payload::write_schema_v2_parquet_impl(Path::new(&path), &payload_id, rows)
}

#[uniffi::export]
pub fn write_schema_v3_parquet(
    path: String,
    payload_id: String,
    scan_session_id: Option<String>,
    environment: ScanEnvironment,
    rows: Vec<SchemaV3Row>,
) -> Result<PayloadInspection, CoreError> {
    payload::write_schema_v3_parquet_impl(
        Path::new(&path),
        &payload_id,
        scan_session_id.as_deref(),
        &environment,
        rows,
    )
}

/// Validates a scan payload of either generation (v2 or v3).
#[uniffi::export]
pub fn inspect_schema_v2_parquet(path: String) -> Result<PayloadInspection, CoreError> {
    payload::inspect_schema_v2_parquet_impl(Path::new(&path))
}

uniffi::setup_scaffolding!();
