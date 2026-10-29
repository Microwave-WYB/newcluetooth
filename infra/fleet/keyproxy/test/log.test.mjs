import assert from "node:assert/strict";
import { after, test } from "node:test";
import { secretVariants } from "../src/log.mjs";
import { SECRETS, fakeUpstream, startProxy, waitForLogs } from "./helpers.mjs";

const closers = [];
after(async () => {
  for (const c of closers) await c();
});

/** Every form of every secret that must never appear in a log line or a relayed body. */
const FORBIDDEN = [...secretVariants(Object.values(SECRETS)), Buffer.from(`wiki-bot:${SECRETS.gitea}`).toString("base64")];

function assertClean(text) {
  for (const s of FORBIDDEN) assert.ok(!text.includes(s), `contains a secret form: ${s.slice(0, 4)}…`);
}

test("every request is logged with caller, path, params and status, and no secret", async () => {
  // An upstream that echoes back whatever credentials it was sent, as JSON or as an error page.
  const up = await fakeUpstream((req, _body, res) => {
    const echo = JSON.stringify({ echo_auth: req.headers.authorization ?? null, echo_url: req.url, success: true });
    res.writeHead(req.url.startsWith("/api/download") ? 401 : 200, { "content-type": req.url.startsWith("/api/v2") ? "application/json" : "text/plain" });
    res.end(echo);
  });
  const proxy = await startProxy({ upstream: up.url, env: { ANDROZOO_MIN_INTERVAL_SECONDS: "0" } });
  closers.push(proxy.close, up.close);

  const bodies = [];
  const call = async (path, init = {}) => {
    const r = await fetch(proxy.url + path, { ...init, headers: { "x-caller": "worker-7", ...(init.headers ?? {}) } });
    bodies.push(await r.text());
    return r.status;
  };
  // Secrets smuggled in by the caller, and echoed back by the upstream.
  await call(`/wigle/api/v2/bluetooth/search?name=${encodeURIComponent(SECRETS.androzoo)}`); // JSON, read by the budget
  await call(`/androzoo/api/download?sha256=${"C".repeat(64)}`); // error page
  await call(`/gitea/api/v1/admin/users?token=${SECRETS.gitea}`);
  await call(`/gitea/api/v1/repos/y2wei/wirelesswiki/issues/1/comments`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ body: `leak ${SECRETS.gitea}` }),
  });

  const logs = await waitForLogs(proxy, 4);
  assert.equal(logs.length, 4);
  for (const l of proxy.lines) assertClean(l);
  // Buffered bodies (the WiGLE JSON) and error bodies are scrubbed before they are relayed.
  assertClean(bodies[0]);
  assertClean(bodies[1]);

  const [w, a, g] = logs;
  assert.equal(w.caller, "worker-7");
  assert.equal(w.service, "wigle");
  assert.equal(w.path, "/wigle/api/v2/bluetooth/search");
  assert.equal(w.params.name, "<redacted>");
  assert.equal(w.status, 200);
  assert.equal(a.service, "androzoo");
  assert.equal(a.status, 401);
  assert.equal(g.params.token, "<redacted>");
  for (const l of logs) assert.ok(typeof l.ts === "string" && typeof l.ms === "number");
});

test("a missing or malformed X-Caller is logged as such, and changes nothing else", async () => {
  const up = await fakeUpstream();
  const proxy = await startProxy({ upstream: up.url });
  closers.push(proxy.close, up.close);
  await fetch(`${proxy.url}/gitea/api/v1/user`);
  await fetch(`${proxy.url}/gitea/api/v1/user`, { headers: { "x-caller": "bad value with spaces" } });
  const [a, b] = await waitForLogs(proxy, 2);
  assert.equal(a.caller, "unknown");
  assert.equal(b.caller, "invalid");
  assert.equal(a.status, 200);
  assert.equal(b.status, 200);
});

test("secretVariants covers the WiGLE pair and its token", () => {
  const v = secretVariants([SECRETS.wigle]);
  assert.ok(v.includes("wigle-name:wigle-token-secret"));
  assert.ok(v.includes("wigle-token-secret"));
});
