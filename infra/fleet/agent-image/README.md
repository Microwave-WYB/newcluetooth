# research-agent image

The container the Wireless Wiki research agents run in: a Paseo daemon with pi and Claude
Code, headless Chromium with the Playwright MCP server, and the agent instructions. Moved here
from wirelesswiki `agent-env` (`a8bfee0`, what runs on simi) so the agents cannot change it.
The agents' own tools and skills are not here: they live in the wiki repo (`mise.toml`,
`.agents/skills/`), where the agents change them by PR.

```
Dockerfile          system layer: base image by digest, apt from a fixed Debian snapshot, mise
mise.toml, .lock    node, gh, ripgrep, pueue and the setup:* tasks; the image's system mise config
package*.json       the npm toolset (pi, Claude Code, Paseo, Playwright MCP, pg), npm ci
entrypoint.sh       policy first, refuses key variables, git through keyproxy, then the command
AGENTS.md           agent instructions; the entrypoint puts ../policy/POLICY.md above them
GITEA.md            Gitea recipes through keyproxy (linked as /workspace/GITEA.md)
bin/, tools/        wiki-sql, wigle, wigle-request, gitea-watch, playwright-mcp-headless
home/               config stowed into $HOME (pi settings and MCP, Claude Code MCP)
build-image.sh      docker build with zstd layers (gzip export of 13 GB takes tens of minutes)
```

It runs from `infra/fleet/compose.yaml` (`mise run fleet:up`), never on its own.

## What changed from agent-env

- **No keys.** The image and container hold no Gitea, WiGLE, AndroZoo or GitHub key. The
  entrypoint refuses to start if one is set, removes credential files the old setup left in
  the home volume, rewrites git's `http://gitea:3000/` to `http://keyproxy:8080/gitea/` and
  labels git requests `X-Caller: git`. `GITEA_URL`, `WIGLE_BASE_URL` and `KEYPROXY_URL` point
  at keyproxy.
- **Policy first.** The entrypoint needs `/etc/research-agent/policy/*.md` (mounted read-only
  from `infra/fleet/policy`) and writes `~/.pi/agent/AGENTS.md` as the policy followed by
  `AGENTS.md`; `~/.claude/CLAUDE.md` links to it. The bottom-line rules moved from `AGENTS.md`
  into the policy. Agents no longer merge: humans approve and merge.
- **Tools behind keyproxy.** `wigle` sends no `Authorization` when `WIGLE_BASE_URL` is set and
  no key exists, and passes `--caller` as `X-Caller`. `gitea-watch` takes `token_env = "none"`
  (no token, `X-Caller: gitea-watch`); its acknowledgement reactions must be off, because
  keyproxy does not allow reactions. The other `wigle` endpoints (`network/search`,
  `cell/search`) are refused by keyproxy.
- No `compose.yaml` or `.env.example` here: `infra/fleet/compose.yaml` and the host secrets
  directory replace them. `agent-runtime.env` may set `WIKI_DB_URL`, `WIGLE_DB_URL` and
  `PASEO_PASSWORD` (see `infra/fleet/README.md`).

## How it fits together

- **Tools.** The wiki repo's `mise.toml` on main is the container's global mise config, so
  its tools work anywhere; in a worktree that branch's copy wins. Installs go to the
  `mise-data` volume and survive a new image. The entrypoint runs `mise install` and the
  repo's `setup:re` in the background (log: `~/.cache/mise-tools.log`), and starts `pueued`
  with group `re` (2 parallel) for heavy jobs.
- **Skills** load as project skills from the wiki repo's `.agents/skills/`.
- **State** is in three volumes: `agent-home`, `agent-workspace` (root's wiki checkout and
  sources) and `mise-data`.
- **Isolation.** Not privileged, no docker.sock, no host mounts except the read-only policy,
  all capabilities dropped, `no-new-privileges`, a 40 GB memory limit, and JVMs capped at 25%
  of it; even root cannot apt-get at run time.

Pins: the base image digest and Debian snapshot in the Dockerfile, mise by version and
checksum, tools by `mise.lock`, npm tools by `package-lock.json`.

Tests: `mise run fleet:test` (the `wigle` and `gitea-watch` tests run without network; the
`wigle` Postgres integration test is skipped without `pg`).
