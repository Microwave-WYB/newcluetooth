# keyproxy

The only holder of the fleet's API keys. The research agent container has no keys; it calls
`http://keyproxy:8080` and keyproxy adds the key to **whitelisted endpoints only**. There is no
generic forwarding: anything not listed below is refused before it reaches an upstream.

Node built-ins only (no framework, no dependencies). `npm test` runs the tests (`node --test`).

## Endpoints

| Service | Allowed | Notes |
|---|---|---|
| WiGLE | `GET /wigle/api/v2/bluetooth/search?…` | Parameters: `name`, `namelike` (passed through unchanged, `%` wildcards included), `searchAfter`, `resultsPerPage`, `latrange1/2`, `longrange1/2`, `closestLat`, `closestLong`, `lastupdt`, `country`, `region`, `city`, `postalCode`. Any other parameter, a repeated one, or any other WiGLE endpoint is refused. |
| WiGLE | `GET /wigle/budget` | Today's usage; not a WiGLE call. |
| AndroZoo | `GET /androzoo/apk/<sha256>` | Streams the APK. One download at a time, `ANDROZOO_MIN_INTERVAL_SECONDS` between starts, `ANDROZOO_DAILY_LIMIT` per day. |
| AndroZoo | `GET /androzoo/gp-metadata/<package>` | Only with `ANDROZOO_GP_METADATA=1`, i.e. when the key is known to have gp-metadata access; otherwise 403. |
| Gitea API | `GET /gitea/api/v1/user`, and below `/gitea/api/v1/repos/{owner}/{repo}` for allowed repos: `GET` repo, `issues`, `issues/{n}`, `issues/{n}/comments`, `issues/{n}/timeline`, `issues/comments[/{id}]`, `labels`, `pulls`, `pulls/{n}`, `pulls/{n}.diff/.patch`, `pulls/{n}/files`, `pulls/{n}/commits`, `pulls/{n}/reviews[/{id}/comments]`, `contents[/…]`, `raw/…`, `media/…`, `branches[/…]`, `commits`, `commits/{ref}/status(es)`, `activities/feeds` | Read issues, PRs and files. |
| Gitea API | `POST issues` (`title`, `body`, `labels`), `POST issues/{n}/comments` (`body`), `POST pulls` (`title`, `body`, `head` = `task/*`, `base`) | Create an issue, comment on an issue or PR, open a PR. Unknown body fields are refused. |
| git | `GET /gitea/{owner}/{repo}.git/info/refs?service=git-upload-pack\|git-receive-pack`, `POST …/git-upload-pack`, `POST …/git-receive-pack` | Smart HTTP passthrough. A push is refused unless **every** ref it updates is `refs/heads/task/*`; deletes, push options and signed pushes are refused too. |

Not routed, so always refused: merging, reviews and approvals, editing or closing issues and PRs,
reactions, file writes through the API, branch deletion, branch protection, repository settings,
hooks, collaborators, keys, `/admin`, user settings, other repositories, Gitea's web UI.
`token`, `access_token` and `sudo` query parameters are dropped; caller `Authorization` headers
are never forwarded.

## X-Caller is not authorisation

Callers send `X-Caller: <agent or task label>` (`[A-Za-z0-9._:@/+-]`, up to 64 characters). It
is used **only** for the request log and the optional per-caller WiGLE budget
(`WIGLE_PER_CALLER_DAILY`). Any client on the network can claim any label, so nothing is ever
allowed or denied because of who the header says is calling. A missing header is logged as
`unknown`, a malformed one as `invalid`.

## Budgets

- **WiGLE.** `WIGLE_DAILY_BUDGET` calls per WiGLE day (Pacific time, rolling over at 00:05),
  of which `WIGLE_RESERVE` (default 10) are never used: the proxy stops with that many calls
  left. Every call that reaches WiGLE counts, whatever its outcome. A 429 or a "too many
  queries" answer stops all calls until the next day. Counters live in
  `/var/lib/keyproxy/counters.json`, so a restart does not reset them.
- **AndroZoo.** One download at a time, a minimum interval between starts and a daily cap.

## Logging and secrets

One JSON line per request on stdout (and in `LOG_FILE`, `/var/lib/keyproxy/requests.log` in
compose): `ts`, `caller`, `method`, `service`, `endpoint` (or `rejected` plus the path),
`params`, `status`, `bytes_out`, `ms`, `error`. `/healthz` is not logged.

Secrets are read once at start from files under `SECRETS_DIR` (`/run/secrets`): `gitea-token`,
`wigle-api-key` (WiGLE's "Encoded for use" value), `androzoo-api-key`. An empty or missing file
disables that service (503). Restart keyproxy after rotating a key. Every log line, every
relayed error and every buffered upstream body is passed through a scrubber that replaces each
secret, in raw, URL-encoded and base64 form (and the decoded WiGLE `name:token` pair), with
`<redacted>`. Secret values are never written anywhere else.

## Configuration (environment, no secrets)

| Variable | Default |
|---|---|
| `GITEA_URL`, `GITEA_USER`, `GITEA_REPOS` | `http://gitea:3000`, `wiki-bot`, `y2wei/wirelesswiki` (space- or comma-separated) |
| `WIGLE_DAILY_BUDGET`, `WIGLE_RESERVE`, `WIGLE_PER_CALLER_DAILY` | `100`, `10`, `0` (off) |
| `ANDROZOO_MIN_INTERVAL_SECONDS`, `ANDROZOO_DAILY_LIMIT`, `ANDROZOO_GP_METADATA` | `10`, `200`, unset (off) |
| `WIGLE_URL`, `ANDROZOO_URL` | the public APIs (tests point them at fakes) |
| `STATE_FILE`, `LOG_FILE`, `SECRETS_DIR`, `PORT` | `/var/lib/keyproxy/counters.json`, unset, `/run/secrets`, `8080` |

Every upstream request carries an honest `User-Agent: cluetooth-keyproxy/1 (…)`; git requests
keep the git client's own.
