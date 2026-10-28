# Local data on the phone: rule matcher, wiki bundle, stores

Everything here runs on the phone and uploads nothing. The recording upload
path (Rust core, `scans/`, `gatt/`) is unchanged.

```
wiki checkout --(Node exporter, build time)--> app/src/main/assets/wiki-bundle.json
                                                 |
   scan callback -> CluetoothRepository -> LocalDataSink (LocalDataHub)
                                                 |  hash, match once per distinct payload
                                                 |  (Rust RuleMatcher via UniFFI)
                                                 v
                                   local-data.db (SQLite) --> LocalData (Flows) --> UI
```

## 1. Rule matcher (`cluetooth-core/src/matcher/`)

The Wireless Wiki describes a device with a `rule.json` predicate tree
(`all`/`any`/`not`, `name`, `company`, `uuid`, `struct`, `shape`, `addr`).
`RuleMatcher` evaluates those trees with the semantics of the Python reference
`kb.rules` (`cluetooth-agents/kb/src/kb/rules.py` on branch `kb/lab`): split the
advertisement into AD structures (stopping at a zero length or truncation),
derive name / company / UUIDs per structure, evaluate the tree.

* `rule.rs`: the serde types of `kb-interface` (`kb/lab`) without the schemars
  derive; unknown fields are rejected exactly as the schema does. When
  `kb-interface` lands on this branch, replace the copy with the dependency.
* `adv.rs`: `structures(raw)`; `compiled.rs`: `compile(node)` (validation as in
  `kb.rules.validate`, regexes built once) and `Compiled::eval`.
* UniFFI surface (additive, `API_VERSION` stays 6):
  * `RuleMatcher.fromBundleJson(json)`: compile every `pages[].rule`; a rule that
    does not compile is skipped and listed in `ruleErrors()`, never fatal.
  * `matchAdvertisement(raw, addr?, addrType?) -> List<pageId>` (bundle order),
    `bundleVersion()`, `ruleCount()`.
  * `evaluateRule(ruleJson, raw, addr?, addrType?) -> Boolean` for one rule.
* An `addr` condition never matches without an address; `type` also needs the
  address type (Android reports it from API 35 only).

Where the Python code is vague this implementation is strict and says so
(rule rejected, not guessed): shape items must be uppercase `TT/N`, `TT/*`,
`TT/a-b` with `a <= b`; UUIDs must be canonical; `uuid.ad` must be an AD type
that carries UUIDs (0x02-0x07, 0x16, 0x20, 0x21; the schema text also lists
0x14/0x15/0x1F, the Python evaluator derives UUIDs from the first set only and
so does this one).

Tests (`cluetooth-core/tests/matcher.rs`): the fixtures shared with the Python
and SQL evaluators (`tests/fixtures/rule_cases.json`), semantics case by case,
and every rule of the bundle asset (27 in the snapshot) with synthetic
advertisements built from the rule text (positives, near misses, unrelated
ones). All (rule, advertisement) pairs also run through
`tests/python/reference_rules.py`, a verbatim copy of the Python evaluator with
the SQL compiler removed, and the two must agree. Needs `python3` on `PATH`.

## 2. Wiki bundle (`cluetooth-android/tools/wiki-bundle/`)

`mise run //cluetooth-android:wiki-bundle` (`WIKI_DIR`, default
`~/Projects/wiki-snapshot`) reads `wiki-content/<kind>--<slug>/index.md` front
matter and `rule.json` and writes `app/src/main/assets/wiki-bundle.json`:

```json
{ "schema": 1, "version": "2026-10-26+9f9ccfd7",
  "categories": [{ "id", "label", "label_zh" }],
  "pages": [{ "id", "title", "kind", "one_line", "categories"?, "entity", "has_rule", "rule"? }] }
```

* Front matter is parsed as YAML, so the old JSON front matter and the new YAML
  one are both read. `kind` is `device|platform|identifier`; old kinds are mapped
  by `legacy-mapping.json` (model, product_family -> device; ecosystem, protocol
  -> platform; cohort, company_identifier -> identifier).
* `categories` in the page's own front matter win. Pages without any (the old
  snapshot has none) take them from `legacy-mapping.json`, which is only a
  stand-in: delete its entries once the wiki carries categories. An unknown
  category id is an error.
* Labels: English from `categories.json` / `categories.draft.json` of the
  checkout (or `--categories`), Chinese from `category-labels.zh.json`. Every
  active category needs both.
* `version` is `<newest page date>+<8 hex of a digest of pages and categories>`:
  it changes whenever anything the app reads changes, which is what triggers a
  re-match. The Chinese labels are part of the digest.
* `mise run //cluetooth-android:wiki-bundle-test` runs the exporter tests
  (Node's built-in runner; the only dependency is `yaml`, locked).

## 3. Local stores (`.../cluetoothscanner/local/`)

Plain SQLite (`local-data.db`, WAL, `user_version` migrations), not Room: no
annotation processor, and the SQL runs unchanged on the JVM for tests
(`SqlDatabase` is the only seam; tests use an in-memory SQLite through JDBC).
Queries are cold `Flow`s that re-emit after each write batch (about 1 s).

| Table | What |
|---|---|
| `seen_payload` | one row per distinct advertisement: SHA-256 (hex), raw, first/last seen, count, `match_version` |
| `payload_match` | pages the payload matched (payload-level; `match_version` says which bundle) |
| `rematch_run`, `newly_identified` | bundle updates: payloads that went from unmatched to matched |
| `recording` | a core scan session: start, end, distance, device count |
| `sighting_minute` | per recording, address, minute, payload: best RSSI, location of the best sample, sample count, first/last ms |
| `recording_route` | location samples per recording |

### Decisions

* **Seen payloads are kept for plain scanning too, not only while recording.**
  They never leave the phone and are what "previously seen products" is made
  of. Unmatched payloads nobody references are pruned after 30 days so
  rotating-payload noise stays bounded; matched ones stay.
* **Sighting history and the route are written only while a recording (a core
  scan session) is active.** Retention is 30 days, pruned at app start together
  with old recordings. A recording the process died in is closed at the time of
  its last sighting or route point.
* **Best sample of a minute:** among samples that have a fix the strongest RSSI
  wins; a sample with a fix beats one without; if no sample has a fix the
  strongest wins, without location. Ties keep the earlier sample. A fix counts
  for an observation when it is at most 5 s older (the core's freshness window).
* **Route:** a fix is stored when it is the first, moved at least 5 m, or the
  previous point is 30 s old; fixes worse than 100 m are ignored; distance only
  adds real steps (at least 5 m), so a stationary phone's drift adds nothing.
* **Bundle change at app start** re-matches every stored payload (batches of
  500, resumable: payloads already at the new `match_version` are skipped and an
  unfinished run is continued) and records which went from unmatched to matched.
  Payloads inserted later are matched on the spot and never appear as newly
  identified. "N devices newly identified" = `newlyIdentified.size`, i.e.
  distinct advertisements: the store keeps no per-address history outside
  recordings.
* **Stored matches are payload-level (no address).** The live "in range" view
  also passes the address (type unknown), so an `addr` rule can show a product
  there but never in the stored history. No rule of the current wiki uses
  `addr`.
* The stores are fed from `CluetoothRepository` (observations, location,
  session start/finish), so the existing scan service is untouched. Today every
  scan is a session, hence every scan is a recording; when the UI separates
  "nearby" scanning from recording it only has to start the core session later:
  the hub already distinguishes the two.
* Debug variant only for now; nothing here changes `versionName` or uploads.

### API for the UI (`LocalData`, `CluetoothApplication.localData`)

| Call | Result |
|---|---|
| `catalog: StateFlow<WikiCatalog?>` | pages and categories (English and Chinese labels); `page(id)`, `category(id)` |
| `productsInRange(windowMs = 30 000)` | `InRangeSnapshot`: identified products with device count, strongest RSSI, last seen; unidentified count |
| `previouslySeenProducts()` | `List<CategoryProducts>` (category order of the wiki, then uncategorized) |
| `previouslySeenProducts(categoryId)` | `List<SeenProduct>` newest first |
| `recordings()` / `recording(id)` | `Recording`: start, end, duration, distance, device count, active |
| `devicesInRecording(id)` | `RecordedDevice`: address, first/last seen, strongest RSSI, minutes, matched pages, best location |
| `route(id)` | `RoutePoint` list, oldest first |
| `sightingsOfDevice(addr, limit)` | `Sighting` minute rows across recordings, newest first |
| `sightingsOfProduct(pageId, limit)` | the same, for every payload that matched the page |
| `pendingRematchReport()` + `acknowledgeRematch(id)` | the newest unacknowledged re-match with `newlyIdentified` |

Models are in `local/Models.kt`; the interface is `local/LocalData.kt` and can
be faked for previews.
