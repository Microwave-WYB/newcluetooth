//! On-device rule matcher: which Wireless Wiki pages does an advertisement
//! match? Same semantics as the Python reference (`kb.rules.matches`):
//!
//! ```text
//! structures(raw) -> evaluate(rule, structures, addr, addr_type)
//! ```
//!
//! A [`RuleMatcher`] is built once from the app's wiki bundle (see
//! `cluetooth-android/tools/wiki-bundle`) and then matches advertisements:
//! raw bytes plus the address and address type when known; an `addr` rule never
//! matches when the address is unknown. The result is the ids of the matching
//! pages, in bundle order.

mod adv;
mod compiled;
mod rule;

use std::sync::Arc;

use serde::Deserialize;

pub use adv::{Structure, structures};
pub use compiled::{AddrContext, Compiled, compile, regex_ok};
pub use rule::{AddrType, Node};

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MatcherError {
    #[error("invalid wiki bundle: {detail}")]
    InvalidBundle { detail: String },
    #[error("invalid rule: {detail}")]
    InvalidRule { detail: String },
}

/// Whether the advertiser address is public or random (Android's
/// `BluetoothDevice.ADDRESS_TYPE_*`; null when the platform does not say).
#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum AdvAddressType {
    Public,
    Random,
}

impl From<AdvAddressType> for AddrType {
    fn from(value: AdvAddressType) -> Self {
        match value {
            AdvAddressType::Public => AddrType::Public,
            AdvAddressType::Random => AddrType::Random,
        }
    }
}

#[derive(Deserialize)]
struct Bundle {
    version: serde_json::Value,
    pages: Vec<BundlePage>,
}

#[derive(Deserialize)]
struct BundlePage {
    id: String,
    rule: Option<serde_json::Value>,
}

/// Compiled rules of one wiki bundle.
#[derive(uniffi::Object)]
pub struct RuleMatcher {
    version: String,
    rules: Vec<(String, Compiled)>,
    errors: Vec<String>,
    uses_address: bool,
}

impl RuleMatcher {
    /// Ids of the pages whose rule matches the structures.
    pub fn match_structures(&self, structs: &[Structure], ctx: &AddrContext<'_>) -> Vec<String> {
        self.rules
            .iter()
            .filter(|(_, rule)| rule.eval(structs, ctx))
            .map(|(id, _)| id.clone())
            .collect()
    }
}

#[uniffi::export]
impl RuleMatcher {
    /// Compile the rules of a wiki bundle (`{"version": …, "pages": [{"id", "rule"?, …}]}`).
    /// A page whose rule does not compile is skipped and listed in
    /// [`rule_errors`](Self::rule_errors); it never fails the whole bundle.
    #[uniffi::constructor]
    pub fn from_bundle_json(json: String) -> Result<Arc<Self>, MatcherError> {
        let bundle: Bundle =
            serde_json::from_str(&json).map_err(|e| MatcherError::InvalidBundle {
                detail: e.to_string(),
            })?;
        let version = match &bundle.version {
            serde_json::Value::String(s) => s.clone(),
            other => other.to_string(),
        };
        let mut rules = Vec::new();
        let mut errors = Vec::new();
        for page in bundle.pages {
            let Some(rule) = page.rule else { continue };
            let compiled = serde_json::from_value::<Node>(rule)
                .map_err(|e| e.to_string())
                .and_then(|node| compile(&node));
            match compiled {
                Ok(c) => rules.push((page.id, c)),
                Err(e) => errors.push(format!("{}: {e}", page.id)),
            }
        }
        let uses_address = rules.iter().any(|(_, rule)| rule.uses_address());
        Ok(Arc::new(Self {
            version,
            rules,
            errors,
            uses_address,
        }))
    }

    /// The bundle's `version`, as the bundle states it.
    pub fn bundle_version(&self) -> String {
        self.version.clone()
    }

    /// Number of compiled rules.
    pub fn rule_count(&self) -> u32 {
        self.rules.len() as u32
    }

    /// Whether any compiled rule looks at the address. When none does, the result
    /// for an advertisement depends on its bytes alone and can be cached by payload.
    pub fn uses_address_rules(&self) -> bool {
        self.uses_address
    }

    /// `page-id: reason` for every rule that was skipped.
    pub fn rule_errors(&self) -> Vec<String> {
        self.errors.clone()
    }

    /// Ids of the pages matching one advertisement (its raw bytes, and the
    /// address and address type when known), in bundle order.
    pub fn match_advertisement(
        &self,
        raw: Vec<u8>,
        addr: Option<String>,
        addr_type: Option<AdvAddressType>,
    ) -> Vec<String> {
        let ctx = AddrContext {
            addr: addr.as_deref(),
            addr_type: addr_type.map(Into::into),
        };
        self.match_structures(&structures(&raw), &ctx)
    }
}

/// Evaluate one `rule.json` against one advertisement.
#[uniffi::export]
pub fn evaluate_rule(
    rule_json: String,
    raw: Vec<u8>,
    addr: Option<String>,
    addr_type: Option<AdvAddressType>,
) -> Result<bool, MatcherError> {
    let node: Node = serde_json::from_str(&rule_json).map_err(|e| MatcherError::InvalidRule {
        detail: e.to_string(),
    })?;
    let compiled = compile(&node).map_err(|detail| MatcherError::InvalidRule { detail })?;
    let ctx = AddrContext {
        addr: addr.as_deref(),
        addr_type: addr_type.map(Into::into),
    };
    Ok(compiled.eval(&structures(&raw), &ctx))
}
