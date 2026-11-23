#!/usr/bin/env bash
# Fails when a change removes a test from .claude/failing-first.txt and also changes or deletes that test.
#
#   scripts/check-failing-first.sh [--base <ref>]
#
# A bug's test is committed failing and listed in .claude/failing-first.txt (CLAUDE.md rule 5). The PR that fixes
# the bug removes the line, and its fix must make the test pass as it is. The test jobs in ci.yml show that it
# passes; this check shows that it wasn't changed to pass. Paths are ignored: the check always looks at the list.
# See scripts/check-common.sh for the arguments and exit status.

set -o pipefail
. "$(dirname "$0")/check-common.sh"
guard_init "$@"

list=.claude/failing-first.txt

# Test files listed in failing-first.txt: the first word of each line that isn't blank or a comment.
entries() { sed -e 's/#.*//' | awk 'NF { print $1 }' | sort -u; }

before=$(git show "$GUARD_FROM:$list" 2>/dev/null | entries)
after=$([ -f "$list" ] && entries <"$list")
removed=$(comm -23 <(printf '%s\n' "$before" | sed '/^$/d') <(printf '%s\n' "$after" | sed '/^$/d'))

[ -n "$removed" ] || exit 0

rule="A fix must make the test pass as it is (CLAUDE.md rule 5)"
printf '%s\n' "$removed" | while IFS= read -r test; do
  if [ ! -f "$test" ]; then
    printf '%s\t1\t%s\t%s\n' "$list" "Failing-first test deleted" "$test is removed from $list and deleted. $rule"
  elif ! git diff --quiet "$GUARD_FROM" -- "$test" ||
    [ -n "$(git ls-files --others --exclude-standard -- "$test")" ]; then
    printf '%s\t1\t%s\t%s\n' "$test" "Failing-first test changed" \
      "$test is removed from $list and changed in the same change. $rule"
  fi
done | guard_report
