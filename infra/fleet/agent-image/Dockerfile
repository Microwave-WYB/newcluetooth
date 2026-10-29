# syntax=docker/dockerfile:1
# System layer only. Tools and configuration come from `mise install && mise run setup`,
# the same command that sets up a plain Linux host.
# node:22-trixie-slim (Debian 13.7; blutter needs g++ 13+), pinned by digest.
FROM node:22-trixie-slim@sha256:8eb619e402eaede1748a3c8173bffab391592812e85136452f781741c7242ddf

# apt reads a fixed snapshot of the Debian archive, so the same package versions install on
# every build. Update SNAPSHOT and the base digest together.
ARG SNAPSHOT=20261020T213255Z
ARG MISE_VERSION=2026.10.19
ARG MISE_SHA256_X64=b7ad610a39474b36663a6b1134c17086fa77e0f39b7ecce85c684165eed5f086
ARG MISE_SHA256_ARM64=f6dbd376928095d3bc8fde72eb1b27a8450d35c1795aa80c05aacf1ce42584aa

RUN rm -f /etc/apt/sources.list.d/debian.sources \
 && printf '%s\n' \
      "deb http://snapshot.debian.org/archive/debian/${SNAPSHOT}/ trixie main" \
      "deb http://snapshot.debian.org/archive/debian/${SNAPSHOT}/ trixie-updates main" \
      "deb http://snapshot.debian.org/archive/debian-security/${SNAPSHOT}/ trixie-security main" \
      > /etc/apt/sources.list \
 && echo 'Acquire::Check-Valid-Until "false";' > /etc/apt/apt.conf.d/10snapshot \
 && apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates curl git jq openssh-client python3 stow tini xz-utils \
      binutils unzip file binwalk \
      build-essential cmake ninja-build pkg-config libicu-dev libcapstone-dev \
      python3-pyelftools python3-requests \
      fonts-liberation fonts-noto-color-emoji libasound2t64 libatk-bridge2.0-0t64 libatk1.0-0t64 \
      libatspi2.0-0t64 libcairo2 libcups2t64 libdbus-1-3 libdrm2 libgbm1 libglib2.0-0t64 libnspr4 \
      libnss3 libpango-1.0-0 libx11-6 libxcb1 libxcomposite1 libxdamage1 libxext6 \
      libxfixes3 libxkbcommon0 libxrandr2 \
 && rm -rf /var/lib/apt/lists/*

# mise, pinned and checksum verified.
RUN case "$(dpkg --print-architecture)" in amd64) a=x64; sha="$MISE_SHA256_X64";; arm64) a=arm64; sha="$MISE_SHA256_ARM64";; *) exit 1;; esac \
 && curl -fsSL -o /tmp/mise.tgz "https://github.com/jdx/mise/releases/download/v${MISE_VERSION}/mise-v${MISE_VERSION}-linux-${a}.tar.gz" \
 && echo "${sha}  /tmp/mise.tgz" | sha256sum -c - \
 && tar -xzf /tmp/mise.tgz -C /usr/local --strip-components=1 mise/bin/mise \
 && rm /tmp/mise.tgz

RUN useradd --create-home --uid 1001 --shell /bin/bash agent \
 && mkdir -p /opt/research-agent /opt/mise /opt/research-agent-data /workspace \
 && chown agent:agent /opt/research-agent /opt/mise /opt/research-agent-data /workspace

USER agent
ENV HOME=/home/agent \
    MISE_DATA_DIR=/opt/mise \
    MISE_YES=1 \
    NUGET_PACKAGES=/home/agent/.nuget/packages \
    DOTNET_CLI_TELEMETRY_OPTOUT=1 \
    DOTNET_NOLOGO=1 \
    RESEARCH_AGENT_HOME=/opt/research-agent-data \
    PATH=/opt/research-agent/bin:/workspace/wirelesswiki/bin:/home/agent/.local/bin:/opt/research-agent-data/bin:/opt/research-agent-data/tools/node_modules/.bin:/opt/mise/shims:$PATH

WORKDIR /opt/research-agent
COPY --chown=agent:agent . .
RUN mise trust && mise install --locked && mise run setup
# The shims resolve versions from the configs in scope. This file becomes the system config
# (a copy, so /opt/research-agent stays the project root for its tasks); the wiki repo's
# mise.toml on main, with the agents' own tools, is the global config above it.
USER root
RUN mkdir -p /etc/mise && cp /opt/research-agent/mise.toml /etc/mise/config.toml
USER agent
ENV MISE_GLOBAL_CONFIG_FILE=/workspace/wirelesswiki/mise.toml

ENV PASEO_HOME=/home/agent/.paseo \
    PASEO_LISTEN=0.0.0.0:6767 \
    PASEO_RELAY_ENABLED=false \
    PI_SKIP_VERSION_CHECK=1 \
    DISABLE_AUTOUPDATER=1 \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
WORKDIR /workspace
# /opt/mise is a volume too: tools the wiki repo adds survive a new image.
VOLUME ["/home/agent", "/workspace", "/opt/mise"]
EXPOSE 6767
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/research-agent/entrypoint.sh"]
CMD ["paseo", "daemon", "run"]
