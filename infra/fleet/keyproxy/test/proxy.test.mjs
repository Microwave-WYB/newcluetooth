import assert from "node:assert/strict";
import { after, before, beforeEach, describe, test } from "node:test";
import { OID_A, SECRETS, ZERO, fakeUpstream, pushBody, startProxy } from "./helpers.mjs";

let up;
let proxy;
before(async () => {
  up = await fakeUpstream((req, _body, res) => {
    if (req.url.startsWith("/redirect")) {
      res.writeHead(302, { location: "https://elsewhere.example/landing?token=x" });
      return res.end("moved");
    }
    res.writeHead(200, { "content-type": "application/octet-stream", "set-cookie": "i_like_gitea=session", "x-internal": "1" });
    res.end("ok");
  });
  proxy = await startProxy({ upstream: up.url, env: { ANDROZOO_RATE_LIMIT_ENABLED: "off", WIGLE_DAILY_BUDGET_ENABLED: "off" } });
});
after(async () => {
  await proxy.close();
  await up.close();
});
beforeEach(() => {
  up.requests.length = 0;
});

const R = "/gitea/api/v1/repos/y2wei/wirelesswiki";
const BASIC = `Basic ${Buffer.from(`wiki-bot:${SECRETS.gitea}`).toString("base64")}`;

describe("every path and method passes through with the credential added", () => {
  // These were refused by the old whitelist; keyproxy no longer decides what is allowed.
  const cases = [
    ["POST", `${R}/pulls/3/merge`, { Do: "merge" }],
    ["PATCH", `${R}/pulls/3`, { state: "closed" }],
    ["DELETE", `${R}/issues/comments/9`],
    ["POST", `${R}/pulls/3/reviews`, { event: "APPROVED" }],
    ["POST", `${R}/branch_protections`, { branch_name: "main" }],
    ["PATCH", R, { private: false }],
    ["POST", `${R}/issues/3/reactions`, { content: "eyes" }],
    ["GET", "/gitea/api/v1/admin/users"],
    ["GET", "/gitea/api/v1/repos/someone/else/issues"],
    ["PUT", `${R}/contents/README.md`, { content: "eA==" }],
    ["GET", "/gitea/y2wei/wirelesswiki/settings"],
  ];
  for (const [method, path, body] of cases) {
    test(`${method} ${path}`, async () => {
      const r = await fetch(proxy.url + path, {
        method,
        headers: body ? { "content-type": "application/json" } : {},
        body: body ? JSON.stringify(body) : undefined,
      });
      assert.equal(r.status, 200);
      assert.equal(up.requests.length, 1);
      const q = up.requests[0];
      assert.equal(q.method, method);
      assert.equal(q.url, path.slice("/gitea".length));
      assert.equal(q.headers.authorization, BASIC);
      if (body) assert.deepEqual(JSON.parse(q.body), body);
    });
  }

  test("the query string passes through, except a caller-supplied Gitea token", async () => {
    await fetch(`${proxy.url}${R}/issues?state=open&token=x&access_token=y&sudo=y2wei`);
    assert.equal(up.requests[0].url, "/api/v1/repos/y2wei/wirelesswiki/issues?state=open&sudo=y2wei");
  });

  test("upstream headers are built from scratch: caller credentials, cookies and X-Caller never reach it", async () => {
    await fetch(`${proxy.url}/gitea/api/v1/user`, {
      headers: { authorization: "token agent-supplied", cookie: "i_like_gitea=x", "x-api-key": "k", "x-caller": "root", "x-custom": "y", accept: "application/json" },
    });
    const h = up.requests[0].headers;
    assert.equal(h.authorization, BASIC);
    for (const k of ["cookie", "x-api-key", "x-caller", "x-custom"]) assert.equal(h[k], undefined, k);
    assert.equal(h.accept, "application/json");
    assert.match(h["user-agent"], /^cluetooth-keyproxy\//);
  });

  test("only known response headers are relayed: no Set-Cookie", async () => {
    const r = await fetch(`${proxy.url}/gitea/api/v1/user`);
    assert.equal(r.headers.get("set-cookie"), null);
    assert.equal(r.headers.get("x-internal"), null);
    assert.equal(r.headers.get("content-type"), "application/octet-stream");
  });

  test("redirects are not followed and their Location is not relayed", async () => {
    const r = await fetch(`${proxy.url}/gitea/redirect`, { redirect: "manual" });
    assert.equal(r.status, 302);
    assert.equal(r.headers.get("location"), null);
    assert.equal(up.requests.length, 1);
  });

  test("WiGLE: any endpoint, basic auth added", async () => {
    for (const path of ["/wigle/api/v2/network/search?ssid=x", "/wigle/api/v2/profile/user", "/wigle/api/v3/anything"]) {
      assert.equal((await fetch(proxy.url + path, { headers: { authorization: "Basic agent" } })).status, 200);
    }
    assert.deepEqual(
      up.requests.map((q) => q.url),
      ["/api/v2/network/search?ssid=x", "/api/v2/profile/user", "/api/v3/anything"],
    );
    assert.ok(up.requests.every((q) => q.headers.authorization === `Basic ${SECRETS.wigle}`));
  });

  test("WiGLE: name and namelike reach WiGLE exactly as sent", async () => {
    await fetch(`${proxy.url}/wigle/api/v2/bluetooth/search?namelike=DT-TAG%25&name=Bose+QC%2B+35`);
    const u = new URL(up.requests[0].url, "http://x");
    assert.equal(u.searchParams.get("namelike"), "DT-TAG%");
    assert.equal(u.searchParams.get("name"), "Bose QC+ 35");
  });

  test("AndroZoo: any endpoint, the caller's apikey replaced by the proxy's", async () => {
    const sha = "A".repeat(64);
    await fetch(`${proxy.url}/androzoo/api/download?sha256=${sha}&apikey=agent-key`);
    await fetch(`${proxy.url}/androzoo/api/get_gp_metadata/com.example.app`);
    await fetch(`${proxy.url}/androzoo/lists/latest.csv.gz`);
    const urls = up.requests.map((q) => new URL(q.url, "http://x"));
    assert.deepEqual(
      urls.map((u) => u.pathname),
      ["/api/download", "/api/get_gp_metadata/com.example.app", "/lists/latest.csv.gz"],
    );
    assert.ok(urls.every((u) => u.searchParams.getAll("apikey").join() === SECRETS.androzoo));
    assert.equal(urls[0].searchParams.get("sha256"), sha);
  });

  test("AndroZoo: a form body gets the apikey in the form", async () => {
    await fetch(`${proxy.url}/androzoo/api/query`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: "q=com.example&apikey=agent-key",
    });
    const form = new URLSearchParams(up.requests[0].body.toString());
    assert.equal(form.get("q"), "com.example");
    assert.deepEqual(form.getAll("apikey"), [SECRETS.androzoo]);
    assert.equal(new URL(up.requests[0].url, "http://x").searchParams.get("apikey"), null);
  });

  test("an unknown prefix is not proxied anywhere", async () => {
    assert.equal((await fetch(`${proxy.url}/elsewhere/x`)).status, 404);
    assert.equal(up.requests.length, 0);
  });

  test("a service without a key answers 503", async () => {
    const p = await startProxy({ upstream: up.url, secrets: { ...SECRETS, androzoo: "" } });
    assert.equal((await fetch(`${p.url}/androzoo/api/download?sha256=x`)).status, 503);
    assert.equal(up.requests.length, 0);
    await p.close();
  });
});

describe("git smart HTTP", () => {
  const push = (body) =>
    fetch(`${proxy.url}/gitea/y2wei/wirelesswiki.git/git-receive-pack`, {
      method: "POST",
      headers: { "content-type": "application/x-git-receive-pack-request" },
      body,
    });

  test("fetch goes through with basic auth", async () => {
    await fetch(`${proxy.url}/gitea/y2wei/wirelesswiki.git/info/refs?service=git-upload-pack`, { headers: { "git-protocol": "version=2" } });
    await fetch(`${proxy.url}/gitea/y2wei/wirelesswiki.git/git-upload-pack`, { method: "POST", body: "0000" });
    assert.ok(up.requests.every((q) => q.headers.authorization === BASIC));
    assert.equal(up.requests[0].headers["git-protocol"], "version=2");
  });

  for (const [name, commands] of [
    ["main", [{ ref: "refs/heads/main" }]],
    ["any branch", [{ ref: "refs/heads/feature/x" }]],
    ["a tag", [{ ref: "refs/tags/v1" }]],
    ["a delete", [{ ref: "refs/heads/task/1", old: OID_A, new: ZERO }]],
    ["push options", [{ ref: "refs/heads/main", caps: "report-status push-options" }]],
  ]) {
    test(`push of ${name} is forwarded byte for byte`, async () => {
      const body = pushBody(commands, Buffer.alloc(100_000, 5));
      assert.equal((await push(body)).status, 200);
      assert.equal(up.requests.length, 1);
      assert.equal(up.requests[0].headers.authorization, BASIC);
      assert.ok(up.requests[0].body.equals(body));
    });
  }
});
