#!/usr/bin/env node
// gitea-watch: report human activity (and pushes to protected branches) in Gitea repos as JSONL.
//
// Node 22 built-ins only. See README.md. Exit codes: 0 ok, 1 token rejected (401/403),
// 2 usage or configuration error, 3 --once round failed transiently.
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { parseArgs } from "node:util";
import { pathToFileURL } from "node:url";

const MIN_INTERVAL = 30;
const MAX_BACKOFF = 600;
const TIMEOUT_MS = 30_000;
const TEXT_LIMIT = 160;
const PULL_OPS = new Set([
  "create_pull_request", "comment_pull", "merge_pull_request", "close_pull_request",
  "reopen_pull_request", "approve_pull_request", "reject_pull_request",
  "pull_review_dismissed", "auto_merge_pull_request", "pull_request_ready_for_review",
]);
const DEFAULTS = {
  token_env: "GITEA_TOKEN",
  bots: [],
  interval: 60,
  state_dir: "~/.local/state/gitea-watch",
  protected_refs: ["main"],
  page_size: 50,
};
const ACK_DEFAULTS = { enabled: true, reaction: "eyes", on: ["comment_issue", "comment_pull"] };

/** The token is rejected (401/403); retrying cannot help. */
export class Fatal extends Error {}
/** Network error or server-side failure; skip the round and retry later. */
export class Transient extends Error {}
export class ConfigError extends Error {}

// ---------------------------------------------------------------- TOML subset

/**
 * Parses the subset the config uses: `key = value` with basic/literal strings, integers,
 * booleans and (possibly multi-line) arrays of those, `[table]` headers, `#` comments.
 */
export function parseToml(text) {
  let i = 0;
  const root = Object.create(null);
  let cur = root;
  const line = () => text.slice(0, i).split("\n").length;
  const fail = (msg) => { throw new Error(`line ${line()}: ${msg}`); };
  const skipSpace = () => { while (text[i] === " " || text[i] === "\t") i++; };
  const skipComment = () => { if (text[i] === "#") while (i < text.length && text[i] !== "\n") i++; };
  const skipBlank = () => {
    for (;;) {
      skipSpace(); skipComment();
      if (text[i] === "\n" || text[i] === "\r") i++; else break;
    }
  };
  const BARE = /[A-Za-z0-9_-]/;
  const ESC = { b: "\b", t: "\t", n: "\n", f: "\f", r: "\r", '"': '"', "\\": "\\" };

  function basicString() {
    if (text.startsWith('"""', i)) fail("multi-line strings are not supported");
    i++;
    let s = "";
    for (;;) {
      const c = text[i++];
      if (c === undefined || c === "\n") fail("unterminated string");
      if (c === '"') return s;
      if (c !== "\\") { s += c; continue; }
      const e = text[i++];
      if (e in ESC) s += ESC[e];
      else if (e === "u" || e === "U") {
        const n = e === "u" ? 4 : 8;
        const hex = text.slice(i, i + n);
        if (!new RegExp(`^[0-9a-fA-F]{${n}}$`).test(hex)) fail("bad unicode escape");
        s += String.fromCodePoint(parseInt(hex, 16));
        i += n;
      } else fail(`bad escape \\${e}`);
    }
  }
  function literalString() {
    if (text.startsWith("'''", i)) fail("multi-line strings are not supported");
    const end = text.indexOf("'", i + 1);
    const nl = text.indexOf("\n", i);
    if (end < 0 || (nl >= 0 && nl < end)) fail("unterminated string");
    const s = text.slice(i + 1, end);
    i = end + 1;
    return s;
  }
  function value() {
    const c = text[i];
    if (c === '"') return basicString();
    if (c === "'") return literalString();
    if (c === "[") {
      i++;
      const arr = [];
      for (;;) {
        skipBlank();
        if (text[i] === "]") { i++; return arr; }
        arr.push(value());
        skipBlank();
        if (text[i] === ",") i++;
        else if (text[i] !== "]") fail("expected , or ] in array");
      }
    }
    const m = /^(true|false|[+-]?\d+(?:_\d+)*)(?![\w.])/.exec(text.slice(i, i + 64));
    if (!m) fail("unsupported value");
    i += m[0].length;
    if (m[1] === "true") return true;
    if (m[1] === "false") return false;
    return Number(m[1].replaceAll("_", ""));
  }
  function key() {
    if (text[i] === '"') return basicString();
    if (text[i] === "'") return literalString();
    const start = i;
    while (BARE.test(text[i] ?? "")) i++;
    if (i === start) fail("expected a key");
    return text.slice(start, i);
  }
  function endOfLine() {
    skipSpace(); skipComment();
    if (i < text.length && text[i] !== "\n" && text[i] !== "\r") fail("unexpected characters after value");
  }

  for (;;) {
    skipBlank();
    if (i >= text.length) return root;
    if (text[i] === "[") {
      if (text[i + 1] === "[") fail("arrays of tables are not supported");
      i++;
      let t = root;
      for (;;) {
        skipSpace();
        const k = key();
        skipSpace();
        if (!(k in t)) t[k] = Object.create(null);
        else if (typeof t[k] !== "object" || Array.isArray(t[k])) fail(`${k} is not a table`);
        t = t[k];
        if (text[i] === ".") { i++; continue; }
        if (text[i] !== "]") fail("expected ]");
        i++;
        break;
      }
      cur = t;
      endOfLine();
      continue;
    }
    const k = key();
    skipSpace();
    if (text[i] !== "=") fail("expected =");
    i++;
    skipSpace();
    if (k in cur) fail(`duplicate key ${k}`);
    cur[k] = value();
    endOfLine();
  }
}

// ---------------------------------------------------------------- config

const isInt = (v) => typeof v === "number" && Number.isInteger(v);

export function loadConfig(args) {
  let cfg = {};
  if (args.config) {
    try {
      cfg = { ...parseToml(fs.readFileSync(args.config, "utf8")) };
    } catch (e) {
      throw new ConfigError(`cannot read config ${args.config}: ${e.message}`);
    }
  }
  const flags = {
    gitea_url: args.gitea_url, token_env: args.token_env, repos: args.repos,
    bots: args.bots, interval: args.interval, state_dir: args.state_dir,
    protected_refs: args.protected_refs, page_size: args.page_size,
  };
  for (const [k, v] of Object.entries(flags)) if (v !== undefined) cfg[k] = v;
  for (const [k, v] of Object.entries(DEFAULTS)) if (!(k in cfg)) cfg[k] = v;
  cfg.ack = { ...ACK_DEFAULTS, ...(cfg.ack ?? {}) };
  if (!cfg.gitea_url) throw new ConfigError("gitea_url is not set");
  cfg.gitea_url = String(cfg.gitea_url).replace(/\/+$/, "");
  const repos = cfg.repos;
  if (!Array.isArray(repos) || !repos.length ||
      !repos.every((r) => typeof r === "string" && r.split("/").length === 2)) {
    throw new ConfigError("repos must be a non-empty list of owner/name");
  }
  if (!isInt(cfg.interval) || cfg.interval < MIN_INTERVAL) {
    throw new ConfigError(`interval must be an integer >= ${MIN_INTERVAL} seconds`);
  }
  if (!isInt(cfg.page_size) || cfg.page_size < 1) {
    throw new ConfigError("page_size must be a positive integer");
  }
  cfg.state_dir = expandUser(cfg.state_dir);
  return cfg;
}

function expandUser(p) {
  if (p === "~") return os.homedir();
  if (p.startsWith("~/")) return path.join(os.homedir(), p.slice(2));
  return p;
}

// ---------------------------------------------------------------- state

export function statePath(stateDir, repo) {
  return path.join(stateDir, repo.replaceAll("/", "__") + ".json");
}

/** Returns last_id, or null if there is no state file. */
export function readState(file) {
  let raw;
  try {
    raw = fs.readFileSync(file, "utf8");
  } catch (e) {
    if (e.code === "ENOENT") return null;
    throw new Transient(`unreadable state file ${file}: ${e.message}`);
  }
  try {
    const n = Math.trunc(Number(JSON.parse(raw).last_id));
    if (!Number.isFinite(n)) throw new Error("last_id is not a number");
    return n;
  } catch (e) {
    throw new Transient(`unreadable state file ${file}: ${e.message}`);
  }
}

/** Atomic: write a temp file in the same directory, then rename over the target. */
export function writeState(file, lastId, fsx = fs) {
  const dir = path.dirname(file);
  fsx.mkdirSync(dir, { recursive: true });
  const tmp = path.join(dir, `.tmp-${process.pid}-${Math.random().toString(36).slice(2)}.json`);
  try {
    const fd = fsx.openSync(tmp, "wx", 0o600);
    try {
      fsx.writeSync(fd, JSON.stringify({ last_id: lastId }));
      fsx.fsyncSync(fd);
    } finally {
      fsx.closeSync(fd);
    }
    fsx.renameSync(tmp, file);
  } catch (e) {
    try { fsx.unlinkSync(tmp); } catch { /* already gone */ }
    throw e;
  }
}

// ---------------------------------------------------------------- event helpers

export function normRef(ref) {
  for (const prefix of ["refs/heads/", "refs/tags/"]) {
    if (ref.startsWith(prefix)) return ref.slice(prefix.length);
  }
  return ref;
}

export function isoUtc(s) {
  const t = new Date(typeof s === "string" ? s : NaN);
  if (Number.isNaN(t.getTime())) return typeof s === "string" ? s : "";
  return t.toISOString().replace(/\.\d+Z$/, "Z");
}

export function truncate(text) {
  const cp = Array.from(text);
  return cp.length <= TEXT_LIMIT ? text : cp.slice(0, TEXT_LIMIT).join("") + "…";
}

const digits = (s) => /^\d+$/.test(s);

/** Flattens a raw feed event into the output record; also reports whether the actor is a bot. */
export function parseEvent(ev, cfg, repo) {
  const op = ev.op_type || "";
  const user = ev.act_user?.login || "";
  const ref = ev.ref_name || "";
  const comment = ev.comment && typeof ev.comment === "object" && !Array.isArray(ev.comment) ? ev.comment : {};
  const content = ev.content || "";

  // Issue/PR events carry "<index>|<title or comment text>" in content.
  const bar = content.indexOf("|");
  const head = bar >= 0 ? content.slice(0, bar) : "";
  const rest = bar >= 0 ? content.slice(bar + 1) : content;
  let index = bar >= 0 && digits(head) ? Number(head) : null;
  if (index === null) {
    const tail = (comment.issue_url || comment.pull_request_url || "").split("/").pop();
    index = digits(tail) ? Number(tail) : null;
  }
  const commentId = ev.comment_id || comment.id || null;

  let text;
  if (op === "commit_repo") {
    text = "";
    try {
      const commits = JSON.parse(content).Commits || [];
      const msg = commits.length ? commits[0].Message : "";
      text = typeof msg === "string" ? msg : "";
    } catch { /* not a push payload */ }
  } else {
    text = comment.body || rest;
  }

  const base = cfg.gitea_url;
  const kind = PULL_OPS.has(op) ? "pulls" : "issues";
  let url = comment.html_url;
  if (!url) {
    if (index !== null) url = `${base}/${repo}/${kind}/${index}` + (commentId ? `#issuecomment-${commentId}` : "");
    else if (op === "commit_repo" && ref) url = `${base}/${repo}/commits/branch/${normRef(ref)}`;
    else url = `${base}/${repo}`;
  }

  const isBot = cfg.bots.some((b) => b.toLowerCase() === user.toLowerCase());
  const alert = op === "commit_repo" && cfg.protected_refs.map(normRef).includes(normRef(ref));
  return {
    rec: {
      repo, id: ev.id, created: isoUtc(ev.created), user, op, ref, issue: index, comment_id: commentId,
      url, text: truncate(text), alert,
    },
    isBot,
  };
}

// ---------------------------------------------------------------- client

export class Gitea {
  constructor(base, token, log) {
    this.base = base;
    this.token = token;
    this.log = log;
    this._me = null;
  }

  /** Returns [status, parsed JSON or null]. Throws Fatal on 401/403, Transient on I/O errors. */
  async request(method, apiPath, body) {
    // No token behind keyproxy (token_env = "none"): the proxy adds it.
    const headers = { Accept: "application/json", "X-Caller": "gitea-watch" };
    if (this.token) headers.Authorization = "token " + this.token;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    let status, raw;
    try {
      const r = await fetch(this.base + "/api/v1" + apiPath, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(TIMEOUT_MS),
      });
      status = r.status;
      raw = await r.text();
    } catch (e) {
      throw new Transient(`${method} ${apiPath}: ${e.cause?.code ?? e.cause?.message ?? e.message}`);
    }
    if (status === 401 || status === 403) throw new Fatal(`${method} ${apiPath}: HTTP ${status}`);
    try {
      return [status, raw ? JSON.parse(raw) : null];
    } catch {
      if (status >= 400) return [status, null];
      throw new Transient(`${method} ${apiPath}: HTTP ${status} with a non-JSON body`);
    }
  }

  /** All events with id > lastId, ascending, following pages until one reaches lastId. */
  async fetchNew(repo, lastId, limit) {
    const found = new Map();
    for (let page = 1; ; page++) {
      const [status, batch] = await this.request("GET", `/repos/${repo}/activities/feeds?limit=${limit}&page=${page}`);
      if (status >= 400) throw new Transient(`${repo}: activity feed returned HTTP ${status}`);
      if (!Array.isArray(batch)) throw new Transient(`${repo}: unexpected activity feed body`);
      if (!batch.length) break;
      let reached = false;
      for (const ev of batch) {
        if (ev.id <= lastId) reached = true;
        else found.set(ev.id, ev);
      }
      if (reached) break;
    }
    return [...found.keys()].sort((a, b) => a - b).map((id) => found.get(id));
  }

  async latestId(repo) {
    const [status, batch] = await this.request("GET", `/repos/${repo}/activities/feeds?limit=1&page=1`);
    if (status >= 400 || !Array.isArray(batch)) throw new Transient(`${repo}: activity feed returned HTTP ${status}`);
    return batch.reduce((m, ev) => Math.max(m, ev.id), 0);
  }

  /** Accounts whose existing reaction counts as ours: the token's user, plus the bots. */
  async myLogins(bots) {
    if (this._me === null) {
      const me = new Set(bots.map((b) => b.toLowerCase()));
      try {
        const [status, user] = await this.request("GET", "/user");
        if (status === 200 && user && typeof user === "object" && user.login) me.add(user.login.toLowerCase());
        else this.log(`GET /user returned HTTP ${status}; matching existing reactions by bots list only`);
      } catch (e) {
        if (!(e instanceof Transient)) throw e;
        this.log(`${e.message}; matching existing reactions by bots list only`);
      }
      this._me = me;
    }
    return this._me;
  }

  /** Idempotent reaction (GET before POST). Returns the ack record. */
  async react(apiPath, reaction, bots) {
    const ack = { reaction };
    try {
      const [status, existing] = await this.request("GET", apiPath);
      if (status >= 400) {
        ack.status = status;
        return ack;
      }
      const mine = await this.myLogins(bots);
      for (const r of Array.isArray(existing) ? existing : []) {
        if ((r.content || r.reaction) === reaction && mine.has((r.user?.login || "").toLowerCase())) {
          ack.status = "exists";
          return ack;
        }
      }
      [ack.status] = await this.request("POST", apiPath, { content: reaction });
    } catch (e) {
      if (!(e instanceof Transient)) throw e;
      this.log(`ack failed: ${e.message}`);
      ack.status = "error";
      ack.error = "network error";
    }
    return ack;
  }
}

// ---------------------------------------------------------------- watcher

export class Watcher {
  constructor(cfg, token, { dryRun = false, out = process.stdout, err = process.stderr } = {}) {
    this.cfg = cfg;
    this.token = token;
    this.dryRun = dryRun;
    this.out = out;
    this.err = err;
    this.client = new Gitea(cfg.gitea_url, token, (m) => this.log(m));
  }

  log(msg) {
    // The token is never part of a message we build; scrub anyway.
    this.err.write("gitea-watch: " + (this.token ? String(msg).replaceAll(this.token, "***") : String(msg)) + "\n");
  }

  emit(rec) {
    this.out.write(JSON.stringify(rec) + "\n");
  }

  async ackFor(repo, rec) {
    const ack = this.cfg.ack;
    if (!ack.enabled || !ack.on.includes(rec.op)) return null;
    const reaction = ack.reaction;
    let apiPath;
    if (rec.op.startsWith("comment_")) {
      if (!rec.comment_id) return { reaction, status: "skipped", error: "no comment_id" };
      apiPath = `/repos/${repo}/issues/comments/${rec.comment_id}/reactions`;
    } else if (rec.issue !== null) {
      apiPath = `/repos/${repo}/issues/${rec.issue}/reactions`;
    } else {
      return { reaction, status: "skipped", error: "no issue index" };
    }
    if (this.dryRun) return { reaction, status: "dry-run" };
    return this.client.react(apiPath, reaction, this.cfg.bots);
  }

  async initRepo(repo) {
    const last = await this.client.latestId(repo);
    if (!this.dryRun) writeState(statePath(this.cfg.state_dir, repo), last);
    this.log(`${repo}: starting after event ${last}`);
  }

  async pollRepo(repo) {
    const file = statePath(this.cfg.state_dir, repo);
    const last = readState(file);
    if (last === null) {
      await this.initRepo(repo);
      return;
    }
    const events = await this.client.fetchNew(repo, last, this.cfg.page_size);
    let done = last;
    try {
      for (const ev of events) {
        const { rec, isBot } = parseEvent(ev, this.cfg, repo);
        if (!isBot || rec.alert) {
          rec.ack = isBot ? null : await this.ackFor(repo, rec);
          this.emit(rec);
        }
        done = ev.id;
      }
    } finally {
      // Commit only after output and reactions; on an error keep what was completed.
      if (done !== last && !this.dryRun) writeState(file, done);
    }
  }

  /** Returns true if every repo succeeded. Throws Fatal on a rejected token. */
  async round(repos) {
    let ok = true;
    for (const repo of repos) {
      try {
        await this.pollRepo(repo);
      } catch (e) {
        if (!(e instanceof Transient)) throw e;
        this.log(`${repo}: ${e.message}`);
        ok = false;
      }
    }
    return ok;
  }
}

// ---------------------------------------------------------------- main

const MULTI_FLAGS = new Set(["--repos", "--bots", "--protected-refs"]);
const USAGE = `usage: gitea-watch [--config FILE] [--once] [--dry-run] [--init] [--gitea-url URL]
                   [--token-env VAR] [--repos R [R ...]] [--bots B [B ...]] [--interval SECONDS]
                   [--state-dir DIR] [--protected-refs REF [REF ...]] [--page-size N]
`;

/** argparse-style nargs="+": `--repos a b` becomes `--repos a --repos b` for parseArgs. */
function expandMulti(argv) {
  const out = [];
  for (let i = 0; i < argv.length; i++) {
    if (!MULTI_FLAGS.has(argv[i])) { out.push(argv[i]); continue; }
    const flag = argv[i];
    out.push(flag);
    if (i + 1 < argv.length && !argv[i + 1].startsWith("-")) out.push(argv[++i]);
    while (i + 1 < argv.length && !argv[i + 1].startsWith("-")) out.push(flag, argv[++i]);
  }
  return out;
}

export function parseCli(argv) {
  const { values } = parseArgs({
    args: expandMulti(argv),
    options: {
      help: { type: "boolean", short: "h" },
      config: { type: "string" },
      once: { type: "boolean" },
      "dry-run": { type: "boolean" },
      init: { type: "boolean" },
      "gitea-url": { type: "string" },
      "token-env": { type: "string" },
      repos: { type: "string", multiple: true },
      bots: { type: "string", multiple: true },
      interval: { type: "string" },
      "state-dir": { type: "string" },
      "protected-refs": { type: "string", multiple: true },
      "page-size": { type: "string" },
    },
    allowPositionals: false,
  });
  const int = (name, v) => {
    if (v === undefined) return undefined;
    if (!/^[+-]?\d+$/.test(v)) throw new TypeError(`argument --${name}: invalid int value: '${v}'`);
    return Number(v);
  };
  return {
    help: values.help, config: values.config, once: values.once, dryRun: values["dry-run"], init: values.init,
    gitea_url: values["gitea-url"], token_env: values["token-env"], repos: values.repos, bots: values.bots,
    interval: int("interval", values.interval), state_dir: values["state-dir"],
    protected_refs: values["protected-refs"], page_size: int("page-size", values["page-size"]),
  };
}

/** Resolves true if stopped while waiting, false when the delay elapsed. */
function defaultSleep(seconds, stop) {
  return new Promise((resolve) => {
    if (stop.stopped) return resolve(true);
    const timer = setTimeout(() => { stop.waiters.delete(wake); resolve(false); }, seconds * 1000);
    const wake = () => { clearTimeout(timer); resolve(true); };
    stop.waiters.add(wake);
  });
}

/**
 * Runs the CLI and resolves to the exit code. `io` lets tests inject env, output streams,
 * the sleep function (seconds; resolves true to stop) and signal handling.
 */
export async function main(argv, io = {}) {
  const env = io.env ?? process.env;
  const out = io.out ?? process.stdout;
  const err = io.err ?? process.stderr;
  const sleep = io.sleep ?? defaultSleep;

  let args, cfg;
  try {
    args = parseCli(argv);
  } catch (e) {
    err.write(`${USAGE}gitea-watch: error: ${e.message}\n`);
    return 2;
  }
  if (args.help) {
    out.write(USAGE);
    return 0;
  }
  try {
    cfg = loadConfig(args);
  } catch (e) {
    if (!(e instanceof ConfigError)) throw e;
    err.write(`gitea-watch: ${e.message}\n`);
    return 2;
  }
  const token = cfg.token_env === "none" ? "" : env[cfg.token_env] ?? "";
  if (!token && cfg.token_env !== "none") {
    err.write(`gitea-watch: environment variable ${cfg.token_env} is not set\n`);
    return 2;
  }

  const w = new Watcher(cfg, token, { dryRun: !!args.dryRun, out, err });
  const stop = { stopped: false, waiters: new Set() };
  const onSignal = () => {
    stop.stopped = true;
    for (const wake of [...stop.waiters]) wake();
  };
  const signals = io.signals === false ? [] : ["SIGINT", "SIGTERM"];
  for (const s of signals) process.on(s, onSignal);

  try {
    if (args.init) {
      let ok = true;
      for (const repo of cfg.repos) {
        try {
          await w.initRepo(repo);
        } catch (e) {
          if (!(e instanceof Transient)) throw e;
          w.log(`${repo}: ${e.message}`);
          ok = false;
        }
      }
      return ok ? 0 : 3;
    }
    let failures = 0;
    for (;;) {
      const ok = await w.round(cfg.repos);
      if (args.once) return ok ? 0 : 3;
      failures = ok ? 0 : failures + 1;
      const delay = ok ? cfg.interval : Math.min(MAX_BACKOFF, cfg.interval * 2 ** (failures - 1));
      if (!ok) w.log(`round failed (${failures} in a row); retrying in ${delay}s`);
      if ((await sleep(delay, stop)) || stop.stopped) return 0;
    }
  } catch (e) {
    if (!(e instanceof Fatal)) throw e;
    w.log(`${e.message}: the token was rejected; check its permissions (read repo, write issues)`);
    return 1;
  } finally {
    for (const s of signals) process.off(s, onSignal);
  }
}

const entry = process.argv[1] && (() => { try { return fs.realpathSync(process.argv[1]); } catch { return ""; } })();
if (entry && import.meta.url === pathToFileURL(entry).href) {
  process.exitCode = await main(process.argv.slice(2));
}
