# Builds ci.yml's job matrices from versions.yml (as JSON), as {"include": [{spark, redis, jdk}, ...]}.
#   jq --arg which pr   -f .github/matrix.jq versions.json   # what a pull request must pass
#   jq --arg which full -f .github/matrix.jq versions.json   # everything else, after each merge
# The full matrix is every Spark line on every supported Redis line with the `jdk`, plus every Spark line on the
# `redis` line with each `jdk_secondary`, minus the pull-request jobs, so each combination runs once.

def combos($sparks; $redises; $jdks):
  [$sparks[] as $s | $redises[] as $r | $jdks[] as $j | {spark: $s, redis: $r, jdk: $j}];

combos([.spark_reference]; .redis; .jdk) as $pr
| if $which == "pr" then
    {include: $pr}
  elif $which == "full" then
    {include: ((combos(.spark; .redis + (.redis_secondary // []); .jdk)
                + combos(.spark; .redis; (.jdk_secondary // [])))
               | map(select(IN($pr[]) | not)))}
  else
    error("--arg which must be pr or full")
  end
