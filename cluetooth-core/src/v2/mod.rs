//! The v2 core (`docs/v2-api.md`). The v3 `CluetoothCore` stays alongside it until integration (§6.7).

mod core;
mod error;
mod search;
mod types;

pub use core::{API_VERSION, Core, CoreListener, match_device};
pub use error::CoreError;
pub use types::*;
