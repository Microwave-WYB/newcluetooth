// Gitea: the proxy adds the bot token and allows only
//   - reading issues, PRs, files, branches, commit statuses and the activity feed;
//   - commenting on an issue or PR, creating an issue, opening a PR from a task/* branch;
//   - git smart HTTP: fetch, and push to refs/heads/task/* only.
// Merging, reviews, edits, deletes, branch protection, settings, hooks, collaborators and every
// admin or user-settings endpoint are not routed at all.
import { Readable } from "node:stream";
import { Reject, encodePath, pathSegments, readBody } from "./http.mjs";

const N = /^[1-9][0-9]{0,9}$/;
const PULL_DIFF = /^[1-9][0-9]{0,9}\.(diff|patch)$/;
const REF = /^[A-Za-z0-9._-]{1,200}$/;
const REST = Symbol("rest");
const TASK_BRANCH = /^task\/[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/;
const BRANCH = /^[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/;
export const TASK_REF = /^refs\/heads\/task\/[A-Za-z0-9._-]+(\/[A-Za-z0-9._-]+)*$/;

// [method, pattern below /repos/{owner}/{repo}, endpoint name, body spec for POST]
const REPO_ROUTES = [
  ["GET", [], "repo"],
  ["GET", ["issues"], "issues.list"],
  ["GET", ["issues", N], "issues.get"],
  ["GET", ["issues", N, "comments"], "issues.comments"],
  ["GET", ["issues", N, "timeline"], "issues.timeline"],
  ["GET", ["issues", "comments"], "issues.comments.repo"],
  ["GET", ["issues", "comments", N], "issues.comment"],
  ["GET", ["labels"], "labels"],
  ["GET", ["pulls"], "pulls.list"],
  ["GET", ["pulls", N], "pulls.get"],
  ["GET", ["pulls", PULL_DIFF], "pulls.diff"],
  ["GET", ["pulls", N, "files"], "pulls.files"],
  ["GET", ["pulls", N, "commits"], "pulls.commits"],
  ["GET", ["pulls", N, "reviews"], "pulls.reviews"],
  ["GET", ["pulls", N, "reviews", N, "comments"], "pulls.review.comments"],
  ["GET", ["contents"], "contents"],
  ["GET", ["contents", REST], "contents"],
  ["GET", ["raw", REST], "raw"],
  ["GET", ["media", REST], "media"],
  ["GET", ["branches"], "branches"],
  ["GET", ["branches", REST], "branch"],
  ["GET", ["commits"], "commits"],
  ["GET", ["commits", REF, "status"], "commits.status"],
  ["GET", ["commits", REF, "statuses"], "commits.statuses"],
  ["GET", ["activities", "feeds"], "activities.feeds"],
  ["POST", ["issues"], "issues.create", { title: "text!", body: "text", labels: "ids" }],
  ["POST", ["issues", N, "comments"], "issues.comment.create", { body: "text!" }],
  ["POST", ["pulls"], "pulls.create", { title: "text!", body: "text", head: "task-branch!", base: "branch!" }],
];

// Query parameters that could change whose authority a request runs with.
const DROPPED_QUERY = new Set(["token", "access_token", "sudo"]);

export async function handleGitea(req, res, ctx, deps) {
  const url = new URL(req.url, "http://keyproxy");
  ctx.service = "gitea";
  const seg = pathSegments(url.pathname, "/gitea");
  if (!seg || seg.length === 0) throw new Reject(403, "not an allowed Gitea endpoint");
  if (!deps.secrets.gitea) throw new Reject(503, "Gitea is not configured (no gitea-token secret)");
  if (seg[0] === "api") return api(req, res, ctx, deps, seg, url);
  return git(req, res, ctx, deps, seg, url);
}

function match(pattern, segs) {
  const out = [];
  for (let i = 0; i < pattern.length; i++) {
    const p = pattern[i];
    if (p === REST) {
      if (i >= segs.length) return null;
      out.push(...segs.slice(i));
      return out;
    }
    const s = segs[i];
    if (s === undefined) return null;
    if (typeof p === "string" ? s !== p : !p.test(s)) return null;
    out.push(s);
  }
  return segs.length === pattern.length ? out : null;
}

function checkRepo(owner, repo, deps) {
  const full = `${owner}/${repo}`.toLowerCase();
  if (!deps.config.gitea.repos.includes(full)) throw new Reject(403, `repository not allowed: ${owner}/${repo}`);
}

async function api(req, res, ctx, deps, seg, url) {
  if (seg[1] !== "v1") throw new Reject(403, "not an allowed Gitea endpoint");
  const rest = seg.slice(2);
  let route;
  if (rest.length === 1 && rest[0] === "user") {
    route = ["GET", ["user"], "user"];
  } else if (rest[0] === "repos" && rest.length >= 3) {
    route = REPO_ROUTES.find(([m, p]) => m === req.method && match(p, rest.slice(3)));
    if (route) checkRepo(rest[1], rest[2], deps);
  }
  if (!route || route[0] !== req.method) throw new Reject(403, `not an allowed Gitea endpoint: ${req.method} ${url.pathname}`);
  const [, , name, bodySpec] = route;
  ctx.endpoint = `gitea.${name}`;

  const query = new URLSearchParams();
  for (const [k, v] of url.searchParams) if (!DROPPED_QUERY.has(k.toLowerCase())) query.append(k, v);
  ctx.params = { path: `/${rest.join("/")}`, ...Object.fromEntries(query) };

  let body;
  if (req.method === "POST") {
    let parsed;
    try {
      parsed = JSON.parse((await readBody(req, 1 << 20)).toString("utf8"));
    } catch (e) {
      if (e instanceof Reject) throw e;
      throw new Reject(400, "body must be JSON");
    }
    body = JSON.stringify(checkBody(parsed, bodySpec));
    if (name === "pulls.create") Object.assign(ctx.params, { head: parsed.head, base: parsed.base });
  }

  const qs = query.size ? `?${query}` : "";
  let up;
  try {
    up = await fetch(`${deps.config.gitea.url}/api/v1/${encodePath(rest)}${qs}`, {
      method: req.method,
      headers: {
        Authorization: `token ${deps.secrets.gitea}`,
        Accept: req.headers.accept ?? "application/json",
        "Accept-Encoding": "identity",
        "User-Agent": deps.config.userAgent,
        ...(body ? { "Content-Type": "application/json" } : {}),
      },
      body,
      redirect: "manual",
      signal: AbortSignal.timeout(120_000),
    });
  } catch (e) {
    throw new Reject(502, `Gitea unreachable: ${e.cause?.code ?? e.name}`);
  }
  // API answers are small JSON: buffer and scrub them. Raw and media files stream unchanged.
  await relay(up, res, ctx, deps, { buffer: name !== "raw" && name !== "media" });
}

function checkBody(body, spec) {
  if (!body || typeof body !== "object" || Array.isArray(body)) throw new Reject(400, "body must be a JSON object");
  for (const k of Object.keys(body)) if (!Object.hasOwn(spec, k)) throw new Reject(400, `field not allowed: ${k}`);
  const out = {};
  for (const [k, kind] of Object.entries(spec)) {
    const required = kind.endsWith("!");
    const type = kind.replace(/!$/, "");
    const v = body[k];
    if (v === undefined || v === null) {
      if (required) throw new Reject(400, `field required: ${k}`);
      continue;
    }
    const ok =
      (type === "text" && typeof v === "string" && v.length <= 65536 && (!required || v.trim() !== "")) ||
      (type === "ids" && Array.isArray(v) && v.length <= 50 && v.every((x) => Number.isInteger(x) && x > 0)) ||
      (type === "task-branch" && typeof v === "string" && TASK_BRANCH.test(v) && safeRefName(v)) ||
      (type === "branch" && typeof v === "string" && v.length <= 200 && BRANCH.test(v) && safeRefName(v));
    if (!ok) {
      throw new Reject(400, type === "task-branch" ? `${k} must be a task/* branch` : `field ${k} is not a valid ${type}`);
    }
    out[k] = v;
  }
  return out;
}

const safeRefName = (r) => !r.includes("..") && !r.endsWith(".lock") && !r.split("/").some((p) => p.startsWith("."));

// ---------------------------------------------------------------- git smart HTTP

async function git(req, res, ctx, deps, seg, url) {
  if (seg.length < 3) throw new Reject(403, "not an allowed Gitea endpoint");
  const [owner, repoSeg, ...rest] = seg;
  const repo = repoSeg.replace(/\.git$/, "");
  checkRepo(owner, repo, deps);
  const op = rest.join("/");
  ctx.params = { repo: `${owner}/${repo}` };

  let query = "";
  let body;
  const headers = {
    Authorization: `Basic ${Buffer.from(`${deps.config.gitea.user}:${deps.secrets.gitea}`).toString("base64")}`,
    "Accept-Encoding": "identity",
  };
  for (const h of ["content-type", "accept", "git-protocol", "user-agent"]) {
    if (req.headers[h]) headers[h] = req.headers[h];
  }

  if (req.method === "GET" && op === "info/refs") {
    const service = url.searchParams.get("service");
    if (![...url.searchParams.keys()].every((k) => k === "service") || !["git-upload-pack", "git-receive-pack"].includes(service)) {
      throw new Reject(403, "info/refs needs service=git-upload-pack or git-receive-pack (dumb HTTP is not supported)");
    }
    ctx.endpoint = `git.info-refs.${service}`;
    query = `?service=${service}`;
  } else if (req.method === "POST" && op === "git-upload-pack" && url.search === "") {
    ctx.endpoint = "git.upload-pack";
    if (req.headers["content-encoding"]) headers["content-encoding"] = req.headers["content-encoding"];
    if (req.headers["content-length"]) headers["content-length"] = req.headers["content-length"];
    body = req;
  } else if (req.method === "POST" && op === "git-receive-pack" && url.search === "") {
    ctx.endpoint = "git.receive-pack";
    if (req.headers["content-encoding"] && req.headers["content-encoding"] !== "identity") {
      throw new Reject(415, "compressed push requests are not supported");
    }
    const { head, commands, iterator } = await readCommands(req);
    ctx.params.refs = commands.map((c) => c.ref);
    checkCommands(commands);
    if (req.headers["content-length"]) headers["content-length"] = req.headers["content-length"];
    // The same bytes go upstream: the command list already read, then the rest of the pack.
    body = Readable.from(
      (async function* () {
        yield head;
        for (;;) {
          const { value, done } = await iterator.next();
          if (done) return;
          yield value;
        }
      })(),
    );
  } else {
    throw new Reject(403, `not an allowed git operation: ${req.method} ${op}`);
  }

  let up;
  try {
    up = await fetch(`${deps.config.gitea.url}/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}.git/${op}${query}`, {
      method: req.method,
      headers,
      body,
      duplex: body ? "half" : undefined,
      redirect: "manual",
    });
  } catch (e) {
    throw new Reject(502, `Gitea unreachable: ${e.cause?.code ?? e.name}`);
  }
  await relay(up, res, ctx, deps, { buffer: false });
}

const PKT_COMMAND = /^([0-9a-f]{40}|[0-9a-f]{64}) ([0-9a-f]{40}|[0-9a-f]{64}) (\S+)$/;

/**
 * Parses the receive-pack command list (pkt-lines up to the first flush-pkt) from the start of
 * the push body. Returns the bytes read so far, the commands, and the iterator for the rest.
 */
export async function readCommands(stream, limit = 1 << 20) {
  const iterator = stream[Symbol.asyncIterator]();
  let head = Buffer.alloc(0);
  for (;;) {
    const parsed = parseCommandList(head);
    if (parsed) return { head, commands: parsed, iterator };
    const { value, done } = await iterator.next();
    if (done) throw new Reject(400, "push request ended inside the command list");
    head = Buffer.concat([head, value]);
    if (head.length > limit) throw new Reject(413, "push command list too large");
  }
}

/** Returns the commands once a flush-pkt has been read, null if more bytes are needed. */
export function parseCommandList(buf) {
  const commands = [];
  let pos = 0;
  for (;;) {
    if (buf.length < pos + 4) return null;
    const hex = buf.toString("latin1", pos, pos + 4);
    if (!/^[0-9a-f]{4}$/.test(hex)) throw new Reject(400, "malformed pkt-line");
    const len = Number.parseInt(hex, 16);
    if (len === 0) return commands;
    if (len < 5) throw new Reject(400, "unexpected special pkt-line in the command list");
    if (buf.length < pos + len) return null;
    let line = buf.toString("latin1", pos + 4, pos + len);
    pos += len;
    let caps = "";
    const nul = line.indexOf("\0");
    if (nul >= 0) {
      caps = line.slice(nul + 1);
      line = line.slice(0, nul);
    }
    line = line.replace(/\n$/, "");
    if (caps.split(/\s+/).includes("push-options")) throw new Reject(403, "push options are not allowed");
    if (line.startsWith("shallow ")) continue;
    if (line.startsWith("push-cert")) throw new Reject(403, "signed pushes are not supported");
    const m = PKT_COMMAND.exec(line);
    if (!m) throw new Reject(400, "malformed push command");
    commands.push({ old: m[1], new: m[2], ref: m[3] });
  }
}

export function checkCommands(commands) {
  if (commands.length === 0) throw new Reject(400, "push has no ref updates");
  for (const c of commands) {
    if (!TASK_REF.test(c.ref) || !safeRefName(c.ref.slice("refs/heads/".length))) {
      throw new Reject(403, `push to ${c.ref} refused: only refs/heads/task/* may be pushed`);
    }
    if (/^0+$/.test(c.new)) throw new Reject(403, `deleting ${c.ref} is not allowed`);
  }
}

// ---------------------------------------------------------------- response relay

const RELAYED_HEADERS = ["content-type", "content-length", "content-disposition", "cache-control", "etag", "last-modified", "x-total-count", "link"];

async function relay(up, res, ctx, deps, { buffer }) {
  const headers = {};
  for (const h of RELAYED_HEADERS) {
    const v = up.headers.get(h);
    if (v !== null) headers[h] = v;
  }
  if (buffer || up.status >= 300) {
    const text = deps.scrubber.scrub(await up.text());
    headers["content-length"] = String(Buffer.byteLength(text));
    res.writeHead(up.status, headers);
    res.end(text);
    ctx.bytesOut = Buffer.byteLength(text);
    return;
  }
  res.writeHead(up.status, headers);
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

