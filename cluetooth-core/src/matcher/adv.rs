//! Low-level advertisement structures, derived exactly like
//! `kb.rules.structures` (Python) and the database parser: the AD structures
//! in order, stopping at a zero length or a truncated field.

/// AD types holding lists of service UUIDs -> UUID width in bytes.
pub(crate) fn uuid_list_width(ad_type: u8) -> Option<usize> {
    match ad_type {
        0x02 | 0x03 => Some(2),
        0x04 | 0x05 => Some(4),
        0x06 | 0x07 => Some(16),
        _ => None,
    }
}

/// AD types holding service data -> UUID width in bytes.
pub(crate) fn service_data_width(ad_type: u8) -> Option<usize> {
    match ad_type {
        0x16 => Some(2),
        0x20 => Some(4),
        0x21 => Some(16),
        _ => None,
    }
}

/// Bytes before the data a `struct` condition looks at: the company field of
/// 0xFF, or the UUID of service data.
pub(crate) fn base_offset(ad_type: u8) -> usize {
    if ad_type == 0xFF {
        2
    } else {
        service_data_width(ad_type).unwrap_or(0)
    }
}

pub(crate) fn is_name_type(ad_type: u8) -> bool {
    ad_type == 0x08 || ad_type == 0x09
}

const BT_BASE_SUFFIX: [u8; 12] = [
    0x00, 0x00, 0x10, 0x00, 0x80, 0x00, 0x00, 0x80, 0x5f, 0x9b, 0x34, 0xfb,
];

/// 16/32-bit UUIDs uppercase hex, 128-bit lowercase dashed; a 128-bit UUID on
/// the Bluetooth base UUID folds to its short form. Input is little-endian.
pub(crate) fn canonical_uuid(little_endian: &[u8]) -> String {
    let be: Vec<u8> = little_endian.iter().rev().copied().collect();
    let mut be: &[u8] = &be;
    if be.len() == 16 && be[4..] == BT_BASE_SUFFIX {
        be = if be[..2] == [0, 0] {
            &be[2..4]
        } else {
            &be[..4]
        };
    }
    let h = hex::encode(be);
    if be.len() == 2 || be.len() == 4 {
        return h.to_uppercase();
    }
    format!(
        "{}-{}-{}-{}-{}",
        &h[..8],
        &h[8..12],
        &h[12..16],
        &h[16..20],
        &h[20..]
    )
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Structure {
    pub ad_type: u8,
    pub data: Vec<u8>,
    /// Decoded name (0x08/0x09): UTF-8 with replacement, NULs removed.
    pub name: Option<String>,
    /// Company field of 0xFF structures with at least two data bytes.
    pub company: Option<u16>,
    /// UUIDs a structure carries (UUID lists and service data).
    pub uuids: Vec<String>,
}

pub fn structures(raw: &[u8]) -> Vec<Structure> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < raw.len() {
        let n = raw[i] as usize;
        if n == 0 || i + 1 + n > raw.len() {
            break;
        }
        let ad_type = raw[i + 1];
        let data = &raw[i + 2..i + 1 + n];
        let name = is_name_type(ad_type).then(|| String::from_utf8_lossy(data).replace('\0', ""));
        let company =
            (ad_type == 0xFF && data.len() >= 2).then(|| u16::from_le_bytes([data[0], data[1]]));
        let mut uuids = Vec::new();
        if let Some(w) = uuid_list_width(ad_type) {
            uuids = data.chunks_exact(w).map(canonical_uuid).collect();
        }
        if let Some(w) = service_data_width(ad_type)
            && data.len() >= w
        {
            uuids = vec![canonical_uuid(&data[..w])];
        }
        out.push(Structure {
            ad_type,
            data: data.to_vec(),
            name,
            company,
            uuids,
        });
        i += 1 + n;
    }
    out
}
