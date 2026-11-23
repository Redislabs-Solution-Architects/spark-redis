#!/usr/bin/env bash
# Prints Redis lines from versions.yml, one per line. versions.yml is the only list of them: docs, commands and
# agent files call this script instead of naming lines, so adding or dropping a line changes one file.
#
#   scripts/redis-lines.sh required    # the lines every pull request must pass (`redis`), at least one
#   scripts/redis-lines.sh secondary   # the other supported lines (`redis_secondary`), possibly none
#   scripts/redis-lines.sh supported   # both, the required lines first
#   scripts/redis-lines.sh preview     # upcoming lines, tested from release candidates (`redis_preview`)
#
# For example: ./mvnw -B verify -Dredis.image=redis:$(scripts/redis-lines.sh required | head -n 1)
#
# It reads the one-line lists versions.yml uses (key: ["8.6", "8.8"]) with sed, so it needs no yq.
set -euo pipefail

versions="$(cd "$(dirname "$0")/.." && pwd)/versions.yml"

# items <key>: the items of the one-line list `<key>: [...]`, one per line; nothing if the key is missing.
items() {
  { sed -n "s/^$1:[[:space:]]*\[\([^]]*\)\].*/\1/p" "$versions" | tr ',' '\n' | tr -d ' \t"' | grep -v '^$'; } || true
}

# required: the `redis` items, or an error, because every caller needs at least one line.
required() {
  local lines
  lines=$(items redis)
  if [ -z "$lines" ]; then
    echo "redis-lines.sh: no one-line list 'redis: [...]' in $versions" >&2
    exit 1
  fi
  printf '%s\n' "$lines"
}

case "${1:-}" in
  required) required ;;
  secondary) items redis_secondary ;;
  supported) required; items redis_secondary ;;
  preview) items redis_preview ;;
  *)
    echo "usage: $0 required|secondary|supported|preview" >&2
    exit 2
    ;;
esac
