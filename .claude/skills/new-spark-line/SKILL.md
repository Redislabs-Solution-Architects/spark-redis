---
name: new-spark-line
description: The checklist for adding a new Apache Spark minor line to spark-redis 4.x, or dropping one at its upstream EOL. Use when Spark ships a new 4.x minor (an RC or GA), when a supported line reaches end of life, or when someone asks which files a Spark line touches. Spark ships quarterly, so this runs about once a month.
---

# Adding or dropping a Spark line

> Phase 1 plan steps B1 (the per-Spark modules) and B2 (the CI matrix) built what this skill describes. CI reads
> `versions.yml` through `.github/matrix.jq`, so adding a line there adds its jobs; `scripts/spark-lines.sh` prints
> the lines for commands and docs.

spark-redis 4.x publishes **one artifact per supported Spark minor**, for example
`com.redis:spark-redis-spark-4.2_2.13` (spec §2, "Build"). All artifacts share one source tree. A thin overlay holds
anything that needs a newer Spark interface. The policy is in spec §1, "Version policy":

- **Add** a line in the first connector minor release after its GA. Run CI against its RCs before that.
- **Drop** a line in the first connector minor release after its upstream EOL.
- Support only lines inside their published maintenance window. Check
  [Spark's versioning policy](https://spark.apache.org/versioning-policy.html) and the release dates before you
  start.

## Adding a line

Do these in one PR, in this order:

1. **Module.** Copy the newest `spark-4.x/pom.xml` to `spark-4.<n>/pom.xml`. Change:
   - the artifactId to `spark-redis-spark-4.<n>_2.13`, and the `<name>`;
   - `spark.line` to `spark-4.<n>`, which picks the overlay directory;
   - `spark.version` to the new line's latest patch;
   - `scala.complete.version` to the Scala version that Spark release builds with. Read it from
     `spark-parent_2.13-<version>.pom` (`<scala.version>`) on Maven Central; don't guess.

   Add the module to the parent pom's `<modules>`. The plugins are configured in the parent's `pluginManagement`,
   so the module only lists them.
2. **JVM options.** Compare the new line's `org.apache.spark.launcher.JavaModuleOptions` (in its `spark-launcher`
   jar) with the parent pom's `spark.test.jvm.options`. If the test JVM needs a new option on JDK 17, add it there.
3. **Overlay.** Create `src/spark-4.<n>/scala/` with the same one-line README as the other overlays. Put code there
   only when the shared source can't compile against this line, or when the line adds an interface we use
   (spec §1, "Anything newer is capability-gated"). Keep each overlay as small as possible.
4. **`versions.yml`.** Add the line to `spark`. CI then tests it on every supported Redis line with `jdk`, and on
   `jdk_secondary` with every required Redis line. Don't copy the list anywhere else: CI and the scripts read this file.
5. **Build and test.** Run `./mvnw -B verify -pl spark-4.<n>` on JDK 17, with each required Redis line. The golden and
   legacy fixtures must pass unchanged. A failure is a finding: fix the code, never the test or a fixture.
6. **Docs.** Add the line to the README's compatibility table and to the release notes of the next connector
   minor.
7. **Spark internals.** If the new line moved or changed a class in `scripts/spark-internal-allowlist.txt`, say so
   in the PR. The shim belongs in the overlay, not in the shared source.

## Dropping a line

1. Delete `spark-4.<n>/` and `src/spark-4.<n>/`, and remove the module from the parent pom.
2. Remove the line from `versions.yml`.
3. If the dropped line was the floor, look for code that only existed for it: a shim in another overlay, or a
   capability check that the new floor makes unconditional (spec §1, "What the floor buys us").
4. Update the README table and the release notes.

## Proof for the PR

- `./mvnw -B verify -pl spark-4.<n>` passes for the new line, and the other modules still pass.
- `./mvnw -B install -DskipTests` produces the new artifact with the expected coordinates.
- A push to `spike/phase-1` runs the full matrix with the new line in it.
