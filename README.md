# newcluetooth

BLE observations, PostGIS migrations, and a Python sync pipeline.

## Local development

Install [mise](https://mise.jdx.dev/) and Docker with Compose. From this root:

```sh
mise install      # Python 3.14, uv, dbmate 2.32.0; no Node/npm
mise run install  # uv sync --locked in cluetooth-sync/
mise run check    # lint, format, types, integration tests (Docker required)
```

The Python package versions remain in `cluetooth-sync/uv.lock`. Integration
tests create disposable PostGIS containers and run dbmate from mise's PATH.
For a standalone local test database, see [db/README.md](db/README.md).

`mise run //cluetooth-sync:sync -- --help` invokes the local sync CLI.
To run an actual sync, explicitly provide `CLUETOOTH_DATABASE_URL`,
`CLUETOOTH_BUCKET`, `CLUETOOTH_PRIVATE_KEY_PATH` (an absolute path to a local
private-key file), and optionally `GOOGLE_APPLICATION_CREDENTIALS` (an absolute
path to a local service-account JSON file), `CLUETOOTH_PREFIX`,
`CLUETOOTH_MIRROR_DIR`, or `CLUETOOTH_MAX_BLOBS` in your own environment.
Keep local credentials in a gitignored `secrets/` directory, not in mise config.
Install and check do not load these credentials or contact production services;
integration tests use disposable local PostGIS containers. Running the sync CLI
without `--help` can contact GCS and Postgres. Production Compose services are
separate and unchanged by the local mise toolchain.
