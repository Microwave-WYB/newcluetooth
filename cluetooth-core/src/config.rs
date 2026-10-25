pub const DEFAULT_LOCATION_MAX_AGE_MS: u64 = 5_000;
pub const DEFAULT_RECENT_LOCATION_CAPACITY: u32 = 32;
pub const MAX_RECENT_LOCATION_CAPACITY: u32 = 4_096;
/// Emergency ceiling; normal payloads rotate first at 10 MiB or five minutes.
pub const DEFAULT_PAYLOAD_MAX_ROWS: u32 = 100_000;
pub const MAX_PAYLOAD_MAX_ROWS: u32 = 1_000_000;
pub const DEFAULT_PAYLOAD_MAX_ESTIMATED_BYTES: u64 = 10 * 1024 * 1024;
pub const MAX_PAYLOAD_MAX_ESTIMATED_BYTES: u64 = 256 * 1024 * 1024;
pub const DEFAULT_PAYLOAD_MAX_AGE_MS: u64 = 5 * 60 * 1_000;
pub const MAX_PAYLOAD_MAX_AGE_MS: u64 = 24 * 60 * 60 * 1_000;
pub const MAX_INPUT_BATCH_ROWS: usize = 64;
pub const MAX_INPUT_BATCH_ESTIMATED_BYTES: u64 = 1024 * 1024;
pub(crate) const INPUT_FIXED_ESTIMATE_BYTES: u64 = 8 + 2 + 2 + 1 + 8 + 16;
pub(crate) const ROW_FIXED_ESTIMATE_BYTES: u64 = 8 + 4 + 4 + 1 + 8 + 8 + 8 + 16;
/// Largest actual converted-row estimate admitted by the input estimator.
pub(crate) const MAX_CONVERTED_BATCH_ESTIMATED_BYTES: u64 = MAX_INPUT_BATCH_ESTIMATED_BYTES
    + MAX_INPUT_BATCH_ROWS as u64 * (ROW_FIXED_ESTIMATE_BYTES - INPUT_FIXED_ESTIMATE_BYTES);
const DEFAULT_RECIPIENT_PUBLIC_KEY: [u8; 32] = [
    11, 115, 19, 47, 15, 30, 88, 220, 63, 252, 188, 149, 240, 71, 8, 208, 192, 97, 228, 228, 46,
    217, 100, 189, 199, 96, 164, 56, 129, 203, 250, 79,
];

#[derive(Clone, Debug, uniffi::Record)]
pub struct CoreConfig {
    pub location_max_age_ms: u64,
    pub recent_location_capacity: u32,
    pub payload_max_rows: u32,
    pub payload_max_estimated_bytes: u64,
    pub payload_max_age_ms: u64,
    /// Raw 32-byte Curve25519 recipient key used for libsodium-compatible sealed boxes.
    pub recipient_public_key: Vec<u8>,
}

impl Default for CoreConfig {
    fn default() -> Self {
        Self {
            location_max_age_ms: DEFAULT_LOCATION_MAX_AGE_MS,
            recent_location_capacity: DEFAULT_RECENT_LOCATION_CAPACITY,
            payload_max_rows: DEFAULT_PAYLOAD_MAX_ROWS,
            payload_max_estimated_bytes: DEFAULT_PAYLOAD_MAX_ESTIMATED_BYTES,
            payload_max_age_ms: DEFAULT_PAYLOAD_MAX_AGE_MS,
            recipient_public_key: DEFAULT_RECIPIENT_PUBLIC_KEY.to_vec(),
        }
    }
}

#[uniffi::export]
pub fn default_core_config() -> CoreConfig {
    CoreConfig::default()
}
