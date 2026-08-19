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
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parsing and selection rules, exercised without touching the network or a Maven installation.
 */
class DefaultPluginDriftCheckTest {

    private static final String METADATA =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <metadata>
              <groupId>org.apache.maven.plugins</groupId>
              <artifactId>maven-jar-plugin</artifactId>
              <versioning>
                <latest>4.0.0-beta-1</latest>
                <release>4.0.0-beta-1</release>
                <versions>
                  <version>3.4.2</version>
                  <version>3.5.0</version>
                  <version>3.5.1</version>
                  <version>3.6.0-beta-1</version>
                  <version>4.0.0-beta-1</version>
                  <version>4.0.0</version>
                </versions>
              </versioning>
            </metadata>
            """;

    // --- version selection -----------------------------------------------------------------------

    @Test
    void picksTheNewestStableBelowTheGivenMajor() {
        assertEquals("3.5.1", DefaultPluginDriftCheck.pickLatestStable(METADATA, 4).orElseThrow());
    }

    @Test
    void aBetaIsNeverPickedEvenWhenTheMetadataCallsItTheRelease() {
        assertFalse(DefaultPluginDriftCheck.pickLatestStable(METADATA, 4).orElseThrow().contains("beta"));
    }

    @Test
    void raisingTheMajorBoundAdmitsTheNextLine() {
        assertEquals("4.0.0", DefaultPluginDriftCheck.pickLatestStable(METADATA, 5).orElseThrow());
    }

    @Test
    void noStableVersionBelowTheBoundYieldsNothing() {
        String onlyPreviews =
                "<metadata><versioning><versions><version>4.0.0-beta-1</version></versions></versioning></metadata>";
        assertTrue(DefaultPluginDriftCheck.pickLatestStable(onlyPreviews, 4).isEmpty());
    }

    // --- what counts as stable -------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"3.5.1", "3.15.0", "1.0-ga", "1.0-final", "1.0-sp1", "33.7.0-jre", "1.0-arc"})
    void stableVersions(String version) {
        assertTrue(DefaultPluginDriftCheck.isStable(version), version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"4.0.0-beta-1", "1.0-alpha-2", "1.0-M1", "1.0-rc1", "1.0-cr1", "1.0-b2", "1.0-SNAPSHOT"})
    void previewVersions(String version) {
        assertFalse(DefaultPluginDriftCheck.isStable(version), version);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.5.1", "3.15.0"})
    void majorOfAThreeDotXVersion(String version) {
        assertEquals(3, DefaultPluginDriftCheck.majorOf(version));
    }

    @Test
    void unparseableVersionsAreNeverBelowAnyBound() {
        assertEquals(Integer.MAX_VALUE, DefaultPluginDriftCheck.majorOf("not-a-version"));
    }

    // --- reading the defaults out of an effective POM ---------------------------------------------

    @Test
    void extractsThePluginVersionsFromAnEffectivePom() {
        String effectivePom =
                """
                <project>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-jar-plugin</artifactId>
                        <version>3.4.2</version>
                      </plugin>
                      <plugin>
                        <artifactId>maven-surefire-plugin</artifactId>
                        <version>3.5.2</version>
                      </plugin>
                      <plugin>
                        <groupId>com.example</groupId>
                        <artifactId>some-other-plugin</artifactId>
                        <version>1.0</version>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """;
        Map<String, String> versions = DefaultPluginDriftCheck.pluginVersions(effectivePom);
        assertEquals(Map.of("maven-jar-plugin", "3.4.2", "maven-surefire-plugin", "3.5.2"), versions);
    }

    @Test
    void findsTheDocumentInsideAMavenLog() throws IOException {
        String log =
                """
                [INFO] Scanning for projects...
                [INFO] --- help:3.5.1:effective-pom (default-cli) @ probe ---
                <?xml version="1.0" encoding="UTF-8"?>
                <project><build><plugins/></build></project>
                [INFO] BUILD SUCCESS
                """;
        assertTrue(DefaultPluginDriftCheck.extractDocument(log).startsWith("<?xml"));
        assertTrue(DefaultPluginDriftCheck.extractDocument(log).endsWith("</project>"));
    }

    @Test
    void aLogWithoutADocumentIsAnError() {
        assertThrows(IOException.class, () -> DefaultPluginDriftCheck.extractDocument("[ERROR] boom"));
    }
}
