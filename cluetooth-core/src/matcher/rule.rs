//! Rule JSON as the Wireless Wiki stores it (`rule.json`).
//!
//! These are the serde types of `kb-interface` (branch `kb/lab`,
//! `cluetooth-core/kb-interface/src/lib.rs`) without the JSON Schema derive, so
//! the app does not link `schemars`. Unknown fields are rejected exactly as
//! there; `tests/matcher.rs` reads every rule of the wiki snapshot through them.

use serde::Deserialize;

/// One node: exactly one operator or condition, plus optional `desc`/`note`.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Node {
    pub all: Option<Vec<Node>>,
    pub any: Option<Vec<Node>>,
    pub not: Option<Box<Node>>,
    pub name: Option<NameCondition>,
    pub company: Option<Vec<u16>>,
    pub uuid: Option<UuidCondition>,
    #[serde(rename = "struct")]
    pub structure: Option<StructCondition>,
    pub shape: Option<ShapeCondition>,
    pub addr: Option<AddrCondition>,
    pub desc: Option<String>,
    pub note: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct NameCondition {
    pub equals: Option<String>,
    pub prefix: Option<String>,
    pub regex: Option<String>,
    pub case: Option<Case>,
    pub ad: Option<Vec<u8>>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Case {
    Sensitive,
    Insensitive,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct UuidCondition {
    pub r#in: Vec<String>,
    pub ad: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct StructCondition {
    pub ad: AdTypes,
    pub company: Option<u16>,
    pub uuid: Option<String>,
    pub len: Option<Length>,
    pub bytes: Option<MaskedBytes>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(untagged)]
pub enum AdTypes {
    One(u8),
    Many(Vec<u8>),
}

impl AdTypes {
    pub fn to_vec(&self) -> Vec<u8> {
        match self {
            AdTypes::One(t) => vec![*t],
            AdTypes::Many(ts) => ts.clone(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(untagged)]
pub enum Length {
    Exact(u8),
    Range([u8; 2]),
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct MaskedBytes {
    #[serde(default)]
    pub offset: u8,
    pub value: String,
    pub mask: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct ShapeCondition {
    pub items: Vec<String>,
    pub rest: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct AddrCondition {
    pub oui: Option<Vec<String>>,
    #[serde(rename = "type")]
    pub addr_type: Option<AddrType>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum AddrType {
    Public,
    Random,
}
