/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.tools.drift;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rewrite rules, against a source tree shaped like the three maintained lines.
 */
class DefaultPluginDriftRepairTest {

    @TempDir
    Path root;

    private Path constants;
    private Path bindings;
    private Path pom;
    private Path itFixture;
    private Path unitTest;

    @BeforeEach
    void layOutASourceTree() throws IOException {
        // 4.x: Java constants, in the two files that carry them
        constants = write(
                "impl/maven-core/src/main/java/org/apache/maven/AbstractLifecycleMappingProvider.java",
                """
                protected static final String JAR_PLUGIN_VERSION = "3.4.2";
                private static final String MAVEN_CLEAN_PLUGIN_VERSION = "3.4.0";
                """);
        // 3.9.x: the XML default bindings
        bindings = write(
                "maven-core/src/main/resources/META-INF/plexus/default-bindings.xml",
                "<configuration>org.apache.maven.plugins:maven-jar-plugin:3.5.0:jar</configuration>");
        // 3.10.x: a POM property
        pom = write("pom.xml", "<version.maven-jar-plugin>3.5.0</version.maven-jar-plugin>");
        // an IT fixture that pins an old version on purpose
        itFixture = write(
                "its/core-it-support/plugin/src/main/resources/META-INF/plexus/components.xml",
                "<configuration>org.apache.maven.plugins:maven-jar-plugin:2.2</configuration>");
        // a unit test that asserts against a version
        unitTest = write(
                "impl/maven-core/src/test/java/org/apache/maven/SomeTest.java",
                "assertEquals(\"3.4.2\", JAR_PLUGIN_VERSION);");
    }

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private Map<String, java.util.List<String>> repair(Map<String, String> targets) throws IOException {
        return new DefaultPluginDriftRepair(root, false).apply(targets);
    }

    @Test
    void rewritesJavaConstantsWithAndWithoutTheMavenPrefix() throws IOException {
        repair(Map.of("maven-jar-plugin", "3.5.1", "maven-clean-plugin", "3.5.0"));
        String content = Files.readString(constants);
        assertTrue(content.contains("JAR_PLUGIN_VERSION = \"3.5.1\""), content);
        assertTrue(content.contains("MAVEN_CLEAN_PLUGIN_VERSION = \"3.5.0\""), content);
    }

    @Test
    void rewritesTheXmlDefaultBindings() throws IOException {
        repair(Map.of("maven-jar-plugin", "3.5.1"));
        assertTrue(Files.readString(bindings).contains("maven-jar-plugin:3.5.1:jar"));
    }

    @Test
    void rewritesThePomProperty() throws IOException {
        repair(Map.of("maven-jar-plugin", "3.5.1"));
        assertTrue(Files.readString(pom).contains("<version.maven-jar-plugin>3.5.1</version.maven-jar-plugin>"));
    }

    @Test
    void leavesIntegrationTestFixturesAlone() throws IOException {
        repair(Map.of("maven-jar-plugin", "3.5.1"));
        assertTrue(Files.readString(itFixture).contains("maven-jar-plugin:2.2"), "an IT fixture pins on purpose");
    }

    @Test
    void leavesUnitTestsAlone() throws IOException {
        repair(Map.of("maven-jar-plugin", "3.5.1"));
        assertTrue(Files.readString(unitTest).contains("\"3.4.2\""), "a test asserts against a version on purpose");
    }

    @Test
    void reportsEveryFileItTouched() throws IOException {
        Map<String, java.util.List<String>> changes = repair(Map.of("maven-jar-plugin", "3.5.1"));
        assertEquals(3, changes.get("maven-jar-plugin").size(), changes.toString());
    }

    @Test
    void aVersionThatIsAlreadyCurrentIsLocatedButNotChanged() throws IOException {
        DefaultPluginDriftRepair repair = new DefaultPluginDriftRepair(root, false);
        Map<String, java.util.List<String>> changes = repair.apply(Map.of("maven-clean-plugin", "3.4.0"));
        assertTrue(changes.isEmpty(), "nothing differed");
        assertTrue(repair.located().contains("maven-clean-plugin"), "but the site was found");
    }

    @Test
    void aPluginWithoutAVersionSiteIsNotLocated() throws IOException {
        DefaultPluginDriftRepair repair = new DefaultPluginDriftRepair(root, false);
        repair.apply(Map.of("maven-shade-plugin", "3.6.2"));
        assertFalse(repair.located().contains("maven-shade-plugin"), "so the run fails instead of doing nothing");
    }

    @Test
    void dryRunChangesNothingOnDisk() throws IOException {
        String before = Files.readString(constants);
        Map<String, java.util.List<String>> changes =
                new DefaultPluginDriftRepair(root, true).apply(Map.of("maven-jar-plugin", "3.5.1"));
        assertFalse(changes.isEmpty(), "it still reports what it would do");
        assertEquals(before, Files.readString(constants));
    }

    @Test
    void onlyMainSourcesAndPomsAreVersionSites() {
        assertTrue(DefaultPluginDriftRepair.isVersionSite(Path.of("/x/pom.xml")));
        assertTrue(DefaultPluginDriftRepair.isVersionSite(Path.of("/x/src/main/java/A.java")));
        assertTrue(DefaultPluginDriftRepair.isVersionSite(Path.of("/x/src/main/resources/b.xml")));
        assertFalse(DefaultPluginDriftRepair.isVersionSite(Path.of("/x/target/classes/b.xml")));
        assertFalse(DefaultPluginDriftRepair.isVersionSite(Path.of("/x/src/test/java/A.java")));
        assertFalse(DefaultPluginDriftRepair.isVersionSite(Path.of("/x/its/y/src/main/resources/b.xml")));
        assertFalse(DefaultPluginDriftRepair.isVersionSite(Path.of("/x/src/main/java/A.txt")));
    }
}
