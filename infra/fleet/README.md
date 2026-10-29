# infra/fleet: the research agent fleet

A clone of this repo plus `mise` brings up the Wireless Wiki research fleet:

```sh
mise run fleet:secrets   # check the secret files (names and modes only, never contents)
mise run fleet:up        # build what is missing and start everything
```

Everything the agents must not be able to change lives here, outside their reach: the
compose file, the agent image, keyproxy and the fleet policy. Secrets never enter this repo
or the agent container.

```
compose.yaml   gitea, cloudflared, runner-1..4 (project wirelesswiki-gitea);
               keyproxy, research-agent (project research-agent)
fleet.sh       what the mise tasks run (simi has no mise: run ./fleet.sh <command> there)
keyproxy/      the only holder of API keys; whitelisted endpoints only (see keyproxy/README.md)
agent-image/   the research-agent image (moved from wirelesswiki agent-env a8bfee0)
policy/        the bottom-line rules, mounted read-only; the agent entrypoint reads them first
runner/        the Gitea Actions runner image (host mode, no Docker socket)
.env.example   host settings, no secrets
```

## Trust boundaries

- **The agent container holds no API keys.** No `GITEA_TOKEN`, no WiGLE or AndroZoo key, no
  GitHub token. It knows only `http://keyproxy:8080`. It is not on the `wirelesswiki` network,
  so it cannot reach Gitea directly; git's `http://gitea:3000/` is rewritten to the proxy. The
  entrypoint refuses to start if a key variable is set, and deletes credential files left in
  the home volume by the old setup (`~/.git-credentials`, `~/.config/wigle/api-key`,
  `~/.config/gh/hosts.yml`).
- **keyproxy** reads the keys from `/run/secrets/*` (compose file secrets, mounted only into
  keyproxy) and allows: WiGLE Bluetooth search under a daily budget with a 10-call reserve;
  one-at-a-time, rate-limited AndroZoo APK downloads by sha256 (gp-metadata only if enabled);
  Gitea reads, comments, issue creation, PR creation from `task/*`, and git fetch plus pushes
  to `refs/heads/task/*` only. No merge, reviews, branch protection, settings or admin. Every
  request is logged (caller, endpoint, params, status) with secrets scrubbed. `X-Caller` is
  for logs and budgets only, **not** authorisation.
- **The policy** (`policy/POLICY.md`) is mounted read-only at `/etc/research-agent/policy`.
  The entrypoint will not start without it and writes it at the top of the global
  `AGENTS.md`/`CLAUDE.md` that pi and Claude Code load, ahead of the agent instructions.
- **Known exceptions** (still in the agent container): `agent-runtime.env` with the scan
  database URLs (`WIKI_DB_URL`, read-only role `wiki_ro`; `WIGLE_DB_URL`, insert/select role
  `wigle_writer` for the WiGLE call archive) and, if set, `PASEO_PASSWORD`; and the harnesses'
  own login state in the `agent-home` volume (Claude Code's OAuth login, Paseo's relay keys).
  On simi every model-provider key in the old `.env` is empty. Moving the DB credentials
  behind a proxy is follow-up work.

## Secrets

All in `FLEET_SECRETS_DIR` (default `~/.config/cluetooth-fleet/secrets`, directory mode 700,
files mode 600, owned by the account that runs the fleet). `mise run fleet:secrets` checks
them and prints names, modes and `empty` only.

| File | Required | Read by | Content |
|---|---|---|---|
| `gitea-token` | yes | keyproxy | access token of the Gitea bot `wiki-bot` |
| `wigle-api-key` | yes (may be empty) | keyproxy | WiGLE "Encoded for use" value, base64(name:token); empty disables WiGLE |
| `androzoo-api-key` | yes (may be empty) | keyproxy | AndroZoo API key; empty disables AndroZoo |
| `cloudflared-tunnel-token` | yes | cloudflared | tunnel token for git.wirelesswiki.org and db.wirelesswiki.org |
| `gitea-runner-registration-token` | no | `fleet:register-runners` only | runner registration token, for registering new runners once |
| `agent-runtime.env` | no | research-agent | `KEY=value` lines: `WIKI_DB_URL`, `WIGLE_DB_URL`, `PASEO_PASSWORD`. Must not contain `GITEA_TOKEN`, `GH_TOKEN`, `GITHUB_TOKEN`, `WIGLE_API_*`, `ANDROZOO_*` (checked) |

keyproxy and cloudflared run as `FLEET_UID:FLEET_GID` (the invoking user, set by `fleet.sh`),
so 600 files are readable by them and by nothing else. `docker compose config` would print
`agent-runtime.env` values; use `mise run fleet:config`, which renders with
`--no-env-resolution`.

## Deploy (fresh host)

1. `mise install` (Node for the tests; Docker with Compose v2 is assumed on the host).
2. Secrets:
   ```sh
   install -d -m 700 ~/.config/cluetooth-fleet/secrets
   cd ~/.config/cluetooth-fleet/secrets
   umask 077
   # write each file with an editor or by redirecting from where the value is stored;
   # never paste a value on a command line that is logged or echoed
   touch gitea-token wigle-api-key androzoo-api-key cloudflared-tunnel-token
   ```
   then `mise run fleet:secrets` until it passes.
3. Host settings: `cp infra/fleet/.env.example infra/fleet/.env` and set at least
   `GITEA_HOST_DIR` (where Gitea's `data/` and `config/` live), `GITEA_UID`/`GITEA_GID`,
   `PASEO_RELAY_ENABLED` and `WIGLE_DAILY_BUDGET` (the account's real daily limit).
4. The scan database network: start `infra/data` first (project `newcluetooth`), or let
   `fleet:up` create an empty `newcluetooth_default`.
5. `mise run fleet:up`. It checks the secrets, creates the external networks `wirelesswiki`
   and `newcluetooth_default` if missing, builds the research-agent image if missing (~13 GB,
   zstd layers through `agent-image/build-image.sh`), starts the Gitea project, then builds
   keyproxy and starts the agent project.
6. A fresh Gitea: create the admin and the `wiki-bot` user (bot token: `write:repository`,
   `write:issue`, `read:user`; no admin), the `y2wei/wirelesswiki` repo with branch protection
   on `main` (no pushes; merge whitelist of humans only; required approvals), write the bot
   token into `gitea-token`, `docker restart research-agent-keyproxy-1`. Put a runner
   registration token into `gitea-runner-registration-token` and run
   `mise run fleet:register-runners`.
7. The Cloudflare side (tunnel routes, Access apps) is configured in the dashboard, not here.

Day to day: `fleet:status` (containers, keyproxy health, today's WiGLE budget),
`fleet:logs <service>`, `fleet:down` (containers only; volumes and host data stay),
`fleet:build`, `fleet:test` (keyproxy and agent tool tests).

## What runs on simi today (surveyed read-only, 2026-10-28)

- `/data/services/wirelesswiki/gitea/`: compose project `wirelesswiki-gitea`, containers
  `wirelesswiki-gitea-{gitea,cloudflared,runner-1..4}-1`; Gitea `gitea/gitea:1.27.0-rootless`
  as 1000:1000 with `./data` and `./config` bind mounts; runners `wirelesswiki-runner:0.6.1`
  built from `./runner/` (same files as `runner/` here, except the new idle-when-unregistered
  entrypoint); volumes `wirelesswiki-gitea_runner-{1..4}-data`; network `wirelesswiki`;
  cloudflared also on `newcluetooth_default`. Secret-bearing files: `.env` (`TUNNEL_TOKEN`),
  `runner.env` (`GITEA_RUNNER_REGISTRATION_TOKEN`, plus `GITEA_INSTANCE_URL` and
  `GITEA_RUNNER_LABELS`), `.admin-token`.
- `/data/services/wirelesswiki/research-agent/`: compose project `research-agent`
  (`compose.yaml` + a simi-only `compose.override.yaml`: Paseo relay on, networks
  `wirelesswiki` and `newcluetooth_default`), container `research-agent-research-agent-1`,
  image `research-agent:dev`, volumes `research-agent_{agent-home,agent-workspace,mise-data}`.
  Files identical to wirelesswiki `agent-env` at `a8bfee0` (that branch exists only locally;
  it is not on GitHub). Its `.env` sets `GITEA_TOKEN`, `WIGLE_API_KEY`, `WIKI_DB_URL`,
  `WIGLE_DB_URL` (plus non-secret `GITEA_URL`, `GITEA_REPO`, `GITEA_USER`); every model
  provider key, `GH_TOKEN` and `PASEO_PASSWORD` are empty. The previous copy is in
  `research-agent.prev/`.
- `/data/services/wirelesswiki/GITEA.md` and `ROOT_TASK.md`: the root's Gitea recipes (curl
  with `$GITEA_TOKEN`) and the pilot migration task. `agent-image/GITEA.md` replaces the
  recipes; the entrypoint links `/workspace/GITEA.md` to it.
- **pgweb is not running** on simi (no container, image or process). The scan DB's
  `db.wirelesswiki.org` is not a separate tunnel: it is a route of the Gitea project's
  cloudflared (TCP to `cluetooth-db:5432`, behind a Cloudflare Access app). `infra/data`
  now carries both as opt-in profiles (`pgweb`, `db-tunnel`); see docs/operations.md.
- The data stack still runs from `/home/y2wei/Projects/newcluetooth` at `ac84d8f` (before the
  `infra/data` restructure) with one local edit (Postgres on `127.0.0.1` only, already in
  `infra/data/compose.yaml`) and an untracked migration `20261027070000_wigle_archive.sql`
  (the `wigle.responses` archive and role `wigle_writer`), which is not in this repo yet.

## Migration plan (simi)

Nothing here has been run. Every step keeps the existing data paths, volumes and networks,
so the new containers pick up the old state. Do it with the owner present.

**Preparation (no downtime)**

1. Merge this PR. Clone the repo on simi next to the services, e.g.
   `/data/services/wirelesswiki/newcluetooth` (leave the data stack checkout alone).
2. Backups and rollback tags:
   ```sh
   mkdir -p /data/services/wirelesswiki/backups && cd /data/services/wirelesswiki/backups
   sudo tar -C /data/services/wirelesswiki -czf gitea-pre-fleet.tgz gitea/data gitea/config
   for v in agent-home agent-workspace; do
     docker run --rm -v research-agent_$v:/v:ro -v "$PWD":/b busybox tar -C /v -czf /b/$v-pre-fleet.tgz .
   done
   docker tag research-agent:dev research-agent:pre-fleet
   docker tag wirelesswiki-runner:0.6.1 wirelesswiki-runner:pre-fleet
   ```
3. Secrets dir from the existing files, without printing anything:
   ```sh
   install -d -m 700 ~/.config/cluetooth-fleet/secrets && cd ~/.config/cluetooth-fleet/secrets && umask 077
   S=/data/services/wirelesswiki
   sed -n 's/^GITEA_TOKEN=//p'   $S/research-agent/.env > gitea-token
   sed -n 's/^WIGLE_API_KEY=//p' $S/research-agent/.env > wigle-api-key
   sed -n 's/^TUNNEL_TOKEN=//p'  $S/gitea/.env          > cloudflared-tunnel-token
   sed -n 's/^GITEA_RUNNER_REGISTRATION_TOKEN=//p' $S/gitea/runner.env > gitea-runner-registration-token
   grep -E '^(WIKI_DB_URL|WIGLE_DB_URL)=' $S/research-agent/.env > agent-runtime.env
   : > androzoo-api-key    # until the owner has an AndroZoo key
   cd /data/services/wirelesswiki/newcluetooth && infra/fleet/fleet.sh secrets
   ```
4. `infra/fleet/.env`: `GITEA_HOST_DIR=/data/services/wirelesswiki/gitea`, `GITEA_UID=1000`,
   `GITEA_GID=1000`, `PASEO_RELAY_ENABLED=true`, `WIGLE_DAILY_BUDGET=<the account limit>`.
5. Check the render against what runs: `infra/fleet/fleet.sh config` must show the container
   names, volume names (`wirelesswiki-gitea_runner-N-data`, `research-agent_*`), networks
   (`wirelesswiki`, `newcluetooth_default`, `research-agent_default`) and bind paths listed
   above.
6. Build without touching running containers: `docker compose -p wirelesswiki-gitea --profile
   gitea build`, `docker compose -p research-agent --profile agent build keyproxy`,
   `RESEARCH_AGENT_IMAGE=research-agent:dev infra/fleet/agent-image/build-image.sh` (tens of
   minutes on simi's disks).

**Cutover 1: Gitea project (a minute of Git downtime)**

7. Rename (do not delete) `/data/services/wirelesswiki/gitea/compose.yaml` to
   `compose.yaml.pre-fleet`, so nobody brings the old definition back by accident.
8. From `infra/fleet`: `docker compose -p wirelesswiki-gitea --profile gitea up -d --no-build`.
   Compose recreates gitea, cloudflared (now reading the token from a file) and the runners
   on the same bind mounts and volumes.
9. Check: `https://git.wirelesswiki.org` loads, `mise run db:connect` reaches Postgres through
   `db.wirelesswiki.org`, the four runners are idle-online in Gitea's admin page and a CI run
   passes.

**Cutover 2: research agent (agents paused)**

10. Tell root (Paseo) to finish or pause, and to stop `gitea-watch`.
11. Rename `/data/services/wirelesswiki/research-agent/compose.yaml` and
    `compose.override.yaml` to `*.pre-fleet`.
12. From `infra/fleet`: `docker compose -p research-agent --profile agent up -d --no-build`.
    The agent container is recreated without any key variables, with the policy mounted, on
    `research-agent_default` (with keyproxy) and `newcluetooth_default`. On first start its
    entrypoint deletes `~/.git-credentials` and `~/.config/wigle/api-key` from the home
    volume (it logs the file names).
13. Check (counts and names only):
    `docker exec research-agent-research-agent-1 sh -c 'env | grep -cE "^(GITEA_TOKEN|WIGLE_API_KEY)="'`
    prints 0; `git -C /workspace/wirelesswiki fetch` works; `wigle search bluetooth/search
    name=...` works and appears in `fleet:logs keyproxy`; a push to `main` is rejected; the
    Paseo app reconnects and root's session is still there.
14. Root restarts `gitea-watch` with `gitea_url = "http://keyproxy:8080/gitea"`,
    `token_env = "none"` and `[ack] enabled = false` (keyproxy does not allow reactions).

**Rollback** (any step; volumes and host data are never removed)

- Gitea: `docker compose -p wirelesswiki-gitea --profile gitea down` from `infra/fleet`,
  rename `compose.yaml.pre-fleet` back, `docker tag wirelesswiki-runner:pre-fleet
  wirelesswiki-runner:0.6.1`, `docker compose up -d` in `/data/services/wirelesswiki/gitea`.
- Agent: `docker compose -p research-agent --profile agent down` from `infra/fleet`,
  `docker tag research-agent:pre-fleet research-agent:dev`, rename the two compose files back,
  `docker compose up -d --no-build` in `/data/services/wirelesswiki/research-agent`. Restore
  `~/.git-credentials` from `agent-home-pre-fleet.tgz` (the old setup pushes with it).
- If keys were already rotated, the old `.env` files need the new values first.

**After the cutover**: rotate (next section), then the owner decides whether to delete the
old `.env`, `runner.env` and `.admin-token` files and the `*.pre-fleet` compose files. The
backups stay until the fleet has run cleanly for a while.

## Secret rotation after the migration

Rotate once keyproxy is the only holder, so the new values never reach the agent container:

1. **GITEA_TOKEN (wiki-bot): must rotate.** It has been printed before. Create a new token
   for `wiki-bot` with scopes `write:repository`, `write:issue`, `read:user` (write it straight
   into the file, e.g. `docker exec wirelesswiki-gitea-gitea-1 gitea admin user
   generate-access-token --username wiki-bot --token-name keyproxy-YYYYMMDD --scopes
   write:repository,write:issue,read:user --raw > ~/.config/cluetooth-fleet/secrets/gitea-token.new`,
   then `mv` over `gitea-token`), `docker restart research-agent-keyproxy-1`, check a fetch
   through the proxy, then delete every older `wiki-bot` token in Gitea.
2. **WiGLE API key: rotate.** It sat in the agent's environment, where any agent could read
   it. Regenerate it in the WiGLE account, update `wigle-api-key`, restart keyproxy.
3. **Any key that ever appeared in agent output.** Search the agents' transcripts and notes
   for each current secret without printing it, file names only, e.g.
   `docker run --rm -v research-agent_agent-home:/h:ro -v research-agent_agent-workspace:/w:ro -v ~/.config/cluetooth-fleet/secrets:/s:ro busybox sh -c 'for f in /s/*; do grep -rlF -f "$f" /h /w; done'`.
   Candidates: the scan DB passwords of `wiki_ro` and `wigle_writer` (`ALTER ROLE ...
   PASSWORD`, then update `agent-runtime.env`), the Gitea admin token in `.admin-token`, the
   tunnel token (rotate in the Cloudflare dashboard), the runner registration token (reset
   in Gitea). Rotate every one that turns up.
4. Separately tracked: the `cluetooth` database password committed in
   `infra/data/compose.yaml` (GitHub Microwave-WYB/newcluetooth#22).

## Planned repo split

Today one Gitea repo, `y2wei/wirelesswiki`, holds both the public wiki and the agents' own
working material, so everything gets the strict human-approval rule. The plan is two repos:

- **`wiki`**: the public content and its site build. Unchanged rules: humans approve and
  merge, CI must pass, agents push `task/*` and open PRs only. Deploys come from `main`.
- **`research-runtime`**: the agents' notes, tools (`mise.toml`), skills (`.agents/skills`) and
  prompts. Agents may merge their own PRs there once CI passes (tests, lint, a secret scan, and
  a check that nothing outside the allowed paths changed), and a scheduled workflow sends the
  owner a **daily digest** of everything merged. keyproxy would get one more route: merge,
  for `research-runtime` only, forwarded only when the PR's combined commit status is
  `success`, on top of Gitea's own required checks. This policy stays in this repo, never in
  `research-runtime`.

Steps when it happens: create `research-runtime`, move skills, tools config and notes out of
`wiki`, point the agent image's `MISE_GLOBAL_CONFIG_FILE` and skills path at its checkout,
add it to `GITEA_REPOS`, add the merge route and its tests, and add the digest workflow.
