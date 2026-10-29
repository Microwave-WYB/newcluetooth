#!/usr/bin/env node
// wigle: WiGLE search CLI that archives every API call in wigle.responses (Postgres).
// An archive, not a cache: it always calls when asked, never retries, never skips; scheduling and
// budgeting are the caller's job. `pg` is pinned in package.json (installed into $RESEARCH_AGENT_HOME/tools).
import { createRequire } from "node:module";
import { createHash } from "node:crypto";
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

export const API_BASE = "https://api.wigle.net";
// resultsPerPage is always forced to the maximum for the endpoint. Source: WiGLE swagger
// (https://api.wigle.net/swagger.json): "Bounded at 1000 for COMMAPI, 100 for site" on all three search endpoints;
// API-token accounts are "site" accounts.
export const MAX_RESULTS_PER_PAGE = {
  "/api/v2/bluetooth/search": 100,
  "/api/v2/network/search": 100,
  "/api/v2/cell/search": 100,
};
export const EXIT = { OK: 0, USAGE: 1, FAILED: 2, LIMIT: 3 };
const LIMIT_WORDS = ["too many", "daily limit", "throttl"]; // as in the old cron collector
const ERROR_TEXT_MAX = 2000;

class UsageError extends Error {}

// ---------- pure helpers ----------

export function normalizeEndpoint(raw) {
  let e = String(raw).trim().replace(/^\/+/, "");
  if (!e.startsWith("api/v2/")) e = `api/v2/${e}`;
  e = `/${e}`;
  if (!(e in MAX_RESULTS_PER_PAGE)) {
    throw new UsageError(`unknown endpoint ${raw} (known: ${Object.keys(MAX_RESULTS_PER_PAGE).join(", ")})`);
  }
  return e;
}

/** Canonical params: all values strings, sorted keys, resultsPerPage forced, cursor never included. */
export function buildParams(endpoint, pairs) {
  const out = {};
  for (const [k, v] of Object.entries(pairs)) {
    if (k === "resultsPerPage") continue;
    if (k === "searchAfter") throw new UsageError("use --page-after for the cursor, not searchAfter=");
    out[k] = String(v);
  }
  out.resultsPerPage = String(MAX_RESULTS_PER_PAGE[endpoint]);
  return Object.fromEntries(Object.keys(out).sort().map((k) => [k, out[k]]));
}

export const canonicalJson = (params) => JSON.stringify(params); // buildParams already sorts keys
export const paramsHash = (params) => createHash("sha256").update(canonicalJson(params)).digest("hex");

export function parseSearchArgs(argv) {
  const pairs = {}, opts = { cursor: null, caller: process.env.WIGLE_CALLER || "cli", maxPages: null };
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--page-after") opts.cursor = need(argv, ++i, a);
    else if (a === "--caller") opts.caller = need(argv, ++i, a);
    else if (a === "--max-pages") {
      opts.maxPages = Number(need(argv, ++i, a));
      if (!Number.isInteger(opts.maxPages) || opts.maxPages < 1) throw new UsageError("--max-pages must be a positive integer");
    } else if (a.startsWith("--")) throw new UsageError(`unknown option ${a}`);
    else if (!positional.length) positional.push(a);
    else {
      const eq = a.indexOf("=");
      if (eq < 1) throw new UsageError(`expected key=value, got ${a}`);
      const k = a.slice(0, eq);
      if (k in pairs) throw new UsageError(`duplicate parameter ${k}`);
      pairs[k] = a.slice(eq + 1);
    }
  }
  if (!positional.length) throw new UsageError("missing endpoint");
  return { endpoint: normalizeEndpoint(positional[0]), pairs, ...opts };
}
const need = (argv, i, flag) => {
  if (i >= argv.length) throw new UsageError(`${flag} needs a value`);
  return argv[i];
};

/**
 * Turns an HTTP outcome into the row fields shared by live calls and the collector import.
 * kind: ok | limit | http | api | network. `error` is null only for kind ok.
 */
export function classify({ status, text, networkError }) {
  if (networkError) return { kind: "network", error: `network: ${networkError}`, response: null, total: null, next: null, count: null };
  let body = null;
  try { body = JSON.parse(text); } catch { /* not JSON */ }
  const response = body !== null && typeof body === "object" ? body : null;
  const snippet = () => String(text ?? "").slice(0, ERROR_TEXT_MAX);
  const base = { response, total: null, next: null, count: null };
  if (status === 429) return { ...base, kind: "limit", error: `http 429: ${snippet()}` };
  if (status !== 200) return { ...base, kind: "http", error: `http ${status}: ${snippet()}` };
  if (!response) return { ...base, kind: "api", error: `http 200: body is not JSON: ${snippet().slice(0, 200)}` };
  if (response.success !== true) {
    const msg = String(response.message ?? response.error ?? "");
    const limit = LIMIT_WORDS.some((w) => msg.toLowerCase().includes(w));
    return { ...base, kind: limit ? "limit" : "api", error: `${limit ? "daily limit" : "api"}: ${msg.slice(0, ERROR_TEXT_MAX) || snippet()}` };
  }
  const next = typeof response.searchAfter === "string" && response.searchAfter ? response.searchAfter : null;
  return {
    kind: "ok", error: null, response, next,
    total: Number.isFinite(response.totalResults) ? response.totalResults : null,
    count: Array.isArray(response.results) ? response.results.length : 0,
  };
}

// ---------- API call ----------

export function loadApiKey(env = process.env) {
  let k = env.WIGLE_API_KEY;
  if (!k) {
    try { k = readFileSync(`${homedir()}/.config/wigle/api-key`, "utf8"); } catch { /* handled below */ }
  }
  k = (k || "").trim().replace(/^Basic\s+/i, "");
  if (!k) throw new Error("no WiGLE API key (WIGLE_API_KEY or ~/.config/wigle/api-key)");
  return k;
}

export async function callApi({ endpoint, params, cursor, apiKey, baseUrl = API_BASE, timeoutMs = 900_000, fetchImpl = fetch }) {
  const q = new URLSearchParams(params);
  if (cursor) q.set("searchAfter", cursor);
  try {
    const res = await fetchImpl(`${baseUrl}${endpoint}?${q}`, {
      headers: { Authorization: `Basic ${apiKey}`, Accept: "application/json", "User-Agent": "wigle-cli/1 (research-agent; no automatic retries)" },
      redirect: "manual",
      signal: AbortSignal.timeout(timeoutMs),
    });
    return { status: res.status, text: await res.text() };
  } catch (e) {
    // Never include the request (headers) in the message.
    return { networkError: `${e?.name ?? "Error"}${e?.cause?.code ? ` ${e.cause.code}` : ""}: ${String(e?.message ?? e).slice(0, 200)}` };
  }
}

// ---------- store ----------
// Store interface: insert(row) -> id; findPrior({endpoint, params_hash, search_after}) -> {id, called_at}|null;
// rowExists({params_hash, search_after, called_at}) -> bool; today() -> 'YYYY-MM-DD'; dailyCounts(days) -> [{day, ok, limited, last_limited}].

export function dsn(env = process.env) {
  const v = env.WIGLE_DB_URL || (() => { try { return readFileSync(`${homedir()}/.config/wigle/db-url`, "utf8"); } catch { return ""; } })();
  if (!v.trim()) throw new Error("no database DSN (WIGLE_DB_URL or ~/.config/wigle/db-url)");
  return v.trim();
}

/** jsonb cannot hold U+0000 (WiGLE ssids contain it): store the six characters \\u0000 in its place. */
export function escapeNul(v) {
  if (typeof v === "string") return v.includes("\0") ? v.replaceAll("\0", "\\u0000") : v;
  if (Array.isArray(v)) return v.map(escapeNul);
  if (v && typeof v === "object") return Object.fromEntries(Object.entries(v).map(([k, x]) => [escapeNul(k), escapeNul(x)]));
  return v;
}

export async function openPgStore(url) {
  const home = process.env.RESEARCH_AGENT_HOME || `${homedir()}/.local/share/research-agent`;
  const { Client } = createRequire(`${home}/tools/node_modules/`)("pg");
  const client = new Client({ connectionString: url });
  await client.connect();
  // Pacific day with the reset at 00:05: shift by 5 minutes before taking the date.
  const DAY = "((called_at at time zone 'America/Los_Angeles') - interval '5 minutes')::date";
  return {
    async insert(r) {
      const { rows } = await client.query(
        `insert into wigle.responses (endpoint, params, params_hash, search_after, next_search_after, total, http_status, error, caller, called_at, response)
         values ($1, $2::jsonb, $3, $4, $5, $6, $7, $8, $9, $10, $11::jsonb) returning id`,
        [r.endpoint, JSON.stringify(r.params), r.params_hash, r.search_after, r.next_search_after, r.total, r.http_status, r.error, r.caller,
          r.called_at, r.response === null ? null : JSON.stringify(escapeNul(r.response))]);
      return Number(rows[0].id);
    },
    async findPrior({ endpoint, params_hash, search_after }) {
      const { rows } = await client.query(
        `select id, called_at from wigle.responses where endpoint = $1 and params_hash = $2 and search_after is not distinct from $3
         order by called_at desc limit 1`, [endpoint, params_hash, search_after]);
      return rows[0] ? { id: Number(rows[0].id), called_at: rows[0].called_at } : null;
    },
    async rowExists({ params_hash, search_after, called_at }) {
      const { rowCount } = await client.query(
        `select 1 from wigle.responses where params_hash = $1 and search_after is not distinct from $2 and called_at = $3 limit 1`,
        [params_hash, search_after, called_at]);
      return rowCount > 0;
    },
    async today() {
      const { rows } = await client.query(`select to_char(((now() at time zone 'America/Los_Angeles') - interval '5 minutes')::date, 'YYYY-MM-DD') d`);
      return rows[0].d;
    },
    async dailyCounts(days = 60) {
      const { rows } = await client.query(
        `select to_char(${DAY}, 'YYYY-MM-DD') as day,
                count(*) filter (where http_status = 200 and error is null)::int as ok,
                count(*) filter (where http_status = 429 or error like 'daily limit:%')::int as limited,
                max(called_at) filter (where http_status = 429 or error like 'daily limit:%') as last_limited
           from wigle.responses where called_at > now() - make_interval(days => $1)
          group by 1 order by 1 desc`, [days]);
      return rows.map((r) => ({ ...r, last_limited: r.last_limited ? r.last_limited.toISOString() : null }));
    },
    close: () => client.end().catch(() => {}),
  };
}

// ---------- commands ----------

/** One API call, always archived. Returns {out, exit}. */
export async function searchOnce(ctx, { endpoint, params, cursor, caller }) {
  const hash = paramsHash(params);
  const prior = await ctx.store.findPrior({ endpoint, params_hash: hash, search_after: cursor });
  if (prior) ctx.notice(`wigle: notice: identical query+cursor archived before as id ${prior.id} on ${new Date(prior.called_at).toISOString()}`);
  const calledAt = new Date();
  const res = await callApi({ endpoint, params, cursor, apiKey: ctx.apiKey, baseUrl: ctx.baseUrl, timeoutMs: ctx.timeoutMs, fetchImpl: ctx.fetchImpl });
  const c = classify(res);
  const id = await ctx.store.insert({
    endpoint, params, params_hash: hash, search_after: cursor, next_search_after: c.next, total: c.total,
    http_status: res.status ?? null, error: c.error, caller, called_at: calledAt, response: c.response,
  });
  const out = { id, http_status: res.status ?? null, total: c.total, results_count: c.count, next_search_after: c.next, done: c.kind === "ok" && c.next === null };
  if (c.error) out.error = c.error.slice(0, 200);
  return { out, exit: c.kind === "ok" ? EXIT.OK : c.kind === "limit" ? EXIT.LIMIT : EXIT.FAILED };
}

export async function cmdSearch(ctx, argv) {
  const a = parseSearchArgs(argv);
  const params = buildParams(a.endpoint, a.pairs);
  const { out, exit } = await searchOnce(ctx, { endpoint: a.endpoint, params, cursor: a.cursor, caller: a.caller });
  ctx.print(out);
  return exit;
}

export async function cmdPages(ctx, argv) {
  const a = parseSearchArgs(argv);
  if (!a.maxPages) throw new UsageError("pages needs --max-pages N");
  const params = buildParams(a.endpoint, a.pairs);
  let cursor = a.cursor, pages = 0, exit = EXIT.OK, done = false;
  while (pages < a.maxPages) {
    const r = await searchOnce(ctx, { endpoint: a.endpoint, params, cursor, caller: a.caller });
    pages++;
    ctx.print(r.out);
    if (r.exit !== EXIT.OK) { exit = r.exit; break; }
    cursor = r.out.next_search_after;
    if (cursor === null) { done = true; break; }
  }
  ctx.print({ summary: true, pages, done, next_search_after: exit === EXIT.OK && !done ? cursor : null, stopped_on_error: exit !== EXIT.OK });
  return exit;
}

/** Pure: derives the quota report from per-day counts (newest first) and today's Pacific day. */
export function computeQuota(days, today) {
  const t = days.find((d) => d.day === today) ?? { ok: 0, limited: 0, last_limited: null };
  const hitDays = days.filter((d) => d.limited > 0 && d.ok > 0).slice(0, 14);
  const lastHit = days.find((d) => d.last_limited);
  const est = hitDays.length ? hitDays[0].ok : null;
  const estMax = hitDays.length ? Math.max(...hitDays.map((d) => d.ok)) : null;
  return {
    pacific_day: today, resets_at: "00:05 America/Los_Angeles",
    used_today: t.ok, rate_limited_today: t.limited, last_rate_limited_at: lastHit ? lastHit.last_limited : null,
    estimated_daily_limit: est, estimate_basis: est === null ? null : `successful calls on ${hitDays[0].day}, the most recent day with a limit hit`,
    estimated_daily_limit_max_14d: estMax,
    remaining: est === null ? "unknown" : Math.max(0, est - t.ok),
  };
}

export async function cmdQuota(ctx) {
  ctx.print(computeQuota(await ctx.store.dailyCounts(60), await ctx.store.today()));
  return EXIT.OK;
}

/** Maps one collector request dir (metadata.json + response.raw) to a row, or null when unreadable. */
export function collectorRecord(dir) {
  let meta, raw;
  try {
    meta = JSON.parse(readFileSync(join(dir, "metadata.json"), "utf8"));
    raw = existsSync(join(dir, "response.raw")) ? readFileSync(join(dir, "response.raw"), "utf8") : "";
  } catch { return null; }
  if (!meta?.endpoint || !meta.params || !meta.started_utc) return null;
  const endpoint = new URL(meta.endpoint).pathname;
  const { searchAfter, ...rest } = meta.params;
  const params = Object.fromEntries(Object.keys(rest).sort().map((k) => [k, String(rest[k])]));
  const netErr = meta.transport_error_type || (meta.response_truncated && !meta.http_status ? "request failed" : null);
  const c = classify({ status: meta.http_status ?? null, text: raw, networkError: netErr });
  return {
    endpoint, params, params_hash: paramsHash(params), search_after: searchAfter == null ? null : String(searchAfter),
    next_search_after: c.next, total: c.total, http_status: meta.http_status ?? null, error: c.error,
    caller: "cron:us-osm-hospitals", called_at: new Date(meta.started_utc), response: c.response,
  };
}

export async function cmdImportCollector(ctx, argv) {
  const dirArg = argv[0];
  if (!dirArg) throw new UsageError("import-collector needs <run-dir>");
  const reqDir = existsSync(join(dirArg, "requests")) ? join(dirArg, "requests") : dirArg;
  const names = readdirSync(reqDir).sort();
  const r = { imported: 0, skipped: 0, errors_kept: 0, unreadable: 0 };
  for (const n of names) {
    const rec = collectorRecord(join(reqDir, n));
    if (!rec) { r.unreadable++; continue; }
    if (await ctx.store.rowExists(rec)) { r.skipped++; continue; }
    await ctx.store.insert(rec);
    r.imported++;
    if (rec.error) r.errors_kept++;
  }
  ctx.print(r);
  return EXIT.OK;
}

const USAGE = `usage:
  wigle search <endpoint> key=value ... [--page-after <cursor>] [--caller <label>]
  wigle pages <endpoint> key=value ... --max-pages N [--page-after <cursor>] [--caller <label>]
  wigle quota
  wigle import-collector <run-dir>
endpoints: ${Object.keys(MAX_RESULTS_PER_PAGE).join(", ")} (the /api/v2/ prefix is optional)`;

/** ctx: {store, apiKey?, baseUrl?, timeoutMs?, fetchImpl?, print, notice}. Returns the exit code. */
export async function run(argv, ctx) {
  const [cmd, ...rest] = argv;
  try {
    if (cmd === "search") return await cmdSearch(ctx, rest);
    if (cmd === "pages") return await cmdPages(ctx, rest);
    if (cmd === "quota") return await cmdQuota(ctx);
    if (cmd === "import-collector") return await cmdImportCollector(ctx, rest);
    throw new UsageError(cmd ? `unknown command ${cmd}` : "missing command");
  } catch (e) {
    if (e instanceof UsageError) { ctx.notice(`wigle: ${e.message}\n${USAGE}`); return EXIT.USAGE; }
    throw e;
  }
}

async function main() {
  const argv = process.argv.slice(2);
  const needsApi = argv[0] === "search" || argv[0] === "pages";
  let store, code;
  try {
    // Validate usage before touching credentials or the network.
    if (needsApi) parseSearchArgs(argv.slice(1));
    store = await openPgStore(dsn());
    const ctx = {
      store, baseUrl: process.env.WIGLE_BASE_URL || API_BASE,
      apiKey: needsApi ? loadApiKey() : null,
      print: (o) => console.log(JSON.stringify(o)), notice: (m) => console.error(m),
    };
    code = await run(argv, ctx);
  } catch (e) {
    if (e instanceof UsageError) { console.error(`wigle: ${e.message}\n${USAGE}`); code = EXIT.USAGE; }
    else { console.error(`wigle: ${String(e?.message ?? e).replace(/postgres(ql)?:\/\/\S+/g, "postgres://***")}`); code = EXIT.FAILED; }
  } finally { await store?.close(); }
  process.exit(code);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) await main();
