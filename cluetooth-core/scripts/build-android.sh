#!/usr/bin/env bash
set -euo pipefail

readonly EXPECTED_CARGO_NDK_VERSION="4.1.2"
readonly EXPECTED_NDK_VERSION="27.2.12479018"
readonly ANDROID_API="24"
# CLUETOOTH_ABIS is a comma-separated Android ABI list; default arm64-v8a. Every requested ABI is
# built and verified, otherwise the script fails. Other ABIs (emulators, 32-bit) are opt-in.
declare -A RUST_TARGET_FOR_ABI=(
  ["arm64-v8a"]="aarch64-linux-android"
  ["armeabi-v7a"]="armv7-linux-androideabi"
  ["x86_64"]="x86_64-linux-android"
  ["x86"]="i686-linux-android"
)
IFS=',' read -r -a ABIS <<<"${CLUETOOTH_ABIS:-arm64-v8a}"
RUST_TARGETS=()
CARGO_NDK_TARGET_ARGS=()
for abi in "${ABIS[@]}"; do
  if [[ -z "${RUST_TARGET_FOR_ABI[$abi]:-}" ]]; then
    echo "unsupported ABI '$abi' in CLUETOOTH_ABIS; supported: ${!RUST_TARGET_FOR_ABI[*]}" >&2
    exit 2
  fi
  RUST_TARGETS+=("${RUST_TARGET_FOR_ABI[$abi]}")
  CARGO_NDK_TARGET_ARGS+=(--target "$abi")
done

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <jniLibs-output-directory>" >&2
  exit 2
fi

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "missing prerequisite: cargo-ndk ${EXPECTED_CARGO_NDK_VERSION} is not installed; run: cargo install cargo-ndk --version ${EXPECTED_CARGO_NDK_VERSION} --locked" >&2
  exit 1
fi
actual_cargo_ndk_version="$(cargo ndk --version 2>/dev/null || true)"
if [[ "$actual_cargo_ndk_version" != "cargo-ndk ${EXPECTED_CARGO_NDK_VERSION}" ]]; then
  echo "cargo-ndk version mismatch: expected ${EXPECTED_CARGO_NDK_VERSION}, got '${actual_cargo_ndk_version:-unknown}'; run: cargo install cargo-ndk --version ${EXPECTED_CARGO_NDK_VERSION} --locked --force" >&2
  exit 1
fi

ndk_home="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk_home" ]]; then
  for sdk_root in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "${HOME:-}/Android/Sdk"; do
    if [[ -n "$sdk_root" && -d "$sdk_root/ndk/$EXPECTED_NDK_VERSION" ]]; then
      ndk_home="$sdk_root/ndk/$EXPECTED_NDK_VERSION"
      break
    fi
  done
fi
if [[ -z "$ndk_home" || ! -f "$ndk_home/source.properties" ]]; then
  echo "missing prerequisite: Android NDK ${EXPECTED_NDK_VERSION}; set ANDROID_NDK_HOME to its installed directory" >&2
  exit 1
fi
actual_ndk_version="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ndk_home/source.properties" | head -n 1)"
if [[ "$actual_ndk_version" != "$EXPECTED_NDK_VERSION" ]]; then
  echo "Android NDK version mismatch: expected ${EXPECTED_NDK_VERSION}, got '${actual_ndk_version:-unknown}' from $ndk_home" >&2
  exit 1
fi
export ANDROID_NDK_HOME="$ndk_home"

if ! command -v rustup >/dev/null 2>&1; then
  echo "missing prerequisite: rustup is required to verify the pinned Android Rust targets" >&2
  exit 1
fi
installed_targets="$(rustup target list --installed)"
for target in "${RUST_TARGETS[@]}"; do
  if ! grep -Fxq "$target" <<<"$installed_targets"; then
    echo "missing Rust target: $target; run: rustup target add $target --toolchain 1.96.1" >&2
    exit 1
  fi
done

crate_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out_dir="$1"
cd "$crate_dir"

rm -rf "$out_dir"
mkdir -p "$out_dir"
cargo ndk \
  --platform "$ANDROID_API" \
  "${CARGO_NDK_TARGET_ARGS[@]}" \
  --output-dir "$out_dir" \
  build --locked --release --lib

for abi in "${ABIS[@]}"; do
  if [[ ! -s "$out_dir/$abi/libcluetooth_core.so" ]]; then
    echo "requested ABI $abi was not built: missing $out_dir/$abi/libcluetooth_core.so" >&2
    exit 1
  fi
done
