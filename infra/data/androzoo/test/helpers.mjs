import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import http from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import pg from "pg";

const HERE = dirname(fileURLToPath(import.meta.url));
export const FIXTURE = join(HERE, "fixtures/latest.csv");
const MIGRATION = join(HERE, "../../../../db/db/migrations/20261029120000_androzoo_apks.sql");

function docker(args) {
  return execFileSync("docker", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

export function dockerAvailable() {
  try {
    docker(["info", "--format", "{{.ServerVersion}}"]);
    return true;
  } catch {
    return false;
  }
}

/**
 * A throwaway Postgres 18 on the default bridge network with the androzoo migration applied.
 * Returns its URL and a stop function.
 */
export async function startPostgres() {
  const name = `androzoo-test-${process.pid}-${Date.now()}`;
  docker(["run", "-d", "--rm", "--name", name, "-e", "POSTGRES_PASSWORD=test", "-p", "127.0.0.1::5432", "postgres:18-alpine"]);
  const port = docker(["port", name, "5432/tcp"]).split("\n")[0].split(":").pop();
  const url = `postgres://postgres:test@127.0.0.1:${port}/postgres`;
  for (let i = 0; ; i++) {
    const c = new pg.Client({ connectionString: url });
    try {
      await c.connect();
      await c.query("select 1");
      await c.end();
      break;
    } catch {
      await c.end().catch(() => {});
      if (i > 60) throw new Error("postgres did not start");
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  const up = readFileSync(MIGRATION, "utf8").split("-- migrate:down")[0];
  await query(url, up);
  return { url, stop: () => docker(["rm", "-f", name]) };
}

export async function query(url, sql, params) {
  const c = new pg.Client({ connectionString: url });
  await c.connect();
  try {
    return await c.query(sql, params);
  } finally {
    await c.end();
  }
}

/**
 * Serves `body` with HEAD, Range and If-Range like a static file server. `failSegment(start,
 * attempt)` may return "drop" (cut the response halfway) or "error" (HTTP 500) per request.
 */
export async function fileServer(body, { etag = '"v1"', lastModified = "Thu, 29 Oct 2026 04:25:31 GMT", failSegment } = {}) {
  const attempts = new Map();
  const requests = [];
  const server = http.createServer((req, res) => {
    requests.push({ method: req.method, range: req.headers.range, ifRange: req.headers["if-range"] });
    const headers = { etag, "last-modified": lastModified, "accept-ranges": "bytes", "content-type": "application/octet-stream" };
    if (req.method === "HEAD") {
      res.writeHead(200, { ...headers, "content-length": body.length });
      return res.end();
    }
    const m = /^bytes=(\d+)-(\d+)$/.exec(req.headers.range ?? "");
    if (!m || (req.headers["if-range"] && req.headers["if-range"] !== etag)) {
      res.writeHead(200, { ...headers, "content-length": body.length });
      return res.end(body);
    }
    const start = Number(m[1]);
    const end = Math.min(Number(m[2]), body.length - 1);
    const n = (attempts.get(start) ?? 0) + 1;
    attempts.set(start, n);
    const fail = failSegment?.(start, n);
    if (fail === "error") {
      res.writeHead(500);
      return res.end("boom");
    }
    const slice = body.subarray(start, end + 1);
    res.writeHead(206, { ...headers, "content-length": slice.length, "content-range": `bytes ${start}-${end}/${body.length}` });
    if (fail === "drop") {
      res.write(slice.subarray(0, Math.floor(slice.length / 2)));
      return setTimeout(() => res.destroy(), 10);
    }
    res.end(slice);
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  return {
    url: `http://127.0.0.1:${server.address().port}/static/lists/latest.csv.gz`,
    requests,
    close: () => {
      server.closeAllConnections();
      return new Promise((r) => server.close(r));
    },
  };
}
