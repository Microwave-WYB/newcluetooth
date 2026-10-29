// Configuration from the environment (no secrets) and secrets from files under SECRETS_DIR
// (/run/secrets in the container: compose file secrets mounted only into keyproxy).
import { readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import { userAgent } from "./http.mjs";

export const VERSION = "1";

/** Secret file names below SECRETS_DIR. An empty or missing file disables that service (503). */
export const SECRET_FILES = {
  gitea: "gitea-token",
  wigle: "wigle-api-key",
  androzoo: "androzoo-api-key",
};

const int = (v, d) => {
  if (v === undefined || v === "") return d;
  const n = Number.parseInt(v, 10);
  if (!Number.isFinite(n) || n < 0) throw new Error(`not a non-negative integer: ${v}`);
  return n;
};

export function loadConfig(env = process.env) {
  return {
    port: int(env.PORT, 8080),
    host: env.HOST ?? "0.0.0.0",
    secretsDir: env.SECRETS_DIR ?? "/run/secrets",
    stateFile: env.STATE_FILE ?? "/var/lib/keyproxy/counters.json",
    logFile: env.LOG_FILE || undefined,
    userAgent: userAgent(VERSION),
    gitea: {
      url: (env.GITEA_URL ?? "http://gitea:3000").replace(/\/+$/, ""),
      user: env.GITEA_USER ?? "wiki-bot",
      repos: (env.GITEA_REPOS ?? "y2wei/wirelesswiki")
        .split(/[\s,]+/)
        .filter(Boolean)
        .map((r) => r.toLowerCase()),
    },
    wigle: {
      url: (env.WIGLE_URL ?? "https://api.wigle.net").replace(/\/+$/, ""),
      dailyBudget: int(env.WIGLE_DAILY_BUDGET, 100),
      reserve: int(env.WIGLE_RESERVE, 10),
      perCallerDaily: int(env.WIGLE_PER_CALLER_DAILY, 0),
      timeoutMs: int(env.WIGLE_TIMEOUT_SECONDS, 900) * 1000,
    },
    androzoo: {
      url: (env.ANDROZOO_URL ?? "https://androzoo.uni.lu").replace(/\/+$/, ""),
      minIntervalMs: int(env.ANDROZOO_MIN_INTERVAL_SECONDS, 10) * 1000,
      dailyLimit: int(env.ANDROZOO_DAILY_LIMIT, 200),
      gpMetadata: env.ANDROZOO_GP_METADATA === "1",
    },
  };
}

/** Reads each secret file; returns values plus warnings that name files, never contents. */
export function loadSecrets(dir) {
  const secrets = {};
  const warnings = [];
  for (const [key, name] of Object.entries(SECRET_FILES)) {
    const path = join(dir, name);
    try {
      const mode = statSync(path).mode & 0o777;
      if (mode & 0o077) warnings.push(`${name}: mode ${mode.toString(8)} is readable by group/others; expected 600`);
      secrets[key] = readFileSync(path, "utf8").trim();
      if (!secrets[key]) warnings.push(`${name}: empty, ${key} disabled`);
    } catch (e) {
      secrets[key] = "";
      warnings.push(`${name}: ${e.code === "ENOENT" ? "missing" : `unreadable (${e.code})`}, ${key} disabled`);
    }
  }
  if (secrets.wigle) secrets.wigle = secrets.wigle.replace(/^Basic\s+/i, "");
  return { secrets, warnings };
}
