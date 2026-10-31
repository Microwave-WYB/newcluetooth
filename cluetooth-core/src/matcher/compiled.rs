//! A rule compiled once (regexes built, hex decoded, shapes parsed) and its
//! evaluator. The semantics follow `kb.rules` (Python, branch `kb/lab`):
//! `validate` -> [`compile`], `evaluate` -> [`Compiled::eval`]. Differences are
//! only where the Python code is undefined (see `compile` errors).

use std::sync::LazyLock;

use regex::Regex;

use super::adv::{Structure, base_offset, is_name_type, service_data_width, uuid_list_width};
use super::rule::{AddrType, Case, Length, Node};

const NODE_FIELDS: &str = "all, any, not, name, company, uuid, struct, shape, addr";

/// The address the advertisement came from, when known.
#[derive(Debug, Clone, Copy, Default)]
pub struct AddrContext<'a> {
    /// `AA:BB:CC:DD:EE:FF` (separators and case are ignored).
    pub addr: Option<&'a str>,
    pub addr_type: Option<AddrType>,
}

#[derive(Debug)]
pub enum Compiled {
    All(Vec<Compiled>),
    Any(Vec<Compiled>),
    Not(Box<Compiled>),
    Name {
        ads: Vec<u8>,
        test: NameTest,
    },
    Company(Vec<u16>),
    Uuid {
        ads: Vec<u8>,
        want: Vec<String>,
    },
    Struct(StructTest),
    Shape {
        items: Vec<ShapeItem>,
        rest: bool,
    },
    Addr {
        oui: Option<Vec<String>>,
        addr_type: Option<AddrType>,
    },
}

#[derive(Debug)]
pub enum NameTest {
    Equals {
        want: String,
        insensitive: bool,
    },
    Prefix {
        want: String,
        insensitive: bool,
    },
    /// Anchored `^(?:regex)$`, already case-folded when insensitive.
    Regex(Regex),
}

#[derive(Debug)]
pub struct StructTest {
    ads: Vec<u8>,
    company: Option<u16>,
    uuid: Option<String>,
    len: Option<(usize, usize)>,
    bytes: Option<ByteTest>,
}

#[derive(Debug)]
struct ByteTest {
    offset: usize,
    value: Vec<u8>,
    mask: Vec<u8>,
}

#[derive(Debug)]
pub struct ShapeItem {
    ad_type: u8,
    min: usize,
    max: usize,
}

fn err<T>(message: impl Into<String>) -> Result<T, String> {
    Err(message.into())
}

pub fn compile(node: &Node) -> Result<Compiled, String> {
    let present: Vec<&str> = [
        ("all", node.all.is_some()),
        ("any", node.any.is_some()),
        ("not", node.not.is_some()),
        ("name", node.name.is_some()),
        ("company", node.company.is_some()),
        ("uuid", node.uuid.is_some()),
        ("struct", node.structure.is_some()),
        ("shape", node.shape.is_some()),
        ("addr", node.addr.is_some()),
    ]
    .into_iter()
    .filter_map(|(k, p)| p.then_some(k))
    .collect();
    if present.len() != 1 {
        return err(format!(
            "one operator or condition per node ({NODE_FIELDS}), got {present:?}"
        ));
    }
    if let Some(children) = node.all.as_ref().or(node.any.as_ref()) {
        if children.is_empty() {
            return err("all/any need a non-empty list");
        }
        let compiled = children
            .iter()
            .map(compile)
            .collect::<Result<Vec<_>, _>>()?;
        return Ok(if node.all.is_some() {
            Compiled::All(compiled)
        } else {
            Compiled::Any(compiled)
        });
    }
    if let Some(child) = &node.not {
        return Ok(Compiled::Not(Box::new(compile(child)?)));
    }
    if let Some(name) = &node.name {
        let modes = [&name.equals, &name.prefix, &name.regex]
            .iter()
            .filter(|m| m.is_some())
            .count();
        if modes != 1 {
            return err("name needs exactly one of equals, prefix, regex");
        }
        let insensitive = name.case == Some(Case::Insensitive);
        let ads = name.ad.clone().unwrap_or_else(|| vec![0x08, 0x09]);
        if ads.is_empty() || !ads.iter().all(|a| is_name_type(*a)) {
            return err("name ad is 0x08 and/or 0x09");
        }
        let test = if let Some(pattern) = &name.regex {
            if !regex_ok(pattern) {
                return err(format!("regex outside the shared subset: {pattern:?}"));
            }
            let flag = if insensitive { "(?i)" } else { "" };
            let re = Regex::new(&format!("{flag}^(?:{pattern})$"))
                .map_err(|e| format!("bad regex {pattern:?}: {e}"))?;
            NameTest::Regex(re)
        } else if let Some(want) = &name.equals {
            NameTest::Equals {
                want: fold(want, insensitive),
                insensitive,
            }
        } else {
            NameTest::Prefix {
                want: fold(name.prefix.as_deref().unwrap_or_default(), insensitive),
                insensitive,
            }
        };
        return Ok(Compiled::Name { ads, test });
    }
    if let Some(companies) = &node.company {
        if companies.is_empty() {
            return err("company needs a non-empty list");
        }
        return Ok(Compiled::Company(companies.clone()));
    }
    if let Some(uuid) = &node.uuid {
        if uuid.r#in.is_empty() || uuid.ad.is_empty() {
            return err("uuid needs in (UUIDs) and ad (AD types)");
        }
        if let Some(bad) = uuid
            .ad
            .iter()
            .find(|a| uuid_list_width(**a).is_none() && service_data_width(**a).is_none())
        {
            return err(format!("AD type {bad} does not carry UUIDs"));
        }
        for u in &uuid.r#in {
            check_uuid(u)?;
        }
        return Ok(Compiled::Uuid {
            ads: uuid.ad.clone(),
            want: uuid.r#in.clone(),
        });
    }
    if let Some(s) = &node.structure {
        return compile_struct(s).map(Compiled::Struct);
    }
    if let Some(shape) = &node.shape {
        if shape.items.is_empty() {
            return err("shape needs items");
        }
        let items = shape
            .items
            .iter()
            .map(|i| parse_shape_item(i))
            .collect::<Result<Vec<_>, _>>()?;
        return Ok(Compiled::Shape {
            items,
            rest: shape.rest.unwrap_or(true),
        });
    }
    if let Some(addr) = &node.addr {
        if addr.oui.is_none() && addr.addr_type.is_none() {
            return err("addr needs oui or type");
        }
        if let Some(ouis) = &addr.oui {
            if ouis.is_empty() {
                return err("addr oui needs a non-empty list");
            }
            for o in ouis {
                if o.len() != 6 || !o.chars().all(|c| c.is_ascii_hexdigit()) {
                    return err(format!("OUI {o:?} is six hex digits"));
                }
            }
        }
        return Ok(Compiled::Addr {
            oui: addr
                .oui
                .as_ref()
                .map(|v| v.iter().map(|o| o.to_ascii_uppercase()).collect()),
            addr_type: addr.addr_type,
        });
    }
    unreachable!("exactly one operator is present")
}

fn fold(s: &str, insensitive: bool) -> String {
    if insensitive {
        s.to_lowercase()
    } else {
        s.to_string()
    }
}

fn check_uuid(u: &str) -> Result<(), String> {
    static PATTERN: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(
            "^([0-9A-F]{4}|[0-9A-F]{8}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$",
        )
        .expect("static pattern")
    });
    if PATTERN.is_match(u) {
        Ok(())
    } else {
        err(format!("UUID {u:?} is not in canonical form"))
    }
}

fn hex_bytes(s: &str, what: &str) -> Result<Vec<u8>, String> {
    let lower = !s.is_empty()
        && s.len().is_multiple_of(2)
        && s.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'));
    if !lower {
        return err(format!("{what} is lowercase hex"));
    }
    hex::decode(s).map_err(|e| format!("{what}: {e}"))
}

fn compile_struct(s: &super::rule::StructCondition) -> Result<StructTest, String> {
    let ads = s.ad.to_vec();
    if ads.is_empty() {
        return err("struct ad is required");
    }
    let mut offsets: Vec<usize> = ads.iter().map(|a| base_offset(*a)).collect();
    offsets.sort_unstable();
    offsets.dedup();
    if offsets.len() != 1 {
        return err("AD types with different data offsets cannot share a condition");
    }
    if s.company.is_some() && ads != [0xFF] {
        return err("struct company needs ad 0xFF");
    }
    if let Some(uuid) = &s.uuid {
        if !ads.iter().all(|a| service_data_width(*a).is_some()) {
            return err("struct uuid needs a service-data type");
        }
        check_uuid(uuid)?;
    }
    let len = match &s.len {
        None => None,
        Some(Length::Exact(n)) => Some((*n as usize, *n as usize)),
        Some(Length::Range([lo, hi])) => Some((*lo as usize, *hi as usize)),
    };
    let bytes = match &s.bytes {
        None => None,
        Some(b) => {
            let value = hex_bytes(&b.value, "bytes.value")?;
            let mask = match &b.mask {
                Some(m) => {
                    let mask = hex_bytes(m, "bytes.mask")?;
                    if mask.len() != value.len() {
                        return err("bytes.mask and bytes.value have the same length");
                    }
                    mask
                }
                None => vec![0xFF; value.len()],
            };
            Some(ByteTest {
                offset: b.offset as usize,
                value,
                mask,
            })
        }
    };
    Ok(StructTest {
        ads,
        company: s.company,
        uuid: s.uuid.clone(),
        len,
        bytes,
    })
}

fn parse_shape_item(item: &str) -> Result<ShapeItem, String> {
    let bad = || format!("bad shape item {item:?} (TT/N, TT/*, TT/a-b with TT uppercase hex)");
    let (ad, len) = item.split_once('/').ok_or_else(bad)?;
    if ad.len() != 2 || !ad.bytes().all(|b| matches!(b, b'0'..=b'9' | b'A'..=b'F')) {
        return err(bad());
    }
    let ad_type = u8::from_str_radix(ad, 16).map_err(|_| bad())?;
    let num = |s: &str| -> Result<usize, String> {
        if s.is_empty() || !s.bytes().all(|b| b.is_ascii_digit()) {
            return err(bad());
        }
        s.parse().map_err(|_| bad())
    };
    let (min, max) = if len == "*" {
        (0, usize::MAX)
    } else if let Some((lo, hi)) = len.split_once('-') {
        let (lo, hi) = (num(lo)?, num(hi)?);
        if lo > hi {
            return err(format!("shape item {item:?}: empty range"));
        }
        (lo, hi)
    } else {
        let n = num(len)?;
        (n, n)
    };
    Ok(ShapeItem { ad_type, min, max })
}

/// A restricted regex both Postgres (ARE) and Python read the same way: literals,
/// escaped punctuation, `\d`, `.`, groups, `|`, quantifiers (optionally lazy), and
/// character classes made only of literals, ranges, escaped punctuation and `\d`.
/// Classes cannot nest or use `&&`, `~~`, `--`, `||` (set operators in Rust,
/// future syntax in Python); a quantifier cannot follow a quantifier except as the
/// lazy `?` (Python reads `a*+` as possessive, Rust rejects it). Same token
/// grammar and checks as `regex_ok` in `tests/python/reference_rules.py`.
pub fn regex_ok(pattern: &str) -> bool {
    static TOKEN: LazyLock<Regex> = LazyLock::new(|| {
        Regex::new(concat!(
            r"\\[.\-+*?()\[\]{}|^$/\\ _:#d]",
            r"|\[\^?(?:\\[.\-+*?()\[\]{}|^$/\\ _:#d]|[^\\\[\]])+\]",
            r"|\{\d+(?:,\d*)?\}",
            r"|[*+?|()]",
            r"|\.",
            r"|[^\\\[\]{}()*+?|.^$]",
        ))
        .expect("static pattern")
    });
    let mut pos = 0;
    // 0: after an atom (or start); 1: after a quantifier; 2: after a quantifier's lazy `?`
    let mut state = 0;
    while pos < pattern.len() {
        let token = match TOKEN.find_at(pattern, pos) {
            Some(m) if m.start() == pos && m.end() > pos => m.as_str(),
            _ => return false,
        };
        pos += token.len();
        if token.starts_with('[') && ["&&", "~~", "--", "||"].iter().any(|f| token.contains(f)) {
            return false;
        }
        let quantifier = matches!(token, "*" | "+" | "?") || token.starts_with('{');
        state = match (quantifier, state) {
            (false, _) => 0,
            (true, 0) => 1,
            (true, 1) if token == "?" => 2,
            (true, _) => return false,
        };
    }
    !pattern.contains("(?") && Regex::new(pattern).is_ok()
}

impl Compiled {
    /// Whether any condition of the tree looks at the address.
    pub fn uses_address(&self) -> bool {
        match self {
            Compiled::All(children) | Compiled::Any(children) => {
                children.iter().any(Compiled::uses_address)
            }
            Compiled::Not(child) => child.uses_address(),
            Compiled::Addr { .. } => true,
            _ => false,
        }
    }

    pub fn eval(&self, structs: &[Structure], ctx: &AddrContext<'_>) -> bool {
        match self {
            Compiled::All(children) => children.iter().all(|c| c.eval(structs, ctx)),
            Compiled::Any(children) => children.iter().any(|c| c.eval(structs, ctx)),
            Compiled::Not(child) => !child.eval(structs, ctx),
            Compiled::Name { ads, test } => structs.iter().any(|s| {
                ads.contains(&s.ad_type) && s.name.as_deref().is_some_and(|n| test.matches(n))
            }),
            Compiled::Company(companies) => structs
                .iter()
                .any(|s| s.ad_type == 0xFF && s.company.is_some_and(|c| companies.contains(&c))),
            Compiled::Uuid { ads, want } => structs
                .iter()
                .any(|s| ads.contains(&s.ad_type) && s.uuids.iter().any(|u| want.contains(u))),
            Compiled::Struct(test) => structs.iter().any(|s| test.matches(s)),
            Compiled::Shape { items, rest } => shape_matches(items, *rest, structs),
            Compiled::Addr { oui, addr_type } => addr_matches(oui, *addr_type, ctx),
        }
    }
}

impl NameTest {
    fn matches(&self, name: &str) -> bool {
        match self {
            NameTest::Regex(re) => re.is_match(name),
            NameTest::Equals { want, insensitive } => fold(name, *insensitive) == *want,
            NameTest::Prefix { want, insensitive } => {
                fold(name, *insensitive).starts_with(want.as_str())
            }
        }
    }
}

impl StructTest {
    fn matches(&self, s: &Structure) -> bool {
        if !self.ads.contains(&s.ad_type) {
            return false;
        }
        if self.company.is_some() && s.company != self.company {
            return false;
        }
        if let Some(uuid) = &self.uuid
            && s.uuids.first() != Some(uuid)
        {
            return false;
        }
        let base = base_offset(s.ad_type);
        if s.data.len() < base {
            return false;
        }
        let data = &s.data[base..];
        if let Some((lo, hi)) = self.len
            && !(lo..=hi).contains(&data.len())
        {
            return false;
        }
        if let Some(b) = &self.bytes {
            let Some(seg) = data.get(b.offset..b.offset + b.value.len()) else {
                return false;
            };
            return seg
                .iter()
                .zip(&b.value)
                .zip(&b.mask)
                .all(|((s, v), m)| s & m == v & m);
        }
        true
    }
}

fn shape_matches(items: &[ShapeItem], rest: bool, structs: &[Structure]) -> bool {
    if structs.len() < items.len() || (!rest && structs.len() != items.len()) {
        return false;
    }
    items
        .iter()
        .zip(structs)
        .all(|(item, s)| item.ad_type == s.ad_type && (item.min..=item.max).contains(&s.data.len()))
}

fn addr_matches(
    oui: &Option<Vec<String>>,
    addr_type: Option<AddrType>,
    ctx: &AddrContext<'_>,
) -> bool {
    let Some(addr) = ctx.addr else { return false };
    if let Some(ouis) = oui {
        let hexed: String = addr
            .chars()
            .filter(|c| *c != ':' && *c != '-')
            .map(|c| c.to_ascii_uppercase())
            .collect();
        match hexed.get(..6) {
            Some(prefix) if ouis.iter().any(|o| o == prefix) => {}
            _ => return false,
        }
    }
    addr_type.is_none_or(|t| ctx.addr_type == Some(t))
}
