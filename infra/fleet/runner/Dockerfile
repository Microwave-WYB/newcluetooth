# Gitea Actions runner in host mode: jobs run inside this container (no Docker socket, no dind).
# Debian + Node so actions like actions/setup-node work (glibc); act_runner binary from the official image.
FROM gitea/act_runner:0.6.1 AS act
FROM node:22.23.4-trixie-slim
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates curl tar xz-utils zstd && rm -rf /var/lib/apt/lists/*
COPY --from=act /usr/local/bin/act_runner /usr/local/bin/act_runner
RUN useradd -m -u 1001 runner && mkdir -p /data && chown runner /data
COPY entrypoint.sh /entrypoint.sh
USER runner
WORKDIR /data
ENTRYPOINT ["/bin/sh", "/entrypoint.sh"]
