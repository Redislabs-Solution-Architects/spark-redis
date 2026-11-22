#!/usr/bin/env bash
# Fails when new commits or changed files contain a secret, using gitleaks.
#
#   scripts/check-secrets.sh [--base <ref>] [<path>...]
#
# With --base, scans the commits since the merge base (gitleaks git), plus uncommitted and untracked files.
# Without it, scans uncommitted and untracked files only (gitleaks dir), which is what the edit hook uses.
# Runs a local gitleaks if there is one, otherwise the pinned container image ($GITLEAKS_IMAGE). Outside CI, a
# machine with neither skips the scan with a warning. Needs jq.
# See scripts/check-common.sh for the arguments and exit status.

set -o pipefail
. "$(dirname "$0")/check-common.sh"
guard_init "$@"

GITLEAKS_IMAGE=${GITLEAKS_IMAGE:-ghcr.io/gitleaks/gitleaks:v8.30.1}

if command -v gitleaks >/dev/null 2>&1; then
  runner=local
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  runner=docker
elif [ "${GITHUB_ACTIONS:-}" = "true" ] || [ -n "${CI:-}" ]; then
  guard_die "neither gitleaks nor a running Docker is available"
else
  echo "$GUARD_NAME: skipped: neither gitleaks nor a running Docker is available" >&2
  exit 0
fi
command -v jq >/dev/null 2>&1 || guard_die "jq is required"

root=$(pwd -P)
common=$(cd "$(git rev-parse --git-common-dir)" && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/check-secrets.XXXXXX") || guard_die "mktemp failed"
work=$(cd "$work" && pwd -P)
chmod 777 "$work"
trap 'rm -rf "$work"' EXIT

# Runs gitleaks. The repository, its git directory (a worktree's lives elsewhere) and $work keep their absolute
# paths inside the container.
run_gitleaks() {
  if [ "$runner" = local ]; then
    gitleaks "$@"
  else
    docker run --rm \
      -v "$root:$root:ro" -v "$common:$common:ro" -v "$work:$work" -w "$root" \
      -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
      "$GITLEAKS_IMAGE" "$@"
  fi
}

common_flags="--no-banner --redact --log-level error --exit-code 0 --report-format json"

# 1. Commits since the merge base.
if [ -n "$GUARD_BASE" ] && [ "$GUARD_FROM" != "$(git rev-parse HEAD)" ]; then
  log_opts="$GUARD_FROM..HEAD"
  if [ ${#GUARD_PATHS[@]} -gt 0 ]; then
    log_opts="$log_opts -- ${GUARD_PATHS[*]}"
  fi
  # shellcheck disable=SC2086
  run_gitleaks git $common_flags --report-path "$work/git.json" --log-opts="$log_opts" "$root" ||
    guard_die "gitleaks git failed"
fi

# 2. Files that differ from HEAD, and untracked files.
mkdir -p "$work/files"
{
  git -c core.quotePath=false diff --name-only --diff-filter=AMR HEAD -- ${GUARD_PATHS[@]+"${GUARD_PATHS[@]}"}
  guard_untracked
} | sort -u | while IFS= read -r f; do
  [ -f "$f" ] || continue
  mkdir -p "$work/files/$(dirname "$f")" && cp -p "$f" "$work/files/$f"
done
if [ -n "$(ls -A "$work/files")" ]; then
  # shellcheck disable=SC2086
  run_gitleaks dir $common_flags --report-path "$work/dir.json" "$work/files" || guard_die "gitleaks dir failed"
fi

for report in "$work/git.json" "$work/dir.json"; do
  [ -s "$report" ] || continue
  jq -r --arg prefix "$work/files/" '
    .[] | [
      (.File | ltrimstr($prefix)),
      (.StartLine | tostring),
      "Possible secret",
      ("possible secret (gitleaks rule " + .RuleID + ")" +
        (if (.Commit // "") != "" then " in commit " + .Commit[0:12] else "" end) +
        ": remove it, and rotate it if it is real")
    ] | @tsv
  ' "$report"
done | guard_report
