//! The catalog: the app's wiki bundle, schema 2 (`docs/v2-api.md` §3.4), and the SIG company names.

use std::collections::{BTreeSet, HashMap};
use std::sync::Arc;

use serde::Deserialize;

use super::error::CoreError;
use super::types::{DeviceFilter, Identification, PageKind, PageRef};
use crate::matcher::{AddrContext, AddrType, MATCHER_ENGINE_VERSION, RuleMatcher, Structure};

/// §3.4: the Trackers preset (owner decision).
pub(crate) const TRACKER_CATEGORIES: &[&str] = &["asset-tracker"];
pub(crate) const WIKI_URL: &str = "https://wirelesswiki.org/p/";
pub(crate) const UNIDENTIFIED: &str = "unidentified";

#[derive(Deserialize)]
struct BundleJson {
    schema: u32,
    version: String,
    #[serde(default)]
    wiki_commit: Option<String>,
    rules_version: String,
    #[serde(default)]
    vocabularies: Option<Vocabularies>,
    /// Schema 1 only: `[{id, label}]`, the category vocabulary.
    #[serde(default)]
    categories: Vec<VocabJson>,
    pages: Vec<PageJson>,
}

#[derive(Deserialize)]
struct Vocabularies {
    categories: Vec<VocabJson>,
    #[serde(default)]
    contexts: Vec<VocabJson>,
    #[serde(default)]
    traits: Vec<VocabJson>,
}

#[derive(Deserialize)]
struct VocabJson {
    id: String,
    /// Schema 2 `title`, schema 1 `label`.
    #[serde(alias = "label")]
    title: String,
}

#[derive(Deserialize)]
struct PageJson {
    id: String,
    kind: String,
    title: String,
    #[serde(default)]
    one_line: Option<String>,
    #[serde(default)]
    categories: Vec<String>,
    #[serde(default)]
    contexts: Vec<String>,
    #[serde(default)]
    traits: Vec<String>,
    #[serde(default)]
    decoder: Option<serde_json::Value>,
    #[serde(default)]
    vulnerabilities: Vec<String>,
    #[serde(default)]
    icon: Option<IconJson>,
}

#[derive(Clone, Debug, Deserialize)]
pub(crate) struct IconJson {
    pub asset: String,
    pub source: String,
    pub license: String,
    #[serde(default)]
    pub author: Option<String>,
}

#[derive(Clone, Debug)]
pub(crate) struct Page {
    pub id: String,
    pub kind: PageKind,
    pub title: String,
    pub one_line: Option<String>,
    pub categories: Vec<String>,
    pub traits: Vec<String>,
    /// The page has a decoder.js.
    pub readable: bool,
    pub vulnerabilities: Vec<String>,
    pub icon: Option<IconJson>,
    pub tracker: bool,
}

impl Page {
    pub fn page_ref(&self) -> PageRef {
        PageRef {
            id: self.id.clone(),
            kind: self.kind,
            title: self.title.clone(),
        }
    }
    pub fn vulnerable(&self) -> bool {
        !self.vulnerabilities.is_empty()
    }
}

/// Which pages a filter allows (§3.6): no constraint, `page_id IN (…)`, `page_id IS NULL`, or nothing.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum PageSet {
    Any,
    Only(BTreeSet<String>),
    Unidentified,
    Nothing,
}

pub(crate) struct Catalog {
    pub pages: Vec<Page>,
    index: HashMap<String, usize>,
    /// Category id → title, in vocabulary order.
    pub categories: Vec<(String, String)>,
    pub matcher: Arc<RuleMatcher>,
    /// `meta.rules_key`: `<rules_version>+m<engine>`.
    pub rules_key: String,
    pub version: String,
    pub wiki_commit: Option<String>,
    company_names: HashMap<u16, String>,
    uuid16_names: HashMap<u16, String>,
    appearance_names: HashMap<u16, String>,
}

fn invalid(detail: impl Into<String>) -> CoreError {
    CoreError::InvalidBundle {
        detail: detail.into(),
    }
}

impl Catalog {
    pub fn load(bundle_json: &str, sig_numbers_tsv: &str) -> Result<Self, CoreError> {
        let mut bundle: BundleJson =
            serde_json::from_str(bundle_json).map_err(|e| invalid(e.to_string()))?;
        // Schema 1 (the v3 app's bundle) is read until the schema-2 export ships: its `categories` list is the
        // category vocabulary, and it has no contexts, traits, decoders or vulnerability pages.
        let vocabularies = match (bundle.schema, bundle.vocabularies.take()) {
            (2, Some(v)) => v,
            (1, None) => Vocabularies {
                categories: std::mem::take(&mut bundle.categories),
                contexts: Vec::new(),
                traits: Vec::new(),
            },
            (schema, _) => return Err(invalid(format!("unsupported bundle schema {schema}"))),
        };
        let vocab = |v: &[VocabJson]| v.iter().map(|x| x.id.clone()).collect::<BTreeSet<_>>();
        let (cats, contexts, traits) = (
            vocab(&vocabularies.categories),
            vocab(&vocabularies.contexts),
            vocab(&vocabularies.traits),
        );
        let ids: BTreeSet<&str> = bundle.pages.iter().map(|p| p.id.as_str()).collect();
        let mut pages = Vec::with_capacity(bundle.pages.len());
        for p in &bundle.pages {
            let kind = match p.kind.as_str() {
                "device" => PageKind::Device,
                "platform" => PageKind::Platform,
                "identifier" => PageKind::Identifier,
                "vulnerability" => PageKind::Vulnerability,
                other => return Err(invalid(format!("{}: unknown kind {other}", p.id))),
            };
            for (field, values, known) in [
                ("category", &p.categories, &cats),
                ("context", &p.contexts, &contexts),
                ("trait", &p.traits, &traits),
            ] {
                if let Some(bad) = values.iter().find(|v| !known.contains(*v)) {
                    return Err(invalid(format!("{}: unknown {field} {bad}", p.id)));
                }
            }
            if let Some(bad) = p.vulnerabilities.iter().find(|v| !ids.contains(v.as_str())) {
                return Err(invalid(format!(
                    "{}: unknown vulnerability page {bad}",
                    p.id
                )));
            }
            pages.push(Page {
                id: p.id.clone(),
                kind,
                title: p.title.clone(),
                one_line: p.one_line.clone(),
                categories: p.categories.clone(),
                traits: p.traits.clone(),
                readable: p.decoder.as_ref().is_some_and(|d| !d.is_null()),
                vulnerabilities: p.vulnerabilities.clone(),
                icon: p.icon.clone(),
                tracker: p
                    .categories
                    .iter()
                    .any(|c| TRACKER_CATEGORIES.contains(&c.as_str())),
            });
        }
        let matcher = RuleMatcher::from_bundle_json(bundle_json.to_owned())
            .map_err(|e| invalid(e.to_string()))?;
        let index = pages
            .iter()
            .enumerate()
            .map(|(i, p)| (p.id.clone(), i))
            .collect();
        Ok(Self {
            pages,
            index,
            categories: vocabularies
                .categories
                .iter()
                .map(|c| (c.id.clone(), c.title.clone()))
                .collect(),
            matcher,
            rules_key: format!("{}+m{MATCHER_ENGINE_VERSION}", bundle.rules_version),
            version: bundle.version,
            wiki_commit: bundle.wiki_commit,
            company_names: parse_sig(sig_numbers_tsv, "company"),
            uuid16_names: parse_sig(sig_numbers_tsv, "uuid16"),
            appearance_names: parse_sig(sig_numbers_tsv, "appearance"),
        })
    }

    pub fn page(&self, id: &str) -> Option<&Page> {
        self.index.get(id).map(|&i| &self.pages[i])
    }

    /// Bundle order of a page (the initial card order, §2.5).
    pub fn order(&self, id: &str) -> usize {
        self.index.get(id).copied().unwrap_or(usize::MAX)
    }

    pub fn category_title(&self, id: &str) -> String {
        self.categories
            .iter()
            .find(|(c, _)| c == id)
            .map_or_else(|| id.to_owned(), |(_, t)| t.clone())
    }

    pub fn category_order(&self, id: &str) -> usize {
        self.categories
            .iter()
            .position(|(c, _)| c == id)
            .unwrap_or(usize::MAX)
    }

    pub fn company_name(&self, id: u16) -> Option<String> {
        self.company_names.get(&id).cloned()
    }

    /// The SIG name of a 16-bit UUID (services and member UUIDs).
    pub fn uuid16_name(&self, uuid: u16) -> Option<String> {
        self.uuid16_names.get(&uuid).cloned()
    }

    /// The SIG appearance name: the exact value, else its category (`value & 0xFFC0`).
    pub fn appearance_name(&self, value: u16) -> Option<String> {
        self.appearance_names
            .get(&value)
            .or_else(|| self.appearance_names.get(&(value & 0xFFC0)))
            .cloned()
    }

    /// §3.5 step 1: the first match of kind device, else platform, else identifier.
    pub fn page_of_payload(
        &self,
        structures: &[Structure],
        addr: Option<&str>,
        addr_type: Option<AddrType>,
    ) -> Option<String> {
        let matches = self
            .matcher
            .match_structures(structures, &AddrContext { addr, addr_type });
        [PageKind::Device, PageKind::Platform, PageKind::Identifier]
            .iter()
            .find_map(|kind| {
                matches
                    .iter()
                    .find(|id| self.page(id).is_some_and(|p| p.kind == *kind))
                    .cloned()
            })
    }

    /// §3.6 `:pages`: the intersection of the page sets of `categories` (any-of), `readable`, `trackers`,
    /// `vulnerable`, `page` and `Identified`; `Unidentified` is `page_id IS NULL`, and empty with any page
    /// constraint. `with_categories = false` leaves the category facet out (faceted counts, D-3a).
    pub fn page_set(&self, f: &DeviceFilter, with_categories: bool) -> PageSet {
        let all = || {
            self.pages
                .iter()
                .map(|p| p.id.clone())
                .collect::<BTreeSet<_>>()
        };
        let mut set: Option<BTreeSet<String>> = None;
        let mut and = |s: BTreeSet<String>| {
            set = Some(match set.take() {
                None => s,
                Some(prev) => prev.intersection(&s).cloned().collect(),
            });
        };
        let pick = |pred: &dyn Fn(&Page) -> bool| {
            self.pages
                .iter()
                .filter(|p| pred(p))
                .map(|p| p.id.clone())
                .collect::<BTreeSet<_>>()
        };
        if with_categories && !f.categories.is_empty() {
            and(pick(&|p| {
                p.categories.iter().any(|c| f.categories.contains(c))
            }));
        }
        if f.readable {
            and(pick(&|p| p.readable));
        }
        if f.trackers {
            and(pick(&|p| p.tracker));
        }
        if f.vulnerable {
            and(pick(&|p| p.vulnerable()));
        }
        if let Some(page) = &f.page {
            and(BTreeSet::from([page.clone()]));
        }
        match f.identification {
            Identification::Any => {}
            Identification::Identified => and(all()),
            Identification::Unidentified => {
                return if set.is_some() {
                    PageSet::Nothing
                } else {
                    PageSet::Unidentified
                };
            }
        }
        match set {
            None => PageSet::Any,
            Some(s) if s.is_empty() => PageSet::Nothing,
            Some(s) => PageSet::Only(s),
        }
    }
}

/// `sig-numbers.tsv`: `<table><TAB>004C<TAB>Name` lines of one table (`company`, `uuid16`, `appearance`).
fn parse_sig(tsv: &str, table: &str) -> HashMap<u16, String> {
    tsv.lines()
        .filter_map(|line| {
            let mut cols = line.split('\t');
            (cols.next()? == table).then_some(())?;
            let id = u16::from_str_radix(cols.next()?, 16).ok()?;
            Some((id, cols.next()?.to_owned()))
        })
        .collect()
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    pub(crate) fn bundle() -> String {
        serde_json::json!({
            "schema": 2, "version": "test", "wiki_commit": "abc", "rules_version": "r1",
            "decoder_interface_version": 1,
            "vocabularies": {
                "categories": [{"id": "asset-tracker", "title": "Asset tracker"}, {"id": "audio-device", "title": "Audio device"}],
                "contexts": [], "traits": [{"id": "no-local-name", "title": "No local name"}]
            },
            "pages": [
                {"id": "device--tile", "kind": "device", "title": "Tile", "categories": ["asset-tracker"],
                 "traits": ["no-local-name"], "rule": {"uuid": {"in": ["FEED"], "ad": [3, 22]}}, "decoder": null,
                 "vulnerabilities": []},
                {"id": "platform--x", "kind": "platform", "title": "X", "categories": ["audio-device"],
                 "rule": {"uuid": {"in": ["FEED"], "ad": [3, 22]}}, "decoder": {"sha256": "00", "source": ""},
                 "vulnerabilities": ["vulnerability--v"]},
                {"id": "vulnerability--v", "kind": "vulnerability", "title": "V", "categories": [], "rule": null}
            ]
        })
        .to_string()
    }

    #[test]
    fn loads_and_builds_page_sets() {
        let c = Catalog::load(
            &bundle(),
            "company\t004C\tApple, Inc.\nuuid16\tFEED\tTile\n",
        )
        .unwrap();
        assert_eq!(c.rules_key, "r1+m1");
        assert_eq!(c.company_name(76).as_deref(), Some("Apple, Inc."));
        let only = |ids: &[&str]| PageSet::Only(ids.iter().map(|s| s.to_string()).collect());
        let f = DeviceFilter::default();
        assert_eq!(c.page_set(&f, true), PageSet::Any);
        let trackers = DeviceFilter {
            trackers: true,
            ..Default::default()
        };
        assert_eq!(c.page_set(&trackers, true), only(&["device--tile"]));
        let readable_trackers = DeviceFilter {
            readable: true,
            ..trackers.clone()
        };
        assert_eq!(c.page_set(&readable_trackers, true), PageSet::Nothing);
        let cat = DeviceFilter {
            categories: vec!["audio-device".into()],
            ..Default::default()
        };
        assert_eq!(c.page_set(&cat, true), only(&["platform--x"]));
        assert_eq!(c.page_set(&cat, false), PageSet::Any);
        let unid = DeviceFilter {
            identification: Identification::Unidentified,
            ..Default::default()
        };
        assert_eq!(c.page_set(&unid, true), PageSet::Unidentified);
        let unid_vuln = DeviceFilter {
            vulnerable: true,
            ..unid
        };
        assert_eq!(c.page_set(&unid_vuln, true), PageSet::Nothing);
        // a device page wins over a platform page that also matches (§3.5)
        let raw = [3u8, 3, 0xed, 0xfe];
        let page = c.page_of_payload(&crate::matcher::structures(&raw), None, None);
        assert_eq!(page.as_deref(), Some("device--tile"));
    }

    /// The v3 app's shipped bundle (schema 1) loads: its categories are the vocabulary, its rules compile.
    #[test]
    fn reads_the_shipped_schema_1_bundle() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../scanner/app/src/main/assets/wiki-bundle.json"
        );
        let c = Catalog::load(&std::fs::read_to_string(path).unwrap(), "").unwrap();
        assert!(c.matcher.rule_count() > 0);
        assert!(!c.categories.is_empty());
        assert!(c.pages.iter().all(|p| {
            p.categories
                .iter()
                .all(|x| c.categories.iter().any(|(id, _)| id == x))
        }));
    }

    #[test]
    fn vocabulary_violations_are_refused() {
        let bad = bundle().replace("\"no-local-name\"]", "\"nope\"]");
        assert!(matches!(
            Catalog::load(&bad, ""),
            Err(CoreError::InvalidBundle { .. })
        ));
        let schema3 = bundle().replace("\"schema\":2", "\"schema\":3");
        assert!(matches!(
            Catalog::load(&schema3, ""),
            Err(CoreError::InvalidBundle { .. })
        ));
    }
}
