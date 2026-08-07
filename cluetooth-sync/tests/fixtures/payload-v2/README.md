# Rust payload-v2 contract fixture

`scans/v2/2025/03/24/0195c920-7c00-7abc-8def-0123456789ab.parquet`
is plaintext, non-sensitive test data written by the production Rust schema-v2 writer.
Its UUID date, path, and footer identity agree. It contains two synthetic rows,
including binary bytes `02 01 06 05 ff 00 80 fe` and `00 ff 10 00` plus a fully
nullable second row.

Regenerate from the repository root:

```sh
cd cluetooth-core
cargo run --locked --example generate_payload_v2_fixture -- \
  ../cluetooth-sync/tests/fixtures/payload-v2
```

The expected SHA-256 is
`a0f7c814d28fc92523fd30b1df51cd909ef8765d728e3ee81ac0026bf7859ae2` (Float32 `accuracy` contract).
Python contract tests read this exact file without conversion or PyArrow.
