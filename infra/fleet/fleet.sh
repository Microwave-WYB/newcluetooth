#!/usr/bin/env bash
# Drives infra/fleet/compose.yaml as its two compose projects. Called by the root mise tasks
# (mise run fleet:secrets | fleet:up | fleet:down | fleet:status | fleet:logs | fleet:build).
# Never prints secret contents: only file names, modes and whether a file is empty.
set -euo pipefail
cd "$(dirname "$0")"

# Host settings (no secrets): compose reads .env itself; the script needs some of them too.
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

SECRETS_DIR="${FLEET_SECRETS_DIR:-$HOME/.config/cluetooth-fleet/secrets}"
export FLEET_SECRETS_DIR="$SECRETS_DIR"
# keyproxy and cloudflared run as the owner of the secret files, so mode 600 is enough.
export FLEET_UID="${FLEET_UID:-$(id -u)}" FLEET_GID="${FLEET_GID:-$(id -g)}"

# file | required (yes/no) | what reads it
SECRETS=(
  "gitea-token|yes|keyproxy: the Gitea bot's (wiki-bot) access token"
  "wigle-api-key|yes|keyproxy: WiGLE \"Encoded for use\" value, base64(name:token); empty disables WiGLE"
  "androzoo-api-key|yes|keyproxy: AndroZoo API key; empty disables AndroZoo"
  "cloudflared-tunnel-token|yes|cloudflared: tunnel token (git. and db.wirelesswiki.org)"
  "gitea-runner-registration-token|no|fleet:register-runners only (first registration of a runner)"
  "wiki-db-url|no|research-agent: ~/.config/wiki-db/url for wiki-sql (scan DB, read-only role wiki_ro)"
  "wigle-db-url|no|research-agent: ~/.config/wigle/db-url for wigle (its call archive, role wigle_writer)"
  "androzoo-db-url|no|research-agent: ~/.config/androzoo-db/url for androzoo-sql (AndroZoo list, role androzoo_ro)"
  "agent-runtime.env|no|research-agent: environment, PASEO_PASSWORD only"
)
# Variables that must never be in agent-runtime.env: keys belong to keyproxy, and the DB URLs
# go in their files so `env` does not show them.
FORBIDDEN_AGENT_VARS='GITEA_TOKEN|GH_TOKEN|GITHUB_TOKEN|WIGLE_API_KEY|WIGLE_API_NAME|WIGLE_API_TOKEN|ANDROZOO_API_KEY|ANDROZOO_APIKEY|WIKI_DB_URL|WIGLE_DB_URL'

# Gitea's host directory has no default: a wrong or missing path would start an empty Gitea.
need_gitea_dir() {
  if [[ -z "${GITEA_HOST_DIR:-}" ]]; then
    echo "GITEA_HOST_DIR is not set: put it in infra/fleet/.env (simi: /data/services/wirelesswiki/gitea)" >&2
    exit 1
  fi
}

gitea() { need_gitea_dir; docker compose -p wirelesswiki-gitea --profile gitea "$@"; }
# The DB URLs travel only in this process's environment, to compose, which copies them into
# the agent container as files.
agent() {
  need_gitea_dir
  local wiki="" wigle="" androzoo=""
  [[ -r "$SECRETS_DIR/wiki-db-url" ]] && wiki="$(<"$SECRETS_DIR/wiki-db-url")"
  [[ -r "$SECRETS_DIR/wigle-db-url" ]] && wigle="$(<"$SECRETS_DIR/wigle-db-url")"
  [[ -r "$SECRETS_DIR/androzoo-db-url" ]] && androzoo="$(<"$SECRETS_DIR/androzoo-db-url")"
  FLEET_WIKI_DB_URL="$wiki" FLEET_WIGLE_DB_URL="$wigle" FLEET_ANDROZOO_DB_URL="$androzoo" \
    docker compose -p research-agent --profile agent "$@"
}

cmd_secrets() {
  local bad=0 dir_mode
  echo "secrets directory: $SECRETS_DIR"
  if [[ ! -d "$SECRETS_DIR" ]]; then
    echo "  MISSING: create it with: mkdir -p '$SECRETS_DIR' && chmod 700 '$SECRETS_DIR'"
    return 1
  fi
  dir_mode=$(stat -c '%a' "$SECRETS_DIR")
  if [[ "$dir_mode" != 700 ]]; then echo "  directory mode is $dir_mode, expected 700"; bad=1; fi
  for entry in "${SECRETS[@]}"; do
    IFS='|' read -r name required what <<<"$entry"
    local f="$SECRETS_DIR/$name" mode owner
    if [[ ! -e "$f" ]]; then
      if [[ "$required" == yes ]]; then echo "  MISSING   $name  ($what)"; bad=1; else echo "  absent    $name  (optional; $what)"; fi
      continue
    fi
    mode=$(stat -c '%a' "$f")
    owner=$(stat -c '%u' "$f")
    if [[ ! -f "$f" || -L "$f" ]]; then echo "  NOT A FILE $name"; bad=1; continue; fi
    if [[ "$mode" != 600 ]]; then echo "  MODE $mode  $name  (expected 600: chmod 600 '$f')"; bad=1; continue; fi
    if [[ "$owner" != "$FLEET_UID" ]]; then echo "  OWNER uid $owner  $name  (expected $FLEET_UID)"; bad=1; continue; fi
    if [[ ! -s "$f" ]]; then echo "  empty     $name  ($what)"; continue; fi
    if [[ "$name" == agent-runtime.env ]] && grep -qE "^[[:space:]]*(export[[:space:]]+)?($FORBIDDEN_AGENT_VARS)=" "$f"; then
      echo "  FORBIDDEN $name sets a key that belongs to keyproxy: $(grep -oE "^[[:space:]]*(export[[:space:]]+)?($FORBIDDEN_AGENT_VARS)=" "$f" | tr -d ' =' | sed 's/^export//' | sort -u | tr '\n' ' ')"
      bad=1
      continue
    fi
    echo "  ok        $name"
  done
  if (( bad )); then echo "secrets check failed"; return 1; fi
  echo "secrets check passed"
}

ensure_network() {
  if ! docker network inspect "$1" >/dev/null 2>&1; then
    echo "creating network $1"
    docker network create "$1" >/dev/null
  fi
}

cmd_build() {
  gitea build
  agent build keyproxy
  # The research agent image is ~13 GB: build it with zstd layers, not compose's gzip.
  ./agent-image/build-image.sh
}

# Refuses to start Gitea on a directory other than the one it already runs on, or on one
# without data/ and config/.
check_gitea_dir() {
  need_gitea_dir
  local dir src want
  dir=$(realpath -m "$GITEA_HOST_DIR")
  if [[ ! -d "$dir/data" || ! -d "$dir/config" ]]; then
    echo "GITEA_HOST_DIR=$dir has no data/ and config/. For a new Gitea create them first:" >&2
    echo "  mkdir -p '$dir/data' '$dir/config'" >&2
    exit 1
  fi
  if docker container inspect wirelesswiki-gitea-gitea-1 >/dev/null 2>&1; then
    for want in "data:/var/lib/gitea" "config:/etc/gitea"; do
      src=$(docker container inspect -f "{{range .Mounts}}{{if eq .Destination \"${want#*:}\"}}{{.Source}}{{end}}{{end}}" wirelesswiki-gitea-gitea-1)
      if [[ "$(realpath -m "$src")" != "$dir/${want%%:*}" ]]; then
        echo "wirelesswiki-gitea-gitea-1 mounts $src at ${want#*:}, not $dir/${want%%:*}; refusing to recreate it on other data." >&2
        echo "Fix GITEA_HOST_DIR in infra/fleet/.env, or remove the old container deliberately first." >&2
        exit 1
      fi
    done
  fi
}

# up [gitea|agent]: both groups, or one of them (the migration cuts over one at a time).
cmd_up() {
  local group="${1:-all}"
  case "$group" in all | gitea | agent) ;; *) echo "usage: fleet.sh up [gitea|agent]" >&2; exit 2 ;; esac
  cmd_secrets
  check_gitea_dir
  ensure_network wirelesswiki
  ensure_network "${SCANDB_NETWORK:-newcluetooth_default}"
  if [[ "$group" != agent ]]; then
    gitea up -d --build
  fi
  if [[ "$group" != gitea ]]; then
    if ! docker image inspect "${RESEARCH_AGENT_IMAGE:-research-agent:dev}" >/dev/null 2>&1; then
      ./agent-image/build-image.sh
    fi
    agent build keyproxy
    agent up -d --no-build
  fi
  cmd_status
}

cmd_config() {
  # --no-env-resolution: plain `config` would print the values of agent-runtime.env.
  need_gitea_dir
  # Secret values (DB URLs) are not printed: config shows secret sources, not contents.
  FLEET_WIKI_DB_URL="" FLEET_WIGLE_DB_URL="" FLEET_ANDROZOO_DB_URL="" docker compose --profile '*' config --no-env-resolution
}

cmd_register_runners() {
  local f="$SECRETS_DIR/gitea-runner-registration-token" i
  if [[ ! -s "$f" ]]; then
    echo "missing or empty: $f (Gitea: Site Administration > Actions > Runners > Create new runner)" >&2
    return 1
  fi
  # Passed by name only (-e VAR) to one-off containers, never written into compose's model.
  GITEA_RUNNER_REGISTRATION_TOKEN="$(<"$f")"
  export GITEA_RUNNER_REGISTRATION_TOKEN
  for i in 1 2 3 4; do
    gitea run --rm --no-deps -e GITEA_RUNNER_REGISTRATION_TOKEN "runner-$i" true
  done
  unset GITEA_RUNNER_REGISTRATION_TOKEN
  gitea restart runner-1 runner-2 runner-3 runner-4
}

cmd_down() {
  agent down
  gitea down
}

cmd_status() {
  gitea ps
  agent ps
  echo "keyproxy:"
  docker exec research-agent-keyproxy-1 wget -qO- http://127.0.0.1:8080/healthz 2>/dev/null || echo "  not reachable"
  docker exec research-agent-keyproxy-1 wget -qO- http://127.0.0.1:8080/limits 2>/dev/null || true
}

cmd_logs() {
  local svc="${1:-}"
  case "$svc" in
    "") echo "usage: fleet:logs <service>  (gitea cloudflared runner-1..4 keyproxy research-agent)" >&2; return 2 ;;
    keyproxy | research-agent) agent logs -f --tail 200 "$svc" ;;
    *) gitea logs -f --tail 200 "$svc" ;;
  esac
}

case "${1:-}" in
  secrets) cmd_secrets ;;
  build) cmd_build ;;
  up) shift; cmd_up "$@" ;;
  down) cmd_down ;;
  status) cmd_status ;;
  logs) shift; cmd_logs "$@" ;;
  config) cmd_config ;;
  register-runners) cmd_register_runners ;;
  *) echo "usage: $0 secrets|build|up [gitea|agent]|down|status|logs <service>|config|register-runners" >&2; exit 2 ;;
esac
