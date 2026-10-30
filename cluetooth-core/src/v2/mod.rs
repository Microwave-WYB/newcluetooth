//! The v2 core (`docs/v2-api.md`). The v3 `CluetoothCore` stays alongside it until integration (§6.7).
//!
//! Crashes are ruled out structurally (AGENTS.md): production code here may not unwrap, expect, panic or index;
//! tests are exempt.
#![cfg_attr(
    not(test),
    deny(
        clippy::unwrap_used,
        clippy::expect_used,
        clippy::panic,
        clippy::indexing_slicing,
        clippy::unreachable,
        clippy::todo,
        clippy::unimplemented
    )
)]

mod adv;
mod catalog;
mod core;
mod db;
mod discovery;
mod error;
mod gatt_view;
mod keys;
mod live;
mod machine;
mod query;
mod search;
mod space;
mod store;
mod types;

#[cfg(test)]
mod parity_tests;
#[cfg(test)]
mod staging_tests;

pub use core::{API_VERSION, Core, CoreListener, match_device};
pub use error::CoreError;
pub use types::*;
