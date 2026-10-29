import assert from "node:assert/strict";
import { after, describe, test } from "node:test";
import { pacificDay } from "../src/budget.mjs";
import { fakeUpstream, startProxy } from "./helpers.mjs";

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

const wigle = (proxy, path = "/api/v2/bluetooth/search?name=DT-TAG") => fetch(`${proxy.url}/wigle${path}`);

describe("WiGLE daily budget (WIGLE_DAILY_BUDGET_ENABLED)", () => {
  test("stops with the reserve left; every endpoint counts", async () => {
    const { up, proxy } = await setup({ env: { WIGLE_DAILY_BUDGET: "13", WIGLE_RESERVE: "10" } });
    const statuses = [];
    for (const path of ["/api/v2/bluetooth/search?name=a", "/api/v2/network/search?ssid=b", "/api/v2/profile/user", "/api/v2/bluetooth/search", "/x"]) {
      statuses.push((await wigle(proxy, path)).status);
    }
    assert.deepEqual(statuses, [200, 200, 200, 429, 429]);
    assert.equal(up.requests.length, 3);
    const l = await (await fetch(`${proxy.url}/limits`)).json();
    assert.equal(l["/wigle"].used, 3);
    assert.equal(l["/wigle"].remaining, 0);
  });

  test("survives a restart and resets on the next Pacific day", async () => {
    let t = Date.parse("2026-10-28T12:00:00Z");
    const env = { WIGLE_DAILY_BUDGET: "12", WIGLE_RESERVE: "10" };
    const first = await setup({ env, now: () => t });
    assert.equal((await wigle(first.proxy)).status, 200);
    assert.equal((await wigle(first.proxy)).status, 200);
    const second = await setup({ env, now: () => t, stateFile: first.proxy.stateFile });
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

  test("switched off: no budget at all", async () => {
    const { up, proxy } = await setup({ env: { WIGLE_DAILY_BUDGET_ENABLED: "false", WIGLE_DAILY_BUDGET: "11", WIGLE_RESERVE: "10" } });
    for (let i = 0; i < 5; i++) assert.equal((await wigle(proxy)).status, 200);
    assert.equal(up.requests.length, 5);
    assert.deepEqual((await (await fetch(`${proxy.url}/limits`)).json())["/wigle"], { enabled: false });
  });

  test("pacificDay rolls over at 00:05 Los Angeles time", () => {
    assert.equal(pacificDay(Date.parse("2026-10-29T07:04:00Z")), "2026-10-28");
    assert.equal(pacificDay(Date.parse("2026-10-29T07:06:00Z")), "2026-10-29");
  });
});

describe("AndroZoo rate limit (ANDROZOO_RATE_LIMIT_ENABLED)", () => {
  const SHA = "A".repeat(64);

  test("one request at a time", async () => {
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
    const first = await fetch(`${proxy.url}/androzoo/api/download?sha256=${SHA}`);
    assert.equal(first.status, 200);
    const second = await fetch(`${proxy.url}/androzoo/api/download?sha256=${"B".repeat(64)}`);
    assert.equal(second.status, 429);
    assert.ok(second.headers.get("retry-after"));
    release();
    await first.arrayBuffer();
    assert.equal(up.requests.length, 1);
  });

  test("a pause between starts", async () => {
    let t = 1_000_000;
    const { up, proxy } = await setup({ env: { ANDROZOO_MIN_INTERVAL_SECONDS: "30" }, now: () => t, handler: (_req, _body, res) => res.end("apk") });
    assert.equal((await fetch(`${proxy.url}/androzoo/api/download?sha256=${SHA}`)).status, 200);
    const limited = await fetch(`${proxy.url}/androzoo/api/download?sha256=${SHA}`);
    assert.equal(limited.status, 429);
    assert.equal(limited.headers.get("retry-after"), "30");
    t += 31_000;
    assert.equal((await fetch(`${proxy.url}/androzoo/api/download?sha256=${SHA}`)).status, 200);
    assert.equal(up.requests.length, 2);
  });

  test("switched off: concurrent requests and no pause", async () => {
    let release;
    const gate = new Promise((r) => {
      release = r;
    });
    const { up, proxy } = await setup({
      env: { ANDROZOO_RATE_LIMIT_ENABLED: "no", ANDROZOO_MIN_INTERVAL_SECONDS: "3600" },
      handler: async (_req, _body, res) => {
        res.writeHead(200);
        res.write("x");
        await gate;
        res.end("y");
      },
    });
    const a = await fetch(`${proxy.url}/androzoo/api/download?sha256=${SHA}`);
    const b = await fetch(`${proxy.url}/androzoo/api/download?sha256=${SHA}`);
    assert.deepEqual([a.status, b.status], [200, 200]);
    release();
    await Promise.all([a.text(), b.text()]);
    assert.equal(up.requests.length, 2);
  });
});
