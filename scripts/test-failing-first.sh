#!/usr/bin/env bash
# Tests the failing-first guardrail, .claude/hooks/protect-paths.sh and scripts/check-failing-first.sh, against a
# throwaway git repository:
#
#   scripts/test-failing-first.sh [<checkout>]    # default: the checkout this script is in
#
# Prints one line per case and exits 1 if any case fails. Needs jq or python3, like the hook.
set -u
G=${1:-$(cd "$(dirname "$0")/.." && pwd)}
T=$(mktemp -d "${TMPDIR:-/tmp}/ffcheck.XXXX")
fails=0
expect() { # expect <label> <wanted exit> <actual exit>
  if [ "$2" = "$3" ]; then echo "ok    $1 (exit $3)"; else echo "FAIL  $1: wanted exit $2, got $3"; fails=$((fails + 1)); fi
}
hook() { # hook <json>: runs the hook as Claude Code would, in the throwaway repo
  printf '%s' "$1" | CLAUDE_PROJECT_DIR=$T bash "$G/.claude/hooks/protect-paths.sh" >/dev/null 2>"$T.err"
}
edit() { printf '{"tool_name":"Edit","cwd":"%s","tool_input":{"file_path":"%s","old_string":"%s","new_string":"%s"}}' "$T" "$1" "$2" "$3"; }

cd "$T" && git init -q && git config user.email t@example.com && git config user.name t
mkdir -p .claude scripts src/test/A src/test/resources/golden
cp "$G/scripts/check-common.sh" "$G/scripts/check-failing-first.sh" scripts/
printf '# header\nsrc/test/A/ASuite.scala  # #1\nsrc/test/A/BSuite.scala  # #2\n' > .claude/failing-first.txt
echo 'class ASuite' > src/test/A/ASuite.scala
echo 'class BSuite' > src/test/A/BSuite.scala
echo 'class CSuite' > src/test/A/CSuite.scala
git add -A && git commit -qm base && git branch base

echo "-- hook"
hook "$(edit "$T/.claude/failing-first.txt" 'src/test/A/ASuite.scala  # #1\\n' '')"; expect "removing a line is allowed" 0 $?
hook "$(edit "$T/src/test/A/ASuite.scala" 'class ASuite' 'class ASuite // weaker')"; expect "a listed test is protected" 2 $?
grep -v ASuite .claude/failing-first.txt > .claude/ff.tmp && mv .claude/ff.tmp .claude/failing-first.txt
hook "$(edit "$T/src/test/A/ASuite.scala" 'class ASuite' 'class ASuite // weaker')"; expect "removed in the working tree, still listed at HEAD: protected" 2 $?
git commit -qam "fix #1: remove ASuite's line"
hook "$(edit "$T/src/test/A/ASuite.scala" 'class ASuite' 'class ASuite // later')"; expect "removal committed: no longer protected by the hook" 0 $?
hook "$(edit "$T/src/test/A/CSuite.scala" 'class CSuite' 'class CSuite // ok')"; expect "an unlisted test is editable" 0 $?
hook '{"tool_name":"Bash","tool_input":{"command":"sed -i \"\" /BSuite/d .claude/failing-first.txt"}}'; expect "a shell edit of the list is blocked" 2 $?
grep -q 'Edit or Write, not a shell command' "$T.err"; expect "  ... with the new message" 0 $?
hook "$(edit "$T/src/test/resources/golden/x.json" '{}' '{\"a\":1}')"; expect "golden fixtures still protected" 2 $?
hook '{"tool_name":"Bash","tool_input":{"command":"./mvnw verify -Dgolden.update=true"}}'; expect "golden.update still blocked" 2 $?
hook '{"tool_name":"Bash","tool_input":{"command":"echo x > src/test/A/BSuite.scala"}}'; expect "a shell write to a listed test is blocked" 2 $?

echo "-- scripts/check-failing-first.sh --base base"
scripts/check-failing-first.sh --base base 2>/dev/null; expect "line removed, test unchanged" 0 $?
echo '// weaker' >> src/test/A/ASuite.scala
scripts/check-failing-first.sh --base base 2>"$T.err"; expect "line removed, test changed (uncommitted)" 1 $?
grep -q 'ASuite.scala is removed from .claude/failing-first.txt and changed' "$T.err"; expect "  ... names the test" 0 $?
git commit -qam "weaken ASuite"
scripts/check-failing-first.sh --base base 2>/dev/null; expect "line removed, test changed (committed)" 1 $?
git reset -q --hard HEAD~1
git rm -q src/test/A/ASuite.scala
scripts/check-failing-first.sh --base base 2>/dev/null; expect "line removed, test deleted" 1 $?
git reset -q --hard HEAD
echo '// edit' >> src/test/A/CSuite.scala
scripts/check-failing-first.sh --base base 2>/dev/null; expect "unlisted test changed" 0 $?
git checkout -q -- src/test/A/CSuite.scala
printf 'src/test/A/CSuite.scala  # #3\n' >> .claude/failing-first.txt
scripts/check-failing-first.sh --base base 2>/dev/null; expect "a line added" 0 $?
scripts/check-failing-first.sh --base nope 2>/dev/null; expect "an unknown base is a usage error" 2 $?

rm -rf "$T" "$T.err"
echo "failures: $fails"
[ "$fails" = 0 ]
