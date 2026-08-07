use std::fs::{File, OpenOptions};
use std::io::Read;
use std::path::Path;

use crypto_box::PublicKey;
use crypto_box::aead::OsRng;

use crate::CoreError;

pub const SEALED_BOX_OVERHEAD_BYTES: u64 = 48;

pub(crate) fn validate_recipient_public_key(bytes: &[u8]) -> Result<[u8; 32], CoreError> {
    let key: [u8; 32] = bytes.try_into().map_err(|_| {
        CoreError::invalid_config("recipient_public_key must contain exactly 32 raw bytes")
    })?;
    // Like libsodium, require a contributory X25519 result. This rejects zero and
    // the other low-order encodings for which sealed-box encryption cannot be used.
    if x25519_dalek::x25519([0x42; 32], key) == [0; 32] {
        return Err(CoreError::invalid_config(
            "recipient_public_key is not a valid contributory Curve25519 key",
        ));
    }
    Ok(key)
}

pub(crate) fn encrypt_file_atomically(
    plaintext_path: &Path,
    ciphertext_path: &Path,
    recipient_public_key: &[u8; 32],
) -> Result<u64, CoreError> {
    let mut plaintext = Vec::new();
    File::open(plaintext_path)
        .and_then(|mut file| file.read_to_end(&mut plaintext))
        .map_err(CoreError::io)?;
    let public_key = PublicKey::from(*recipient_public_key);
    let ciphertext = public_key
        .seal(&mut OsRng, &plaintext)
        .map_err(|_| CoreError::encryption("crypto_box seal operation failed"))?;

    let parent = ciphertext_path
        .parent()
        .ok_or_else(|| CoreError::upload_state("ciphertext path has no parent"))?;
    std::fs::create_dir_all(parent).map_err(CoreError::io)?;
    let temporary = tempfile::Builder::new()
        .prefix(".cluetooth-upload-")
        .suffix(".tmp")
        .tempfile_in(parent)
        .map_err(CoreError::io)?
        .into_temp_path();
    {
        let mut output = OpenOptions::new()
            .write(true)
            .truncate(true)
            .open(&temporary)
            .map_err(CoreError::io)?;
        std::io::Write::write_all(&mut output, &ciphertext).map_err(CoreError::io)?;
        output.sync_all().map_err(CoreError::io)?;
    }
    temporary
        .persist(ciphertext_path)
        .map_err(|error| CoreError::io(error.error))?;
    File::open(parent)
        .and_then(|directory| directory.sync_all())
        .map_err(CoreError::io)?;
    Ok(ciphertext.len() as u64)
}

#[cfg(test)]
mod tests {
    use crypto_box::SecretKey;

    use super::validate_recipient_public_key;

    #[test]
    fn rejects_known_low_order_curve25519_keys() {
        let mut one = [0_u8; 32];
        one[0] = 1;
        for key in [[0_u8; 32], one] {
            assert!(validate_recipient_public_key(&key).is_err());
        }
    }

    #[test]
    fn accepts_current_default_and_generated_valid_keys() {
        assert!(
            validate_recipient_public_key(
                &crate::config::CoreConfig::default().recipient_public_key,
            )
            .is_ok()
        );
        assert!(
            validate_recipient_public_key(SecretKey::from([7_u8; 32]).public_key().as_bytes())
                .is_ok()
        );
    }
}
