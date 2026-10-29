# research-agent

The container the Wireless Wiki research agents run in: a Paseo daemon with pi and Claude
Code, headless Chromium with the Playwright MCP server, and the global agent instructions.
The agents' own tools and skills are not here: they live in the wiki repo (`mise.toml`,
`.agents/skills/`), where the agents change them by PR.

```
Dockerfile          system layer: base image by digest, apt from a fixed Debian snapshot, mise
mise.toml, .lock    node, gh, ripgrep and the setup:* tasks; the image's system mise config
package*.json       the npm toolset (pi, Claude Code, Paseo, Playwright MCP, pg), npm ci
bin/, tools/        wiki-sql, wigle, wigle-request, gitea-watch, playwright-mcp-headless
home/               config stowed into $HOME: global AGENTS.md (CLAUDE.md links to it), MCP
compose.yaml        local-only service; entrypoint.sh links config and installs repo tools
```

## Build and run

```sh
cp .env.example .env     # secrets; never committed
mise run agent:build     # from the repo root (zstd layers)
mise run agent:deploy    # build, then recreate the container
```

On simi a `compose.override.yaml` (not committed) joins the scan-database network and turns
on the Paseo relay.

## How it fits together

- **Tools.** The wiki repo's `mise.toml` on main is the container's global mise config, so
  its tools work anywhere; in a worktree that branch's copy wins. Installs go to the
  `mise-data` volume and survive a new image. The entrypoint runs `mise install` and the
  repo's `setup:re` in the background (log: `~/.cache/mise-tools.log`).
- **Skills** load as project skills from the wiki repo's `.agents/skills/`.
- **State** is in three volumes: `agent-home`, `agent-workspace` (root's wiki checkout and
  sources) and `mise-data`.
- **Isolation.** Not privileged, no docker.sock, no host mounts, all capabilities dropped,
  `no-new-privileges`; even root cannot apt-get at run time. Keys come from `.env` and the
  home volume, never the image.

Pins: the base image digest and Debian snapshot in the Dockerfile, mise by version and
checksum, tools by `mise.lock`, npm tools by `package-lock.json`.
