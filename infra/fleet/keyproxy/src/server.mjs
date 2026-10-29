// keyproxy: holds the fleet's API keys so the agent container does not have to. Every path and
// method below a service's prefix is forwarded with that service's credential added; keyproxy
// does not decide which operations are allowed. Node built-ins only.
//
// X-Caller names the calling agent in the log. It is NOT authorisation: nothing is allowed or
// denied because of it.
import http from "node:http";
import { Readable } from "node:stream";
import { pathToFileURL } from "node:url";
import { DailyCounters } from "./budget.mjs";
import { loadConfig, loadSecrets } from "./config.mjs";
import { Reject, callerOf, netError, sendJson } from "./http.mjs";
import { createLimit } from "./limits.mjs";
import { createLogger, createScrubber } from "./log.mjs";

// Upstream request headers are built from scratch: these are copied from the caller, then the
// credential is added. Anything else (Authorization, cookies, key headers, X-Caller) is dropped,
// so the proxy's credential is the only one an upstream sees.
const FORWARDED_REQUEST_HEADERS = [
  "accept",
  "accept-language",
  "content-type",
  "content-length",
  "content-encoding",
  "git-protocol",
  "if-none-match",
  "if-modified-since",
  "range",
];
// Response headers relayed back. Not relayed: Location (redirects are not followed and their
// target is not handed on), Set-Cookie (a session cookie is a credential), hop-by-hop headers.
const RELAYED_RESPONSE_HEADERS = [
  "content-type",
  "content-length",
  "content-disposition",
  "content-range",
  "accept-ranges",
  "cache-control",
  "expires",
  "pragma",
  "etag",
  "last-modified",
  "link",
  "x-total-count",
  "retry-after",
];
const MAX_BUFFERED = 8 << 20;
const MAX_FORM = 1 << 20;

/** The credential as it goes on the wire, from the entry's injection rule. */
export function credentialValue(inject, secret) {
  if (inject.param) return secret;
  const basic = Buffer.from(`${inject.user ?? ""}:${secret}`).toString("base64");
  return inject.template.replaceAll("{secret}", secret).replaceAll("{basic}", basic);
}

export function createKeyproxy({ config, secrets, write, now = () => Date.now() }) {
  const services = config.services.map((svc) => ({ ...svc, secret: secrets[svc.prefix] ?? "" }));
  const scrubber = createScrubber(services.flatMap((s) => (s.secret ? [s.secret, credentialValue(s.inject, s.secret)] : [])));
  const logger = createLogger({ scrubber, write, file: config.logFile });
  const counters = new DailyCounters({ file: config.stateFile, now });
  for (const s of services) s.limiter = createLimit(s, counters, now);

  const server = http.createServer(async (req, res) => {
    const started = now();
    const inbound = new URL(req.url, "http://keyproxy");
    if (req.method === "GET" && inbound.pathname === "/healthz") {
      // Not a proxied call, so not logged; reports which services have a key, never the key.
      return sendJson(res, 200, { ok: true, services: Object.fromEntries(services.map((s) => [s.prefix, !!s.secret])) });
    }
    if (req.method === "GET" && inbound.pathname === "/limits") {
      return sendJson(res, 200, Object.fromEntries(services.map((s) => [s.prefix, s.limiter?.status() ?? { enabled: false }])));
    }

    const svc = services.find((s) => inbound.pathname === s.prefix || inbound.pathname.startsWith(`${s.prefix}/`));
    const ctx = { caller: callerOf(req), headers: {} };
    res.on("close", () => {
      logger.log({
        ts: new Date(started).toISOString(),
        caller: ctx.caller,
        method: req.method,
        service: svc?.prefix.slice(1) ?? null,
        path: inbound.pathname,
        params: Object.fromEntries(inbound.searchParams),
        status: res.statusCode,
        bytes_out: ctx.bytesOut ?? 0,
        ms: now() - started,
        error: ctx.error,
        complete: res.writableFinished,
      });
    });
    try {
      if (!svc) throw new Reject(404, `unknown service; prefixes: ${services.map((s) => s.prefix).join(", ")}`);
      if (!svc.secret) throw new Reject(503, `${svc.prefix} is not configured (no ${svc.secretFile} secret)`);
      svc.limiter?.before(ctx, res);
      await forward(req, res, ctx, svc, inbound, config, scrubber);
    } catch (e) {
      const reject = e instanceof Reject ? e : null;
      ctx.error = reject ? reject.message : `internal: ${e?.name ?? "Error"}`;
      if (!reject) process.stderr.write(`keyproxy: ${scrubber.scrub(e?.stack ?? e)}\n`);
      if (res.headersSent) return res.destroy();
      req.resume();
      sendJson(res, reject?.status ?? 500, { error: scrubber.scrub(ctx.error) }, reject?.retryAfter ? { "retry-after": String(reject.retryAfter) } : {});
    }
  });
  // Pushes and APK downloads can be large: no overall request timeout.
  server.requestTimeout = 0;
  return { server, counters };
}

async function forward(req, res, ctx, svc, inbound, config, scrubber) {
  const url = new URL(svc.url);
  url.pathname = url.pathname.replace(/\/$/, "") + (inbound.pathname.slice(svc.prefix.length) || "/");
  url.search = inbound.search;
  for (const p of svc.stripQuery ?? []) url.searchParams.delete(p);

  const headers = { "user-agent": config.userAgent, "accept-encoding": "identity" };
  for (const h of FORWARDED_REQUEST_HEADERS) if (req.headers[h] !== undefined) headers[h] = req.headers[h];

  let body = req.method === "GET" || req.method === "HEAD" ? undefined : req;
  const { inject } = svc;
  if (inject.header) {
    headers[inject.header] = credentialValue(inject, svc.secret);
  } else if (inject.param && body && /^application\/x-www-form-urlencoded/i.test(headers["content-type"] ?? "")) {
    // A form body carries the parameter itself (the caller's value replaced).
    const chunks = [];
    let size = 0;
    for await (const c of req) {
      size += c.length;
      if (size > MAX_FORM) throw new Reject(413, `form body over ${MAX_FORM} bytes`);
      chunks.push(c);
    }
    const form = new URLSearchParams(Buffer.concat(chunks).toString("utf8"));
    form.set(inject.param, svc.secret);
    body = form.toString();
    headers["content-length"] = String(Buffer.byteLength(body));
    url.searchParams.delete(inject.param);
  } else if (inject.param) {
    url.searchParams.set(inject.param, svc.secret);
  }

  let up;
  try {
    up = await fetch(url, { method: req.method, headers, body, duplex: body && typeof body !== "string" ? "half" : undefined, redirect: "manual" });
  } catch (e) {
    throw new Reject(502, `${svc.prefix} unreachable: ${netError(e)}`);
  }

  const out = { ...ctx.headers };
  for (const h of RELAYED_RESPONSE_HEADERS) {
    const v = up.headers.get(h);
    if (v !== null) out[h] = v;
  }

  // Error answers, redirects and bodies a limiter inspects are read whole and scrubbed (an
  // error page can echo a credential back); everything else streams through untouched.
  if (up.status >= 300 || svc.limiter?.wantsBody?.(up)) {
    const text = scrubber.scrub((await up.text()).slice(0, MAX_BUFFERED));
    svc.limiter?.after?.(up.status, text);
    out["content-length"] = String(Buffer.byteLength(text));
    if (up.status >= 300) ctx.error = `upstream ${up.status}`;
    res.writeHead(up.status, out);
    res.end(req.method === "HEAD" ? undefined : text);
    ctx.bytesOut = Buffer.byteLength(text);
    return;
  }
  svc.limiter?.after?.(up.status, "");
  res.writeHead(up.status, out);
  if (!up.body) return res.end();
  const src = Readable.fromWeb(up.body);
  src.on("data", (c) => {
    ctx.bytesOut = (ctx.bytesOut ?? 0) + c.length;
  });
  await new Promise((resolve) => {
    src.on("error", (e) => {
      ctx.error = `stream: ${e.code ?? e.name}`;
      res.destroy();
      resolve();
    });
    res.on("close", resolve);
    src.pipe(res);
  });
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const config = loadConfig();
  const { secrets, warnings } = loadSecrets(config.secretsDir, config.services);
  for (const w of warnings) process.stderr.write(`keyproxy: ${w}\n`);
  const { server } = createKeyproxy({ config, secrets });
  server.listen(config.port, config.host, () => {
    const desc = config.services
      .map((s) => `${s.prefix} ${secrets[s.prefix] ? "key" : "NO KEY"}${s.limit ? `, ${s.limit.type} ${s.limit.enabled ? "on" : "OFF"}` : ""}`)
      .join("; ");
    process.stderr.write(`keyproxy: listening on ${config.host}:${config.port}; ${desc}\n`);
  });
  for (const sig of ["SIGTERM", "SIGINT"]) process.on(sig, () => server.close(() => process.exit(0)));
}
