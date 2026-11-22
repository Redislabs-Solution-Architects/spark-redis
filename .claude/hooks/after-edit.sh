#!/usr/bin/env bash
# PostToolUse hook (.claude/settings.json): runs after every Edit, Write or MultiEdit.
#
#  1. A .scala file is formatted with scalafmt (.scalafmt.conf). Only that file: the rest of the tree is never
#     reformatted in bulk.
#  2. The guardrail checks run on the file: scripts/check-legacy-redis.sh, check-spark-internal-imports.sh and
#     check-secrets.sh. They look at every line of the file that differs from HEAD.
#
# If a check fails, or scalafmt can't parse the file, the hook exits 2 and Claude Code shows the agent the findings
# so it can fix them in its next edit. The edit itself has already happened. Each failed check is also appended to
# .claude/hook-blocks.log (ignored by git), for the Phase 0 measurement and the evals.
#
# scalafmt is found as $SCALAFMT, on PATH, or where Coursier installs it. It needs Java 11+: set SCALAFMT_JAVA_HOME,
# otherwise Homebrew's openjdk@21 is used if it is installed, otherwise JAVA_HOME as it is.
# Written for the bash 3.2 that macOS ships. Needs jq or python3.

input=$(cat)
project=${CLAUDE_PROJECT_DIR:-$(pwd)}

json() {
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$input" | jq -r "$1 // empty"
  else
    printf '%s' "$input" | python3 -c '
import json, sys
value = json.load(sys.stdin)
for key in sys.argv[1].lstrip(".").split("."):
    value = value.get(key) if isinstance(value, dict) else None
print("" if value is None else value)
' "$1"
  fi
}

file=$(json .tool_input.file_path)
[ -n "$file" ] || exit 0
case "$file" in
  /*) ;;
  *) file="$(json .cwd)/$file" ;;
esac
[ -f "$file" ] || exit 0

problems=""
note() { problems="$problems$1"$'\n'; }
log() { # rule
  printf '%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$(json .tool_name)" "$file" \
    >>"$project/.claude/hook-blocks.log" 2>/dev/null
}

find_scalafmt() {
  local c
  for c in "${SCALAFMT:-}" "$(command -v scalafmt 2>/dev/null)" \
    "$HOME/Library/Application Support/Coursier/bin/scalafmt" "$HOME/.local/share/coursier/bin/scalafmt"; do
    [ -n "$c" ] && [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}

# 1. scalafmt, on this file only.
case "$file" in
  *.scala | *.sc)
    if scalafmt=$(find_scalafmt); then
      java_home=${SCALAFMT_JAVA_HOME:-}
      if [ -z "$java_home" ] && [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then
        java_home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
      fi
      if ! out=$(cd "$project" && JAVA_HOME=${java_home:-${JAVA_HOME:-}} "$scalafmt" --non-interactive \
        --config "$project/.scalafmt.conf" "$file" 2>&1); then
        log "after-edit:scalafmt"
        note "scalafmt could not format $file:"
        note "$(printf '%s\n' "$out" | grep -v '^Picked up JAVA_TOOL_OPTIONS' | tail -20)"
      fi
    else
      echo "after-edit: scalafmt not found, so $file was not formatted (see .claude/hooks/after-edit.sh)" >&2
    fi
    ;;
esac

# 2. The guardrail checks, on this file only.
for check in check-legacy-redis check-spark-internal-imports check-secrets; do
  script="$project/scripts/$check.sh"
  [ -x "$script" ] || continue
  if ! out=$(cd "$project" && "$script" "$file" 2>&1); then
    log "after-edit:$check"
    note "$out"
  fi
done

if [ -n "$problems" ]; then
  {
    echo "The edit to $file was made, but the after-edit hook found problems. Fix them in your next edit:"
    printf '%s' "$problems"
  } >&2
  exit 2
fi
exit 0
