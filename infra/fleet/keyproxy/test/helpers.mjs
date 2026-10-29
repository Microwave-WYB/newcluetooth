import http from "node:http";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadConfig } from "../src/config.mjs";
import { createKeyproxy } from "../src/server.mjs";

export const SECRETS = {
  gitea: "gitea-token-0123456789abcdef",
  // base64("wigle-name:wigle-token-secret")
  wigle: Buffer.from("wigle-name:wigle-token-secret").toString("base64"),
  androzoo: "androzoo-key-fedcba9876543210",
};

/** A fake upstream that records every request; `handler(req, body, res)` answers. */
export async function fakeUpstream(handler = (_req, _body, res) => res.end("{}")) {
  const requests = [];
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    const body = Buffer.concat(chunks);
    requests.push({ method: req.method, url: req.url, headers: req.headers, body });
    await handler(req, body, res);
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return { url: `http://127.0.0.1:${server.address().port}`, requests, close: () => new Promise((r) => server.close(r)) };
}

/** Starts keyproxy against the given upstreams; returns its URL and the captured log lines. */
export async function startProxy({ upstream, env = {}, secrets = SECRETS, now, stateFile } = {}) {
  const dir = mkdtempSync(join(tmpdir(), "keyproxy-test-"));
  const config = loadConfig({
    GITEA_URL: upstream,
    WIGLE_URL: upstream,
    ANDROZOO_URL: upstream,
    STATE_FILE: stateFile ?? join(dir, "counters.json"),
    ...env,
  });
  const lines = [];
  const { server, counters } = createKeyproxy({ config, secrets, write: (l) => lines.push(l), now });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  server.closeIdleConnections?.();
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    lines,
    logs: () => lines.map((l) => JSON.parse(l)),
    counters,
    stateFile: config.stateFile,
    close: () => {
      server.closeAllConnections?.();
      return new Promise((r) => server.close(r));
    },
  };
}

/** Waits until the access log has `n` lines (they are written when each response closes). */
export async function waitForLogs(proxy, n) {
  for (let i = 0; i < 100 && proxy.lines.length < n; i++) await new Promise((r) => setTimeout(r, 10));
  return proxy.logs();
}

export function pkt(line) {
  const len = Buffer.byteLength(line) + 4;
  return len.toString(16).padStart(4, "0") + line;
}

export const OID_A = "a".repeat(40);
export const OID_B = "b".repeat(40);
export const ZERO = "0".repeat(40);

export function pushBody(commands, pack = Buffer.from("PACK-fake-data")) {
  const lines = commands.map((c, i) => pkt(`${c.old ?? OID_A} ${c.new ?? OID_B} ${c.ref}${i === 0 ? `\0${c.caps ?? "report-status side-band-64k"}` : ""}\n`));
  return Buffer.concat([Buffer.from(lines.join("") + "0000"), pack]);
}
