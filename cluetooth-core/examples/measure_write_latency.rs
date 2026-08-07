use std::time::{Duration, Instant};

use cluetooth_core::{SchemaV2Row, write_schema_v2_parquet};
use tempfile::tempdir;

const PAYLOAD_ID: &str = "0195c920-7c00-7abc-8def-0123456789ab";
const ROW_COUNT: usize = 10_000;
const WARMUP_RUNS: usize = 1;
const MEASURED_RUNS: usize = 5;
const RAW_BYTES_PER_ROW: usize = 31;

fn representative_rows() -> Vec<SchemaV2Row> {
    (0..ROW_COUNT)
        .map(|index| SchemaV2Row {
            addr: format!(
                "AA:BB:{:02X}:{:02X}:{:02X}:{:02X}",
                (index >> 24) & 0xff,
                (index >> 16) & 0xff,
                (index >> 8) & 0xff,
                index & 0xff
            ),
            rssi: (index % 17 != 0).then_some(-35 - (index % 55) as i32),
            scanned_at_ms: 1_741_435_200_000 + index as i64 * 250,
            raw: (0..RAW_BYTES_PER_ROW)
                .map(|offset| ((index + offset) & 0xff) as u8)
                .collect(),
            local_name: (index % 3 == 0).then(|| format!("sensor-{}", index % 100)),
            tx_power: (index % 5 == 0).then_some(-12),
            is_connectable: (index % 7 != 0).then_some(index % 2 == 0),
            lat: (index % 11 != 0).then_some(32.88 + (index % 100) as f64 / 10_000.0),
            lon: (index % 11 != 0).then_some(-117.23 - (index % 100) as f64 / 10_000.0),
            accuracy: (index % 11 != 0).then_some(3.0 + (index % 20) as f32 / 10.0),
        })
        .collect()
}

fn write_once(path: &std::path::Path) -> Duration {
    let rows = representative_rows();
    let started = Instant::now();
    write_schema_v2_parquet(
        path.to_string_lossy().into_owned(),
        PAYLOAD_ID.to_owned(),
        rows,
    )
    .expect("representative schema-v2 write should succeed");
    started.elapsed()
}

fn main() {
    let directory = tempdir().expect("temporary measurement directory should be available");
    let path = directory.path().join(format!("{PAYLOAD_ID}.parquet"));

    for _ in 0..WARMUP_RUNS {
        write_once(&path);
    }

    let mut durations: Vec<Duration> = (0..MEASURED_RUNS).map(|_| write_once(&path)).collect();
    let output_bytes = std::fs::metadata(&path)
        .expect("measurement output should exist")
        .len();
    durations.sort_unstable();
    let median = durations[durations.len() / 2];

    println!("host={}-{}", std::env::consts::OS, std::env::consts::ARCH);
    println!(
        "dataset_rows={ROW_COUNT} raw_bytes_per_row={RAW_BYTES_PER_ROW} total_raw_bytes={}",
        ROW_COUNT * RAW_BYTES_PER_ROW
    );
    println!("parquet_output_bytes={output_bytes} compression=zstd");
    println!(
        "method=1_warmup_then_5_public_atomic_write_calls_including_frame_build_sync_and_inspection"
    );
    for (index, duration) in durations.iter().enumerate() {
        println!(
            "sorted_run_{}_milliseconds={:.3}",
            index + 1,
            duration.as_secs_f64() * 1_000.0
        );
    }
    println!(
        "median_write_milliseconds={:.3}",
        median.as_secs_f64() * 1_000.0
    );
    println!("performance_assertions=none");
}
