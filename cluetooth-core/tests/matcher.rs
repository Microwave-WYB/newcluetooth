//! The on-device rule matcher against the knowledge base's rule language.
//!
//! * `rule_cases.json`: the fixtures the Python and SQL evaluators share
//!   (copied from branch kb/lab) must give their expected results here too.
//! * Every rule of the wiki bundle asset gets synthetic advertisements built
//!   from the rule text: positives must match, near-misses and unrelated
//!   advertisements must not.
//! * Every (rule, advertisement) pair is also evaluated by the Python
//!   reference (`tests/python/reference_rules.py`, a copy of `kb.rules`) and
//!   the two must agree.

use std::collections::BTreeSet;
use std::io::Write;
use std::process::{Command, Stdio};

use cluetooth_core::{
    AddrContext, AddrType, AdvAddressType, MatcherError, Node, RuleMatcher, compile, evaluate_rule,
    regex_ok, structures,
};
use regex_syntax::hir::{Class, Hir, HirKind};
use serde_json::{Value, json};

const BUNDLE: &str = include_str!("../../cluetooth-android/app/src/main/assets/wiki-bundle.json");
const CASES: &str = include_str!("fixtures/rule_cases.json");

fn hex(s: &str) -> Vec<u8> {
    hex::decode(s).expect("hex")
}

fn eval(rule: &Value, raw: &[u8], addr: Option<&str>, ty: Option<AdvAddressType>) -> bool {
    evaluate_rule(rule.to_string(), raw.to_vec(), addr.map(str::to_string), ty)
        .expect("rule evaluates")
}

// Shared fixtures ------------------------------------------------------------

#[test]
fn shared_fixtures_give_the_expected_results() {
    let cases: Vec<Value> = serde_json::from_str(CASES).unwrap();
    assert_eq!(cases.len(), 8);
    let mut checked = 0;
    for case in &cases {
        for ad in case["advertisements"].as_array().unwrap() {
            let got = eval(
                &case["rule"],
                &hex(ad["hex"].as_str().unwrap()),
                ad["addr"].as_str(),
                None,
            );
            assert_eq!(
                got,
                ad["expect"].as_bool().unwrap(),
                "{}: {} ({})",
                case["name"],
                ad["hex"],
                ad["why"]
            );
            checked += 1;
        }
    }
    assert_eq!(checked, 34);
}

// Semantics, case by case ------------------------------------------------------

#[test]
fn structures_stop_at_padding_and_truncation_like_the_database_parser() {
    let rule = json!({"name": {"equals": "AB"}});
    // name, then a zero length: the second name never counts
    assert!(eval(&rule, &hex("03094142"), None, None));
    let after_padding = hex("0201060003094142");
    assert!(!eval(&rule, &after_padding, None, None));
    // truncated field: declared 5 data bytes, only 2 present
    assert!(!eval(&rule, &hex("0201060609414200"), None, None));
    assert_eq!(structures(&hex("0201060003094142")).len(), 1);
    assert_eq!(structures(&[]).len(), 0);
}

#[test]
fn names_are_utf8_lossy_without_nul_and_come_from_8_or_9() {
    assert!(eval(
        &json!({"name": {"equals": "Tile"}}),
        &hex("050954696c65"),
        None,
        None
    ));
    // NUL padding is removed, as in the database
    assert!(eval(
        &json!({"name": {"equals": "Tile"}}),
        &hex("06095469006c65"),
        None,
        None
    ));
    // shortened name (0x08) counts by default, `ad: [9]` restricts
    let short = hex("0508546c6965");
    assert!(!eval(
        &json!({"name": {"equals": "Tile"}}),
        &short,
        None,
        None
    ));
    assert!(eval(
        &json!({"name": {"equals": "Tlie"}}),
        &short,
        None,
        None
    ));
    assert!(eval(
        &json!({"name": {"prefix": "Tli"}}),
        &short,
        None,
        None
    ));
    assert!(!eval(
        &json!({"name": {"prefix": "Tli", "ad": [9]}}),
        &short,
        None,
        None
    ));
    // invalid UTF-8 becomes U+FFFD
    assert!(eval(
        &json!({"name": {"regex": "a\u{fffd}b"}}),
        &hex("040961ff62"),
        None,
        None
    ));
}

#[test]
fn name_modes_case_and_full_match() {
    let raw = hex("0b09476f7665655f48313233"); // Govee_H123
    assert!(eval(
        &json!({"name": {"prefix": "Govee_H"}}),
        &raw,
        None,
        None
    ));
    assert!(!eval(
        &json!({"name": {"prefix": "govee_h"}}),
        &raw,
        None,
        None
    ));
    assert!(eval(
        &json!({"name": {"prefix": "govee_h", "case": "insensitive"}}),
        &raw,
        None,
        None
    ));
    assert!(eval(
        &json!({"name": {"equals": "GOVEE_h123", "case": "insensitive"}}),
        &raw,
        None,
        None
    ));
    assert!(!eval(
        &json!({"name": {"equals": "Govee_H"}}),
        &raw,
        None,
        None
    ));
    // a regex is a FULL match
    assert!(eval(
        &json!({"name": {"regex": "Govee_H\\d{3}"}}),
        &raw,
        None,
        None
    ));
    assert!(!eval(
        &json!({"name": {"regex": "Govee_H\\d{2}"}}),
        &raw,
        None,
        None
    ));
    assert!(!eval(
        &json!({"name": {"regex": "Govee"}}),
        &raw,
        None,
        None
    ));
    assert!(eval(
        &json!({"name": {"regex": "govee_h[0-9]+", "case": "insensitive"}}),
        &raw,
        None,
        None
    ));
}

#[test]
fn company_uuid_and_service_data() {
    let ibeacon = hex("0201061aff4c000215000000000000000000000000000000000001000203c5");
    assert!(eval(&json!({"company": [76]}), &ibeacon, None, None));
    assert!(!eval(&json!({"company": [77, 78]}), &ibeacon, None, None));
    // UUID in a 16-bit list; the base-UUID form of a 128-bit list folds to the short form
    let list16 = hex("03031828");
    assert!(eval(
        &json!({"uuid": {"in": ["2818"], "ad": [3]}}),
        &list16,
        None,
        None
    ));
    assert!(!eval(
        &json!({"uuid": {"in": ["1828"], "ad": [3]}}),
        &list16,
        None,
        None
    ));
    let base_form = hex("1107fb349b5f800000800010000028180000");
    assert!(eval(
        &json!({"uuid": {"in": ["1828"], "ad": [7]}}),
        &base_form,
        None,
        None
    ));
    let full128 = hex("1107000102030405060708090a0b0c0d0e0f");
    assert!(eval(
        &json!({"uuid": {"in": ["0f0e0d0c-0b0a-0908-0706-050403020100"], "ad": [7]}}),
        &full128,
        None,
        None
    ));
    // service data: the UUID is the first two bytes
    let sd = hex("0516aafe0001");
    assert!(eval(
        &json!({"uuid": {"in": ["FEAA"], "ad": [22]}}),
        &sd,
        None,
        None
    ));
    assert!(!eval(
        &json!({"uuid": {"in": ["FEAA"], "ad": [3]}}),
        &sd,
        None,
        None
    ));
}

#[test]
fn struct_offsets_lengths_and_masks_count_after_company_or_uuid() {
    let ibeacon = hex("0201061aff4c0002150000000000000000000000000000000000010002c5");
    let r = |bytes: Value| json!({"struct": {"ad": 255, "company": 76, "bytes": bytes}});
    assert!(eval(
        &r(json!({"offset": 0, "value": "0215"})),
        &ibeacon,
        None,
        None
    ));
    assert!(!eval(
        &r(json!({"offset": 1, "value": "0215"})),
        &ibeacon,
        None,
        None
    ));
    assert!(eval(
        &r(json!({"offset": 0, "value": "f215", "mask": "0fff"})),
        &ibeacon,
        None,
        None
    ));
    assert!(!eval(
        &r(json!({"offset": 0, "value": "f215", "mask": "ffff"})),
        &ibeacon,
        None,
        None
    ));
    // length counts after the company field: 0x1a - 1 (type) - 2 (company) = 23
    let len = |l: Value| json!({"struct": {"ad": 255, "len": l}});
    assert!(eval(&len(json!(23)), &ibeacon, None, None));
    assert!(eval(&len(json!([20, 23])), &ibeacon, None, None));
    assert!(!eval(&len(json!([24, 30])), &ibeacon, None, None));
    // bytes beyond the structure never match
    assert!(!eval(
        &json!({"struct": {"ad": 255, "bytes": {"offset": 22, "value": "0000"}}}),
        &ibeacon,
        None,
        None
    ));
    // service data: Eddystone, UUID FEAA, then frame type 00
    let eddystone = hex("0916aafe00e700010203");
    let ed = json!({"struct": {"ad": 22, "uuid": "FEAA", "len": 6, "bytes": {"value": "00"}}});
    assert!(eval(&ed, &eddystone, None, None));
    assert!(!eval(
        &json!({"struct": {"ad": 22, "uuid": "FE95"}}),
        &eddystone,
        None,
        None
    ));
    // several AD types sharing a data offset share a condition (names count from the start)
    assert!(eval(
        &json!({"struct": {"ad": [8, 9], "len": 2}}),
        &hex("03094142"),
        None,
        None
    ));
    assert!(!eval(
        &json!({"struct": {"ad": [8, 9], "len": 3}}),
        &hex("03094142"),
        None,
        None
    ));
    // a 0xFF structure shorter than its company field has no company
    assert!(!eval(
        &json!({"struct": {"ad": 255}}),
        &hex("02ff4c"),
        None,
        None
    ));
}

#[test]
fn shape_prefix_rest_and_length_ranges() {
    let adv = hex("0201060309414209ff4c00021500000000");
    let shape = |items: Value, rest: Value| json!({"shape": {"items": items, "rest": rest}});
    assert!(eval(
        &shape(json!(["01/1", "09/2"]), json!(true)),
        &adv,
        None,
        None
    ));
    assert!(eval(
        &shape(json!(["01/1", "09/1-3", "FF/*"]), json!(false)),
        &adv,
        None,
        None
    ));
    assert!(!eval(
        &shape(json!(["01/1", "09/2"]), json!(false)),
        &adv,
        None,
        None
    ));
    assert!(!eval(
        &shape(json!(["01/1", "09/3"]), json!(true)),
        &adv,
        None,
        None
    ));
    assert!(!eval(
        &shape(json!(["09/2"]), json!(true)),
        &adv,
        None,
        None
    ));
    // `rest` defaults to true
    assert!(eval(
        &json!({"shape": {"items": ["01/1"]}}),
        &adv,
        None,
        None
    ));
    // an empty advertisement has no shape
    assert!(!eval(
        &json!({"shape": {"items": ["01/1"]}}),
        &[],
        None,
        None
    ));
}

#[test]
fn address_conditions_need_a_known_address() {
    let flags = hex("020106");
    let oui = json!({"addr": {"oui": ["001a7d", "AABBCC"]}});
    assert!(!eval(&oui, &flags, None, None));
    assert!(eval(&oui, &flags, Some("00:1A:7D:00:00:01"), None));
    assert!(eval(&oui, &flags, Some("aa-bb-cc-11-22-33"), None));
    assert!(!eval(&oui, &flags, Some("11:22:33:44:55:66"), None));
    let ty = json!({"addr": {"type": "random"}});
    assert!(!eval(&ty, &flags, Some("11:22:33:44:55:66"), None));
    assert!(!eval(
        &ty,
        &flags,
        Some("11:22:33:44:55:66"),
        Some(AdvAddressType::Public)
    ));
    assert!(eval(
        &ty,
        &flags,
        Some("11:22:33:44:55:66"),
        Some(AdvAddressType::Random)
    ));
    let both = json!({"addr": {"oui": ["112233"], "type": "public"}});
    assert!(eval(
        &both,
        &flags,
        Some("11:22:33:44:55:66"),
        Some(AdvAddressType::Public)
    ));
    assert!(!eval(
        &both,
        &flags,
        Some("11:22:33:44:55:66"),
        Some(AdvAddressType::Random)
    ));
    // `not` of an address condition holds when the address is unknown
    assert!(eval(
        &json!({"not": {"addr": {"type": "public"}}}),
        &flags,
        None,
        None
    ));
}

#[test]
fn operators_combine() {
    let raw = hex("0201060b09476f7665655f4831323304ff4c0001");
    let govee = json!({"name": {"prefix": "Govee_H"}});
    let apple = json!({"company": [76]});
    assert!(!eval(
        &json!({"all": [govee, {"not": apple}]}),
        &raw,
        None,
        None
    ));
    assert!(eval(&json!({"any": [govee, apple]}), &raw, None, None));
    assert!(eval(
        &json!({"all": [govee, apple], "desc": "both"}),
        &raw,
        None,
        None
    ));
    assert!(eval(&json!({"not": {"company": [1]}}), &raw, None, None));
}

#[test]
fn rules_outside_the_language_are_rejected_not_guessed() {
    let bad = |rule: Value| match evaluate_rule(rule.to_string(), vec![], None, None) {
        Err(MatcherError::InvalidRule { detail }) => detail,
        other => panic!("expected an invalid rule, got {other:?}"),
    };
    assert!(bad(json!({"company": [1], "name": {"equals": "x"}})).contains("one operator"));
    assert!(bad(json!({"all": []})).contains("non-empty"));
    assert!(bad(json!({"name": {"prefix": "a", "equals": "b"}})).contains("exactly one"));
    assert!(bad(json!({"name": {"regex": "(?=x)abc"}})).contains("shared subset"));
    assert!(bad(json!({"name": {"regex": "(a)\\1"}})).contains("shared subset"));
    assert!(bad(json!({"name": {"equals": "x", "ad": [3]}})).contains("0x08"));
    assert!(bad(json!({"uuid": {"in": ["FEAA"], "ad": [255]}})).contains("does not carry"));
    assert!(bad(json!({"uuid": {"in": ["feaa"], "ad": [3]}})).contains("canonical"));
    assert!(bad(json!({"struct": {"ad": [255, 3]}})).contains("different data offsets"));
    assert!(bad(json!({"struct": {"ad": 22, "company": 1}})).contains("company needs ad 0xFF"));
    assert!(bad(json!({"struct": {"ad": 255, "uuid": "FEAA"}})).contains("service-data"));
    assert!(
        bad(json!({"struct": {"ad": 255, "bytes": {"value": "0A"}}})).contains("lowercase hex")
    );
    assert!(
        bad(json!({"struct": {"ad": 255, "bytes": {"value": "0a", "mask": "ffff"}}}))
            .contains("same length")
    );
    assert!(bad(json!({"shape": {"items": ["1/1"]}})).contains("bad shape item"));
    assert!(bad(json!({"shape": {"items": ["01/5-2"]}})).contains("empty range"));
    assert!(bad(json!({"addr": {}})).contains("oui or type"));
    // unknown fields are errors, like the schema's additionalProperties
    assert!(bad(json!({"company": [1], "colour": "red"})).contains("unknown field"));
    assert!(regex_ok("ihoment_H[0-9]{4}_[0-9A-Fa-f]{2}"));
    assert!(!regex_ok("(?=x)abc"));
}

// The wiki bundle ----------------------------------------------------------------

fn bundle() -> Value {
    serde_json::from_str(BUNDLE).unwrap()
}

#[test]
fn every_rule_of_the_bundle_parses_and_compiles() {
    let b = bundle();
    let pages = b["pages"].as_array().unwrap();
    let with_rule: Vec<&Value> = pages.iter().filter(|p| p["has_rule"] == true).collect();
    assert_eq!(with_rule.len(), 27, "the snapshot has 27 rules");
    for p in &with_rule {
        let node: Node = serde_json::from_value(p["rule"].clone())
            .unwrap_or_else(|e| panic!("{}: {e}", p["id"]));
        compile(&node).unwrap_or_else(|e| panic!("{}: {e}", p["id"]));
    }
    let matcher = RuleMatcher::from_bundle_json(BUNDLE.to_string()).unwrap();
    assert_eq!(matcher.rule_errors(), Vec::<String>::new());
    assert_eq!(matcher.rule_count(), 27);
    assert_eq!(matcher.bundle_version(), b["version"].as_str().unwrap());
}

#[test]
fn a_bad_page_rule_is_skipped_and_reported_without_failing_the_bundle() {
    let json = json!({"version": 7, "pages": [
        {"id": "device--ok", "rule": {"company": [76]}},
        {"id": "device--bad", "rule": {"name": {"regex": "(?=x)"}}},
        {"id": "device--unknown-field", "rule": {"company": [1], "x": 1}},
        {"id": "device--no-rule"}
    ]});
    let m = RuleMatcher::from_bundle_json(json.to_string()).unwrap();
    assert_eq!(m.bundle_version(), "7");
    assert_eq!(m.rule_count(), 1);
    assert_eq!(m.rule_errors().len(), 2);
    assert_eq!(
        m.match_advertisement(hex("04ff4c0001"), None, None),
        vec!["device--ok".to_string()]
    );
    assert!(matches!(
        RuleMatcher::from_bundle_json("{}".into()),
        Err(MatcherError::InvalidBundle { .. })
    ));
}

// Synthetic advertisements for the bundle's rules ------------------------------------

#[derive(Clone, Debug)]
struct Ad {
    structs: Vec<(u8, Vec<u8>)>,
    addr: Option<String>,
    addr_type: Option<AdvAddressType>,
    label: String,
}

impl Ad {
    fn raw(&self) -> Vec<u8> {
        let mut out = Vec::new();
        for (t, d) in &self.structs {
            out.push(d.len() as u8 + 1);
            out.push(*t);
            out.extend_from_slice(d);
        }
        out
    }
}

fn flags() -> (u8, Vec<u8>) {
    (0x01, vec![0x06])
}

fn le_uuid(uuid: &str) -> Vec<u8> {
    let mut b = hex(&uuid.replace('-', ""));
    b.reverse();
    b
}

fn sample(hir: &Hir) -> String {
    match hir.kind() {
        HirKind::Empty | HirKind::Look(_) => String::new(),
        HirKind::Literal(l) => String::from_utf8_lossy(&l.0).into_owned(),
        HirKind::Class(Class::Unicode(c)) => c.ranges()[0].start().to_string(),
        HirKind::Class(Class::Bytes(c)) => (c.ranges()[0].start() as char).to_string(),
        HirKind::Repetition(r) => sample(&r.sub).repeat(r.min as usize),
        HirKind::Capture(c) => sample(&c.sub),
        HirKind::Concat(v) => v.iter().map(sample).collect(),
        HirKind::Alternation(v) => sample(&v[0]),
    }
}

/// A name satisfying a name condition (rule JSON).
fn name_for(n: &Value) -> String {
    let text = |k: &str| n.get(k).and_then(Value::as_str);
    let base = if let Some(e) = text("equals") {
        e.to_string()
    } else if let Some(p) = text("prefix") {
        format!("{p}x")
    } else {
        sample(&regex_syntax::parse(text("regex").unwrap()).unwrap())
    };
    // exercise case-insensitivity: the advertisement says it in capitals
    if n.get("case") == Some(&json!("insensitive")) {
        base.to_uppercase()
    } else {
        base
    }
}

type Leaf = Value;

/// Disjunctive normal form over the positive leaves of a rule: each clause is
/// a set of leaves that together are meant to satisfy the rule. `not` subtrees
/// add no requirement.
fn clauses(node: &Value) -> Vec<Vec<Leaf>> {
    if let Some(children) = node.get("all") {
        let mut acc = vec![vec![]];
        for child in children.as_array().unwrap() {
            let alts = clauses(child);
            acc = acc
                .iter()
                .flat_map(|a| alts.iter().map(move |b| [a.clone(), b.clone()].concat()))
                .collect();
        }
        acc
    } else if let Some(children) = node.get("any") {
        children
            .as_array()
            .unwrap()
            .iter()
            .flat_map(clauses)
            .collect()
    } else if node.get("not").is_some() {
        vec![vec![]]
    } else {
        vec![vec![node.clone()]]
    }
}

fn leaf_kind(leaf: &Leaf) -> &'static str {
    ["name", "company", "uuid", "struct", "shape", "addr"]
        .into_iter()
        .find(|k| leaf.get(*k).is_some())
        .expect("a leaf condition")
}

fn adtypes(v: &Value) -> Vec<u8> {
    match v {
        Value::Array(a) => a.iter().map(|x| x.as_u64().unwrap() as u8).collect(),
        n => vec![n.as_u64().unwrap() as u8],
    }
}

fn uuid_width(uuid: &str) -> usize {
    match uuid.len() {
        4 => 2,
        8 => 4,
        _ => 16,
    }
}

fn ad_for_width(ads: &[u8], width: usize) -> Option<u8> {
    ads.iter().copied().find(|a| match width {
        2 => matches!(a, 0x02 | 0x03 | 0x16),
        4 => matches!(a, 0x04 | 0x05 | 0x20),
        _ => matches!(a, 0x06 | 0x07 | 0x21),
    })
}

fn is_service_data(ad: u8) -> bool {
    matches!(ad, 0x16 | 0x20 | 0x21)
}

fn struct_for(leaf: &Leaf) -> Option<(u8, Vec<u8>)> {
    let s = &leaf["struct"];
    let ad = adtypes(&s["ad"])[0];
    let mut data = Vec::new();
    if ad == 0xFF {
        data.extend_from_slice(
            &s.get("company")
                .and_then(Value::as_u64)
                .unwrap_or(0x1234)
                .to_le_bytes()[..2],
        );
    } else if let Some(w) = match ad {
        0x16 => Some(2),
        0x20 => Some(4),
        0x21 => Some(16),
        _ => None,
    } {
        match s.get("uuid").and_then(Value::as_str) {
            Some(u) => data.extend(le_uuid(u)),
            None => data.extend(vec![0xEE; w]),
        }
    }
    let mut payload = Vec::new();
    if let Some(b) = s.get("bytes") {
        let off = b.get("offset").and_then(Value::as_u64).unwrap_or(0) as usize;
        payload = vec![0u8; off];
        payload.extend(hex(b["value"].as_str().unwrap()));
    }
    if let Some(len) = s.get("len") {
        let (lo, hi) = match len {
            Value::Array(a) => (
                a[0].as_u64().unwrap() as usize,
                a[1].as_u64().unwrap() as usize,
            ),
            n => (n.as_u64().unwrap() as usize, n.as_u64().unwrap() as usize),
        };
        if payload.len() > hi {
            return None;
        }
        payload.resize(payload.len().max(lo), 0x11);
    }
    data.extend(payload);
    Some((ad, data))
}

fn shape_structs(leaf: &Leaf) -> Vec<(u8, Vec<u8>)> {
    leaf["shape"]["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|i| {
            let (ad, len) = i.as_str().unwrap().split_once('/').unwrap();
            let n = match len {
                "*" => 2,
                r if r.contains('-') => r.split_once('-').unwrap().0.parse().unwrap(),
                n => n.parse().unwrap(),
            };
            (u8::from_str_radix(ad, 16).unwrap(), vec![0x41; n])
        })
        .collect()
}

/// An advertisement meant to satisfy every leaf of a clause.
fn positive(clause: &[Leaf], label: &str) -> Option<Ad> {
    let mut ad = Ad {
        structs: vec![],
        addr: None,
        addr_type: None,
        label: label.into(),
    };
    let shapes: Vec<&Leaf> = clause.iter().filter(|l| leaf_kind(l) == "shape").collect();
    if shapes.len() > 1 {
        return None;
    }
    if let Some(shape) = shapes.first() {
        // a closed shape fixes the whole advertisement; other leaves would have to merge into it
        if shape["shape"]["rest"] == false && clause.len() > 1 {
            return None;
        }
        ad.structs = shape_structs(shape);
    } else {
        ad.structs.push(flags());
    }
    for leaf in clause {
        match leaf_kind(leaf) {
            "shape" => {}
            "name" => {
                ad.structs
                    .push((0x09, name_for(&leaf["name"]).into_bytes()));
            }
            "company" => {
                let c = leaf["company"][0].as_u64().unwrap() as u16;
                let mut d = c.to_le_bytes().to_vec();
                d.extend([1, 2, 3]);
                ad.structs.push((0xFF, d));
            }
            "uuid" => {
                let u = leaf["uuid"]["in"][0].as_str().unwrap();
                let t = ad_for_width(&adtypes(&leaf["uuid"]["ad"]), uuid_width(u))?;
                let mut d = le_uuid(u);
                if is_service_data(t) {
                    d.push(0xAB);
                }
                ad.structs.push((t, d));
            }
            "struct" => ad.structs.push(struct_for(leaf)?),
            "addr" => {
                let a = &leaf["addr"];
                let oui = a
                    .get("oui")
                    .map(|o| o[0].as_str().unwrap())
                    .unwrap_or("123456");
                ad.addr = Some(format!(
                    "{}:{}:{}:00:00:01",
                    &oui[0..2],
                    &oui[2..4],
                    &oui[4..6]
                ));
                ad.addr_type = Some(match a.get("type").and_then(Value::as_str) {
                    Some("random") => AdvAddressType::Random,
                    _ => AdvAddressType::Public,
                });
            }
            other => unreachable!("{other}"),
        }
    }
    Some(ad)
}

/// A variant of a single-leaf clause that breaks exactly that leaf.
fn near_miss(leaf: &Leaf, label: &str) -> Option<Ad> {
    let mut ad = Ad {
        structs: vec![flags()],
        addr: None,
        addr_type: None,
        label: label.into(),
    };
    match leaf_kind(leaf) {
        "name" => ad.structs.push((0x09, b"~no-match~".to_vec())),
        "company" => {
            let used: Vec<u64> = leaf["company"]
                .as_array()
                .unwrap()
                .iter()
                .map(|c| c.as_u64().unwrap())
                .collect();
            let c = (0xFF00u64..).find(|c| !used.contains(c)).unwrap() as u16;
            ad.structs.push((0xFF, c.to_le_bytes().to_vec()));
        }
        "uuid" => {
            let u = leaf["uuid"]["in"][0].as_str().unwrap();
            let t = ad_for_width(&adtypes(&leaf["uuid"]["ad"]), uuid_width(u))?;
            // right structure type, wrong UUID; and the right UUID in a structure that does not count
            ad.structs.push((t, vec![0xEE; uuid_width(u)]));
            let other = [0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x16, 0x20, 0x21]
                .into_iter()
                .find(|t| {
                    !adtypes(&leaf["uuid"]["ad"]).contains(t)
                        && ad_for_width(&[*t], uuid_width(u)).is_some()
                })?;
            let mut d = le_uuid(u);
            if is_service_data(other) {
                d.push(0xAB);
            }
            ad.structs.push((other, d));
        }
        "struct" => {
            let s = &leaf["struct"];
            let mut st = struct_for(leaf)?;
            let base = if st.0 == 0xFF {
                2
            } else if is_service_data(st.0) {
                uuid_width_of(st.0)
            } else {
                0
            };
            if let Some(b) = s.get("bytes") {
                let off = b.get("offset").and_then(Value::as_u64).unwrap_or(0) as usize;
                let mask = b.get("mask").map(|m| hex(m.as_str().unwrap()));
                let i = (0..hex(b["value"].as_str().unwrap()).len())
                    .find(|i| mask.as_ref().is_none_or(|m| m[*i] != 0))?;
                st.1[base + off + i] ^= 0xFF;
            } else if let Some(len) = s.get("len") {
                let hi = match len {
                    Value::Array(a) => a[1].as_u64().unwrap() as usize,
                    n => n.as_u64().unwrap() as usize,
                };
                st.1.resize(base + hi + 1, 0x11);
            } else if let Some(u) = s.get("uuid").and_then(Value::as_str) {
                st.1[..uuid_width(u)].fill(0xEE);
            } else if let Some(c) = s.get("company").and_then(Value::as_u64) {
                st.1[..2].copy_from_slice(&((c as u16) ^ 0xFF).to_le_bytes());
            } else {
                st.0 = 0x30;
            }
            ad.structs.push(st);
        }
        "shape" => {
            let mut structs = shape_structs(leaf);
            if leaf["shape"]["rest"] == false {
                structs.push((0x30, vec![1]));
            } else {
                structs[0].0 = 0x30;
            }
            ad.structs = structs;
        }
        "addr" => {
            let a = &leaf["addr"];
            if a.get("oui").is_some() {
                ad.addr = Some("FE:DC:BA:00:00:01".into());
                ad.addr_type = Some(AdvAddressType::Public);
            } else {
                ad.addr = Some("12:34:56:00:00:01".into());
                ad.addr_type = Some(if a["type"] == "random" {
                    AdvAddressType::Public
                } else {
                    AdvAddressType::Random
                });
            }
        }
        other => unreachable!("{other}"),
    }
    Some(ad)
}

fn uuid_width_of(ad: u8) -> usize {
    match ad {
        0x16 => 2,
        0x20 => 4,
        _ => 16,
    }
}

fn unrelated() -> Vec<Ad> {
    let mk = |label: &str, structs: Vec<(u8, Vec<u8>)>, addr: Option<&str>, ty| Ad {
        structs,
        addr: addr.map(Into::into),
        addr_type: ty,
        label: label.into(),
    };
    vec![
        mk("flags only", vec![flags()], None, None),
        mk("empty advertisement", vec![], None, None),
        mk(
            "unrelated vendor",
            vec![
                flags(),
                (0xFF, vec![0xFE, 0xFF, 1, 2, 3]),
                (0x09, b"~unrelated~".to_vec()),
            ],
            Some("10:20:30:40:50:60"),
            Some(AdvAddressType::Random),
        ),
    ]
}

/// Clauses whose near miss still satisfies a broader clause of the same rule: Eddystone's
/// UID frame (`struct` with a frame byte) is a special case of "any FEAA service data".
const OVERLAPPING_CLAUSES: [&str; 1] = ["protocol--eddystone clause 1"];

struct Synthetic {
    page: String,
    rule: Value,
    positives: Vec<Ad>,
    negatives: Vec<Ad>,
}

fn synthesize() -> Vec<Synthetic> {
    let b = bundle();
    let mut out = Vec::new();
    for p in b["pages"]
        .as_array()
        .unwrap()
        .iter()
        .filter(|p| p["has_rule"] == true)
    {
        let id = p["id"].as_str().unwrap();
        let rule = p["rule"].clone();
        let cl = clauses(&rule);
        let mut positives = Vec::new();
        let mut negatives = unrelated();
        for (i, clause) in cl.iter().enumerate() {
            let label = format!("{id} clause {i}");
            let ad = positive(clause, &format!("{label} positive"))
                .unwrap_or_else(|| panic!("{label}: no synthetic advertisement for {clause:?}"));
            positives.push(ad);
            if OVERLAPPING_CLAUSES.contains(&label.as_str()) {
                // breaking this clause leaves another clause of the same rule satisfied
            } else if clause.len() == 1 {
                negatives.extend(near_miss(&clause[0], &format!("{label} near miss")));
            } else {
                for skip in 0..clause.len() {
                    let rest: Vec<Leaf> = clause
                        .iter()
                        .enumerate()
                        .filter(|(j, _)| *j != skip)
                        .map(|(_, l)| l.clone())
                        .collect();
                    negatives.extend(positive(&rest, &format!("{label} without leaf {skip}")));
                }
            }
        }
        out.push(Synthetic {
            page: id.into(),
            rule,
            positives,
            negatives,
        });
    }
    out
}

#[test]
fn every_rule_matches_its_positive_and_rejects_its_negative_advertisements() {
    let matcher = RuleMatcher::from_bundle_json(BUNDLE.to_string()).unwrap();
    let synthetic = synthesize();
    assert_eq!(synthetic.len(), 27);
    let mut positives = 0;
    let mut negatives = 0;
    for s in &synthetic {
        assert!(!s.positives.is_empty(), "{}", s.page);
        for ad in &s.positives {
            let got = matcher.match_advertisement(ad.raw(), ad.addr.clone(), ad.addr_type);
            assert!(
                got.contains(&s.page),
                "{}: {:?} should match, matched {got:?}",
                ad.label,
                hex::encode(ad.raw())
            );
            assert!(
                eval(&s.rule, &ad.raw(), ad.addr.as_deref(), ad.addr_type),
                "{}",
                ad.label
            );
            positives += 1;
        }
        for ad in &s.negatives {
            let got = matcher.match_advertisement(ad.raw(), ad.addr.clone(), ad.addr_type);
            assert!(
                !got.contains(&s.page),
                "{}: {:?} must not match, matched {got:?}",
                ad.label,
                hex::encode(ad.raw())
            );
            negatives += 1;
        }
    }
    // the generators cover every clause of every rule (a regression guard for the generators themselves)
    assert!(positives >= 27 * 2, "{positives}");
    assert!(negatives >= 27 * 4, "{negatives}");
}

// Python cross-check ----------------------------------------------------------------

fn python(request: Value) -> Vec<bool> {
    let script = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/python/reference_rules.py"
    );
    let mut child = Command::new("python3")
        .args(["-I", script])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect(
            "python3 is required for the cross-check (mise installs it at the repository root)",
        );
    child
        .stdin
        .take()
        .unwrap()
        .write_all(request.to_string().as_bytes())
        .unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(
        output.status.success(),
        "python failed: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    serde_json::from_slice(&output.stdout).unwrap()
}

/// An extended advertisement of 3 x 201-byte manufacturer structures, flags and a name: over 600 bytes.
fn extended_advertisement() -> Vec<u8> {
    let mut raw = hex("020106");
    for i in 0..3u8 {
        raw.extend([201, 0xFF, 0x4C, 0x00]);
        raw.extend(vec![i; 198]);
    }
    raw.extend([12, 0x09]);
    raw.extend(b"Extended-ID");
    raw
}

#[test]
fn extended_advertisements_beyond_255_bytes_parse_in_full() {
    let raw = extended_advertisement();
    assert_eq!(raw.len(), 3 + 3 * 202 + 13);
    assert_eq!(structures(&raw).len(), 5);
    assert!(eval(
        &json!({"name": {"equals": "Extended-ID"}}),
        &raw,
        None,
        None
    ));
    let shape =
        json!({"shape": {"items": ["01/1", "FF/200", "FF/200", "FF/200", "09/11"], "rest": false}});
    assert!(eval(&shape, &raw, None, None));
    // the last manufacturer structure is the one filled with 2
    let last = json!({"struct": {"ad": 255, "company": 76, "len": 198, "bytes": {"offset": 197, "value": "02"}}});
    assert!(eval(&last, &raw, None, None));
    let first_only = json!({"struct": {"ad": 255, "bytes": {"offset": 0, "value": "05"}}});
    assert!(!eval(&first_only, &raw, None, None));
    // cut inside the name: the structures before it still count, the name does not
    let cut = &raw[..raw.len() - 4];
    assert_eq!(structures(cut).len(), 4);
    assert!(eval(&last, cut, None, None));
    assert!(!eval(&json!({"name": {"prefix": "Ext"}}), cut, None, None));
    // the longest possible structure: 255 bytes after the length byte
    let mut long = vec![255u8, 0x09];
    long.extend(vec![b'a'; 254]);
    assert!(eval(
        &json!({"name": {"regex": "a{254}"}}),
        &long,
        None,
        None
    ));
}

#[test]
fn matcher_reports_whether_any_rule_uses_the_address() {
    let without = json!({"version": "x", "pages": [{"id": "a", "rule": {"company": [76]}}]});
    let with = json!({"version": "x", "pages": [
        {"id": "a", "rule": {"company": [76]}},
        {"id": "b", "rule": {"all": [{"company": [1]}, {"not": {"addr": {"type": "random"}}}]}}
    ]});
    assert!(
        !RuleMatcher::from_bundle_json(without.to_string())
            .unwrap()
            .uses_address_rules()
    );
    assert!(
        RuleMatcher::from_bundle_json(with.to_string())
            .unwrap()
            .uses_address_rules()
    );
    assert!(
        !RuleMatcher::from_bundle_json(BUNDLE.to_string())
            .unwrap()
            .uses_address_rules()
    );
}

/// (pattern, Some(expected)) where the grammar decides; None where only agreement is required.
const REGEX_CORPUS: &[(&str, Option<bool>)] = &[
    ("abc", Some(true)),
    ("ResMed \\d{6}", Some(true)),
    ("ihoment_H[0-9]{4}_[0-9A-Fa-f]{2}", Some(true)),
    ("a|b", Some(true)),
    ("(ab)+c?", Some(true)),
    ("x*?", Some(true)),
    ("[a-c]+", Some(true)),
    ("[^0-9]", Some(true)),
    ("[\\d.]", Some(true)),
    ("[\\-a]", Some(true)),
    ("a{2,}", Some(true)),
    ("[a&&b]", Some(false)),
    ("[a~~b]", Some(false)),
    ("[a--b]", Some(false)),
    ("[a||b]", Some(false)),
    ("[\\w]", Some(false)),
    ("\\w", Some(false)),
    ("[[a]]", Some(false)),
    ("[a[b]]", Some(false)),
    ("[[:alpha:]]", Some(false)),
    ("a*+", Some(false)),
    ("a++", Some(false)),
    ("a?+", Some(false)),
    ("a{2}+", Some(false)),
    ("a**", Some(false)),
    ("a*{2}", Some(false)),
    ("(?i)a", Some(false)),
    ("(?=a)b", Some(false)),
    ("(a)\\1", Some(false)),
    ("[]a]", Some(false)),
    ("a{", Some(false)),
    ("[a-]", None),
    ("[-a]", None),
    ("[a\\-z]", None),
    ("a{,3}", None),
    ("a{2", None),
    ("[\\d-z]", None),
    ("[a-\\d]", None),
    ("[z-a]", None),
    ("\\.", None),
    ("\\ ", None),
    ("a|", None),
    ("|a", None),
    ("()", None),
    ("(|a)", None),
    ("[^]a]", None),
    ("[\\]]", None),
    ("é+", None),
    ("[é-ü]", None),
    ("a&&b", None),
    ("a~~b", None),
    ("a--b", None),
    ("[&]", None),
    ("[~]", None),
    ("[|]", None),
    ("[{}()]", None),
    ("(a|b)*?c", None),
    ("a??", None),
    ("a+?", None),
    ("a{1,2}?", None),
    ("a{1,2}??", None),
    ("a{1}{2}", None),
];

#[test]
fn regex_grammar_agrees_with_python_and_rejects_the_divergent_corners() {
    let patterns: Vec<&str> = REGEX_CORPUS.iter().map(|(p, _)| *p).collect();
    let py = python(json!({ "regexes": patterns }));
    assert_eq!(py.len(), REGEX_CORPUS.len());
    for ((pattern, expected), py) in REGEX_CORPUS.iter().zip(py) {
        let rust = regex_ok(pattern);
        assert_eq!(rust, py, "Rust and Python disagree on {pattern:?}");
        if let Some(expected) = expected {
            assert_eq!(rust, *expected, "{pattern:?}");
        }
    }
}

#[test]
fn rust_and_python_evaluators_agree_on_every_rule_and_advertisement() {
    let synthetic = synthesize();
    let cases: Vec<Value> = serde_json::from_str(CASES).unwrap();

    let mut ads: Vec<(Vec<u8>, Option<String>, Option<AdvAddressType>)> = Vec::new();
    for s in &synthetic {
        for ad in s.positives.iter().chain(&s.negatives) {
            ads.push((ad.raw(), ad.addr.clone(), ad.addr_type));
        }
    }
    for case in &cases {
        for ad in case["advertisements"].as_array().unwrap() {
            ads.push((
                hex(ad["hex"].as_str().unwrap()),
                ad["addr"].as_str().map(Into::into),
                None,
            ));
        }
    }
    // framing edge cases
    for h in [
        "",
        "00",
        "0201060000",
        "05",
        "0309",
        "ff",
        "02010603094142",
        "0309414200ff",
        "0a0941424300",
    ] {
        ads.push((hex(h), None, None));
    }
    let extended = extended_advertisement();
    ads.push((extended[..extended.len() - 4].to_vec(), None, None));
    ads.push((extended, None, None));
    ads.sort_by(|a, b| (&a.0, &a.1).cmp(&(&b.0, &b.1)));
    ads.dedup_by(|a, b| a.0 == b.0 && a.1 == b.1 && a.2 == b.2);

    let mut rules: Vec<Value> = synthetic.iter().map(|s| s.rule.clone()).collect();
    rules.extend(cases.iter().map(|c| c["rule"].clone()));
    rules.push(json!({"not": {"company": [76]}}));
    rules.push(json!({"name": {"equals": "Extended-ID"}}));
    rules.push(json!({"struct": {"ad": 255, "company": 76, "len": 198, "bytes": {"offset": 197, "value": "02"}}}));
    rules.push(
        json!({"shape": {"items": ["01/1", "FF/200", "FF/200", "FF/200", "09/11"], "rest": false}}),
    );
    rules.push(json!({"all": [{"addr": {"type": "random"}}, {"not": {"name": {"prefix": "~"}}}]}));

    let mut requests = Vec::new();
    let mut expected_rust = Vec::new();
    for rule in &rules {
        for (raw, addr, ty) in &ads {
            requests.push(json!({
                "rule": rule, "hex": hex::encode(raw), "addr": addr,
                "addr_type": ty.map(|t| match t { AdvAddressType::Public => "public", AdvAddressType::Random => "random" }),
            }));
            let node: Node = serde_json::from_value(rule.clone()).unwrap();
            let ctx = AddrContext {
                addr: addr.as_deref(),
                addr_type: ty.map(|t| match t {
                    AdvAddressType::Public => AddrType::Public,
                    AdvAddressType::Random => AddrType::Random,
                }),
            };
            expected_rust.push(compile(&node).unwrap().eval(&structures(raw), &ctx));
        }
    }

    let python = python(json!({"cases": requests}));
    assert_eq!(python.len(), expected_rust.len());
    let disagreements: BTreeSet<String> = (0..python.len())
        .filter(|i| python[*i] != expected_rust[*i])
        .map(|i| {
            format!(
                "rust={} python={} {}",
                expected_rust[i], python[i], requests[i]
            )
        })
        .collect();
    assert!(
        disagreements.is_empty(),
        "{} disagreements, first: {:?}",
        disagreements.len(),
        disagreements.iter().next()
    );
    // a cross-check that never sees a match proves nothing
    let matched = python.iter().filter(|b| **b).count();
    assert!(
        matched > 100 && matched < python.len() / 2,
        "{matched} of {}",
        python.len()
    );
}
