# Producer fixtures

Plaintext objects written by the real producer code (Rust core, Kotlin GATT engine and
serializer). Paths mirror the object paths; encrypt them with any test key to get the
`.encrypted` objects. See "Producer fixtures" in `docs/payload-schema-v3.md`.

Regenerate after an intended producer change:

```sh
CLUETOOTH_UPDATE_FIXTURES=1 cargo test --manifest-path cluetooth-core/Cargo.toml --test producer_fixtures
CLUETOOTH_UPDATE_FIXTURES=1 mise run //scanner:test
```
