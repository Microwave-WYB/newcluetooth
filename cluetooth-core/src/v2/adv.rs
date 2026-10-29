//! Low-level advertisement structure, as the research DB parses it (`ble_adv_split`, the `ad_structures`
//! generated columns and `payload_fields`; `db/db/migrations/20261026000000_minute_index_schema.sql`).
//! Nothing protocol-specific is decoded here.

/// One raw advertisement split into its AD structures plus the unparsed remainder.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Split {
    /// `(ad_type, data)` in order, duplicates kept.
    pub structures: Vec<(u8, Vec<u8>)>,
    /// Length of an all-zero remainder.
    pub zero_padding: u32,
    /// Any other remainder (e.g. a truncated field); `None` when there is none or it is all zeros.
    pub tail: Option<Vec<u8>>,
}

/// `ble_adv_split`: stops at a zero length or a truncated field instead of repairing.
pub(crate) fn split(raw: &[u8]) -> Split {
    let mut structures = Vec::new();
    let mut offset = 0usize;
    while offset < raw.len() {
        let len = raw[offset] as usize;
        if len == 0 || offset + len >= raw.len() {
            break;
        }
        structures.push((raw[offset + 1], raw[offset + 2..offset + 1 + len].to_vec()));
        offset += len + 1;
    }
    let rest = &raw[offset..];
    let (zero_padding, tail) = if rest.is_empty() {
        (0, None)
    } else if rest.iter().all(|&b| b == 0) {
        (rest.len() as u32, None)
    } else {
        (0, Some(rest.to_vec()))
    };
    Split {
        structures,
        zero_padding,
        tail,
    }
}

/// `payload_raw`: the exact bytes back from the split.
pub(crate) fn join(split: &Split) -> Vec<u8> {
    let mut raw = Vec::new();
    for (ad_type, data) in &split.structures {
        raw.push(data.len() as u8 + 1);
        raw.push(*ad_type);
        raw.extend_from_slice(data);
    }
    raw.extend(std::iter::repeat_n(0u8, split.zero_padding as usize));
    if let Some(tail) = &split.tail {
        raw.extend_from_slice(tail);
    }
    raw
}

/// `ad_structures.local_name`: types 8/9, trailing NULs stripped, `None` if empty or not UTF-8.
pub(crate) fn local_name(ad_type: u8, data: &[u8]) -> Option<String> {
    if ad_type != 0x08 && ad_type != 0x09 {
        return None;
    }
    let end = data.iter().rposition(|&b| b != 0).map_or(0, |i| i + 1);
    std::str::from_utf8(&data[..end])
        .ok()
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
}

/// `ad_structures.company_id`: type 0xFF with at least 2 bytes, little-endian.
pub(crate) fn company_id(ad_type: u8, data: &[u8]) -> Option<u16> {
    (ad_type == 0xFF && data.len() >= 2).then(|| u16::from_le_bytes([data[0], data[1]]))
}

fn uuid128_le(bytes: &[u8]) -> String {
    let h: Vec<String> = bytes.iter().rev().map(|b| format!("{b:02x}")).collect();
    let h = h.concat();
    format!(
        "{}-{}-{}-{}-{}",
        &h[0..8],
        &h[8..12],
        &h[12..16],
        &h[16..20],
        &h[20..32]
    )
}

fn uuid16_le(bytes: &[u8]) -> String {
    format!("{:04x}", u16::from_le_bytes([bytes[0], bytes[1]]))
}

fn uuid32_le(bytes: &[u8]) -> String {
    format!(
        "{:08x}",
        u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
    )
}

/// The UUIDs one structure lists, in the research notation (lowercase), unsorted.
fn structure_uuid_list(ad_type: u8, data: &[u8]) -> Option<Vec<String>> {
    match ad_type {
        0x02 | 0x03 => Some(data.chunks_exact(2).map(uuid16_le).collect()),
        0x04 | 0x05 => Some(data.chunks_exact(4).map(uuid32_le).collect()),
        0x06 | 0x07 => Some(data.chunks_exact(16).map(uuid128_le).collect()),
        0x16 => Some(
            (data.len() >= 2)
                .then(|| uuid16_le(data))
                .into_iter()
                .collect(),
        ),
        0x20 => Some(
            (data.len() >= 4)
                .then(|| uuid32_le(data))
                .into_iter()
                .collect(),
        ),
        0x21 => Some(
            (data.len() >= 16)
                .then(|| uuid128_le(&data[..16]))
                .into_iter()
                .collect(),
        ),
        _ => None,
    }
}

/// `ad_structures.uuids`: types 2–7 and 0x16/0x20/0x21, distinct and sorted, comma-joined. Types 2–7 give `""`
/// when empty (the research DB's empty array); service data without a UUID gives `None` (its `nullif`).
pub(crate) fn uuids(ad_type: u8, data: &[u8]) -> Option<String> {
    let mut list = structure_uuid_list(ad_type, data)?;
    if list.is_empty() && matches!(ad_type, 0x16 | 0x20 | 0x21) {
        return None;
    }
    list.sort();
    list.dedup();
    Some(list.join(","))
}

/// The materialized `payload_fields` of one payload.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct PayloadFields {
    /// Type 9 before type 8, then the first in order.
    pub local_name: Option<String>,
    /// The first manufacturer structure's company.
    pub company_id: Option<u16>,
    /// The first service UUID (16-bit uppercase as the wiki writes it, others lowercase): service class UUIDs
    /// first, then service data.
    pub service_uuid: Option<String>,
    /// Any structure with company 76 (G-12).
    pub is_apple: bool,
}

pub(crate) const APPLE: u16 = 76;

pub(crate) fn payload_fields(split: &Split) -> PayloadFields {
    let named = |t: u8| {
        split
            .structures
            .iter()
            .find_map(|(ad_type, data)| (*ad_type == t).then(|| local_name(t, data)).flatten())
    };
    let companies: Vec<u16> = split
        .structures
        .iter()
        .filter_map(|(t, d)| company_id(*t, d))
        .collect();
    let display = |u: String| if u.len() == 4 { u.to_uppercase() } else { u };
    let class = split.structures.iter().find_map(|(t, d)| {
        matches!(t, 0x02..=0x07)
            .then(|| structure_uuid_list(*t, d).and_then(|l| l.into_iter().next()))
            .flatten()
    });
    let data = || {
        split.structures.iter().find_map(|(t, d)| {
            matches!(t, 0x16 | 0x20 | 0x21)
                .then(|| structure_uuid_list(*t, d).and_then(|l| l.into_iter().next()))
                .flatten()
        })
    };
    PayloadFields {
        local_name: named(0x09).or_else(|| named(0x08)),
        company_id: companies.first().copied(),
        service_uuid: class.or_else(data).map(display),
        is_apple: companies.contains(&APPLE),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_keeps_order_and_duplicates_and_round_trips() {
        let raw = [2, 1, 6, 3, 3, 0x95, 0xfe, 2, 1, 6, 0, 0, 0];
        let s = split(&raw);
        assert_eq!(
            s.structures,
            vec![(1, vec![6]), (3, vec![0x95, 0xfe]), (1, vec![6])]
        );
        assert_eq!(s.zero_padding, 3);
        assert_eq!(s.tail, None);
        assert_eq!(join(&s), raw);
    }

    #[test]
    fn split_stops_at_a_truncated_field() {
        let raw = [2, 1, 6, 9, 9, b'a', b'b'];
        let s = split(&raw);
        assert_eq!(s.structures, vec![(1, vec![6])]);
        assert_eq!(s.tail, Some(vec![9, 9, b'a', b'b']));
        assert_eq!(join(&s), raw);
        // a structure that ends exactly at the last byte is complete
        let exact = [3, 0xff, 0x4c, 0x00];
        assert_eq!(split(&exact).structures, vec![(0xff, vec![0x4c, 0])]);
        assert_eq!(split(&[]).structures, vec![]);
    }

    #[test]
    fn generated_columns_follow_the_research_rules() {
        assert_eq!(local_name(9, b"Hue\0\0"), Some("Hue".into()));
        assert_eq!(local_name(8, b"\0"), None);
        assert_eq!(local_name(9, &[0xff, 0xfe]), None);
        assert_eq!(local_name(0xff, b"x"), None);
        assert_eq!(company_id(0xff, &[0x4c, 0x00, 1]), Some(76));
        assert_eq!(company_id(0xff, &[0x4c]), None);
        assert_eq!(
            uuids(3, &[0x95, 0xfe, 0x0f, 0xfe]),
            Some("fe0f,fe95".into())
        );
        assert_eq!(uuids(3, &[]), Some(String::new()));
        assert_eq!(uuids(0x16, &[0x95]), None);
        assert_eq!(uuids(0x16, &[0x95, 0xfe, 1, 2]), Some("fe95".into()));
        let u128: Vec<u8> = (0u8..16).collect();
        assert_eq!(
            uuids(7, &u128),
            Some("0f0e0d0c-0b0a-0908-0706-050403020100".into())
        );
        assert_eq!(uuids(1, &[6]), None);
    }

    #[test]
    fn payload_fields_materialize_the_view() {
        let raw = [
            5, 8, b'a', b'b', b'c', b'd', 4, 9, b'X', b'Y', b'Z', 5, 0xff, 0x4c, 0, 1, 2, 3, 0x16,
            0xec, 0x88,
        ];
        let f = payload_fields(&split(&raw));
        assert_eq!(f.local_name.as_deref(), Some("XYZ"));
        assert_eq!(f.company_id, Some(76));
        assert!(f.is_apple);
        assert_eq!(f.service_uuid.as_deref(), Some("88EC"));
    }
}
