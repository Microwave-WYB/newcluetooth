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
  "agent-runtime.env|no|research-agent: harness credentials only (PASEO_PASSWORD, WIKI_DB_URL, WIGLE_DB_URL)"
)
# Variables that must never be in agent-runtime.env: those keys belong to keyproxy.
FORBIDDEN_AGENT_VARS='GITEA_TOKEN|GH_TOKEN|GITHUB_TOKEN|WIGLE_API_KEY|WIGLE_API_NAME|WIGLE_API_TOKEN|ANDROZOO_API_KEY|ANDROZOO_APIKEY'

gitea() { docker compose -p wirelesswiki-gitea --profile gitea "$@"; }
agent() { docker compose -p research-agent --profile agent "$@"; }

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

cmd_up() {
  cmd_secrets
  local gitea_dir="${GITEA_HOST_DIR:-$HOME/.local/share/cluetooth-fleet/gitea}"
  if [[ ! -d "$gitea_dir/data" || ! -d "$gitea_dir/config" ]]; then
    echo "creating $gitea_dir/{data,config} (fresh Gitea: finish its setup in the web UI)"
    mkdir -p "$gitea_dir/data" "$gitea_dir/config"
  fi
  ensure_network wirelesswiki
  ensure_network "${SCANDB_NETWORK:-newcluetooth_default}"
  if ! docker image inspect "${RESEARCH_AGENT_IMAGE:-research-agent:dev}" >/dev/null 2>&1; then
    ./agent-image/build-image.sh
  fi
  gitea up -d --build
  agent build keyproxy
  agent up -d --no-build
  cmd_status
}

cmd_config() {
  # --no-env-resolution: plain `config` would print the values of agent-runtime.env.
  docker compose --profile '*' config --no-env-resolution
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
  docker exec research-agent-keyproxy-1 wget -qO- http://127.0.0.1:8080/wigle/budget 2>/dev/null || true
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
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  logs) shift; cmd_logs "$@" ;;
  config) cmd_config ;;
  register-runners) cmd_register_runners ;;
  *) echo "usage: $0 secrets|build|up|down|status|logs <service>|config|register-runners" >&2; exit 2 ;;
esac
