import assert from "node:assert/strict";
import { after, describe, test } from "node:test";
import { pacificDay } from "../src/budget.mjs";
import { SECRETS, fakeUpstream, startProxy } from "./helpers.mjs";

const closers = [];
after(async () => {
  for (const c of closers) await c();
});

async function setup({ env = {}, handler, now, stateFile } = {}) {
  const up = await fakeUpstream(
    handler ??
      ((_req, _body, res) => {
        res.writeHead(200, { "content-type": "application/json" });
        res.end('{"success":true,"results":[]}');
      }),
  );
  const proxy = await startProxy({ upstream: up.url, env, now, stateFile });
  closers.push(proxy.close, up.close);
  return { up, proxy };
}

const wigle = (proxy, qs = "name=DT-TAG", caller = "root") =>
  fetch(`${proxy.url}/wigle/api/v2/bluetooth/search?${qs}`, { headers: { "x-caller": caller } });

describe("wigle", () => {
  test("only Bluetooth search; other endpoints and parameters are refused", async () => {
    const { up, proxy } = await setup();
    for (const path of [
      "/wigle/api/v2/network/search?ssid=x",
      "/wigle/api/v2/cell/search",
      "/wigle/api/v2/network/detail?netid=x",
      "/wigle/api/v2/profile/user",
      "/wigle/api/v2/bluetooth/detail?netid=x",
      "/wigle/api/v3/bluetooth/search",
    ]) {
      const r = await fetch(proxy.url + path);
      assert.equal(r.status, 403, path);
    }
    assert.equal((await wigle(proxy, "netid=AA:BB")).status, 400);
    assert.equal((await wigle(proxy, "name=a&name=b")).status, 400);
    assert.equal((await fetch(`${proxy.url}/wigle/api/v2/bluetooth/search`, { method: "POST" })).status, 405);
    assert.equal(up.requests.length, 0);
  });

  test("name and namelike pass through unchanged, with the key added", async () => {
    const { up, proxy } = await setup();
    const r = await wigle(proxy, `namelike=${encodeURIComponent("DT-TAG%")}&name=${encodeURIComponent("Bose QC+ 35")}`);
    assert.equal(r.status, 200);
    const u = new URL(up.requests[0].url, "http://x");
    assert.equal(u.pathname, "/api/v2/bluetooth/search");
    assert.equal(u.searchParams.get("namelike"), "DT-TAG%");
    assert.equal(u.searchParams.get("name"), "Bose QC+ 35");
    assert.equal(up.requests[0].headers.authorization, `Basic ${SECRETS.wigle}`);
    assert.match(up.requests[0].headers["user-agent"], /^cluetooth-keyproxy\//);
  });

  test("daily budget stops with the reserve left", async () => {
    const { up, proxy } = await setup({ env: { WIGLE_DAILY_BUDGET: "13", WIGLE_RESERVE: "10" } });
    const statuses = [];
    for (let i = 0; i < 5; i++) statuses.push((await wigle(proxy)).status);
    assert.deepEqual(statuses, [200, 200, 200, 429, 429]);
    assert.equal(up.requests.length, 3);
    const b = await (await fetch(`${proxy.url}/wigle/budget`)).json();
    assert.equal(b.used, 3);
    assert.equal(b.remaining, 0);
  });

  test("the budget survives a restart and resets on the next Pacific day", async () => {
    let t = Date.parse("2026-10-28T12:00:00Z");
    const first = await setup({ env: { WIGLE_DAILY_BUDGET: "12", WIGLE_RESERVE: "10" }, now: () => t });
    assert.equal((await wigle(first.proxy)).status, 200);
    assert.equal((await wigle(first.proxy)).status, 200);
    const second = await setup({ env: { WIGLE_DAILY_BUDGET: "12", WIGLE_RESERVE: "10" }, now: () => t, stateFile: first.proxy.stateFile });
    assert.equal((await wigle(second.proxy)).status, 429);
    t = Date.parse("2026-10-29T08:00:00Z"); // 01:00 in Los Angeles: a new WiGLE day
    assert.equal((await wigle(second.proxy)).status, 200);
  });

  test("a WiGLE rate-limit answer stops further calls for the day", async () => {
    const { up, proxy } = await setup({
      handler: (_req, _body, res) => {
        res.writeHead(200, { "content-type": "application/json" });
        res.end('{"success":false,"message":"too many queries today"}');
      },
    });
    assert.equal((await wigle(proxy)).status, 200);
    assert.equal((await wigle(proxy)).status, 429);
    assert.equal(up.requests.length, 1);
  });

  test("per-caller budget counts by X-Caller", async () => {
    const { proxy } = await setup({ env: { WIGLE_PER_CALLER_DAILY: "1" } });
    assert.equal((await wigle(proxy, "name=a", "worker-1")).status, 200);
    assert.equal((await wigle(proxy, "name=a", "worker-1")).status, 429);
    assert.equal((await wigle(proxy, "name=a", "worker-2")).status, 200);
  });

  test("pacificDay rolls over at 00:05 Los Angeles time", () => {
    assert.equal(pacificDay(Date.parse("2026-10-29T07:04:00Z")), "2026-10-28"); // 00:04 PDT
    assert.equal(pacificDay(Date.parse("2026-10-29T07:06:00Z")), "2026-10-29"); // 00:06 PDT
  });
});

describe("androzoo", () => {
  const SHA = "A".repeat(64);

  test("streams the APK by sha256 with the key added", async () => {
    const apk = Buffer.alloc(300_000, 3);
    const { up, proxy } = await setup({
      env: { ANDROZOO_MIN_INTERVAL_SECONDS: "0" },
      handler: (_req, _body, res) => {
        res.writeHead(200, { "content-type": "application/octet-stream", "content-length": apk.length });
        res.end(apk);
      },
    });
    const r = await fetch(`${proxy.url}/androzoo/apk/${SHA.toLowerCase()}`);
    assert.equal(r.status, 200);
    assert.ok(Buffer.from(await r.arrayBuffer()).equals(apk));
    const u = new URL(up.requests[0].url, "http://x");
    assert.equal(u.pathname, "/api/download");
    assert.equal(u.searchParams.get("sha256"), SHA);
    assert.equal(u.searchParams.get("apikey"), SECRETS.androzoo);
  });

  test("one download at a time", async () => {
    let release;
    const gate = new Promise((r) => {
      release = r;
    });
    const { up, proxy } = await setup({
      env: { ANDROZOO_MIN_INTERVAL_SECONDS: "0" },
      handler: async (_req, _body, res) => {
        res.writeHead(200, { "content-type": "application/octet-stream" });
        res.write(Buffer.alloc(10));
        await gate;
        res.end(Buffer.alloc(10));
      },
    });
    const first = await fetch(`${proxy.url}/androzoo/apk/${SHA}`);
    assert.equal(first.status, 200);
    const second = await fetch(`${proxy.url}/androzoo/apk/${"B".repeat(64)}`);
    assert.equal(second.status, 429);
    assert.ok(second.headers.get("retry-after"));
    release();
    await first.arrayBuffer();
    assert.equal(up.requests.length, 1);
  });

  test("rate limit between downloads, daily limit, bad input", async () => {
    let t = 1_000_000;
    const { up, proxy } = await setup({
      env: { ANDROZOO_MIN_INTERVAL_SECONDS: "30", ANDROZOO_DAILY_LIMIT: "2" },
      now: () => t,
      handler: (_req, _body, res) => res.end("apk"),
    });
    assert.equal((await fetch(`${proxy.url}/androzoo/apk/${SHA}`)).status, 200);
    const limited = await fetch(`${proxy.url}/androzoo/apk/${SHA}`);
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get("retry-after"), "30");
    t += 31_000;
    assert.equal((await fetch(`${proxy.url}/androzoo/apk/${SHA}`)).status, 200);
    t += 31_000;
    assert.equal((await fetch(`${proxy.url}/androzoo/apk/${SHA}`)).status, 429);
    assert.equal((await fetch(`${proxy.url}/androzoo/apk/not-a-sha`)).status, 400);
    assert.equal((await fetch(`${proxy.url}/androzoo/apk/${SHA}?apikey=x`)).status, 400);
    assert.equal((await fetch(`${proxy.url}/androzoo/api/download?sha256=${SHA}`)).status, 400);
    assert.equal((await fetch(`${proxy.url}/androzoo/api/download`)).status, 403);
    assert.equal((await fetch(`${proxy.url}/androzoo/lists/latest.csv.gz`)).status, 403);
    assert.equal(up.requests.length, 2);
  });

  test("gp-metadata only when enabled for the key", async () => {
    const off = await setup();
    assert.equal((await fetch(`${off.proxy.url}/androzoo/gp-metadata/com.example.app`)).status, 403);
    assert.equal(off.up.requests.length, 0);
    const on = await setup({ env: { ANDROZOO_GP_METADATA: "1" }, handler: (_req, _body, res) => res.end("{}") });
    assert.equal((await fetch(`${on.proxy.url}/androzoo/gp-metadata/com.example.app`)).status, 200);
    assert.equal((await fetch(`${on.proxy.url}/androzoo/gp-metadata/..%2Fx`)).status, 400);
    assert.equal(new URL(on.up.requests[0].url, "http://x").pathname, "/api/get_gp_metadata/com.example.app");
  });

  test("a missing key disables the service", async () => {
    const up = await fakeUpstream();
    const proxy = await startProxy({ upstream: up.url, secrets: { ...SECRETS, androzoo: "" } });
    closers.push(proxy.close, up.close);
    assert.equal((await fetch(`${proxy.url}/androzoo/apk/${SHA}`)).status, 503);
    assert.equal(up.requests.length, 0);
  });
});
