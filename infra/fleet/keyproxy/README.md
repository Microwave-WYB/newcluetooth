# keyproxy

keyproxy has one job: keep API keys out of the research agent container. The agent calls
`http://keyproxy:8080/<service>/...`; keyproxy forwards **every path and method** to that
service with the service's credential added. It does not decide which operations are allowed
(what agents may do is written in `../policy/POLICY.md`).

Node built-ins only (no framework, no dependencies). `npm test` runs the tests (`node --test`).

## Services

Each service is one entry in `src/config.mjs`: a prefix, the upstream base URL, how the
credential is injected, the secret file it is read from, and an optional rate limit with its
own switch. Adding a service is adding an entry.

| Prefix | Upstream | Credential | Secret file | Rate limit |
|---|---|---|---|---|
| `/gitea/` | `GITEA_URL` (`http://gitea:3000`) | `Authorization: Basic base64(GITEA_USER:token)` (API, git smart HTTP and web alike; `token`/`access_token` query parameters from the caller are dropped) | `gitea-token` | none |
| `/wigle/` | `WIGLE_URL` (`https://api.wigle.net`) | `Authorization: Basic <key>` (the "Encoded for use" value) | `wigle-api-key` | daily budget |
| `/androzoo/` | `ANDROZOO_URL` (`https://androzoo.uni.lu`) | `apikey` query parameter, or form field for a form POST (the caller's own `apikey` is replaced) | `androzoo-api-key` | one at a time |

Examples: `GET /wigle/api/v2/bluetooth/search?namelike=DT-%25`,
`GET /androzoo/api/download?sha256=<sha256>`, `git clone http://keyproxy:8080/gitea/y2wei/wirelesswiki.git`.
An empty or missing secret file disables that service (503). An unknown prefix is a 404.

## Rate limits (account protection, can be turned off)

They protect the accounts from bans; they never restrict which calls are made.

- **`WIGLE_DAILY_BUDGET_ENABLED`** (default `true`). Every WiGLE call counts against
  `WIGLE_DAILY_BUDGET` per WiGLE day (Pacific time, rolling over at 00:05), and calls stop
  `WIGLE_RESERVE` (default 10) short of it. A 429 or a "too many queries" JSON answer stops
  calls for the rest of the day. Counters live in `/var/lib/keyproxy/counters.json`, so a
  restart does not reset them. Set it to `false` to turn the budget off.
- **`ANDROZOO_RATE_LIMIT_ENABLED`** (default `true`). One AndroZoo request at a time and at
  least `ANDROZOO_MIN_INTERVAL_SECONDS` (default 10) between starts; otherwise 429 with
  `Retry-After`. Set it to `false` to turn it off.

`GET /limits` shows both; `GET /healthz` says which services have a key (never the key).

## What reaches the upstream and what comes back

- Upstream request headers are built from scratch: `Accept`, `Accept-Language`,
  `Content-Type`, `Content-Length`, `Content-Encoding`, `Git-Protocol`, `If-None-Match`,
  `If-Modified-Since` and `Range` are copied, plus keyproxy's honest `User-Agent` and the
  credential. Anything else the caller sends (`Authorization`, cookies, key headers,
  `X-Caller`) never reaches the upstream, so the proxy's credential is the one used. Bodies
  stream through unchanged (git pushes byte for byte).
- Redirects are not followed, and their `Location` is not relayed. Response headers are
  relayed from a fixed list (content and caching headers, `Link`, `X-Total-Count`,
  `Retry-After`); `Set-Cookie` is not, since a session cookie is a credential.
- Error answers, redirects and bodies a limiter reads (WiGLE JSON) are buffered and scrubbed
  of every secret before they are relayed; everything else streams.

## X-Caller is not authorisation

Callers send `X-Caller: <agent or task label>` (`[A-Za-z0-9._:@/+-]`, up to 64 characters).
It labels the request log and nothing else. A missing header is logged as `unknown`, a
malformed one as `invalid`.

## Logging and secrets

One JSON line per request on stdout (and in `LOG_FILE`, `/var/lib/keyproxy/requests.log` in
compose): `ts`, `caller`, `method`, `service`, `path`, `params`, `status`, `bytes_out`, `ms`,
`error`. `/healthz` and `/limits` are not logged.

Secrets are read once at start from files under `SECRETS_DIR` (`/run/secrets`); restart
keyproxy after rotating a key. Every log line and every buffered body passes through a
scrubber that replaces each secret and each injected credential, in raw, URL-encoded and
base64 form (and the decoded WiGLE `name:token` pair), with `<redacted>`.

## Configuration (environment, no secrets)

| Variable | Default |
|---|---|
| `GITEA_URL`, `GITEA_USER` | `http://gitea:3000`, `wiki-bot` |
| `WIGLE_DAILY_BUDGET_ENABLED`, `WIGLE_DAILY_BUDGET`, `WIGLE_RESERVE` | `true`, `100`, `10` |
| `ANDROZOO_RATE_LIMIT_ENABLED`, `ANDROZOO_MIN_INTERVAL_SECONDS` | `true`, `10` |
| `WIGLE_URL`, `ANDROZOO_URL` | the public APIs (tests point them at fakes) |
| `STATE_FILE`, `LOG_FILE`, `SECRETS_DIR`, `PORT` | `/var/lib/keyproxy/counters.json`, unset, `/run/secrets`, `8080` |
