// Configuration from the environment (no secrets). Each upstream is one entry in `services`:
// a path prefix, the upstream base URL, how its credential is injected, the secret file it
// comes from, and optionally a rate limit with its own on/off switch. Adding a service is
// adding an entry.
import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { userAgent } from "./http.mjs";

export const VERSION = "2";

const int = (v, d) => {
  if (v === undefined || v === "") return d;
  const n = Number.parseInt(v, 10);
  if (!Number.isFinite(n) || n < 0) throw new Error(`not a non-negative integer: ${v}`);
  return n;
};

const bool = (v, d) => {
  if (v === undefined || v === "") return d;
  if (/^(1|true|yes|on)$/i.test(v)) return true;
  if (/^(0|false|no|off)$/i.test(v)) return false;
  throw new Error(`not a boolean: ${v}`);
};

const base = (v, d) => (v || d).replace(/\/+$/, "");

export function loadConfig(env = process.env) {
  return {
    port: int(env.PORT, 8080),
    host: env.HOST ?? "0.0.0.0",
    secretsDir: env.SECRETS_DIR ?? "/run/secrets",
    stateFile: env.STATE_FILE ?? "/var/lib/keyproxy/counters.json",
    logFile: env.LOG_FILE || undefined,
    userAgent: userAgent(VERSION),
    services: [
      {
        // API, git smart HTTP and web alike: Gitea accepts the token as the basic-auth password.
        prefix: "/gitea",
        url: base(env.GITEA_URL, "http://gitea:3000"),
        secretFile: "gitea-token",
        inject: { header: "authorization", template: "Basic {basic}", user: env.GITEA_USER || "wiki-bot" },
        stripQuery: ["token", "access_token"],
      },
      {
        prefix: "/wigle",
        url: base(env.WIGLE_URL, "https://api.wigle.net"),
        secretFile: "wigle-api-key", // WiGLE's "Encoded for use" value, base64(name:token)
        inject: { header: "authorization", template: "Basic {secret}" },
        limit: {
          // Protects the account from a ban; it does not restrict which calls are made.
          type: "daily-budget",
          enabled: bool(env.WIGLE_DAILY_BUDGET_ENABLED, true),
          budget: int(env.WIGLE_DAILY_BUDGET, 100),
          reserve: int(env.WIGLE_RESERVE, 10),
        },
      },
      {
        prefix: "/androzoo",
        url: base(env.ANDROZOO_URL, "https://androzoo.uni.lu"),
        secretFile: "androzoo-api-key",
        inject: { param: "apikey" },
        limit: {
          // Protects the account: one request at a time, a pause between starts.
          type: "one-at-a-time",
          enabled: bool(env.ANDROZOO_RATE_LIMIT_ENABLED, true),
          minIntervalSeconds: int(env.ANDROZOO_MIN_INTERVAL_SECONDS, 10),
        },
      },
    ],
  };
}

/** Reads each service's secret file; returns values by prefix plus warnings naming files only. */
export function loadSecrets(dir, services) {
  const secrets = {};
  const warnings = [];
  for (const svc of services) {
    const path = join(dir, svc.secretFile);
    try {
      const mode = statSync(path).mode & 0o777;
      if (mode & 0o077) warnings.push(`${svc.secretFile}: mode ${mode.toString(8)} is readable by group/others; expected 600`);
      secrets[svc.prefix] = readFileSync(path, "utf8").trim();
      if (!secrets[svc.prefix]) warnings.push(`${svc.secretFile}: empty, ${svc.prefix} disabled`);
    } catch (e) {
      secrets[svc.prefix] = "";
      warnings.push(`${svc.secretFile}: ${e.code === "ENOENT" ? "missing" : `unreadable (${e.code})`}, ${svc.prefix} disabled`);
    }
  }
  return { secrets, warnings };
}
