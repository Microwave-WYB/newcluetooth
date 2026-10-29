import assert from "node:assert/strict";
import { after, before, beforeEach, describe, test } from "node:test";
import { OID_A, SECRETS, ZERO, fakeUpstream, pushBody, startProxy } from "./helpers.mjs";

let up;
let proxy;
before(async () => {
  up = await fakeUpstream((_req, _body, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end('{"ok":true}');
  });
  proxy = await startProxy({ upstream: up.url });
});
after(async () => {
  await proxy.close();
  await up.close();
});
beforeEach(() => {
  up.requests.length = 0;
});

const R = "/gitea/api/v1/repos/y2wei/wirelesswiki";
const post = (path, body, headers = {}) =>
  fetch(proxy.url + path, { method: "POST", headers: { "content-type": "application/json", ...headers }, body: JSON.stringify(body) });

describe("gitea whitelist: rejected", () => {
  const rejected = [
    ["POST", `${R}/pulls/3/merge`, {}],
    ["PATCH", `${R}/pulls/3`, { state: "closed" }],
    ["PATCH", `${R}/issues/3`, { state: "closed" }],
    ["DELETE", `${R}/issues/comments/9`],
    ["POST", `${R}/pulls/3/reviews`, { event: "APPROVED" }],
    ["GET", `${R}/branch_protections`],
    ["POST", `${R}/branch_protections`, { branch_name: "main" }],
    ["GET", `${R}/hooks`],
    ["GET", `${R}/collaborators`],
    ["PATCH", R, { private: false }],
    ["DELETE", R],
    ["POST", `${R}/contents/README.md`, { content: "x" }],
    ["PUT", `${R}/contents/README.md`, { content: "x" }],
    ["DELETE", `${R}/branches/task/1`],
    ["POST", `${R}/issues/3/reactions`, { content: "eyes" }],
    ["GET", "/gitea/api/v1/admin/users"],
    ["GET", "/gitea/api/v1/user/settings"],
    ["GET", "/gitea/api/v1/user/repos"],
    ["POST", "/gitea/api/v1/user/repos", { name: "x" }],
    ["GET", "/gitea/api/v1/repos/y2wei/other/issues"],
    ["GET", "/gitea/api/v1/repos/someone/wirelesswiki/issues"],
    ["GET", "/gitea/api/v2/repos/y2wei/wirelesswiki/issues"],
    ["GET", "/gitea/api/v1/repos/y2wei/wirelesswiki/issues/../../../admin/users"],
    ["GET", "/gitea/api/v1/repos/y2wei/wirelesswiki/contents/a%2F..%2F..%2Fadmin"],
    ["GET", "/gitea/api/v1/repos/y2wei/wirelesswiki/raw/%2e%2e/secret"],
    ["GET", "/gitea/y2wei/wirelesswiki"],
    ["GET", "/gitea/y2wei/wirelesswiki.git/HEAD"],
    ["GET", "/gitea/y2wei/wirelesswiki.git/info/refs"],
    ["GET", "/gitea/y2wei/other.git/info/refs?service=git-upload-pack"],
    ["GET", "/gitea/user/login"],
    ["GET", "/gitea"],
    ["GET", "/somewhere-else"],
    ["GET", "/http://example.com/"],
  ];
  for (const [method, path, body] of rejected) {
    test(`${method} ${path}`, async () => {
      const r = await fetch(proxy.url + path, {
        method,
        headers: body ? { "content-type": "application/json" } : {},
        body: body ? JSON.stringify(body) : undefined,
      });
      assert.ok(r.status >= 400 && r.status < 500, `status ${r.status}`);
      assert.match((await r.json()).error, /.+/);
      assert.equal(up.requests.length, 0, "nothing reached Gitea");
    });
  }
});

describe("gitea whitelist: allowed", () => {
  test("reads go upstream with the token added; caller headers are not forwarded", async () => {
    const r = await fetch(`${proxy.url}${R}/issues?state=open&token=stolen&sudo=admin`, {
      headers: { authorization: "token caller-supplied", "x-caller": "root" },
    });
    assert.equal(r.status, 200);
    assert.equal(up.requests.length, 1);
    const q = up.requests[0];
    assert.equal(q.url, "/api/v1/repos/y2wei/wirelesswiki/issues?state=open");
    assert.equal(q.headers.authorization, `token ${SECRETS.gitea}`);
    assert.equal(q.headers["x-caller"], undefined);
  });

  for (const path of [
    "/gitea/api/v1/user",
    R,
    `${R}/issues/12`,
    `${R}/issues/12/comments`,
    `${R}/issues/comments`,
    `${R}/pulls`,
    `${R}/pulls/4`,
    `${R}/pulls/4.diff`,
    `${R}/pulls/4/files`,
    `${R}/contents/wiki-content/devices/a.md?ref=main`,
    `${R}/raw/wiki-site/package.json`,
    `${R}/branches/task/12`,
    `${R}/commits/main/status`,
    `${R}/activities/feeds?limit=10`,
  ]) {
    test(`GET ${path}`, async () => {
      const r = await fetch(proxy.url + path);
      assert.equal(r.status, 200);
      assert.equal(up.requests.length, 1);
    });
  }

  test("comment, create issue, open PR from task/*", async () => {
    assert.equal((await post(`${R}/issues/12/comments`, { body: "hi" })).status, 200);
    assert.equal((await post(`${R}/issues`, { title: "t", body: "b", labels: [1] })).status, 200);
    assert.equal((await post(`${R}/pulls`, { title: "t", head: "task/12", base: "main" })).status, 200);
    assert.equal(up.requests.length, 3);
    assert.deepEqual(JSON.parse(up.requests[2].body), { title: "t", head: "task/12", base: "main" });
  });

  test("bodies with fields outside the whitelist are refused", async () => {
    assert.equal((await post(`${R}/issues/12/comments`, { body: "hi", sudo: "x" })).status, 400);
    assert.equal((await post(`${R}/issues`, { title: "t", assignees: ["y2wei"] })).status, 400);
    assert.equal((await post(`${R}/issues`, { title: "t", closed: true })).status, 400);
    assert.equal((await post(`${R}/pulls`, { title: "t", head: "main", base: "release" })).status, 400);
    assert.equal((await post(`${R}/pulls`, { title: "t", head: "feature/x", base: "main" })).status, 400);
    assert.equal((await post(`${R}/pulls`, { title: "t", head: "task/../main", base: "main" })).status, 400);
    assert.equal(up.requests.length, 0);
  });
});

describe("git smart HTTP", () => {
  const push = (body) =>
    fetch(`${proxy.url}/gitea/y2wei/wirelesswiki.git/git-receive-pack`, {
      method: "POST",
      headers: { "content-type": "application/x-git-receive-pack-request" },
      body,
    });

  test("info/refs and upload-pack pass through with basic auth", async () => {
    const a = await fetch(`${proxy.url}/gitea/y2wei/wirelesswiki.git/info/refs?service=git-upload-pack`);
    const b = await fetch(`${proxy.url}/gitea/y2wei/wirelesswiki/info/refs?service=git-receive-pack`);
    const c = await fetch(`${proxy.url}/gitea/y2wei/wirelesswiki.git/git-upload-pack`, { method: "POST", body: "0000" });
    assert.deepEqual([a.status, b.status, c.status], [200, 200, 200]);
    assert.deepEqual(
      up.requests.map((q) => q.url),
      [
        "/y2wei/wirelesswiki.git/info/refs?service=git-upload-pack",
        "/y2wei/wirelesswiki.git/info/refs?service=git-receive-pack",
        "/y2wei/wirelesswiki.git/git-upload-pack",
      ],
    );
    const basic = `Basic ${Buffer.from(`wiki-bot:${SECRETS.gitea}`).toString("base64")}`;
    assert.ok(up.requests.every((q) => q.headers.authorization === basic));
  });

  test("push to task/* is forwarded byte for byte", async () => {
    const body = pushBody([{ ref: "refs/heads/task/12" }, { ref: "refs/heads/task/12-b/x" }], Buffer.alloc(200_000, 7));
    const r = await push(body);
    assert.equal(r.status, 200);
    assert.equal(up.requests.length, 1);
    assert.ok(up.requests[0].body.equals(body));
  });

  for (const [name, commands] of [
    ["main", [{ ref: "refs/heads/main" }]],
    ["a non-task branch", [{ ref: "refs/heads/feature/x" }]],
    ["task/ prefix trick", [{ ref: "refs/heads/task-12" }]],
    ["a tag", [{ ref: "refs/tags/task/1" }]],
    ["refs/for (AGit)", [{ ref: "refs/for/main/topic" }]],
    ["one bad ref among good ones", [{ ref: "refs/heads/task/1" }, { ref: "refs/heads/main" }]],
    ["a delete of a task branch", [{ ref: "refs/heads/task/1", old: OID_A, new: ZERO }]],
    ["dot-dot in the name", [{ ref: "refs/heads/task/../main" }]],
    ["a .lock suffix", [{ ref: "refs/heads/task/x.lock" }]],
  ]) {
    test(`push refused: ${name}`, async () => {
      const r = await push(pushBody(commands));
      // git's report-status in side-band 1, so `git push` prints "! [remote rejected] <ref> (<reason>)".
      assert.equal(r.status, 200);
      assert.equal(r.headers.get("content-type"), "application/x-git-receive-pack-result");
      const body = Buffer.from(await r.arrayBuffer()).toString("latin1");
      assert.match(body, /unpack ok\n/);
      for (const c of commands) assert.match(body, new RegExp(`ng ${c.ref.replaceAll(".", "\\.")} keyproxy: `));
      assert.match(body, /0000$/);
      assert.equal(up.requests.length, 0, "nothing reached Gitea");
    });
  }

  test("push refused without report-status: plain 403", async () => {
    const r = await push(pushBody([{ ref: "refs/heads/main", caps: "ofs-delta" }]));
    assert.equal(r.status, 403);
    assert.equal(up.requests.length, 0);
  });

  test("push refused: push options", async () => {
    const r = await push(pushBody([{ ref: "refs/heads/task/1", caps: "report-status push-options" }]));
    assert.equal(r.status, 403);
    assert.equal(up.requests.length, 0);
  });

  test("push refused: signed push certificate", async () => {
    const r = await push(Buffer.from("0010push-cert\0\n0000"));
    assert.equal(r.status, 403);
    assert.equal(up.requests.length, 0);
  });

  test("push refused: compressed body", async () => {
    const r = await fetch(`${proxy.url}/gitea/y2wei/wirelesswiki.git/git-receive-pack`, {
      method: "POST",
      headers: { "content-encoding": "gzip" },
      body: pushBody([{ ref: "refs/heads/task/1" }]),
    });
    assert.equal(r.status, 415);
    assert.equal(up.requests.length, 0);
  });

  test("the log records the pushed refs", async () => {
    const before = proxy.lines.length;
    await push(pushBody([{ ref: "refs/heads/main" }]));
    for (let i = 0; i < 50 && proxy.lines.length === before; i++) await new Promise((r) => setTimeout(r, 10));
    const entry = JSON.parse(proxy.lines.at(-1));
    assert.equal(entry.endpoint, "git.receive-pack");
    assert.deepEqual(entry.params.refs, ["refs/heads/main"]);
    assert.equal(entry.params.refused, true);
    assert.match(entry.error, /only refs\/heads\/task\/\* may be pushed/);
  });
});
