#[derive(Debug, thiserror::Error, uniffi::Error)]
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
}

impl CoreError {
    pub(crate) fn invalid(message: impl Into<String>) -> Self {
        Self::InvalidPayload {
            detail: message.into(),
        }
    }

    pub(crate) fn invalid_config(message: impl Into<String>) -> Self {
        Self::InvalidConfig {
            detail: message.into(),
        }
    }

    pub(crate) fn input_batch_too_large(message: impl Into<String>) -> Self {
        Self::InputBatchTooLarge {
            detail: message.into(),
        }
    }

    pub(crate) fn upload_state(message: impl Into<String>) -> Self {
        Self::UploadState {
            detail: message.into(),
        }
    }

    pub(crate) fn encryption(error: impl std::fmt::Display) -> Self {
        Self::Encryption {
            detail: error.to_string(),
        }
    }

    pub(crate) fn io(error: impl std::fmt::Display) -> Self {
        Self::Io {
            detail: error.to_string(),
        }
    }

    pub(crate) fn parquet(error: impl std::fmt::Display) -> Self {
        Self::Parquet {
            detail: error.to_string(),
        }
    }

    pub(crate) fn clock(error: impl std::fmt::Display) -> Self {
        Self::Clock {
            detail: error.to_string(),
        }
    }
}
