#!/usr/bin/env bash
# Checks that a Redis endpoint accepts RESP3, which spark-redis 4.x will require (spec §2.1, Phase 0 step A5).
#
# Usage: scripts/check-resp3.sh <redis-url> [docker-network]
#
#   <redis-url>       for example redis://default:<password>@host:port, or rediss://... for TLS
#   [docker-network]  a Docker network to join, when the endpoint is another container on that network
#
# It sends HELLO 3 from a throwaway redis:8.10 container, so no local redis-cli is needed. It prints the
# server's name, version and mode, and exits non-zero unless the reply says proto 3.
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo "usage: $0 <redis-url> [docker-network]" >&2
  exit 2
fi
url=$1
network_args=()
if [ "$#" -eq 2 ]; then network_args=(--network "$2"); fi

# redis-cli's errors are merged into its output inside the container. The Docker client's own warnings stay on
# this script's stderr, so they never reach the parser.
reply=$(docker run --rm "${network_args[@]+"${network_args[@]}"}" redis:8.10 \
  sh -c 'redis-cli --no-auth-warning -u "$1" HELLO 3 2>&1' sh "$url") || true

# With stdout not a terminal, redis-cli prints each map entry of the HELLO reply as "key value" on one line.
# Older versions print the key and the value on separate lines, so accept both.
field() {
  printf '%s\n' "$reply" | awk -v k="$1" '
    want { print; exit }
    $1 == k && NF > 1 { $1 = ""; sub(/^ /, ""); print; exit }
    $0 == k { want = 1 }'
}
proto=$(field proto)

if [ "$proto" = "3" ]; then
  echo "RESP3 OK: server=$(field server) version=$(field version) mode=$(field mode) proto=3"
else
  echo "RESP3 NOT accepted. Reply to HELLO 3:" >&2
  printf '%s\n' "$reply" | sed '/^$/d; s/^/  /' >&2
  exit 1
fi
