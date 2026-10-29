#!/bin/sh
# The home is a volume, so link the managed configuration into it at every start
# (picks up a new image), then run the command.
set -eu

# 1. The fleet policy comes first. It is mounted read-only from the owner's infra repo
#    (infra/fleet/policy); without it the agents do not start.
policy_dir="${FLEET_POLICY_DIR:-/etc/research-agent/policy}"
if ! ls "$policy_dir"/*.md >/dev/null 2>&1; then
  echo "entrypoint: no fleet policy in $policy_dir (mount infra/fleet/policy read-only); refusing to start" >&2
  exit 1
fi
policy=$(cat "$policy_dir"/*.md)

# 2. This container holds no keys: keyed services go through keyproxy, and the scan-database
#    URLs come as files (~/.config/wiki-db/url, ~/.config/wigle/db-url) so `env` cannot print
#    them. Refuse to start if one was passed in the environment by mistake (variable names
#    only are printed, never values).
forbidden=""
for v in GITEA_TOKEN GH_TOKEN GITHUB_TOKEN WIGLE_API_KEY WIGLE_API_NAME WIGLE_API_TOKEN ANDROZOO_API_KEY ANDROZOO_APIKEY WIKI_DB_URL WIGLE_DB_URL; do
  eval "val=\${$v:-}"
  if [ -n "$val" ]; then forbidden="$forbidden $v"; fi
done
if [ -n "$forbidden" ]; then
  echo "entrypoint: must not be set in this container's environment:$forbidden (keys belong to keyproxy, DB URLs in files); refusing to start" >&2
  exit 1
fi
unset val

cd /opt/research-agent
# Don't let this task install the global config's tools first; that happens in the background below.
MISE_TASK_RUN_AUTO_INSTALL=false mise run setup:home >/dev/null

# Global instructions for pi and Claude Code: the policy first, then the agent instructions.
mkdir -p "$HOME/.pi/agent" "$HOME/.claude"
rm -f "$HOME/.pi/agent/AGENTS.md" "$HOME/.claude/CLAUDE.md"
printf '%s\n\n---\n\n' "$policy" > "$HOME/.pi/agent/AGENTS.md"
cat /opt/research-agent/AGENTS.md >> "$HOME/.pi/agent/AGENTS.md"
ln -s "$HOME/.pi/agent/AGENTS.md" "$HOME/.claude/CLAUDE.md"
# Recipes for Gitea through keyproxy; older prompts point at /workspace/GITEA.md.
ln -sfn /opt/research-agent/GITEA.md /workspace/GITEA.md

# Credentials left in the home volume by the pre-keyproxy setup are removed (names only logged).
for f in "$HOME/.git-credentials" "$HOME/.config/wigle/api-key" "$HOME/.config/gh/hosts.yml"; do
  if [ -e "$f" ]; then rm -f "$f" && echo "entrypoint: removed stale credential file $f" >&2; fi
done

git config --global user.name "${GIT_AUTHOR_NAME:-wirelesswiki research agent}"
git config --global user.email "${GIT_AUTHOR_EMAIL:-research-agent@users.noreply.github.com}"
git config --global --unset-all credential.helper 2>/dev/null || true
# Existing clones use http://gitea:3000/; send them through keyproxy, which adds the token
# (without it Gitea would answer every unauthenticated git request with "not found").
git config --global --unset-all url."${KEYPROXY_URL:-http://keyproxy:8080}/gitea/".insteadOf 2>/dev/null || true
git config --global url."${KEYPROXY_URL:-http://keyproxy:8080}/gitea/".insteadOf "http://gitea:3000/"
git config --global http."${KEYPROXY_URL:-http://keyproxy:8080}/".extraHeader "X-Caller: git"

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
