import { test, describe, before, after } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { createRequire } from "node:module";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import {
  run, buildParams, paramsHash, normalizeEndpoint, classify, computeQuota, collectorRecord, escapeNul, MAX_RESULTS_PER_PAGE, EXIT, openPgStore,
  callApi, loadApiKey,
} from "../wigle.mjs";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const KEY = "dGVzdDpzZWNyZXQtdG9rZW4=";

/** In-memory store with the same interface as the pg store. */
class FakeStore {
  constructor() { this.rows = []; this.daily = []; this.todayDay = "2026-10-26"; }
  async insert(r) { this.rows.push({ id: this.rows.length + 1, ...r }); return this.rows.length; }
  async findPrior(k) {
    const m = this.rows.filter((r) => r.endpoint === k.endpoint && r.params_hash === k.params_hash && r.search_after === k.search_after);
    return m.length ? { id: m.at(-1).id, called_at: m.at(-1).called_at } : null;
  }
  async rowExists(k) {
    return this.rows.some((r) => r.params_hash === k.params_hash && r.search_after === k.search_after && r.called_at.getTime() === k.called_at.getTime());
  }
  async today() { return this.todayDay; }
  async dailyCounts() { return this.daily; }
}

/** Fake WiGLE: scripted responses, records requests. */
class FakeWigle {
  constructor() { this.requests = []; this.script = []; }
  async start() {
    this.server = http.createServer((req, res) => {
      const url = new URL(req.url, "http://x");
      this.requests.push({ path: url.pathname, query: Object.fromEntries(url.searchParams), auth: req.headers.authorization });
      const step = this.script.shift() ?? { status: 500, body: "script exhausted" };
      if (step.destroy) return req.socket.destroy();
      res.writeHead(step.status ?? 200, { "content-type": "application/json" });
      res.end(typeof step.body === "string" ? step.body : JSON.stringify(step.body));
    });
    await new Promise((r) => this.server.listen(0, "127.0.0.1", r));
    this.baseUrl = `http://127.0.0.1:${this.server.address().port}`;
  }
  stop() { return new Promise((r) => { this.server.closeAllConnections?.(); this.server.close(r); }); }
}
const page = (n, next, total = 250) => ({ success: true, totalResults: total, resultCount: n, results: Array.from({ length: n }, (_, i) => ({ netid: `n${i}` })), searchAfter: next });

function harness(wigle, store) {
  const out = [], err = [];
  return {
    out, err, store,
    ctx: { store, apiKey: KEY, baseUrl: wigle.baseUrl, timeoutMs: 5000, print: (o) => out.push(o), notice: (m) => err.push(m) },
  };
}

describe("params", () => {
  test("forces resultsPerPage per endpoint, strips caller value, sorts keys, stringifies", () => {
    const p = buildParams("/api/v2/bluetooth/search", { showBt: "true", resultsPerPage: "5", latrange1: "41.5" });
    assert.deepEqual(Object.keys(p), ["latrange1", "resultsPerPage", "showBt"]);
    assert.equal(p.resultsPerPage, String(MAX_RESULTS_PER_PAGE["/api/v2/bluetooth/search"]));
  });
  test("hash is sha256 of canonical JSON and independent of argument order", () => {
    const a = buildParams("/api/v2/network/search", { ssid: "x", country: "US" });
    const b = buildParams("/api/v2/network/search", { country: "US", ssid: "x" });
    assert.equal(paramsHash(a), paramsHash(b));
    assert.match(paramsHash(a), /^[0-9a-f]{64}$/);
  });
  test("endpoint normalization and unknown endpoint", () => {
    assert.equal(normalizeEndpoint("bluetooth/search"), "/api/v2/bluetooth/search");
    assert.equal(normalizeEndpoint("/api/v2/network/search"), "/api/v2/network/search");
    assert.throws(() => normalizeEndpoint("network/upload"), /unknown endpoint/);
  });
  test("searchAfter= is rejected", () => {
    assert.throws(() => buildParams("/api/v2/network/search", { searchAfter: "1" }), /--page-after/);
  });
});

describe("classify", () => {
  test("daily-limit payload words", () => {
    for (const m of ["too many queries today", "Daily limit exceeded", "request throttled"]) {
      assert.equal(classify({ status: 200, text: JSON.stringify({ success: false, message: m }) }).kind, "limit");
    }
    assert.equal(classify({ status: 200, text: JSON.stringify({ success: false, message: "bad param" }) }).kind, "api");
  });
});

describe("live path against fake WiGLE", () => {
  let w;
  before(async () => { w = new FakeWigle(); await w.start(); });
  after(() => w.stop());

  test("search sends forced resultsPerPage, cursor, auth; archives; reports", async () => {
    w.requests.length = 0;
    w.script = [{ body: page(100, "c2") }];
    const h = harness(w, new FakeStore());
    const code = await run(["search", "bluetooth/search", "latrange1=1", "resultsPerPage=7", "--page-after", "c1", "--caller", "t"], h.ctx);
    assert.equal(code, 0);
    const q = w.requests[0];
    assert.equal(q.path, "/api/v2/bluetooth/search");
    assert.equal(q.query.resultsPerPage, "100");
    assert.equal(q.query.searchAfter, "c1");
    assert.equal(q.auth, `Basic ${KEY}`);
    const row = h.store.rows[0];
    assert.equal(row.search_after, "c1");
    assert.equal(row.next_search_after, "c2");
    assert.equal(row.total, 250);
    assert.equal(row.caller, "t");
    assert.equal(row.params.searchAfter, undefined);
    assert.equal(row.params.resultsPerPage, "100");
    assert.deepEqual(h.out[0], { id: 1, http_status: 200, total: 250, results_count: 100, next_search_after: "c2", done: false });
    assert.ok(!JSON.stringify(h.out).includes(KEY));
  });

  test("final page is done; repeat prints a notice but still calls", async () => {
    w.requests.length = 0;
    w.script = [{ body: page(3, null) }, { body: page(3, null) }];
    const h = harness(w, new FakeStore());
    await run(["search", "network/search", "ssid=a"], h.ctx);
    assert.equal(h.out[0].done, true);
    assert.equal(h.err.length, 0);
    await run(["search", "network/search", "ssid=a"], h.ctx);
    assert.equal(w.requests.length, 2);
    assert.match(h.err[0], /archived before as id 1/);
    assert.equal(h.store.rows.length, 2);
  });

  test("pages follows cursors until null, no retries", async () => {
    w.requests.length = 0;
    w.script = [{ body: page(100, "a") }, { body: page(100, "b") }, { body: page(5, null) }];
    const h = harness(w, new FakeStore());
    const code = await run(["pages", "bluetooth/search", "x=1", "--max-pages", "10"], h.ctx);
    assert.equal(code, 0);
    assert.deepEqual(w.requests.map((r) => r.query.searchAfter), [undefined, "a", "b"]);
    assert.equal(h.out.at(-1).done, true);
    assert.equal(h.store.rows.length, 3);
  });

  test("pages respects --max-pages", async () => {
    w.script = [{ body: page(100, "a") }, { body: page(100, "b") }, { body: page(100, "c") }];
    const h = harness(w, new FakeStore());
    await run(["pages", "bluetooth/search", "x=1", "--max-pages", "2"], h.ctx);
    assert.equal(h.store.rows.length, 2);
    assert.deepEqual(h.out.at(-1), { summary: true, pages: 2, done: false, next_search_after: "b", stopped_on_error: false });
  });

  test("429 is stored and exits 3; pages stops", async () => {
    w.script = [{ body: page(100, "a") }, { status: 429, body: { success: false, message: "too many queries today" } }, { body: page(1, null) }];
    const h = harness(w, new FakeStore());
    const code = await run(["pages", "bluetooth/search", "x=1", "--max-pages", "5"], h.ctx);
    assert.equal(code, EXIT.LIMIT);
    assert.equal(h.store.rows.length, 2);
    assert.equal(h.store.rows[1].http_status, 429);
    assert.match(h.store.rows[1].error, /^http 429/);
    assert.equal(w.script.length, 1);
  });

  test("payload daily limit (HTTP 200, success:false) exits 3", async () => {
    w.script = [{ body: { success: false, message: "Daily limit reached" } }];
    const h = harness(w, new FakeStore());
    assert.equal(await run(["search", "bluetooth/search", "x=1"], h.ctx), EXIT.LIMIT);
    assert.match(h.store.rows[0].error, /^daily limit:/);
    assert.equal(h.store.rows[0].http_status, 200);
  });

  test("other HTTP error exits 2 and is stored with its body", async () => {
    w.script = [{ status: 502, body: "<html>bad gateway</html>" }];
    const h = harness(w, new FakeStore());
    assert.equal(await run(["search", "bluetooth/search", "x=1"], h.ctx), EXIT.FAILED);
    assert.equal(h.store.rows[0].http_status, 502);
    assert.equal(h.store.rows[0].response, null);
    assert.match(h.store.rows[0].error, /bad gateway/);
  });

  test("network failure is stored with null status and exits 2", async () => {
    w.script = [{ destroy: true }];
    const h = harness(w, new FakeStore());
    assert.equal(await run(["search", "bluetooth/search", "x=1"], h.ctx), EXIT.FAILED);
    assert.equal(h.store.rows[0].http_status, null);
    assert.match(h.store.rows[0].error, /^network:/);
    assert.ok(!h.store.rows[0].error.includes(KEY));
  });

  test("usage errors exit 1 without calling", async () => {
    w.requests.length = 0;
    const h = harness(w, new FakeStore());
    assert.equal(await run(["search", "nope/search"], h.ctx), EXIT.USAGE);
    assert.equal(await run(["pages", "bluetooth/search", "x=1"], h.ctx), EXIT.USAGE);
    assert.equal(w.requests.length, 0);
  });
});

test("escapeNul replaces U+0000 in nested strings and keys", () => {
  assert.deepEqual(escapeNul({ "a\0": ["x\0y", 1, null] }), { "a\\u0000": ["x\\u0000y", 1, null] });
});

describe("quota", () => {
  test("estimate from the most recent limit day, max over 14", () => {
    const days = [
      { day: "2026-10-26", ok: 40, limited: 0, last_limited: null },
      { day: "2026-10-25", ok: 150, limited: 2, last_limited: "2026-10-25T20:00:00.000Z" },
      { day: "2026-10-24", ok: 90, limited: 0, last_limited: null },
      { day: "2026-10-23", ok: 170, limited: 1, last_limited: "2026-10-23T21:00:00.000Z" },
    ];
    const q = computeQuota(days, "2026-10-26");
    assert.equal(q.used_today, 40);
    assert.equal(q.rate_limited_today, 0);
    assert.equal(q.last_rate_limited_at, "2026-10-25T20:00:00.000Z");
    assert.equal(q.estimated_daily_limit, 150);
    assert.equal(q.estimated_daily_limit_max_14d, 170);
    assert.equal(q.remaining, 110);
  });
  test("unknown without any limit day", async () => {
    const s = new FakeStore();
    s.daily = [{ day: "2026-10-26", ok: 3, limited: 0, last_limited: null }];
    const out = [];
    await run(["quota"], { store: s, print: (o) => out.push(o), notice() {} });
    assert.equal(out[0].remaining, "unknown");
    assert.equal(out[0].estimated_daily_limit, null);
    assert.equal(out[0].used_today, 3);
  });
});

function mkCollectorDir() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "wigle-coll-"));
  const add = (id, meta, raw) => {
    const d = path.join(root, "requests", id);
    fs.mkdirSync(d, { recursive: true });
    fs.writeFileSync(path.join(d, "metadata.json"), JSON.stringify({ endpoint: "https://api.wigle.net/api/v2/bluetooth/search", request_headers: { Authorization: "[REDACTED]" }, ...meta }));
    if (raw !== undefined) fs.writeFileSync(path.join(d, "response.raw"), raw);
  };
  const params = { latrange1: 41.89, latrange2: 41.9, longrange1: -87.6, longrange2: -87.5, showBt: "true", showBle: "true", minQoS: 7, resultsPerPage: 100 };
  add("a1", { params, started_utc: "2026-10-22T19:40:01.836297+00:00", http_status: 200 }, JSON.stringify(page(2, "cur1", 12)));
  add("a2", { params: { ...params, searchAfter: "cur1" }, started_utc: "2026-10-22T19:45:01+00:00", http_status: 200 }, JSON.stringify(page(0, null, 12)));
  add("a3", { params, started_utc: "2026-10-23T19:45:01+00:00", http_status: 429 }, JSON.stringify({ success: false, message: "too many queries today" }));
  add("a4", { params, started_utc: "2026-10-23T20:45:01+00:00", http_status: null, transport_error_type: "TimeoutError", response_truncated: true }, "");
  add("a5", { params, started_utc: "2026-10-23T21:45:01+00:00", http_status: 502 }, "<html>x</html>");
  fs.mkdirSync(path.join(root, "requests", "broken"));
  return root;
}

describe("collector import", () => {
  test("record mapping", () => {
    const root = mkCollectorDir();
    const r = collectorRecord(path.join(root, "requests", "a2"));
    assert.equal(r.search_after, "cur1");
    assert.equal(r.params.searchAfter, undefined);
    assert.equal(r.params.minQoS, "7");
    assert.equal(r.caller, "cron:us-osm-hospitals");
    assert.equal(r.called_at.toISOString(), "2026-10-22T19:45:01.000Z");
    assert.equal(r.total, 12);
    assert.equal(r.endpoint, "/api/v2/bluetooth/search");
    // same hash as a live call with the same query
    const live = buildParams("/api/v2/bluetooth/search", { latrange1: "41.89", latrange2: "41.9", longrange1: "-87.6", longrange2: "-87.5", showBt: "true", showBle: "true", minQoS: "7" });
    assert.equal(r.params_hash, paramsHash(live));
  });
  test("imports once, is idempotent, keeps errors", async () => {
    const root = mkCollectorDir();
    const store = new FakeStore();
    const out = [];
    const ctx = { store, print: (o) => out.push(o), notice() {} };
    await run(["import-collector", root], ctx);
    assert.deepEqual(out[0], { imported: 5, skipped: 0, errors_kept: 3, unreadable: 1 });
    await run(["import-collector", root], ctx);
    assert.deepEqual(out[1], { imported: 0, skipped: 5, errors_kept: 0, unreadable: 1 });
    assert.equal(store.rows.length, 5);
    assert.equal(store.rows.find((r) => r.http_status === null).error.startsWith("network:"), true);
    assert.equal(JSON.stringify(store.rows).includes("REDACTED"), false);
  });
});

// ---- integration: throwaway postgres container ----
const MIGRATION = process.env.WIGLE_MIGRATION_SQL
  || path.join(os.homedir(), ".paseo/worktrees/1pefufa6/sync/db/db/migrations/20261027070000_wigle_archive.sql");
const home = process.env.RESEARCH_AGENT_HOME || `${os.homedir()}/.local/share/research-agent`;
let pgAvailable = false;
try { createRequire(`${home}/tools/node_modules/`)("pg"); pgAvailable = true; } catch { /* skip */ }
const dockerOk = spawnSync("docker", ["info"], { stdio: "ignore" }).status === 0;

describe("postgres integration", { skip: !dockerOk ? "docker unavailable" : !pgAvailable ? "pg not installed" : !fs.existsSync(MIGRATION) ? "migration SQL not found" : false }, () => {
  let name, store, port;
  before(async () => {
    name = `wigle-test-${process.pid}`;
    const r = spawnSync("docker", ["run", "-d", "--rm", "--name", name, "-e", "POSTGRES_PASSWORD=pw", "-p", "127.0.0.1::5432", "postgres:17-alpine"], { encoding: "utf8" });
    assert.equal(r.status, 0, r.stderr);
    port = /:(\d+)\s*$/m.exec(spawnSync("docker", ["port", name, "5432/tcp"], { encoding: "utf8" }).stdout.split("\n")[0])[1];
    const url = `postgres://postgres:pw@127.0.0.1:${port}/postgres`;
    for (let i = 0; ; i++) {
      try { store = await openPgStore(url); break; } catch (e) { if (i > 60) throw e; await new Promise((r) => setTimeout(r, 500)); }
    }
    const sql = fs.readFileSync(MIGRATION, "utf8").split("-- migrate:down")[0];
    const ps = spawnSync("docker", ["exec", "-i", name, "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1"], { input: sql, encoding: "utf8" });
    assert.equal(ps.status, 0, ps.stderr);
  });
  after(async () => { await store?.close(); spawnSync("docker", ["rm", "-f", name], { stdio: "ignore" }); });

  test("insert/findPrior/rowExists/quota round trip, plus collector import", async () => {
    const root = mkCollectorDir();
    const out = [];
    const ctx = { store, print: (o) => out.push(o), notice() {} };
    await run(["import-collector", root], ctx);
    await run(["import-collector", root], ctx);
    assert.deepEqual(out[0], { imported: 5, skipped: 0, errors_kept: 3, unreadable: 1 });
    assert.deepEqual(out[1], { imported: 0, skipped: 5, errors_kept: 0, unreadable: 1 });

    const p = buildParams("/api/v2/bluetooth/search", { latrange1: "41.89", latrange2: "41.9", longrange1: "-87.6", longrange2: "-87.5", showBt: "true", showBle: "true", minQoS: "7" });
    const prior = await store.findPrior({ endpoint: "/api/v2/bluetooth/search", params_hash: paramsHash(p), search_after: "cur1" });
    assert.ok(prior);
    assert.equal(await store.findPrior({ endpoint: "/api/v2/bluetooth/search", params_hash: paramsHash(p), search_after: "nope" }), null);

    const days = await store.dailyCounts(100000);
    assert.equal(days.reduce((s, d) => s + d.ok, 0), 2);
    const d23 = days.find((d) => d.day === "2026-10-23");
    assert.equal(d23.limited, 1);
    assert.equal(d23.last_limited, "2026-10-23T19:45:01.000Z");
    assert.match(await store.today(), /^\d{4}-\d\d-\d\d$/);
    // re-import keys on the millisecond timestamp the first import stored
    assert.equal(await store.rowExists({ params_hash: paramsHash(p), search_after: null, called_at: new Date("2026-10-22T19:40:01.836Z") }), true);
  });

  test("Pacific day resets at 00:05 America/Los_Angeles", async () => {
    const mk = (iso) => store.insert({ endpoint: "/api/v2/network/search", params: { a: "1" }, params_hash: "boundary", search_after: null, next_search_after: null, total: null, http_status: 200, error: null, caller: "t", called_at: new Date(iso), response: null });
    const id = await store.insert({ endpoint: "/e", params: {}, params_hash: "nul", search_after: null, next_search_after: null, total: null, http_status: 200, error: null, caller: "t", called_at: new Date(), response: { ssid: "a\0b" } });
    assert.ok(id > 0);
    await mk("2026-01-15T08:04:00Z"); // 00:04 PST: still Jan 14
    await mk("2026-01-15T08:06:00Z"); // 00:06 PST: Jan 15
    const days = await store.dailyCounts(100000);
    assert.equal(days.find((d) => d.day === "2026-01-14").ok, 1);
    assert.equal(days.find((d) => d.day === "2026-01-15").ok, 1);
  });
});

describe("behind keyproxy", () => {
  test("no key is needed when WIGLE_BASE_URL points at the proxy", () => {
    if (fs.existsSync(path.join(os.homedir(), ".config/wigle/api-key"))) return; // a local key file wins
    assert.equal(loadApiKey({ WIGLE_BASE_URL: "http://keyproxy:8080/wigle" }), null);
    assert.throws(() => loadApiKey({}), /no WiGLE API key/);
  });

  test("without a key no Authorization header is sent; the caller goes in X-Caller", async () => {
    let seen;
    const fetchImpl = async (url, init) => {
      seen = { url, headers: init.headers };
      return { status: 200, text: async () => "{}" };
    };
    await callApi({ endpoint: "/api/v2/bluetooth/search", params: { name: "x" }, apiKey: null, caller: "root:task-7", baseUrl: "http://keyproxy:8080/wigle", fetchImpl });
    assert.equal(seen.url, "http://keyproxy:8080/wigle/api/v2/bluetooth/search?name=x");
    assert.equal(seen.headers.Authorization, undefined);
    assert.equal(seen.headers["X-Caller"], "root:task-7");
  });
});
