mod config;
mod core;
mod encryption;
mod error;
mod export;
mod location;
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
pub use location::LocationFix;
pub use payload::{
    PayloadFile, PayloadInspection, PayloadResult, PendingUpload, PreparedUpload, SchemaV2Row,
};
pub use scan::ScanObservationInput;
pub use session::{
    AdvertisementCluster, ScanSession, ScanSessionStatus, SessionRoutePoint, SessionUploadState,
};
pub use state::{CoreEffect, CoreState, CoreUpdate};

pub const API_VERSION: u32 = 6;

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
pub fn inspect_schema_v2_parquet(path: String) -> Result<PayloadInspection, CoreError> {
    payload::inspect_schema_v2_parquet_impl(Path::new(&path))
}

uniffi::setup_scaffolding!();
