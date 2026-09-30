# newcluetooth

BLE collection in Android/Rust, PostGIS migrations, and Python sync. The shared
producer/consumer contract is [payload schema v2](docs/payload-schema-v2.md).
Legacy Android 0.0.1–0.0.4 JSONL remains supported.

## Local development

Install [mise](https://mise.jdx.dev/) and Docker with Compose. From this root:

```sh
mise install      # Python 3.14, uv, dbmate 2.32.0, gcloud 587.0.0,
                  # host Rust 1.96.1 + fmt/clippy (Android targets are opt-in),
                  # Temurin JDK 17.0.20+8, Android command-line tools 19.0
mise run install  # uv sync --locked; no SDK license acceptance
```

Rust is pinned only in root `mise.toml`; `Cargo.toml` declares the same minimum
compiler and `Cargo.lock` pins packages. Invoke Cargo through mise, including
from IDEs. Rustup/Cargo homes live under `~/.local/share/mise/newcluetooth/`,
separate from the user's global toolchains. Android SDK packages/licenses live
in that project's `android-sdk/`, not mise's shared command-line-tools install
or an existing Android Studio SDK. The Gradle wrapper remains authoritative;
no system Gradle is required. See [core](cluetooth-core/README.md) and
[Android setup](cluetooth-android/README.md) for explicit SDK/NDK prerequisites.

## Check boundaries

```sh
mise run check                        # sequential heavyweight host stages
mise run //cluetooth-core:check        # fmt, strict clippy, Rust tests
mise run //cluetooth-android:check     # genuine Kotlin/UniFFI compilation, JVM tests, strict lint
mise run //cluetooth-sync:check        # Python lint/types + all fixture/DB/interoperability tests
mise run native-check                 # separate four-ABI API-24 native build
mise run //cluetooth-android:build     # separate product debug APK build; no install/launch
```

Host Android tasks use guarded `-PcluetoothHostChecks=true` to leave Google
Services unapplied only for explicit JVM/lint selectors; product/extra/default
requests fail closed. They exclude native packaging, **not** Kotlin compilation,
generated bindings, JVM tests or lint. SDK platform 35/build-tools 35.0.0 are required; they do not
create or run an app. Host success is not proof of native packaging, product
APK correctness, or device execution. Root checks serialize Rust, Android, then
Python to avoid simultaneous heavyweight compilers; project Cargo jobs are capped
at two without global settings. Python test tasks first
build the Rust encryption example outside the test's 120-second execution
limit; its deterministic key is synthetic and Cargo uses mise's PATH.
Standalone `uv run pytest` needs that explicit prerequisite too.

Python packages remain locked in `cluetooth-sync/uv.lock`. DB tests create
local disposable PostGIS containers and run dbmate from mise's PATH; no
production URL, auth, or cloud objects are needed. Production sync's Docker
image remains Python-only (`uv sync --frozen --no-dev`); it does not run tests
or Cargo. See [db/README.md](db/README.md) for local database tasks and
[payload compatibility](docs/payload-schema-v2.md#coordinate-migration-compatibility)
for the coordinate migration's new-write/legacy policy.

The tracked Firebase client config is public APK metadata for the base package,
associated with project `cluetooth-1da02` / its production bucket, not a test
config or access control. It has no `.debug` client; debug APKs need a separately
registered matching client, preferably in an explicitly chosen test project.
Do not fabricate one or remove the suffix. Client config may be committed after
verification; Firebase rules/App Check/API-key restrictions remain separate.
Private keys, service-account credentials and signing keystores must never be
committed. Device smoke is explicit opt-in and app startup can schedule uploads;
see Android docs. Default checks never install/launch an app or upload data.

## Cloud/production operations are separate

Mise installs gcloud via `vfox:mise-plugins/vfox-gcloud`, pinned to 587.0.0.
`mise exec -- gcloud --version` and `mise exec -- gcloud help` require no auth.

`mise run //cluetooth-sync:sync -- --help` invokes the local sync CLI. To run an
actual sync, explicitly provide `CLUETOOTH_DATABASE_URL`, `CLUETOOTH_BUCKET`,
`CLUETOOTH_PRIVATE_KEY_PATH` (absolute local path) and optionally
`GOOGLE_APPLICATION_CREDENTIALS` (absolute service-account file path),
`CLUETOOTH_PREFIX`, `CLUETOOTH_MIRROR_DIR`, or `CLUETOOTH_MAX_BLOBS` in your own
environment. Keep credentials in gitignored `secrets/`, not mise config.
Running sync without `--help` can access GCS/Postgres. Production Compose is
separate; do not use it as test infrastructure. Historical `scans/schema=v2/`
objects are intentionally rejected, preserved, and require a separate migration
decision—not speculative suffix decoding, conversion, or deletion.
