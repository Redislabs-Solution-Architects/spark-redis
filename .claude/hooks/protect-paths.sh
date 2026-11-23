#!/usr/bin/env bash
# PreToolUse hook (.claude/settings.json): blocks agent changes to files only the owner may change.
#
#   src/test/resources/tls/**         the test certificates
#   src/test/resources/golden/**      golden-layout fixtures (the owner regenerates them with -Dgolden.update=true)
#   src/test/resources/legacy/**      Java-serialized fixtures produced by the 3.x build
#   src/test/resources/quarantine.txt tests skipped on Redis 8, each linked to a findings entry
#   tests listed in .claude/failing-first.txt, which were committed failing ahead of their fix
#
# A test stays protected while it is listed in .claude/failing-first.txt in the working tree or at HEAD. The PR that
# fixes the bug removes the test's line; removing it in the working tree doesn't unprotect the test, and CI
# (scripts/check-failing-first.sh) fails a PR that removes a line and also changes or deletes its test.
#
# Also blocks running -Dgolden.update=true. Edit, Write, MultiEdit and NotebookEdit are checked by path. Bash
# commands are checked on a best-effort basis: a command that names a protected path and writes (a redirect, cp,
# mv, rm, sed -i, tee, git checkout or restore, ...) is blocked.
#
# A block exits 2 with a message naming the rule, which Claude Code shows to the agent. Each block is also appended
# to .claude/hook-blocks.log (ignored by git), for the Phase 0 measurement.
# Written for the bash 3.2 that macOS ships. Needs jq or python3.

input=$(cat)
project=${CLAUDE_PROJECT_DIR:-$(pwd)}
list="$project/.claude/failing-first.txt"

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

block() { # rule, path, explanation
  local rule=$1 path=$2 why=$3
  mkdir -p "$project/.claude" 2>/dev/null &&
    printf '%s\t%s\t%s\t%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$rule" "$tool" "$path" \
      >>"$project/.claude/hook-blocks.log" 2>/dev/null
  {
    echo "Blocked by .claude/hooks/protect-paths.sh, rule \"$rule\": $path"
    echo "$why"
  } >&2
  exit 2
}

# Entries of failing-first.txt: the first word of each line that isn't blank or a comment.
entries() { sed -e 's/#.*//' | awk 'NF { print $1 }' | sort -u; }

# The tests listed in failing-first.txt in the working tree or at HEAD. A line removed in the working tree still
# protects its test until the removal is committed.
listed() {
  {
    [ -f "$list" ] && entries <"$list"
    git -C "$project" show HEAD:.claude/failing-first.txt 2>/dev/null | entries
  } | sort -u
}

# Prints "rule<TAB>explanation" if the path is protected.
rule_for() {
  local path=$1 entry
  case "$path" in
    */src/test/resources/tls/* | src/test/resources/tls/*)
      printf 'tls-certificates\tThe test certificates are fixed test data; nothing in a PR changes them. Ask the owner if you think one must change.\n'
      return ;;
    */src/test/resources/golden/* | src/test/resources/golden/*)
      printf 'golden-fixtures\tGolden fixtures change only with the owner'"'"'s approval (CLAUDE.md rule 6). If the layout really changed, stop and say so: the owner regenerates fixtures with -Dgolden.update=true. To add a scenario, write the test only (see .claude/skills/golden-layout).\n'
      return ;;
    */src/test/resources/legacy/* | src/test/resources/legacy/*)
      printf 'legacy-fixtures\tThe legacy fixtures were produced by the 3.x build (legacy/README.md) and never change.\n'
      return ;;
    */src/test/resources/quarantine.txt | src/test/resources/quarantine.txt)
      printf 'quarantine\tQuarantine entries change only with the owner'"'"'s approval (CLAUDE.md rule 6). Write the findings entry, propose the exact quarantine line, and stop.\n'
      return ;;
  esac
  while IFS= read -r entry; do
    case "$path" in
      "$entry" | */"$entry")
        printf 'failing-first\tThis test was committed failing ahead of its fix (.claude/failing-first.txt). Fix the code, not the test (CLAUDE.md rule 5).\n'
        return ;;
    esac
  done < <(listed)
}

check_path() {
  local path=$1 hit
  [ -n "$path" ] || return 0
  case "$path" in
    /*) ;;
    *) path="$(json .cwd)/$path" ;;
  esac
  hit=$(rule_for "$path")
  [ -n "$hit" ] && block "${hit%%	*}" "$path" "${hit#*	}"
  return 0
}

check_bash() {
  local cmd=$1 targets hay p entry
  case "$cmd" in
    *golden.update=true*)
      block "golden-fixtures" "-Dgolden.update=true" "Only the owner regenerates golden fixtures (CLAUDE.md rule 6). Run the suite without -Dgolden.update; if the layout changed, stop and tell the owner." ;;
  esac
  # Where the command writes: always its redirect targets, and the whole command line when it runs a command
  # that changes files. Reading a protected file (cat, grep, git diff, ...) is fine.
  targets=$(printf '%s' "$cmd" | grep -Eo '(^|[^0-9&>])>>?[[:space:]]*[^[:space:];&|<>]+' | sed -E 's/^[^>]*>>?[[:space:]]*//')
  if printf '%s' "$cmd" |
    grep -Eq '(^|[;&|(`[:space:]])(tee|cp|mv|rm|rmdir|truncate|dd|install|rsync|ln|chmod|touch|patch|unzip)([[:space:]]|$)|sed[[:space:]]+(-[a-zA-Z]*i|--in-place)|perl[[:space:]]+-[a-zA-Z]*i|git[[:space:]]+(checkout|restore|rm|mv|apply|am|stash|reset)'; then
    hay=$cmd
  else
    hay=$targets
  fi
  [ -n "$hay" ] || return 0
  for p in resources/tls/ resources/golden/ resources/legacy/ resources/quarantine.txt; do
    case "$hay" in
      *"$p"*) check_path "$project/src/test/$p" ;;
    esac
  done
  case "$hay" in
    *failing-first.txt*)
      block "failing-first" ".claude/failing-first.txt" "Change .claude/failing-first.txt with Edit or Write, not a shell command." ;;
  esac
  while IFS= read -r entry; do
    case "$hay" in
      *"$entry"* | *"$(basename "$entry")"*) check_path "$project/$entry" ;;
    esac
  done < <(listed)
}

tool=$(json .tool_name)
case "$tool" in
  Edit | Write | MultiEdit)
    check_path "$(json .tool_input.file_path)" ;;
  NotebookEdit)
    check_path "$(json .tool_input.notebook_path)" ;;
  Bash)
    check_bash "$(json .tool_input.command)" ;;
esac
exit 0
