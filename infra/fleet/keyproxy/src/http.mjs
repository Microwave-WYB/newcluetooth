// Small HTTP helpers shared by the routes. No framework.

/** A request the proxy refuses. `status` is the HTTP status sent back. */
export class Reject extends Error {
  constructor(status, message, { retryAfter } = {}) {
    super(message);
    this.status = status;
    this.retryAfter = retryAfter;
  }
}

export function sendJson(res, status, body, headers = {}) {
  const text = `${JSON.stringify(body)}\n`;
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(text),
    ...headers,
  });
  res.end(text);
}

/** Reads the whole request body, refusing more than `limit` bytes. */
export async function readBody(req, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new Reject(413, `request body over ${limit} bytes`);
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

/**
 * Splits a path below `prefix` into decoded segments. Refuses encoded slashes, dot segments,
 * empty segments and anything else that could make the upstream path differ from what was checked.
 */
export function pathSegments(pathname, prefix) {
  if (!pathname.startsWith(prefix)) return null;
  const rest = pathname.slice(prefix.length);
  if (rest === "") return [];
  if (!rest.startsWith("/")) return null;
  if (/%2f|%5c|%00|\\/i.test(rest)) throw new Reject(400, "encoded slashes, backslashes and NUL are not allowed in paths");
  const raw = rest.slice(1).split("/");
  const segments = [];
  for (const s of raw) {
    let d;
    try {
      d = decodeURIComponent(s);
    } catch {
      throw new Reject(400, "malformed percent-encoding in path");
    }
    if (d === "" || d === "." || d === "..") throw new Reject(400, "empty or dot path segments are not allowed");
    segments.push(d);
  }
  return segments;
}

export const encodePath = (segments) => segments.map(encodeURIComponent).join("/");

/** Caller label from X-Caller: for logs and budgets only, never for authorisation. */
export function callerOf(req) {
  const v = req.headers["x-caller"];
  if (v === undefined) return "unknown";
  return typeof v === "string" && /^[A-Za-z0-9._:@/+-]{1,64}$/.test(v) ? v : "invalid";
}

export function userAgent(version) {
  return `cluetooth-keyproxy/${version} (+https://wirelesswiki.org; research fleet key proxy)`;
}

/** A short, secret-free description of a network failure from fetch. */
export function netError(e) {
  const c = e?.cause;
  return c?.code ?? c?.errors?.[0]?.code ?? String(c?.message ?? e?.name ?? "Error").slice(0, 120);
}
