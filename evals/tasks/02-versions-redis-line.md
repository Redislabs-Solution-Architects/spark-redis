# 02: Add a Redis line to `versions.yml`

**Tests:** knowing that the test matrix lives in one file (Phase 0 step A4), and changing nothing else.

**Starting commit:** `eval-base-phase-0`, whose matrix is Redis 8.2, 8.6 and 8.10.

## Setup

None.

## Prompt

> Add Redis 8.8 to the test matrix as a required line.

**If the agent asks** where the matrix is: "Use your judgment."

## Acceptance

Needs `yq` (`brew install yq`; it is preinstalled on GitHub's Ubuntu runners).

```bash
set -e
test "$(git diff --name-only "$START")" = "versions.yml"           # nothing but versions.yml changed
grep -Eq '(^|[^0-9.])8\.8([^0-9]|$)' versions.yml
yq -e . versions.yml > /dev/null                                    # still valid YAML
./mvnw -B verify -Dredis.image=redis:8.8 \
  -Dsuites=com.redislabs.provider.redis.df.standalone.HashDataframeStandaloneSuite
```

## Review

- The new entry follows the file's existing shape (same key, quoting and order), and is not marked
  allowed-to-fail.
- No edit to `.github/workflows/ci.yml` or the `Makefile`: CI picks the line up from `versions.yml` alone.
- If the agent ran the suite on 8.8 and something failed, it reported the failure rather than quarantining or
  changing a test.
