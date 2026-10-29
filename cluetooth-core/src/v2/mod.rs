//! The v2 core (`docs/v2-api.md`). The v3 `CluetoothCore` stays alongside it until integration (§6.7).

mod adv;
mod catalog;
mod core;
mod db;
mod error;
mod gatt_view;
mod keys;
mod live;
mod query;
mod search;
mod store;
mod types;

#[cfg(test)]
mod parity_tests;

pub use core::{API_VERSION, Core, CoreListener, match_device};
pub use error::CoreError;
pub use types::*;
