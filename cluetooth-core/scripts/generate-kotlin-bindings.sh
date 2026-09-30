#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <kotlin-output-directory>" >&2
  exit 2
fi

crate_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out_dir="$1"
cd "$crate_dir"

case "$(uname -s)" in
  Linux)
    host_library="target/release/libcluetooth_core.so"
    ;;
  Darwin)
    host_library="target/release/libcluetooth_core.dylib"
    ;;
  *)
    echo "unsupported binding-generation host: $(uname -s); supported hosts are Linux and macOS" >&2
    exit 1
    ;;
esac

# UniFFI bindgen reads metadata from static symbols that are intentionally absent
# from packaged Android libraries, so retain symbols only in this ignored host artifact.
CARGO_PROFILE_RELEASE_STRIP=none cargo build --locked --release --lib
if [[ ! -f "$host_library" ]]; then
  echo "expected host cdylib was not produced: $host_library" >&2
  exit 1
fi
rm -rf "$out_dir"
mkdir -p "$out_dir"
cargo run --locked --features bindgen-cli --bin uniffi-bindgen -- \
  generate \
  --library "$host_library" \
  --language kotlin \
  --config uniffi.toml \
  --out-dir "$out_dir" \
  --no-format
