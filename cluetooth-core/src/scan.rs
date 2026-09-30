#[derive(Clone, Debug, PartialEq, uniffi::Record)]
pub struct ScanObservationInput {
    pub addr: String,
    pub rssi: Option<i16>,
    pub scanned_at_ms: i64,
    pub elapsed_realtime_nanos: u64,
    pub raw: Vec<u8>,
    pub local_name: Option<String>,
    pub tx_power: Option<i16>,
    pub is_connectable: Option<bool>,
}

pub(crate) fn sanitize_local_name(local_name: Option<String>) -> Option<String> {
    local_name.and_then(|name| {
        let sanitized = name.replace('\0', "");
        if sanitized.is_empty() {
            None
        } else {
            Some(sanitized)
        }
    })
}

#[cfg(test)]
mod tests {
    use super::sanitize_local_name;

    #[test]
    fn platform_local_name_is_sanitized_without_parsing_raw_bytes() {
        assert_eq!(
            sanitize_local_name(Some("sen\0sor".to_owned())).as_deref(),
            Some("sensor")
        );
        assert_eq!(sanitize_local_name(Some("\0\0".to_owned())), None);
        assert_eq!(sanitize_local_name(Some(String::new())), None);
        assert_eq!(sanitize_local_name(None), None);
    }
}
