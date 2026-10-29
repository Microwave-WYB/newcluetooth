// Small HTTP helpers. No framework.

/** A request the proxy answers itself (a rate limit, a missing key). */
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

/** Caller label from X-Caller: for logs only, never for authorisation. */
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
