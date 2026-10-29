// keyproxy: holds the fleet's API keys and lets the agent container use them only through
// whitelisted endpoints. Node built-ins only.
//
// X-Caller names the calling agent in logs and per-caller budgets. It is NOT authorisation:
// any client on the network can send any value, so nothing is allowed or denied because of it.
import http from "node:http";
import { pathToFileURL } from "node:url";
import { createAndrozooState, handleAndrozoo } from "./androzoo.mjs";
import { DailyCounters } from "./budget.mjs";
import { loadConfig, loadSecrets } from "./config.mjs";
import { handleGitea } from "./gitea.mjs";
import { Reject, callerOf, sendJson } from "./http.mjs";
import { createLogger, createScrubber } from "./log.mjs";
import { handleWigle, wigleBudget } from "./wigle.mjs";

export function createKeyproxy({ config, secrets, write, now = () => Date.now() }) {
  const scrubber = createScrubber(Object.values(secrets));
  const logger = createLogger({ scrubber, write, file: config.logFile });
  const counters = new DailyCounters({ file: config.stateFile, now });
  const deps = { config, secrets, scrubber, counters, androzoo: createAndrozooState(), now };

  const server = http.createServer(async (req, res) => {
    const started = now();
    const url = new URL(req.url, "http://keyproxy");
    if (req.method === "GET" && url.pathname === "/healthz") {
      // Not a proxied call, so not logged; reports which services have a key, never the key.
      return sendJson(res, 200, { ok: true, services: { gitea: !!secrets.gitea, wigle: !!secrets.wigle, androzoo: !!secrets.androzoo } });
    }
    const ctx = { caller: callerOf(req), method: req.method, service: null, endpoint: null, params: {}, path: url.pathname };
    res.on("close", () => {
      logger.log({
        ts: new Date(started).toISOString(),
        caller: ctx.caller,
        method: ctx.method,
        service: ctx.service,
        endpoint: ctx.endpoint ?? "rejected",
        path: ctx.endpoint ? undefined : ctx.path,
        params: ctx.params,
        status: res.statusCode,
        bytes_out: ctx.bytesOut ?? 0,
        ms: now() - started,
        error: ctx.error,
        complete: res.writableFinished,
      });
    });
    try {
      if (url.pathname === "/gitea" || url.pathname.startsWith("/gitea/")) await handleGitea(req, res, ctx, deps);
      else if (url.pathname === "/wigle/budget" && req.method === "GET") wigleBudget(res, ctx, deps);
      else if (url.pathname.startsWith("/wigle/")) await handleWigle(req, res, ctx, deps);
      else if (url.pathname.startsWith("/androzoo/")) await handleAndrozoo(req, res, ctx, deps);
      else throw new Reject(404, "no such endpoint; see the keyproxy README for the allowed ones");
    } catch (e) {
      const reject = e instanceof Reject ? e : null;
      ctx.error = reject ? reject.message : `internal: ${e?.name ?? "Error"}`;
      if (!reject) process.stderr.write(`keyproxy: ${scrubber.scrub(e?.stack ?? e)}\n`);
      if (res.headersSent) return res.destroy();
      // Drain what the client is still sending, so it reads our answer instead of a reset.
      req.resume();
      sendJson(
        res,
        reject?.status ?? 500,
        { error: scrubber.scrub(ctx.error) },
        reject?.retryAfter ? { "retry-after": String(reject.retryAfter) } : {},
      );
    }
  });
  // Pushes and APK downloads can be large: no overall request timeout.
  server.requestTimeout = 0;
  return { server, counters, deps };
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const config = loadConfig();
  const { secrets, warnings } = loadSecrets(config.secretsDir);
  for (const w of warnings) process.stderr.write(`keyproxy: ${w}\n`);
  const { server } = createKeyproxy({ config, secrets });
  server.listen(config.port, config.host, () => {
    const on = Object.entries(secrets)
      .filter(([, v]) => v)
      .map(([k]) => k);
    process.stderr.write(`keyproxy: listening on ${config.host}:${config.port}; enabled: ${on.join(", ") || "none"}\n`);
  });
  for (const sig of ["SIGTERM", "SIGINT"]) process.on(sig, () => server.close(() => process.exit(0)));
}
