import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { after, test } from "node:test";
import { download, head } from "../src/download.mjs";
import { fileServer } from "./helpers.mjs";

const closers = [];
after(async () => {
  for (const c of closers) await c();
});
const BODY = randomBytes(1_000_003); // not a multiple of 10
const opts = { segments: 10, retries: 3, backoffMs: 1, userAgent: "test" };

async function serve(o) {
  const s = await fileServer(BODY, o);
  closers.push(s.close);
  return s;
}

test("10 concurrent range segments reassemble to the exact file", async () => {
  const s = await serve();
  const dest = join(mkdtempSync(join(tmpdir(), "az-")), "latest.csv.gz");
  const meta = await head(s.url);
  assert.equal(meta.contentLength, BODY.length);
  const r = await download(s.url, dest, meta, opts);
  assert.equal(r.segments, 10);
  assert.ok(readFileSync(dest).equals(BODY));
  const gets = s.requests.filter((q) => q.method === "GET");
  assert.equal(gets.length, 10);
  assert.ok(gets.every((q) => q.ifRange === '"v1"'));
  assert.equal(existsSync(`${dest}.parts`), false);
});

test("a failed segment is retried on its own, resuming where it stopped", async () => {
  // The segment starting at 300_003 drops halfway on its first try and errors on its second.
  const s = await serve({ failSegment: (start, n) => (start === 300_003 && n === 1 ? "drop" : start > 300_003 && start <= 400_003 && n === 1 ? "error" : null) });
  const dest = join(mkdtempSync(join(tmpdir(), "az-")), "latest.csv.gz");
  const events = [];
  await download(s.url, dest, await head(s.url), { ...opts, log: (e) => events.push(e) });
  assert.ok(readFileSync(dest).equals(BODY));
  const retried = new Set(events.filter((e) => e.event === "segment-retry").map((e) => e.segment));
  assert.deepEqual([...retried].sort(), [3]);
  // The resumed request asked only for the missing bytes of that segment.
  const seg3 = s.requests.filter((q) => q.range?.endsWith("-400003")).map((q) => Number(/bytes=(\d+)-/.exec(q.range)[1]));
  assert.ok(seg3.length >= 3, `segment 3 requests: ${seg3}`);
  assert.equal(seg3[0], 300_003);
  assert.ok(seg3[1] > 300_003, "the retry resumes after the bytes already written");
});

test("a segment that keeps failing fails the download and leaves no file", async () => {
  const s = await serve({ failSegment: (start) => (start === 500_005 ? "error" : null) });
  const dest = join(mkdtempSync(join(tmpdir(), "az-")), "latest.csv.gz");
  await assert.rejects(download(s.url, dest, await head(s.url), opts), /segment 5 incomplete after 3 attempts/);
  assert.equal(existsSync(dest), false);
  assert.equal(existsSync(`${dest}.parts`), false);
});

test("a file that changes during the download is not stitched together", async () => {
  const s = await serve({ etag: '"v2"' });
  const dest = join(mkdtempSync(join(tmpdir(), "az-")), "latest.csv.gz");
  const stale = { ...(await head(s.url)), etag: '"v1"' }; // HEAD saw v1, the server now has v2
  await assert.rejects(download(s.url, dest, stale, opts), /changed during the download/);
  assert.equal(existsSync(dest), false);
});
