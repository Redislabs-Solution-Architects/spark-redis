#!/usr/bin/env bash
# Fails when added code reaches into Spark internals, or adds code under org.apache.spark.
#
#   scripts/check-spark-internal-imports.sh [--base <ref>] [<path>...]
#
# Flags, in added lines of .scala and .java files:
#   - imports and fully qualified references to org.apache.spark.sql.catalyst or org.apache.spark.sql.execution that
#     scripts/spark-internal-allowlist.txt doesn't allow
#   - a new file under an org/apache/spark/ source directory, or a package declaration that moves code there
#     (new code goes in com.redis.sparkredis)
# See scripts/check-common.sh for the arguments and exit status.

set -o pipefail
. "$(dirname "$0")/check-common.sh"
allowlist="$(cd "$(dirname "$0")" && pwd -P)/spark-internal-allowlist.txt"
guard_init "$@"
[ -f "$allowlist" ] || guard_die "missing $allowlist"

{
  guard_added_files |
    awk '/(^|\/)org\/apache\/spark\// && /\.(scala|java)$/ {
      printf "%s\t1\t%s\t%s\n", $0, "Code under org.apache.spark", \
        "new file under org/apache/spark: new code goes in com.redis.sparkredis (CLAUDE.md rule 2)"
    }'

  guard_added_lines |
    awk -F '\t' -v allowlist="$allowlist" '
      BEGIN {
        while ((getline entry < allowlist) > 0) {
          sub(/#.*/, "", entry); gsub(/[ \t\r]/, "", entry)
          if (entry != "") allowed[++n] = entry
        }
      }
      function ok(ref,   i) {
        for (i = 1; i <= n; i++) {
          if (ref == allowed[i] || index(ref, allowed[i] ".") == 1) return 1
        }
        return 0
      }
      function flag(ref) {
        printf "%s\t%s\t%s\t%s\n", $1, $2, "Spark internal API", \
          "Spark internal API " ref " is not in scripts/spark-internal-allowlist.txt: use a public DSv2 or Connector API (spec section 3)"
      }
      $1 !~ /\.(scala|java)$/ { next }
      {
        text = $0
        sub(/^[^\t]*\t[^\t]*\t/, "", text)
        sub(/\/\/.*/, "", text)

        # Files under org/apache/spark/ are 3.x code (or new files, reported above by path).
        if ($1 !~ /(^|\/)org\/apache\/spark\// && text ~ /^[ \t]*package[ \t]+org[.]apache[.]spark([.;\t ]|$)/) {
          printf "%s\t%s\t%s\t%s\n", $1, $2, "Code under org.apache.spark", \
            "package under org.apache.spark: new code goes in com.redis.sparkredis (CLAUDE.md rule 2)"
          next
        }
        if (text !~ /org[.]apache[.]spark[.]sql[.](catalyst|execution)/) next

        # Grouped Scala import: import a.b.{C, D => E, _}
        if (match(text, /^[ \t]*import[ \t]+[A-Za-z0-9_.]+[.][{][^}]*[}]/)) {
          stmt = substr(text, RSTART, RLENGTH)
          sub(/^[ \t]*import[ \t]+/, "", stmt)
          pkg = stmt; sub(/[.][{].*/, "", pkg)
          if (pkg !~ /^org[.]apache[.]spark[.]sql[.](catalyst|execution)/) next
          members = stmt; sub(/^[^{]*[{]/, "", members); sub(/[}].*/, "", members)
          count = split(members, parts, ",")
          for (j = 1; j <= count; j++) {
            m = parts[j]; sub(/=>.*/, "", m); gsub(/[ \t]/, "", m)
            if (m == "") continue
            if (!ok(pkg "." m)) flag(pkg "." m)
          }
          next
        }
        # Any other import or reference: check each fully qualified name on the line.
        while (match(text, /org[.]apache[.]spark[.]sql[.](catalyst|execution)([.][A-Za-z0-9_]+)*/)) {
          ref = substr(text, RSTART, RLENGTH)
          text = substr(text, RSTART + RLENGTH)
          if (!ok(ref)) flag(ref)
        }
      }
    '
} | guard_report
