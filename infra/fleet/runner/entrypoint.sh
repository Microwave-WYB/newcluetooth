set -eu
cd /data
if [ ! -s .runner ]; then
  if [ -z "${GITEA_RUNNER_REGISTRATION_TOKEN:-}" ]; then
    echo "runner $GITEA_RUNNER_NAME is not registered; run: mise run fleet:register-runners" >&2
    exec sleep infinity
  fi
  act_runner register --no-interactive --instance "$GITEA_INSTANCE_URL" --token "$GITEA_RUNNER_REGISTRATION_TOKEN" \
    --name "$GITEA_RUNNER_NAME" --labels "$GITEA_RUNNER_LABELS" --config /etc/act_runner/config.yaml
fi
# `fleet:register-runners` runs a one-off container with a command: register, then run it.
if [ "$#" -gt 0 ]; then exec "$@"; fi
exec act_runner daemon --config /etc/act_runner/config.yaml
