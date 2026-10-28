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
    `bundleVersion()`, `ruleCount()`, `usesAddressRules()` (when false, the result
    depends on the bytes alone and callers can cache it by payload).
  * `evaluateRule(ruleJson, raw, addr?, addrType?) -> Boolean` for one rule.
* Regex grammar (`name.regex`, validated before compile): literals, escaped
  punctuation, `\d`, `.`, groups, `|`, quantifiers (a quantifier may be followed
  only by the lazy `?`: Python reads `a*+` as possessive, Rust rejects it).
  Character classes hold only literals, ranges, escaped punctuation and `\d`;
  they cannot nest or contain `&&`, `~~`, `--`, `||` (set operators in Rust,
  future syntax in Python), and `\w` is out. Rust and Python (the copy in
  `tests/python/`) implement the same check and a 60-pattern corpus must agree.
  **kb/lab's `rules.py` and its SQL path still have the looser check; the same
  tightening belongs there.**
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
  -> platform; cohort, company_identifier -> identifier). A BOM, CRLF and a
  closing `---` without a newline are accepted.
* `categories` in the page's own front matter win. Pages without any (the old
  snapshot has none) take them from `legacy-mapping.json`, which is only a
  stand-in: delete its entries once the wiki carries categories. A stale entry
  (unknown category, page gone) only warns; an unknown category in a page's own
  front matter is an error.
* Labels: English from `categories.json` / `categories.draft.json` of the
  checkout (or `--categories`), Chinese from `category-labels.zh.json`. Every
  active category needs both.
* `version` is `<newest page date>+<8 hex of a digest of pages and categories>`:
  it changes whenever anything the app reads changes (there is no way to set it
  by hand, so a changed bundle can never reuse a version). `rules_version` is a
  digest of the rules only: the app re-matches stored advertisements when it
  changes, not for a retitled page or a relabelled category.
* `mise run //cluetooth-android:wiki-bundle-test` runs the exporter tests
  (Node's built-in runner; the only dependency is `yaml`, locked).

## 3. Local stores (`.../cluetoothscanner/local/`)

Plain SQLite (`local-data.db`, WAL), not Room: no annotation processor, and the
SQL runs unchanged on the JVM for tests (`SqlDatabase` is the only seam; tests
use an in-memory SQLite through JDBC, which is newer than API 24's 3.9, so a unit
test scans the main SQL for UPSERT, window functions, RETURNING and the like).
The file is a cache: an upgrade or a downgrade wipes and recreates it, and an
unreadable file is deleted and recreated once.

| Table | What |
|---|---|
| `seen_payload` | one row per distinct advertisement: SHA-256 (hex), raw (any length, extended advertising included), first/last seen, count, `match_version` (the rules version) |
| `payload_match` | pages the payload matched (payload-level; address rules cannot match here) |
| `seen_product` | per page, forever: first/last seen, seen count, payload count, last payload id (may have expired) |
| `rematch_run`, `newly_identified` | a rules update: cursor, payloads checked, payloads that went from unmatched to matched |
| `recording` | a core scan session: start, end, distance, device count |
| `sighting_minute` | per recording, address, minute, payload: best RSSI, location of the best sample, sample count, first/last ms |
| `recording_route` | location samples per recording |

### Decisions

* **Seen payloads are kept for plain scanning too, not only while recording.**
  They never leave the phone. A payload expires 30 days after it was last heard,
  matched or not (beacons such as Eddystone TLM, MiBeacon, Tile and mesh proxy
  rotate payloads constantly, so keeping matched ones forever is unbounded),
  unless a sighting or an unacknowledged re-match still refers to it.
  **`seen_product` keeps a per-page summary forever**, which is what
  "previously seen products" reads; its payload count is the number of payload
  rows ever created for the page (an expired payload heard again counts again).
* **Sighting history and the route are written only while a recording (a core
  scan session) is active.** This holds when live scanning runs without a
  session (PR #28): recording = session = foreground service; nearby scanning
  only feeds the payload store and the in-range view. Retention is 30 days.
  A recording the process died in is closed at the time of its last sighting or
  route point.
* **Expiry** runs in short bounded steps (one transaction each: 6 hours of
  sightings or route, 2 000 payloads) on the single writer, at start and hourly,
  so it never blocks ingest for long.
* **Best sample of a minute:** among samples that have a fix the strongest RSSI
  wins; a sample with a fix beats one without; if no sample has a fix the
  strongest wins, without location. Ties keep the earlier sample. A fix counts
  for an observation when it is at most 5 s older (the core's freshness window).
* **Route:** a fix is stored when it is the first, moved at least 5 m, or the
  previous point is 30 s old; fixes worse than 100 m are ignored; distance only
  adds real steps (at least 5 m), so a stationary phone's drift adds nothing.
* **Re-match** happens when the bundle's `rules_version` differs from the rules a
  stored payload was matched under (so also for payloads heard while the bundle
  failed to load). It runs in the background after startup, one batch of 500
  per turn of the writer's queue (keyset pagination on a persisted cursor,
  resumable, matching before the write), and records which payloads went from
  unmatched to matched; pages a payload newly matches also join `seen_product`.
  Payloads inserted later are matched on arrival and never appear as newly
  identified. "N devices newly identified" = `newlyIdentified.size`, i.e.
  distinct advertisements: the store keeps no per-address history outside
  recordings.
* **Stored matches are payload-level (no address).** The live "in range" view
  also passes the address (type unknown) when some rule looks at it, so an
  `addr` rule can show a product there but never in the history. No rule of the
  current wiki uses `addr`; without such rules the live view uses the same
  per-payload cache as the store (one matcher call per distinct payload).
* **In range** keeps, per device, the union of the pages of its payloads heard
  within the window, so a device alternating advertisement types does not flicker.
* **Write ordering:** the payload-id cache is filled only after a transaction
  commits (a rolled-back insert gives its AUTOINCREMENT id out again) and is
  cleared after expiry. The distinct-address count of a recording is its own
  statement after the commit. The repository feeds the local stores only after the
  core admitted the observation, so both counts agree.
* **Failure:** if the database cannot be opened every query emits empty data and
  `status` says `UNAVAILABLE`; if the bundle or the native matcher (including an
  `UnsatisfiedLinkError`) cannot load, recording continues, nothing is identified
  and `status` says `IDENTIFICATION_UNAVAILABLE`. Skipped rules are logged.
* The stores are fed from `CluetoothRepository` (observations, location,
  session start/finish), so the existing scan service is untouched.
* Debug variant only for now; nothing here changes `versionName` or uploads.

### Queries and invalidation

Writers bump a version per table group (payloads, recordings, sightings, route,
re-match); a query depends on the groups it reads, so a flush that only added
sightings does not re-run `previouslySeenProducts`. A query re-runs at most once
a second, heavy ones (devices of a recording, sightings of a device or product)
once every 5 s. The versions replay to new collectors, so a change made before
the first query ran is not missed. Queries run on the IO dispatcher (also
`productsInRange`) and are shared between collectors of the same key
(`shareIn`, `WhileSubscribed`).

### API for the UI (`LocalData`, `CluetoothApplication.localData`)

| Call | Result |
|---|---|
| `catalog: StateFlow<WikiCatalog?>` | pages and categories (English and Chinese labels); `page(id)`, `category(id)` |
| `status: StateFlow<LocalDataStatus>` | `STARTING`, `READY`, `IDENTIFICATION_UNAVAILABLE`, `UNAVAILABLE` (+ message) |
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
be faked for previews (`status` has a default, so existing fakes keep compiling).
