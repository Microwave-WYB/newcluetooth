#!/bin/sh
# The home is a volume, so link the managed configuration into it at every start
# (picks up a new image), then run the command. Nothing else in the home is touched.
set -eu
cd /opt/research-agent
# Don't let this task install the global config's tools first; that happens in the background below.
MISE_TASK_RUN_AUTO_INSTALL=false mise run setup:home >/dev/null
git config --global user.name "${GIT_AUTHOR_NAME:-wirelesswiki research agent}"
git config --global user.email "${GIT_AUTHOR_EMAIL:-research-agent@users.noreply.github.com}"
if [ -n "${GH_TOKEN:-}" ]; then gh auth setup-git >/dev/null 2>&1 || true; fi
# Claude Code and pi write into ~/.claude.json and settings; the MCP entry was added at build.
# The agents' tools come from the wiki repo's mise.toml (the global config). Install them in the
# background so the daemon starts at once; meanwhile a shim installs a missing tool on first use.
if [ -f /workspace/wirelesswiki/mise.toml ]; then
  mkdir -p "$HOME/.cache"
  # A fresh install of pipx tools can fail the lockfile check once; the second run passes.
  (cd /workspace/wirelesswiki && { mise install || mise install; } && mise run setup:re) >"$HOME/.cache/mise-tools.log" 2>&1 &
fi
# Heavy-job queue: group "re" runs two jobs at a time (the wiki repo's wrappers submit to it).
pueued -d >/dev/null 2>&1 || true
for _ in 1 2 3 4 5; do pueue status >/dev/null 2>&1 && break; sleep 1; done
pueue group 2>/dev/null | grep -q '"re"' || pueue group add re --parallel 2 >/dev/null 2>&1 || true
cd /workspace
exec "$@"
