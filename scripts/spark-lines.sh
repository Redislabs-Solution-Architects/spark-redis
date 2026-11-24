#!/usr/bin/env bash
# Prints Spark lines from versions.yml, one per line. versions.yml is the only list of them: docs, commands and
# agent files call this script instead of naming lines, so adding or dropping a line changes one file (and its
# module; see .claude/skills/new-spark-line/SKILL.md).
#
#   scripts/spark-lines.sh reference   # the line pull requests test (`spark_reference`)
#   scripts/spark-lines.sh all         # every supported line (`spark`), oldest first
#   scripts/spark-lines.sh newest      # the newest supported line
#
# For example: ./mvnw -B verify -pl spark-$(scripts/spark-lines.sh reference)
#
# It reads versions.yml with sed, like scripts/redis-lines.sh, so it needs no yq.
set -euo pipefail

versions="$(cd "$(dirname "$0")/.." && pwd)/versions.yml"

# items <key>: the items of the one-line list `<key>: [...]`, one per line; nothing if the key is missing.
items() {
  { sed -n "s/^$1:[[:space:]]*\[\([^]]*\)\].*/\1/p" "$versions" | tr ',' '\n' | tr -d ' \t"' | grep -v '^$'; } || true
}

# value <key>: the scalar `<key>: "..."`, or an error, because every caller needs it.
value() {
  local v
  v=$(sed -n "s/^$1:[[:space:]]*\"\{0,1\}\([^\"#]*\)\"\{0,1\}.*/\1/p" "$versions" | tr -d ' \t')
  if [ -z "$v" ]; then
    echo "spark-lines.sh: no '$1:' in $versions" >&2
    exit 1
  fi
  printf '%s\n' "$v"
}

all() {
  local lines
  lines=$(items spark)
  if [ -z "$lines" ]; then
    echo "spark-lines.sh: no one-line list 'spark: [...]' in $versions" >&2
    exit 1
  fi
  printf '%s\n' "$lines"
}

case "${1:-}" in
  reference) value spark_reference ;;
  all) all ;;
  newest) all | tail -n 1 ;;
  *)
    echo "usage: $0 reference|all|newest" >&2
    exit 2
    ;;
esac
