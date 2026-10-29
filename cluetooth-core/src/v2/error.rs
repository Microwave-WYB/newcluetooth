//! `docs/v2-api.md` §2.9.

/// The v2 error. The first eight variants are the v3 ones, unchanged.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
#[uniffi(name = "CoreErrorV2")]
pub enum CoreError {
    #[error("invalid payload: {detail}")]
    InvalidPayload { detail: String },
    #[error("invalid core configuration: {detail}")]
    InvalidConfig { detail: String },
    #[error("input observation batch exceeds the FFI bound: {detail}")]
    InputBatchTooLarge { detail: String },
    #[error("upload state rejected the operation: {detail}")]
    UploadState { detail: String },
    #[error("sealed-box encryption failed: {detail}")]
    Encryption { detail: String },
    #[error("payload I/O failed: {detail}")]
    Io { detail: String },
    #[error("Parquet operation failed: {detail}")]
    Parquet { detail: String },
    #[error("payload clock/random source failed: {detail}")]
    Clock { detail: String },
    #[error("local database failed: {detail}")]
    Db { detail: String },
    /// An older build opened a newer `local.db`.
    #[error("local.db schema {found} is newer than the supported {supported}")]
    SchemaTooNew { found: u32, supported: u32 },
    /// A wiki bundle schema or vocabulary violation.
    #[error("invalid wiki bundle: {detail}")]
    InvalidBundle { detail: String },
    #[error("the ingest queue is full")]
    Backpressure,
    #[error("no recording is active")]
    NotRecording,
    #[error("a recording is already active")]
    AlreadyRecording,
    #[error("the core is closed")]
    Closed,
    /// The caller dropped the query; Kotlin maps it to `CancellationException`.
    #[error("the query was cancelled")]
    Cancelled,
}

impl From<crate::CoreError> for CoreError {
    fn from(error: crate::CoreError) -> Self {
        use crate::CoreError as V3;
        match error {
            V3::InvalidPayload { detail } => Self::InvalidPayload { detail },
            V3::InvalidConfig { detail } => Self::InvalidConfig { detail },
            V3::InputBatchTooLarge { detail } => Self::InputBatchTooLarge { detail },
            V3::UploadState { detail } => Self::UploadState { detail },
            V3::Encryption { detail } => Self::Encryption { detail },
            V3::Io { detail } => Self::Io { detail },
            V3::Parquet { detail } => Self::Parquet { detail },
            V3::Clock { detail } => Self::Clock { detail },
        }
    }
}
