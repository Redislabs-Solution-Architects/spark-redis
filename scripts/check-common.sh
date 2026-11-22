#!/usr/bin/env bash
# Shared helpers for the scripts/check-*.sh guardrails. Source this file; don't run it.
#
# Every check takes the same arguments:
#
#   scripts/check-<name>.sh [--base <ref>] [<path>...]
#
#   --base <ref>  Check what changed since the merge base of <ref> and HEAD, including uncommitted and untracked
#                 files. CI passes the PR's base branch, for example --base origin/spike/phase-0.
#   (no --base)   Check what changed since HEAD: uncommitted edits and untracked files. The edit hook uses this.
#   <path>...     Only check these files.
#
# Only added lines are checked, because the 3.x code still contains the patterns until Phase 1 replaces them.
# Findings are printed as "path:line: message" on stderr, and as error annotations when running in GitHub Actions.
# Exit status: 0 clean, 1 findings, 2 usage or git error.
#
# Written for the bash 3.2 that macOS ships.

GUARD_BASE=""
GUARD_FROM=""
GUARD_PATHS=()

guard_die() {
  echo "$GUARD_NAME: $*" >&2
  exit 2
}

guard_usage() {
  sed -n '2,/^$/s/^# \{0,1\}//p' "$0" >&2
  exit 2
}

# Parses the shared arguments, then changes to the repository root. Paths become relative to the root.
guard_init() {
  GUARD_NAME=$(basename "$0" .sh)
  local cwd root prefix p dir abs
  cwd=$(pwd -P)
  root=$(git rev-parse --show-toplevel 2>/dev/null) || guard_die "not inside a git repository"
  root=$(cd "$root" && pwd -P)
  prefix=$(git rev-parse --show-prefix 2>/dev/null)

  while [ $# -gt 0 ]; do
    case "$1" in
      --base)
        [ $# -ge 2 ] || guard_die "--base needs a ref"
        GUARD_BASE=$2
        shift 2
        ;;
      --base=*)
        GUARD_BASE=${1#--base=}
        shift
        ;;
      -h | --help)
        guard_usage
        ;;
      --)
        shift
        break
        ;;
      -*)
        guard_die "unknown option: $1"
        ;;
      *)
        break
        ;;
    esac
  done

  for p in "$@"; do
    case "$p" in
      /*)
        dir=$(dirname "$p")
        if [ -d "$dir" ]; then
          abs="$(cd "$dir" && pwd -P)/$(basename "$p")"
        else
          abs=$p
        fi
        case "$abs" in
          "$root"/*) GUARD_PATHS+=("${abs#"$root"/}") ;;
          *) ;; # outside this repository: nothing to check
        esac
        ;;
      *)
        GUARD_PATHS+=("$prefix$p")
        ;;
    esac
  done

  cd "$root" || guard_die "cannot cd to $root"

  # Paths were given, but none of them are in this repository.
  if [ $# -gt 0 ] && [ ${#GUARD_PATHS[@]} -eq 0 ]; then
    exit 0
  fi

  # The commit the working tree is compared with. Resolved here, in the main shell, so that a bad ref stops the
  # check instead of looking like a clean diff inside a pipeline.
  if [ -n "$GUARD_BASE" ]; then
    git rev-parse --verify --quiet "$GUARD_BASE^{commit}" >/dev/null || guard_die "unknown ref: $GUARD_BASE"
    GUARD_FROM=$(git merge-base "$GUARD_BASE" HEAD 2>/dev/null) ||
      guard_die "no merge base between $GUARD_BASE and HEAD (a shallow clone needs fetch-depth: 0)"
  else
    GUARD_FROM=$(git rev-parse --verify --quiet HEAD) || guard_die "no HEAD commit"
  fi
}

# Untracked, not ignored files among the checked paths.
guard_untracked() {
  git ls-files --others --exclude-standard -- ${GUARD_PATHS[@]+"${GUARD_PATHS[@]}"}
}

# Prints "path<TAB>line<TAB>text" for every added line among the checked paths.
guard_added_lines() {
  local f
  git -c core.quotePath=false diff --no-color --no-ext-diff --unified=0 --diff-filter=AMR \
    --src-prefix=a/ --dst-prefix=b/ "$GUARD_FROM" -- ${GUARD_PATHS[@]+"${GUARD_PATHS[@]}"} |
    awk '
      /^\+\+\+ / { file = substr($0, 5); sub(/^b\//, "", file); next }
      /^@@ /     { match($0, /\+[0-9]+/); line = substr($0, RSTART + 1, RLENGTH - 1) + 0; next }
      /^\+/      { print file "\t" line "\t" substr($0, 2); line++ }
    '
  guard_untracked | while IFS= read -r f; do
    [ -f "$f" ] && grep -Iq . "$f" 2>/dev/null || continue
    awk -v f="$f" '{ print f "\t" NR "\t" $0 }' "$f"
  done
}

# Prints the paths of files added (new, renamed or untracked) among the checked paths.
guard_added_files() {
  {
    git -c core.quotePath=false diff --name-only --no-renames --diff-filter=A "$GUARD_FROM" -- \
      ${GUARD_PATHS[@]+"${GUARD_PATHS[@]}"}
    guard_untracked
  } | sort -u
}

# Prints the paths of files added or modified among the checked paths, that still exist.
guard_changed_files() {
  local f
  {
    git -c core.quotePath=false diff --name-only --diff-filter=AMR "$GUARD_FROM" -- ${GUARD_PATHS[@]+"${GUARD_PATHS[@]}"}
    guard_untracked
  } | sort -u | while IFS= read -r f; do
    [ -f "$f" ] && echo "$f"
  done
}

# Reads "path<TAB>line<TAB>title<TAB>message" findings on stdin and reports them.
# Returns 1 if there were any.
guard_report() {
  local found=0 file line title msg
  while IFS="$(printf '\t')" read -r file line title msg; do
    [ -n "$file" ] || continue
    found=$((found + 1))
    echo "$file:$line: $msg" >&2
    if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
      echo "::error file=$file,line=$line,title=$title::$msg"
    fi
  done
  if [ "$found" -gt 0 ]; then
    echo "$GUARD_NAME: $found finding(s)." >&2
    return 1
  fi
  return 0
}
