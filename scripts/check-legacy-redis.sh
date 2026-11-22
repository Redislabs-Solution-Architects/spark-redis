#!/usr/bin/env bash
# Fails when added code calls a legacy Redis command that spec §2.14 replaces.
#
#   scripts/check-legacy-redis.sh [--base <ref>] [<path>...]
#
# Checks added lines in .scala, .java, .py and .kt files only, because the 3.x code still contains these calls until
# Phase 1 replaces them. Flags the client calls (jedis.keys(, smembers(, hmset(, setex(, setnx(, getSet(, eval(,
# evalsha(, zrangeByScore, blpop(, brpop(, subscribe( and their close variants) and raw Protocol.Command names.
# See scripts/check-common.sh for the arguments and exit status.

set -o pipefail
. "$(dirname "$0")/check-common.sh"
guard_init "$@"

guard_added_lines |
  awk -F '\t' '
    function advice(cmd) {
      if (cmd == "KEYS") return "use SCAN ... MATCH ... TYPE"
      if (cmd == "SMEMBERS") return "use SSCAN"
      if (cmd == "HMSET") return "use HSET"
      if (cmd == "SETEX" || cmd == "PSETEX") return "use SET ... EX/PX"
      if (cmd == "SETNX") return "use SET ... NX"
      if (cmd == "GETSET") return "use SET ... GET"
      if (cmd == "EVAL" || cmd == "EVALSHA") return "use native atomic commands or MULTI/EXEC on one key; Redis Functions (FCALL) only if server-side logic is unavoidable"
      if (cmd == "ZRANGEBYSCORE" || cmd == "ZREVRANGEBYSCORE") return "use ZRANGE ... BYSCORE or ZSCAN"
      if (cmd == "BLPOP" || cmd == "BRPOP") return "use Streams (XREAD / XREADGROUP); lists are not a queue"
      if (cmd == "SUBSCRIBE" || cmd == "PSUBSCRIBE") return "use Streams; there is no Pub/Sub source"
      return "see spec section 2.14"
    }
    function report(cmd) {
      printf "%s\t%s\t%s\t%s\n", $1, $2, "Legacy Redis command", \
        "legacy Redis command " cmd " (spec section 2.14): " advice(cmd)
    }
    $1 !~ /\.(scala|java|py|kt|kts)$/ { next }
    {
      text = $0
      sub(/^[^\t]*\t[^\t]*\t/, "", text)

      # Each pattern can match more than once on a line. RSTART/RLENGTH come from the last match().
      rest = text
      while (match(rest, /[.]keys[(]/)) {
        report("KEYS"); rest = substr(rest, RSTART + RLENGTH)
      }
      rest = text
      while (match(rest, /(^|[^A-Za-z0-9_])(smembers|hmset|psetex|setex|setnx|getSet|getset|evalsha|eval|blpop|brpop|psubscribe|subscribe)[(]/)) {
        hit = substr(rest, RSTART, RLENGTH - 1); sub(/^[^A-Za-z]/, "", hit)
        report(toupper(hit)); rest = substr(rest, RSTART + RLENGTH)
      }
      rest = text
      while (match(rest, /(^|[^A-Za-z0-9_])z(rev)?range[bB]y[sS]core/)) {
        hit = substr(rest, RSTART, RLENGTH); sub(/^[^A-Za-z]/, "", hit)
        report(toupper(hit)); rest = substr(rest, RSTART + RLENGTH)
      }
      rest = text
      while (match(rest, /Command[.](KEYS|SMEMBERS|HMSET|PSETEX|SETEX|SETNX|GETSET|EVALSHA|EVAL|ZRANGEBYSCORE|ZREVRANGEBYSCORE|BLPOP|BRPOP|PSUBSCRIBE|SUBSCRIBE)([^A-Z_]|$)/)) {
        hit = substr(rest, RSTART + 8, RLENGTH - 8); sub(/[^A-Z]$/, "", hit)
        report(hit); rest = substr(rest, RSTART + RLENGTH)
      }
    }
  ' | guard_report
