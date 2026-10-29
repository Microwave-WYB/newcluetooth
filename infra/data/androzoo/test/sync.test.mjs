// Load-and-swap against a throwaway Postgres 18 (skipped without Docker).
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, readdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, before, describe, test } from "node:test";
import { gzipSync } from "node:zlib";
import pg from "pg";
import { changed, runSync } from "../src/sync.mjs";
import { FIXTURE, dockerAvailable, fileServer, query, startPostgres } from "./helpers.mjs";

const skip = !dockerAvailable() && "docker is not available";
const GZ = gzipSync(readFileSync(FIXTURE));
const quiet = () => {};

describe("androzoo sync", { skip }, () => {
  let db;
  const closers = [];
  before(async () => {
    db = await startPostgres();
  });
  after(async () => {
    for (const c of closers) await c();
    db?.stop();
  });

  const config = (listUrl, dataDir = mkdtempSync(join(tmpdir(), "az-data-"))) => ({
    databaseUrl: db.url,
    listUrl,
    dataDir,
    segments: 10,
    retries: 2,
    userAgent: "test",
  });

  test("one-off load of an existing .csv.gz", async () => {
    const dir = mkdtempSync(join(tmpdir(), "az-initial-"));
    const file = join(dir, "latest.csv.gz");
    writeFileSync(file, GZ);
    const r = await runSync(config("http://unused.invalid/latest.csv.gz"), { initialFile: file, emit: quiet });
    assert.deepEqual(r, { status: "loaded", rows: 6 });

    const { rows } = await query(db.url, "select * from androzoo.apks where pkg_name = 'org.sample.ble, scanner'");
    assert.equal(rows.length, 1);
    assert.equal(rows[0].markets, "appchina|mi.com");
    assert.equal(rows[0].vercode, "7");
    assert.match(rows[0].sha256, /^[0-9A-F]{64}$/);
    const odd = (await query(db.url, "select * from androzoo.apks where pkg_name = 'com.example.odd'")).rows[0];
    assert.equal(odd.vercode, "-1");
    assert.equal(odd.dex_date, null);
    assert.equal(odd.vt_detection, null);
    // An impossible date becomes null instead of failing the load.
    const bad = (await query(db.url, "select dex_date from androzoo.apks where pkg_name = 'com.example.tracker' and vercode = 350")).rows[0];
    assert.equal(bad.dex_date, null);
    const state = (await query(db.url, "select * from androzoo.sync_state")).rows[0];
    assert.equal(state.source, `file:${file}`);
    assert.equal(Number(state.content_length), GZ.length);
    assert.equal(Number(state.row_count), 6);
  });

  test("the example query: latest vercode and sha256 for a package", async () => {
    const { rows } = await query(
      db.url,
      "select vercode, sha256 from androzoo.apks where pkg_name = $1 order by vercode desc nulls last limit 1",
      ["com.example.tracker"],
    );
    assert.equal(rows[0].vercode, "350");
  });

  test("changed upstream: segmented download, load, swap; only the newest file is kept", async () => {
    const s = await fileServer(GZ, { etag: '"list-2"' });
    closers.push(s.close);
    const cfg = config(s.url);
    const r = await runSync(cfg, { emit: quiet });
    assert.deepEqual(r, { status: "loaded", rows: 6 });
    assert.equal(s.requests.filter((q) => q.method === "GET").length, 10);
    assert.deepEqual(readdirSync(cfg.dataDir), ["latest.csv.gz"]);
    const state = (await query(db.url, "select * from androzoo.sync_state")).rows[0];
    assert.equal(state.etag, '"list-2"');
    assert.equal(Number(state.content_length), GZ.length);
    // Indexes and constraint names survive the swap.
    const idx = (await query(db.url, "select indexname from pg_indexes where schemaname = 'androzoo' and tablename = 'apks' order by 1")).rows.map((x) => x.indexname);
    assert.deepEqual(idx, ["apks_pkey", "apks_pkg_name_idx", "apks_pkg_name_vercode_idx"]);
  });

  test("unchanged upstream (same ETag, Last-Modified, Content-Length): HEAD only", async () => {
    const s = await fileServer(GZ, { etag: '"list-2"' });
    closers.push(s.close);
    const r = await runSync(config(s.url), { emit: quiet });
    assert.deepEqual(r, { status: "unchanged" });
    assert.deepEqual(s.requests.map((q) => q.method), ["HEAD"]);
  });

  test("an incomplete download leaves the current table and state in place", async () => {
    const before = (await query(db.url, "select count(*)::int as n from androzoo.apks")).rows[0].n;
    const bigger = gzipSync(Buffer.concat([readFileSync(FIXTURE), Buffer.from("X".repeat(10))]));
    const s = await fileServer(bigger, { etag: '"list-3"', failSegment: (start) => (start > 0 && start < bigger.length / 5 ? "error" : null) });
    closers.push(s.close);
    const cfg = config(s.url);
    await assert.rejects(runSync(cfg, { emit: quiet }), /incomplete after 2 attempts/);
    assert.equal((await query(db.url, "select count(*)::int as n from androzoo.apks")).rows[0].n, before);
    assert.equal((await query(db.url, "select etag from androzoo.sync_state")).rows[0].etag, '"list-2"');
    assert.deepEqual(readdirSync(cfg.dataDir), []);
  });

  test("a file that fails to load (bad header) leaves the current table in place", async () => {
    const s = await fileServer(gzipSync(Buffer.from("not,an,androzoo,list\n1,2,3,4\n")), { etag: '"list-4"' });
    closers.push(s.close);
    await assert.rejects(runSync(config(s.url), { emit: quiet }), /unexpected list header/);
    assert.equal((await query(db.url, "select count(*)::int as n from androzoo.apks")).rows[0].n, 6);
    const left = (await query(db.url, "select tablename from pg_tables where schemaname = 'androzoo' order by 1")).rows.map((x) => x.tablename);
    assert.deepEqual(left, ["apks", "sync_state"]);
  });

  test("readers never see a half-loaded table: a reader during the load sees the old rows", async () => {
    // Hold a read lock in a reader transaction; the swap must wait for it, and the reader's
    // count stays at the old value throughout.
    const reader = new pg.Client({ connectionString: db.url });
    await reader.connect();
    closers.push(() => reader.end());
    await reader.query("begin isolation level repeatable read");
    const n0 = (await reader.query("select count(*)::int as n from androzoo.apks")).rows[0].n;
    const dir = mkdtempSync(join(tmpdir(), "az-initial-"));
    const file = join(dir, "latest.csv");
    writeFileSync(file, readFileSync(FIXTURE, "utf8").split("\n").slice(0, 4).join("\n") + "\n"); // 3 rows, plain .csv
    const load = runSync(config("http://unused.invalid/x"), { initialFile: file, emit: quiet });
    await new Promise((r) => setTimeout(r, 500));
    assert.equal((await reader.query("select count(*)::int as n from androzoo.apks")).rows[0].n, n0);
    await reader.query("commit");
    assert.deepEqual(await load, { status: "loaded", rows: 3 });
    assert.equal((await query(db.url, "select count(*)::int as n from androzoo.apks")).rows[0].n, 3);
  });

  test("the read-only role can read the swapped-in table", async () => {
    await query(db.url, "alter role androzoo_ro login password 'ro'");
    const url = db.url.replace("postgres:test@", "androzoo_ro:ro@");
    assert.equal((await query(url, "select count(*)::int as n from androzoo.apks")).rows[0].n, 3);
    await assert.rejects(query(url, "delete from androzoo.apks"), /permission denied/);
  });
});

test("changed() compares ETag, Last-Modified and Content-Length", () => {
  const state = { etag: '"a"', last_modified: "x", content_length: "10" };
  assert.equal(changed(state, { etag: '"a"', lastModified: "x", contentLength: 10 }), false);
  assert.equal(changed(state, { etag: '"b"', lastModified: "x", contentLength: 10 }), true);
  assert.equal(changed(state, { etag: '"a"', lastModified: "y", contentLength: 10 }), true);
  assert.equal(changed(state, { etag: '"a"', lastModified: "x", contentLength: 11 }), true);
  assert.equal(changed(undefined, { etag: '"a"' }), true);
});
