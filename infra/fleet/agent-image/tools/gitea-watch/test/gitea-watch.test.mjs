import { test, describe, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import { main, writeState, parseToml } from "../gitea-watch.mjs";

const SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "gitea-watch.mjs");
const TOKEN = "s3cr3t-token-0123456789abcdef";
const REPO = "y2wei/wirelesswiki";
const BOT = "wiki-bot";

function event(id, { user = "y2wei", op = "comment_issue", ref = "", comment_id, index, body = "" } = {}) {
  const ev = {
    id, op_type: op, act_user: { login: user }, ref_name: ref,
    created: "2026-10-26T20:02:11+02:00", content: "",
  };
  if (index !== undefined) ev.content = `${index}|${body}`;
  if (comment_id !== undefined) ev.comment_id = comment_id;
  return ev;
}

/** Serves activities/feeds with paging and limit, /user, and comment/issue reactions. */
class FakeGitea {
  constructor() {
    this.events = [];          // any order; served newest first
    this.reactions = {};       // request path -> list of reaction objects
    this.requests = [];        // [method, path]
    this.feedStatus = null;    // force this status on the feed
    this.postStatus = null;    // force this status on POST
    this.cap = 50;             // server-side maximum page size
    this.token = TOKEN;
    this.server = http.createServer(async (req, res) => {
      const u = new URL(req.url, "http://x");
      this.requests.push([req.method, u.pathname + u.search]);
      let raw = "";
      for await (const c of req) raw += c;
      const reply = (status, body) => {
        const b = JSON.stringify(body);
        res.writeHead(status, { "Content-Type": "application/json", "Content-Length": Buffer.byteLength(b) });
        res.end(b);
      };
      if (req.headers.authorization !== "token " + this.token) return reply(401, { message: "bad token" });
      const p = u.pathname.slice("/api/v1".length);
      if (req.method === "GET" && p === "/user") return reply(200, { login: BOT });
      if (req.method === "GET" && p === `/repos/${REPO}/activities/feeds`) {
        if (this.feedStatus) return reply(this.feedStatus, { message: "boom" });
        const limit = Math.min(Number(u.searchParams.get("limit") ?? 30), this.cap);
        const page = Number(u.searchParams.get("page") ?? 1);
        const evs = [...this.events].sort((a, b) => b.id - a.id);
        return reply(200, evs.slice((page - 1) * limit, page * limit));
      }
      if (p.endsWith("/reactions") && p.startsWith(`/repos/${REPO}/issues/`)) {
        if (req.method === "GET") return reply(200, this.reactions[p] ?? []);
        const content = JSON.parse(raw).content;
        if (this.postStatus) return reply(this.postStatus, { message: "boom" });
        (this.reactions[p] ??= []).push({ user: { login: BOT }, content });
        return reply(201, { content });
      }
      reply(404, { message: "not found" });
    });
  }
  async listen() {
    await new Promise((r) => this.server.listen(0, "127.0.0.1", r));
    this.url = `http://127.0.0.1:${this.server.address().port}`;
  }
  async close() {
    this.server.closeAllConnections();
    await new Promise((r) => this.server.close(r));
  }
  posts() { return this.requests.filter((r) => r[0] === "POST"); }
}

class Sink {
  constructor() { this.text = ""; }
  write(s) { this.text += s; return true; }
}

let server, tmp, stateDir, stateFile, outText, errText;

beforeEach(async () => {
  server = new FakeGitea();
  await server.listen();
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), "gw-test-"));
  stateDir = path.join(tmp, "state");
  stateFile = path.join(stateDir, "y2wei__wirelesswiki.json");
  outText = errText = "";
});
afterEach(async () => {
  await server.close();
  fs.rmSync(tmp, { recursive: true, force: true });
});

function config(extra = "") {
  const file = path.join(tmp, "watch.toml");
  fs.writeFileSync(file, `
gitea_url = "${server.url}"
repos = ["${REPO}"]
bots = ["${BOT}"]
state_dir = "${stateDir}"
protected_refs = ["main"]
${extra}
[ack]
enabled = true
reaction = "eyes"
on = ["comment_issue", "comment_pull"]
`);
  return file;
}

async function runCli(args, { token = TOKEN, extra = "", sleep } = {}) {
  const out = new Sink(), err = new Sink();
  const code = await main(["--config", config(extra), ...args], {
    env: { GITEA_TOKEN: token }, out, err, sleep, signals: false,
  });
  outText = out.text;
  errText = err.text;
  assert.ok(!outText.includes(TOKEN));
  assert.ok(!errText.includes(TOKEN));
  return code;
}

const records = () => outText.split("\n").filter(Boolean).map((l) => JSON.parse(l));
const lastId = () => JSON.parse(fs.readFileSync(stateFile, "utf8")).last_id;
const setState = (n) => {
  fs.mkdirSync(stateDir, { recursive: true });
  fs.writeFileSync(stateFile, JSON.stringify({ last_id: n }));
};

describe("feed", () => {
  test("bot events are suppressed", async () => {
    server.events = [
      event(1), event(2, { user: BOT, op: "create_issue", index: 5, body: "t" }),
      event(3, { user: BOT, op: "comment_pull", comment_id: 9, index: 5, body: "bot says" }),
      event(4, { user: BOT, op: "commit_repo", ref: "refs/heads/feature" }),
    ];
    setState(1);
    assert.equal(await runCli(["--once"]), 0);
    assert.equal(outText, "");
    assert.equal(lastId(), 4);
    assert.deepEqual(server.posts(), []);
  });

  test("human comments are reported and acked", async () => {
    server.events = [
      event(1),
      event(2, { op: "comment_issue", comment_id: 20, index: 7, body: "please look" }),
      event(3, { op: "comment_pull", comment_id: 30, index: 36, body: "x".repeat(300) }),
    ];
    setState(1);
    assert.equal(await runCli(["--once"]), 0);
    const [a, b] = records();
    assert.deepEqual([a.id, a.op, a.issue, a.comment_id, a.user], [2, "comment_issue", 7, 20, "y2wei"]);
    assert.equal(a.repo, REPO);
    assert.equal(a.created, "2026-10-26T18:02:11Z");
    assert.equal(a.url, `${server.url}/${REPO}/issues/7#issuecomment-20`);
    assert.equal(a.text, "please look");
    assert.equal(a.alert, false);
    assert.deepEqual(a.ack, { reaction: "eyes", status: 201 });
    assert.equal(b.url, `${server.url}/${REPO}/pulls/36#issuecomment-30`);
    assert.equal(b.text, "x".repeat(160) + "…");
    assert.deepEqual(server.posts(), [
      ["POST", `/api/v1/repos/${REPO}/issues/comments/20/reactions`],
      ["POST", `/api/v1/repos/${REPO}/issues/comments/30/reactions`],
    ]);
    assert.equal(lastId(), 3);
  });

  test("the comment object is preferred when present", async () => {
    const ev = event(2, { op: "comment_pull", index: 3, body: "from content" });
    ev.comment = { id: 55, body: "from comment", html_url: "http://x/y#issuecomment-55" };
    server.events = [event(1), ev];
    setState(1);
    await runCli(["--once"]);
    const [r] = records();
    assert.deepEqual([r.comment_id, r.text, r.url], [55, "from comment", "http://x/y#issuecomment-55"]);
  });

  test("restart gives no duplicate output or reaction", async () => {
    server.events = [event(1), event(2, { comment_id: 20, index: 7, body: "hi" })];
    setState(1);
    await runCli(["--once"]);
    assert.equal(records().length, 1);
    await runCli(["--once"]);
    assert.equal(outText, "");
    assert.equal(server.posts().length, 1);
  });

  test("reaction is idempotent through GET when state is lost", async () => {
    server.events = [event(1), event(2, { comment_id: 20, index: 7, body: "hi" })];
    setState(1);
    await runCli(["--once"]);
    setState(1); // crash before the state commit: the event is replayed
    await runCli(["--once"]);
    const [r] = records();
    assert.deepEqual(r.ack, { reaction: "eyes", status: "exists" });
    assert.equal(server.posts().length, 1);
  });

  test("more than one page between polls", async () => {
    server.events = [event(1)];
    for (let i = 2; i < 13; i++) server.events.push(event(i, { comment_id: 100 + i, index: 1, body: `c${i}` }));
    setState(1);
    assert.equal(await runCli(["--once", "--page-size", "3"]), 0);
    assert.deepEqual(records().map((r) => r.id), [2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
    assert.equal(lastId(), 12);
    assert.ok(server.requests.filter((r) => r[1].includes("activities/feeds")).length > 3);
  });

  test("server caps page size below the requested one", async () => {
    server.cap = 3;
    server.events = [event(1)];
    for (let i = 2; i < 10; i++) server.events.push(event(i, { comment_id: i, index: 1 }));
    setState(1);
    await runCli(["--once", "--page-size", "50"]);
    assert.deepEqual(records().map((r) => r.id), [2, 3, 4, 5, 6, 7, 8, 9]);
  });

  test("push to main by a bot alerts", async () => {
    server.events = [
      event(1), event(2, { user: BOT, op: "commit_repo", ref: "refs/heads/main" }),
      event(3, { user: "y2wei", op: "commit_repo", ref: "refs/heads/topic" }),
    ];
    setState(1);
    await runCli(["--once"]);
    const [a, b] = records();
    assert.equal(a.alert, true);
    assert.deepEqual([a.user, a.op, a.ref], [BOT, "commit_repo", "refs/heads/main"]);
    assert.equal(b.alert, false);
    assert.deepEqual(server.posts(), []);
  });
});

describe("startup", () => {
  test("invalid token exits non-zero with a message", async () => {
    server.events = [event(1)];
    setState(0);
    const bad = "bad-token-ZZZZ";
    assert.equal(await runCli(["--once"], { token: bad }), 1);
    assert.equal(outText, "");
    assert.match(errText, /token was rejected/);
    assert.match(errText, /401/);
    assert.ok(!errText.includes(bad));
  });

  test("missing token env", async () => {
    assert.equal(await runCli(["--once"], { token: "" }), 2);
    assert.match(errText, /GITEA_TOKEN/);
  });

  test("--once is silent without new events", async () => {
    server.events = [event(1), event(2, { comment_id: 5, index: 1 })];
    setState(2);
    assert.equal(await runCli(["--once"]), 0);
    assert.equal(outText, "");
    assert.equal(errText, "");
  });

  test("missing state behaves like --init", async () => {
    server.events = [event(1, { comment_id: 1, index: 1 }), event(2, { comment_id: 2, index: 1 })];
    assert.equal(await runCli(["--once"]), 0);
    assert.equal(outText, "");
    assert.equal(lastId(), 2);
    assert.deepEqual(server.posts(), []);
    server.events.push(event(3, { comment_id: 3, index: 1, body: "new" }));
    await runCli(["--once"]);
    assert.deepEqual(records().map((r) => r.id), [3]);
  });

  test("--init marks the latest event and overwrites state", async () => {
    server.events = [1, 2, 3, 4, 5].map((i) => event(i, { comment_id: i, index: 1 }));
    setState(1);
    assert.equal(await runCli(["--init"]), 0);
    assert.equal(outText, "");
    assert.equal(lastId(), 5);
    assert.deepEqual(server.posts(), []);
  });

  test("--init on an empty feed", async () => {
    assert.equal(await runCli(["--init"]), 0);
    assert.equal(lastId(), 0);
  });

  test("--dry-run writes no state and sends no reactions", async () => {
    server.events = [event(1), event(2, { comment_id: 20, index: 7, body: "hi" })];
    setState(1);
    await runCli(["--once", "--dry-run"]);
    const [r] = records();
    assert.deepEqual(r.ack, { reaction: "eyes", status: "dry-run" });
    assert.equal(lastId(), 1);
    assert.deepEqual(server.posts(), []);
    // dry-run on missing state and with --init also writes nothing
    fs.rmSync(stateFile);
    await runCli(["--once", "--dry-run"]);
    await runCli(["--init", "--dry-run"]);
    assert.ok(!fs.existsSync(stateFile));
  });

  test("interval minimum", async () => {
    assert.equal(await runCli(["--once", "--interval", "5"]), 2);
    assert.match(errText, /interval/);
  });

  test("flags override the config", async () => {
    server.events = [event(1), event(2, { user: BOT, comment_id: 3, index: 1 })];
    setState(1);
    await runCli(["--once", "--bots", "someone-else"]); // wiki-bot is now a human
    assert.equal(records().length, 1);
  });
});

describe("faults", () => {
  test("5xx on the feed skips the round and keeps state", async () => {
    server.events = [event(1), event(2, { comment_id: 2, index: 1 })];
    setState(1);
    server.feedStatus = 503;
    assert.equal(await runCli(["--once"]), 3);
    assert.equal(outText, "");
    assert.match(errText, /503/);
    assert.equal(lastId(), 1);
    server.feedStatus = null;
    assert.equal(await runCli(["--once"]), 0);
    assert.equal(records().length, 1);
  });

  test("network error skips the round", async () => {
    server.events = [event(1)];
    setState(1);
    const cfg = config();
    await server.close();
    const out = new Sink(), err = new Sink();
    const code = await main(["--config", cfg, "--once"], { env: { GITEA_TOKEN: TOKEN }, out, err, signals: false });
    assert.equal(code, 3);
    assert.equal(out.text, "");
    server.close = async () => {}; // cleanup closes it again
    server.server = { closeAllConnections() {}, close: (cb) => cb() };
  });

  test("a failed reaction does not block later events", async () => {
    server.events = [
      event(1), event(2, { comment_id: 20, index: 1, body: "a" }), event(3, { comment_id: 30, index: 1, body: "b" })];
    setState(1);
    server.postStatus = 500;
    assert.equal(await runCli(["--once"]), 0);
    const [a, b] = records();
    assert.deepEqual(a.ack, { reaction: "eyes", status: 500 });
    assert.deepEqual(b.ack, { reaction: "eyes", status: 500 });
    assert.equal(lastId(), 3);
  });

  test("backoff is exponential and capped", async () => {
    const delays = [];
    const sleep = async (d) => { delays.push(d); return delays.length >= 8; };
    setState(0);
    server.feedStatus = 500;
    await runCli(["--interval", "60"], { sleep });
    assert.deepEqual(delays, [60, 120, 240, 480, 600, 600, 600, 600]);
  });

  test("state write is atomic", () => {
    setState(1);
    const calls = [];
    const fsx = {
      ...fs,
      renameSync(src, dst) {
        calls.push([src, dst]);
        assert.deepEqual(JSON.parse(fs.readFileSync(dst, "utf8")), { last_id: 1 }); // old content intact until the rename
        assert.deepEqual(JSON.parse(fs.readFileSync(src, "utf8")), { last_id: 9 }); // new content complete before it
        fs.renameSync(src, dst);
      },
    };
    writeState(stateFile, 9, fsx);
    assert.equal(calls.length, 1);
    assert.equal(path.dirname(calls[0][0]), path.dirname(calls[0][1]));
    assert.equal(lastId(), 9);
    assert.deepEqual(fs.readdirSync(stateDir), ["y2wei__wirelesswiki.json"]);
  });

  test("a failed state write leaves the old state and no temp files", () => {
    setState(1);
    const fsx = { ...fs, renameSync() { throw new Error("disk"); } };
    assert.throws(() => writeState(stateFile, 9, fsx), /disk/);
    assert.equal(lastId(), 1);
    assert.deepEqual(fs.readdirSync(stateDir), ["y2wei__wirelesswiki.json"]);
  });
});

describe("signals", () => {
  test("SIGTERM finishes the round, writes state and exits 0", async () => {
    server.events = [event(1), event(2, { comment_id: 20, index: 7, body: "hi" })];
    setState(1);
    const proc = spawn(process.execPath, [SCRIPT, "--config", config()], {
      env: { ...process.env, GITEA_TOKEN: TOKEN }, stdio: ["ignore", "pipe", "pipe"],
    });
    let out = "", err = "";
    proc.stderr.on("data", (d) => { err += d; });
    const firstLine = new Promise((resolve) => proc.stdout.on("data", (d) => { out += d; if (out.includes("\n")) resolve(); }));
    const exited = new Promise((resolve) => proc.on("close", (code) => resolve(code)));
    try {
      await firstLine; // the first round has run; the process now sleeps
      assert.equal(JSON.parse(out.split("\n")[0]).id, 2);
      const deadline = Date.now() + 5000;
      while (lastId() !== 2 && Date.now() < deadline) await new Promise((r) => setTimeout(r, 50));
      proc.kill("SIGTERM");
      assert.equal(await exited, 0);
    } finally {
      if (proc.exitCode === null) proc.kill("SIGKILL");
    }
    assert.equal(out.split("\n").filter(Boolean).length, 1);
    assert.ok(!err.includes(TOKEN));
    assert.equal(lastId(), 2);
    assert.equal(server.posts().length, 1);
  });
});

describe("token leak", () => {
  test("the token never appears in output, even on errors", async () => {
    server.events = [event(1), event(2, { comment_id: 2, index: 1, body: "hi" })];
    setState(1);
    server.postStatus = 500;
    await runCli(["--once"]);
    server.feedStatus = 502;
    await runCli(["--once"]);
    await runCli(["--once"], { token: "wrong" });
    assert.match(errText, /401/); // runCli asserts the token is absent from stdout and stderr on every call
  });
});

describe("toml subset", () => {
  test("strings, integers, booleans, arrays, tables, comments", () => {
    const t = parseToml(`
# c
a = "x\\ty" # trailing
b = 'lit\\n'
n = 60
m = -3
f = false
arr = [
  "one", # c
  "two",
]
[ack]
enabled = true
on = ["comment_issue"]
`);
    assert.equal(t.a, "x\ty");
    assert.equal(t.b, "lit\\n");
    assert.deepEqual([t.n, t.m, t.f], [60, -3, false]);
    assert.deepEqual(t.arr, ["one", "two"]);
    assert.deepEqual({ ...t.ack }, { enabled: true, on: ["comment_issue"] });
    assert.throws(() => parseToml("a = 1\na = 2"), /duplicate/);
    assert.throws(() => parseToml("a = 1.5"), /unsupported/);
  });
});
