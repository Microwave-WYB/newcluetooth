#!/bin/sh
# Build the research-agent image with zstd-compressed layers.
# With Docker's containerd image store, `docker compose build` gzips every layer on export,
# single-threaded; for this ~13 GB image that step alone takes tens of minutes. zstd is far faster.
# Usage: ./build-image.sh [extra docker build args]
set -eu
cd "$(dirname "$0")"
image="${RESEARCH_AGENT_IMAGE:-research-agent:dev}"
exec docker build \
  --output "type=image,name=$image,compression=zstd,compression-level=3,force-compression=true,oci-mediatypes=true" \
  "$@" .
