// AndroZoo: one streamed APK download at a time by sha256, rate limited; gp-metadata only when
// the key is known to allow it (ANDROZOO_GP_METADATA=1).
import { Readable, Transform } from "node:stream";
import { pipeline } from "node:stream/promises";
import { Reject, netError, pathSegments } from "./http.mjs";

const SHA256 = /^[0-9A-Fa-f]{64}$/;
const PACKAGE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

export function createAndrozooState() {
  return { active: false, lastStart: 0 };
}

export async function handleAndrozoo(req, res, ctx, deps) {
  const url = new URL(req.url, "http://keyproxy");
  ctx.service = "androzoo";
  const seg = pathSegments(url.pathname, "/androzoo");
  if (req.method !== "GET") throw new Reject(405, "only GET is allowed");
  if (url.search !== "") throw new Reject(400, "no query parameters are accepted");
  if (seg?.length === 2 && seg[0] === "apk") return download(seg[1], res, ctx, deps);
  if (seg?.length === 2 && seg[0] === "gp-metadata") return gpMetadata(seg[1], res, ctx, deps);
  throw new Reject(403, "allowed: GET /androzoo/apk/<sha256>, GET /androzoo/gp-metadata/<package>");
}

async function download(sha, res, ctx, deps) {
  const { config, secrets, counters, androzoo: state, now } = deps;
  ctx.endpoint = "androzoo.apk";
  if (!SHA256.test(sha)) throw new Reject(400, "sha256 must be 64 hex characters");
  sha = sha.toUpperCase();
  ctx.params = { sha256: sha };
  if (!secrets.androzoo) throw new Reject(503, "AndroZoo is not configured (no androzoo-api-key secret)");

  if (state.active) throw new Reject(429, "another AndroZoo download is in progress; one at a time", { retryAfter: 10 });
  const wait = state.lastStart + config.androzoo.minIntervalMs - now();
  if (wait > 0) throw new Reject(429, "AndroZoo downloads are rate limited", { retryAfter: Math.ceil(wait / 1000) });
  if (counters.get("androzoo.apk") >= config.androzoo.dailyLimit) {
    throw new Reject(429, `AndroZoo daily limit of ${config.androzoo.dailyLimit} downloads reached`);
  }
  state.active = true;
  state.lastStart = now();
  counters.add("androzoo.apk");
  const abort = new AbortController();
  res.on("close", () => abort.abort());
  try {
    const q = new URLSearchParams({ apikey: secrets.androzoo, sha256: sha });
    let up;
    try {
      up = await fetch(`${config.androzoo.url}/api/download?${q}`, {
        headers: { "User-Agent": config.userAgent },
        signal: abort.signal,
      });
    } catch (e) {
      throw new Reject(502, `AndroZoo unreachable: ${netError(e)}`);
    }
    if (up.status !== 200) return relayError(up, res, ctx, deps);
    const headers = {
      "content-type": up.headers.get("content-type") ?? "application/vnd.android.package-archive",
      "content-disposition": `attachment; filename="${sha}.apk"`,
    };
    const len = up.headers.get("content-length");
    if (len) headers["content-length"] = len;
    res.writeHead(200, headers);
    const count = new Transform({
      transform(chunk, _enc, cb) {
        ctx.bytesOut = (ctx.bytesOut ?? 0) + chunk.length;
        cb(null, chunk);
      },
    });
    await pipeline(Readable.fromWeb(up.body), count, res).catch((e) => {
      ctx.error = `stream: ${e.code ?? e.name}`;
    });
  } finally {
    state.active = false;
  }
}

async function gpMetadata(pkg, res, ctx, deps) {
  const { config, secrets, counters } = deps;
  ctx.endpoint = "androzoo.gp-metadata";
  if (!PACKAGE.test(pkg) || pkg.length > 255) throw new Reject(400, "not a valid Android package name");
  ctx.params = { package: pkg };
  if (!config.androzoo.gpMetadata) throw new Reject(403, "gp-metadata is not enabled for this AndroZoo key");
  if (!secrets.androzoo) throw new Reject(503, "AndroZoo is not configured (no androzoo-api-key secret)");
  if (counters.get("androzoo.gp") >= config.androzoo.dailyLimit) {
    throw new Reject(429, `AndroZoo gp-metadata daily limit of ${config.androzoo.dailyLimit} reached`);
  }
  counters.add("androzoo.gp");
  let up;
  try {
    up = await fetch(`${config.androzoo.url}/api/get_gp_metadata/${encodeURIComponent(pkg)}?${new URLSearchParams({ apikey: secrets.androzoo })}`, {
      headers: { "User-Agent": config.userAgent },
      signal: AbortSignal.timeout(60_000),
    });
  } catch (e) {
    throw new Reject(502, `AndroZoo unreachable: ${netError(e)}`);
  }
  if (up.status !== 200) return relayError(up, res, ctx, deps);
  const body = deps.scrubber.scrub(await up.text());
  res.writeHead(200, { "content-type": up.headers.get("content-type") ?? "application/json", "content-length": Buffer.byteLength(body) });
  res.end(body);
  ctx.bytesOut = Buffer.byteLength(body);
}

/** Relays an upstream error with its body scrubbed: an error page could echo the apikey. */
async function relayError(up, res, ctx, { scrubber }) {
  const text = scrubber.scrub((await up.text().catch(() => "")).slice(0, 4096));
  ctx.error = `upstream ${up.status}`;
  const body = `${JSON.stringify({ error: "AndroZoo returned an error", upstream_status: up.status, body: text })}\n`;
  res.writeHead(up.status, { "content-type": "application/json; charset=utf-8", "content-length": Buffer.byteLength(body) });
  res.end(body);
}
