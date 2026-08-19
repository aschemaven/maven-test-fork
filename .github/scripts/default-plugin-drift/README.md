<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
-->

# Default plugin version drift

Maven binds a fixed version to every plugin of the `default`, `clean` and `site` lifecycles. Those
versions are a decision, not a dependency: nothing in the build fails when they fall behind, so they
quietly age. This tool notices, and can rewrite them.

It is not a module of the Maven build and is never released — it inspects a *built distribution* and
a checkout, on demand or from the weekly
[`Default plugin version drift`](../../workflows/default-plugin-drift.yml) workflow.

## Check a distribution

```bash
cd .github/scripts/default-plugin-drift
mvn                                            # the Maven that runs this build
mvn -Ddrift.maven=/path/to/apache-maven-4.0.0  # any unpacked distribution
mvn -Ddrift.args=--fail-on-drift               # exit non-zero on drift, for CI
mvn -Ddrift.args=--properties                  # artifactId=version, for the repair step
```

`drift.maven` is a Maven home or an `mvn` executable — an unpacked release, a distribution built
from a branch (`apache-maven/target/apache-maven-bin.zip`, unzipped), or whatever your version
manager put on disk. The workflow unpacks the archive it just built; nothing here depends on a
version manager being installed.

```
=== …/4.0.0-rc-6/bin/mvn
  maven-clean-plugin         3.4.0      -> 3.5.0   DRIFT
  maven-compiler-plugin      3.13.0     -> 3.15.0  DRIFT
  maven-jar-plugin           3.4.2      -> 3.5.1   DRIFT
  …
```

Reporting drift is a finding, not an error, so a plain `mvn` prints the table and succeeds. Only
`--fail-on-drift` turns it into a build failure.

### How the current versions are found

By running `help:effective-pom` on an empty project with the distribution under test and reading the
versions Maven injected — not by parsing the sources. That works for any Maven, released or built
from a branch, 3.x or 4.x, and keeps working if the way core stores those versions changes again.

### What counts as a candidate

The newest version on Central whose qualifier is not a preview and whose major is below
`--max-major` (4 by default). "Preview" is decided by `GenericQualifiers` from maven-resolver, the
qualifier table Maven's own version scheme uses, so this tool cannot disagree with the resolver
about what `1.0-beta-1`, `1.0-sp1` or `33.7.0-jre` mean.

## Rewrite a checkout

```bash
mvn -Prepair -Ddrift.args="--source=/path/to/checkout --report=latest.properties --dry-run"
```

```
maven-jar-plugin -> 3.5.1
    impl/maven-core/…/AbstractLifecycleMappingProvider.java: 3.4.2 -> 3.5.1
```

The maintained lines store the defaults in three different ways, so the rewrite matches them by
shape rather than by file name:

| shape | line |
|---|---|
| `org.apache.maven.plugins:maven-jar-plugin:3.5.0` | 3.9.x, in the XML default bindings |
| `<version.maven-jar-plugin>3.5.0</version.maven-jar-plugin>` | 3.10.x, a POM property filtered into `plugin-versions.properties` |
| `[MAVEN_]JAR_PLUGIN_VERSION = "3.4.2"` | 4.x, Java constants in two files |

Deliberate exclusions: `target/`, `src/test/` and `its/`. A fixture that pins an old version pins it
on purpose, and rewriting one would silently change what a test proves.

A plugin named in the report whose version site cannot be found **fails the run**. A repair that
skips a plugin without saying so is worse than no repair; being already at the wanted version is a
no-op, not a miss.

## Tests

```bash
mvn test
```

24 tests, no network and no Maven installation needed: they drive the parsing and selection rules
with a canned `maven-metadata.xml` and a canned `help:effective-pom` log.

## What this does not do

It detects and rewrites. It does not decide *whether* a bump is safe — a patch release can still
change behaviour, so a full build belongs between the pull request and the merge.
