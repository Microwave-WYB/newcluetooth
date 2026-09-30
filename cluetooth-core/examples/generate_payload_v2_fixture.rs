use std::env;
use std::fs;
use std::path::PathBuf;

use cluetooth_core::{SchemaV2Row, write_schema_v2_parquet};

const PAYLOAD_ID: &str = "0195c920-7c00-7abc-8def-0123456789ab";
const RELATIVE_PATH: &str = "scans/v2/2025/03/24/0195c920-7c00-7abc-8def-0123456789ab.parquet";

fn main() {
    let output_root = env::args_os()
        .nth(1)
        .map(PathBuf::from)
        .expect("usage: generate_payload_v2_fixture <output-root>");
    let output = output_root.join(RELATIVE_PATH);
    fs::create_dir_all(output.parent().expect("fixture path parent")).expect("create fixture path");
    let rows = vec![
        SchemaV2Row {
            addr: "AA:BB:CC:DD:EE:FF".to_owned(),
            rssi: Some(-47),
            scanned_at_ms: 1_741_435_200_123,
            raw: vec![0x02, 0x01, 0x06, 0x05, 0xff, 0x00, 0x80, 0xfe],
            local_name: Some("sensor".to_owned()),
            tx_power: Some(-12),
            is_connectable: Some(true),
            lat: Some(32.8801),
            lon: Some(-117.234),
            accuracy: Some(3.25),
        },
        SchemaV2Row {
            addr: "00:11:22:33:44:55".to_owned(),
            rssi: None,
            scanned_at_ms: 1_741_435_201_999,
            raw: vec![0x00, 0xff, 0x10, 0x00],
            local_name: None,
            tx_power: None,
            is_connectable: None,
            lat: None,
            lon: None,
            accuracy: None,
        },
    ];
    let inspection = write_schema_v2_parquet(
        output.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        rows,
    )
    .expect("write fixture with production writer");
    assert_eq!(inspection.row_count, 2);
    println!("{}", output.display());
}
