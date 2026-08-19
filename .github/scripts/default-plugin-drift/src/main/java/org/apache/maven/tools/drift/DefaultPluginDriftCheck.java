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

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.eclipse.aether.util.version.GenericQualifiers;
import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reports whether the lifecycle plugin versions a Maven distribution ships as defaults are still the
 * newest stable release below a given major on Maven Central.
 * <p>
 * The defaults are read out of the <em>distribution</em> rather than out of the source tree:
 * {@code help:effective-pom} on an empty project lists every lifecycle-bound plugin with the version
 * Maven injected. That works for any Maven — released or built from a branch, 3.x or 4.x — and keeps
 * working if the way core stores those versions changes again.
 * <p>
 * "Stable" is decided by {@link GenericQualifiers}, the qualifier table Maven's own version scheme
 * uses, so this tool cannot disagree with the resolver about what a pre-release is.
 * <p>
 * This detects drift. It does not change anything: the machine-readable output exists so a separate
 * job can turn a report into a pull request.
 */
public final class DefaultPluginDriftCheck {

    private static final String CENTRAL = "https://repo1.maven.org/maven2/org/apache/maven/plugins/";

    /**
     * The help plugin is pinned rather than resolved: an unversioned invocation would take whatever
     * Central currently calls the release, which is exactly the trap this tool exists to find. The
     * version is declared in this project's own pluginManagement, so it has a coordinate and the
     * usual dependency bots can raise it -- and the tool reports on maven-help-plugin like any other
     * plugin, so it also notices when its own pin falls behind.
     */
    static final String DEFAULT_HELP_PLUGIN_VERSION = "3.5.2";

    private final String helpPluginVersion;
    private final int maxMajor;
    private final GenericVersionScheme versionScheme = new GenericVersionScheme();
    private final HttpClient http = HttpClient.newHttpClient();

    DefaultPluginDriftCheck(int maxMajor) {
        this(maxMajor, DEFAULT_HELP_PLUGIN_VERSION);
    }

    DefaultPluginDriftCheck(int maxMajor, String helpPluginVersion) {
        this.maxMajor = maxMajor;
        this.helpPluginVersion = helpPluginVersion == null || helpPluginVersion.isBlank()
                ? DEFAULT_HELP_PLUGIN_VERSION
                : helpPluginVersion;
    }

    String helpGoal() {
        return "org.apache.maven.plugins:maven-help-plugin:" + helpPluginVersion + ":effective-pom";
    }

    public static void main(String[] args) throws Exception {
        List<String> mavens = new ArrayList<>();
        boolean properties = false;
        boolean failOnDrift = false;
        int maxMajor = 4;
        String helpPluginVersion = DEFAULT_HELP_PLUGIN_VERSION;
        for (String arg : args) {
            if ("--properties".equals(arg)) {
                properties = true;
            } else if ("--fail-on-drift".equals(arg)) {
                failOnDrift = true;
            } else if (arg.startsWith("--help-plugin-version=")) {
                helpPluginVersion = arg.substring("--help-plugin-version=".length());
            } else if (arg.startsWith("--max-major=")) {
                maxMajor = Integer.parseInt(arg.substring("--max-major=".length()));
            } else if (!arg.isBlank()) {
                mavens.add(arg);
            }
        }
        if (mavens.isEmpty()) {
            System.err.println("usage: DefaultPluginDriftCheck [--properties] [--fail-on-drift]"
                    + " [--max-major=N] [--help-plugin-version=V] <mvn|maven-home>...");
            System.exit(2);
        }

        DefaultPluginDriftCheck check = new DefaultPluginDriftCheck(maxMajor, helpPluginVersion);
        boolean drifted = false;
        for (String maven : mavens) {
            Path mvn = executableOf(Path.of(maven));
            Map<String, String> defaults = check.defaultsOf(mvn);
            if (!properties) {
                System.out.println("=== " + mvn);
            }
            for (Map.Entry<String, String> entry : defaults.entrySet()) {
                String artifactId = entry.getKey();
                String current = entry.getValue();
                Optional<String> latest = check.latestStable(artifactId);
                boolean behind = latest.isPresent() && !latest.get().equals(current);
                drifted |= behind;
                if (properties) {
                    System.out.printf("%s=%s%n", artifactId, latest.orElse(current));
                } else {
                    System.out.printf(
                            "  %-26s %-10s %s%n",
                            artifactId,
                            current,
                            behind ? "-> " + latest.get() + "  DRIFT" : latest.map(v -> "ok").orElse("(none on Central)"));
                }
            }
        }
        // Reporting drift is a finding, not an error: only exit non-zero when the caller asks for a
        // gate (CI), so that a bare `mvn` prints the table without a build failure and a stack trace.
        if (drifted && failOnDrift) {
            System.exit(1);
        }
    }

    /** The plugin versions this Maven injects into a project that pins nothing. */
    Map<String, String> defaultsOf(Path mvn) throws IOException, InterruptedException {
        Path probe = Files.createTempDirectory("drift-probe");
        Files.writeString(
                probe.resolve("pom.xml"),
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>drift</groupId><artifactId>probe</artifactId><version>1.0.0</version>
                  <packaging>jar</packaging>
                </project>
                """);
        // No -q: help:effective-pom writes the document at INFO level.
        Process process = new ProcessBuilder(
                        mvn.toString(),
                        "-B",
                        helpGoal(),
                        "-Dmaven.repo.local=" + probe.resolve("m2"),
                        "-f",
                        probe.resolve("pom.xml").toString())
                .redirectErrorStream(true)
                .start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.MINUTES) || process.exitValue() != 0) {
            throw new IOException("help:effective-pom failed for " + mvn + System.lineSeparator() + log);
        }
        return pluginVersions(extractDocument(log));
    }

    /** The effective POM is embedded in the log; take everything from the XML declaration to the last element. */
    static String extractDocument(String log) throws IOException {
        int start = log.indexOf("<?xml");
        int end = log.lastIndexOf("</project>");
        if (start < 0 || end < 0) {
            throw new IOException("no effective POM in the output");
        }
        return log.substring(start, end + "</project>".length());
    }

    /** Every {@code maven-*-plugin} the effective model carries a version for, in artifactId order. */
    static Map<String, String> pluginVersions(String effectivePom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(effectivePom.getBytes(StandardCharsets.UTF_8)));
            Map<String, String> versions = new LinkedHashMap<>();
            NodeList plugins = document.getElementsByTagName("plugin");
            for (int i = 0; i < plugins.getLength(); i++) {
                Element plugin = (Element) plugins.item(i);
                String artifactId = childText(plugin, "artifactId");
                String version = childText(plugin, "version");
                if (artifactId != null && version != null && artifactId.startsWith("maven-")) {
                    versions.putIfAbsent(artifactId, version);
                }
            }
            return new java.util.TreeMap<>(versions);
        } catch (Exception e) {
            throw new UncheckedIOException(new IOException("cannot parse the effective POM", e));
        }
    }

    private static String childText(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    /** The newest version on Central that a release may adopt: stable, and below {@code maxMajor}. */
    Optional<String> latestStable(String artifactId) throws IOException, InterruptedException {
        return pickLatestStable(fetch(CENTRAL + artifactId + "/maven-metadata.xml"), maxMajor);
    }

    /** The newest stable version below {@code maxMajor} in a {@code maven-metadata.xml} document. */
    static Optional<String> pickLatestStable(String metadata, int maxMajor) {
        GenericVersionScheme scheme = new GenericVersionScheme();
        Version best = null;
        for (String candidate : versionsOf(metadata)) {
            if (!isStable(candidate) || majorOf(candidate) >= maxMajor) {
                continue;
            }
            try {
                Version version = scheme.parseVersion(candidate);
                if (best == null || version.compareTo(best) > 0) {
                    best = version;
                }
            } catch (InvalidVersionSpecificationException e) {
                // an unparseable version is not selectable
            }
        }
        return Optional.ofNullable(best).map(Version::toString);
    }

    static List<String> versionsOf(String metadata) {
        return pluginVersionsFromMetadata(metadata);
    }

    private static List<String> pluginVersionsFromMetadata(String metadata) {
        List<String> versions = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("<version>([^<]+)</version>").matcher(metadata);
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return versions;
    }

    /** A negative qualifier shift marks a preview: alpha, beta, milestone, rc/cr, snapshot. */
    static boolean isStable(String version) {
        return GenericQualifiers.qualifier(version).orElse(GenericQualifiers.QUALIFIER_ZERO) >= 0;
    }

    static int majorOf(String version) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("^(\\d+)").matcher(version);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private static Path executableOf(Path candidate) {
        if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
            return candidate;
        }
        Path bin = candidate.resolve("bin").resolve("mvn");
        if (Files.isRegularFile(bin)) {
            return bin;
        }
        throw new IllegalArgumentException("not a Maven binary or home: " + candidate);
    }

    private String fetch(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }
}
