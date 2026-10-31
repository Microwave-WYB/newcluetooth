//! [`super::DeviceKey`]: the 48-bit MAC as an integer (§2.2).

/// `AA:BB:CC:DD:EE:FF` (case-insensitive) → `0xAABBCCDDEEFF`; `None` unless it is exactly that form.
pub(crate) fn key_of(addr: &str) -> Option<i64> {
    let bytes = addr.as_bytes();
    if bytes.len() != 17 || (0..5).any(|i| bytes.get(i * 3 + 2) != Some(&b':')) {
        return None;
    }
    let hex: String = addr.split(':').collect();
    i64::from_str_radix(&hex, 16).ok()
}

/// The uppercase colon form, as `MapData.geojson` and `DeviceRow.addr` carry it (§2.5).
pub(crate) fn addr_of(key: i64) -> String {
    (0..6)
        .rev()
        .map(|i| format!("{:02X}", (key >> (8 * i)) & 0xff))
        .collect::<Vec<_>>()
        .join(":")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips() {
        assert_eq!(key_of("AA:BB:CC:DD:EE:FF"), Some(0xAABBCCDDEEFF));
        assert_eq!(key_of("aa:bb:cc:dd:ee:0f"), Some(0xAABBCCDDEE0F));
        assert_eq!(addr_of(0x72CD4C4B4B6F), "72:CD:4C:4B:4B:6F");
        assert_eq!(key_of("AABBCCDDEEFF"), None);
        assert_eq!(key_of("AA-BB-CC-DD-EE-FF"), None);
    }
}
