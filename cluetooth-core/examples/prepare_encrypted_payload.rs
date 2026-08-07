use std::path::PathBuf;

use cluetooth_core::{CluetoothCore, CoreConfig, ScanObservationInput};

fn decode_key(value: &str) -> Result<Vec<u8>, String> {
    if value.len() != 64 {
        return Err("public key must be 64 lowercase hexadecimal characters".to_owned());
    }
    value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| {
            let text = std::str::from_utf8(pair).map_err(|error| error.to_string())?;
            u8::from_str_radix(text, 16).map_err(|error| error.to_string())
        })
        .collect()
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut arguments = std::env::args().skip(1);
    let data_directory = PathBuf::from(arguments.next().ok_or("missing data directory")?);
    let public_key = decode_key(&arguments.next().ok_or("missing public key hex")?)?;
    if arguments.next().is_some() {
        return Err("usage: prepare_encrypted_payload <data-directory> <public-key-hex>".into());
    }

    let config = CoreConfig {
        payload_max_rows: 1,
        recipient_public_key: public_key,
        ..CoreConfig::default()
    };
    let core = CluetoothCore::open(data_directory.to_string_lossy().into_owned(), config)?;
    core.record_observations(vec![ScanObservationInput {
        addr: "AA:BB:CC:DD:EE:FF".to_owned(),
        rssi: Some(-42),
        scanned_at_ms: 1_741_435_200_123,
        elapsed_realtime_nanos: 123,
        raw: vec![0x00, 0x80, 0xfe, 0xff],
        local_name: Some("fixture".to_owned()),
        tx_power: Some(-8),
        is_connectable: Some(true),
    }])?;
    let pending = core
        .pending_uploads()
        .into_iter()
        .next()
        .ok_or("payload did not seal")?;
    let prepared = core.prepare_upload(pending.payload_id)?;
    println!("{}", pending.local_path);
    println!("{}", prepared.ciphertext_path);
    println!("{}", prepared.object_path);
    Ok(())
}
