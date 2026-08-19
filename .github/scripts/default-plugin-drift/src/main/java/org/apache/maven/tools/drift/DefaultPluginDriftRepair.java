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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Rewrites the lifecycle plugin versions a Maven source tree compiles in, to the versions a drift
 * report names.
 * <p>
 * The three maintained lines store those versions in three different ways, so the rewrite matches all
 * of them by shape rather than by file:
 * <ul>
 *   <li>{@code org.apache.maven.plugins:maven-jar-plugin:3.5.0} — the XML default bindings (3.9.x);</li>
 *   <li>{@code <version.maven-jar-plugin>3.5.0</version.maven-jar-plugin>} — a POM property (3.10.x);</li>
 *   <li>{@code JAR_PLUGIN_VERSION = "3.4.2"} — a Java constant (4.x), with or without a MAVEN_ prefix.</li>
 * </ul>
 * A plugin the report names but whose version cannot be found is reported as unmatched and makes the
 * run fail: a repair that silently skips a plugin is worse than no repair.
 */
public final class DefaultPluginDriftRepair {

    private final Path root;
    private final boolean dryRun;

    DefaultPluginDriftRepair(Path root, boolean dryRun) {
        this.root = root;
        this.dryRun = dryRun;
    }

    public static void main(String[] args) throws IOException {
        Path root = null;
        Path report = null;
        boolean dryRun = false;
        for (String arg : args) {
            if (arg.startsWith("--source=")) {
                root = Path.of(arg.substring("--source=".length()));
            } else if (arg.startsWith("--report=")) {
                report = Path.of(arg.substring("--report=".length()));
            } else if ("--dry-run".equals(arg)) {
                dryRun = true;
            }
        }
        if (root == null || report == null) {
            System.err.println("usage: DefaultPluginDriftRepair --source=<checkout> --report=<properties> [--dry-run]");
            System.exit(2);
        }

        Properties wanted = new Properties();
        try (var in = Files.newInputStream(report)) {
            wanted.load(in);
        }
        Map<String, String> targets = new TreeMap<>();
        wanted.stringPropertyNames().forEach(key -> targets.put(key, wanted.getProperty(key)));

        DefaultPluginDriftRepair repair = new DefaultPluginDriftRepair(root, dryRun);
        Map<String, List<String>> changes = repair.apply(targets);

        // "already at the wanted version" is a no-op, not a miss: only a plugin whose version site
        // cannot be found at all is a problem, because then the repair silently did nothing.
        List<String> unmatched = new ArrayList<>();
        targets.keySet().forEach(artifactId -> {
            if (!repair.located().contains(artifactId)) {
                unmatched.add(artifactId);
            }
        });

        changes.forEach((artifactId, where) -> {
            System.out.println(artifactId + " -> " + targets.get(artifactId));
            where.forEach(line -> System.out.println("    " + line));
        });
        if (!unmatched.isEmpty()) {
            System.err.println("no version site found for: " + String.join(", ", unmatched));
            System.exit(1);
        }
        if (changes.isEmpty()) {
            System.out.println("nothing to change");
        }
    }

    /** Plugins whose version site was located, whether or not it needed a change. */
    private final java.util.Set<String> located = new java.util.TreeSet<>();

    java.util.Set<String> located() {
        return located;
    }

    /** Rewrites every known version site; returns the files touched per plugin. */
    Map<String, List<String>> apply(Map<String, String> targets) throws IOException {
        Map<String, List<String>> changed = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> sources =
                    files.filter(Files::isRegularFile).filter(DefaultPluginDriftRepair::isVersionSite).toList();
            for (Path file : sources) {
                String original;
                try {
                    original = Files.readString(file, StandardCharsets.UTF_8);
                } catch (java.nio.charset.MalformedInputException notUtf8) {
                    // the tree carries fixtures in other encodings; no version site lives in one
                    continue;
                }
                String content = original;
                for (Map.Entry<String, String> target : targets.entrySet()) {
                    content = rewrite(content, target.getKey(), target.getValue(), file, changed);
                }
                if (!content.equals(original) && !dryRun) {
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                }
            }
        }
        return changed;
    }

    /**
     * Only the places a default may legitimately live: main sources and POMs. Test resources are
     * deliberately excluded -- an IT fixture that pins an old version pins it on purpose.
     */
    static boolean isVersionSite(Path path) {
        String location = path.toString();
        // /its/ is the integration-test harness: its fixtures pin versions on purpose, and
        // /.github/ holds this tool itself, whose documentation quotes the very shapes it matches.
        if (location.contains("/target/")
                || location.contains("/src/test/")
                || location.contains("/its/")
                || location.contains("/.github/")) {
            return false;
        }
        String name = path.getFileName().toString();
        return name.equals("pom.xml")
                || (name.endsWith(".java") && location.contains("/src/main/java/"))
                || (name.endsWith(".xml") && location.contains("/src/main/resources/"));
    }

    private String rewrite(String content, String artifactId, String version, Path file, Map<String, List<String>> changed) {
        String constant = artifactId.replaceFirst("^maven-", "").replaceFirst("-plugin$", "").toUpperCase(Locale.ROOT)
                + "_PLUGIN_VERSION";
        List<Pattern> patterns = List.of(
                // XML default bindings, fully qualified so unrelated coordinates are not touched:
                // org.apache.maven.plugins:maven-jar-plugin:3.5.0
                Pattern.compile("(org\\.apache\\.maven\\.plugins:" + Pattern.quote(artifactId) + ":)([0-9][^:<\\s]*)"),
                // POM property: <version.maven-jar-plugin>3.5.0</version.maven-jar-plugin>
                Pattern.compile("(<version\\." + Pattern.quote(artifactId) + ">)([^<]+)"),
                // Java constant: [MAVEN_]JAR_PLUGIN_VERSION = "3.4.2"
                Pattern.compile("((?:MAVEN_)?" + Pattern.quote(constant) + "\\s*=\\s*\")([^\"]+)"));

        String result = content;
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(result);
            StringBuilder out = new StringBuilder();
            boolean touched = false;
            while (matcher.find()) {
                String current = matcher.group(2);
                located.add(artifactId);
                if (!current.equals(version)) {
                    touched = true;
                    changed.computeIfAbsent(artifactId, key -> new ArrayList<>())
                            .add(root.relativize(file) + ": " + current + " -> " + version);
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group(1) + version));
            }
            matcher.appendTail(out);
            if (touched) {
                result = out.toString();
            }
        }
        return result;
    }
}
