package com.rethinkj.test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * What the build tool says about a module: the file its adapter writes, {@code
 * target/rethinkj-test/project.properties}.
 *
 * @param file the file read
 * @param workdir the module's folder, the working directory of its tests
 * @param classPath the test class path, the module's own class folders included
 * @param classes the folder of the main classes
 * @param testClasses the folder of the test classes
 * @param sources the folders of the main sources
 * @param testSources the folders of the test sources
 * @param resources the folders of the main and test resources
 * @param release the Java release to compile for, or null
 * @param jvmArgs the options of the test JVM
 * @param systemProperties the system properties of the test JVM
 * @param launcher the JUnit Platform Launcher JAR matching the module's JUnit
 * @param poms the build files whose change calls for a new export
 */
record Build(
    Path file,
    Path workdir,
    List<Path> classPath,
    Path classes,
    Path testClasses,
    List<Path> sources,
    List<Path> testSources,
    List<Path> resources,
    String release,
    List<String> jvmArgs,
    Map<String, String> systemProperties,
    Path launcher,
    List<Path> poms) {

  /** Where the adapter writes the file, relative to the module's folder. */
  static final Path EXPORT = Path.of("target", "rethinkj-test", "project.properties");

  static Build read(Path file) {
    var values = new Properties();
    try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      values.load(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read " + file, e);
    }
    var system = new TreeMap<String, String>();
    for (var name : values.stringPropertyNames()) {
      if (name.startsWith("system.")) {
        system.put(name.substring("system.".length()), values.getProperty(name));
      }
    }
    var resources = new ArrayList<>(paths(values, "resources"));
    resources.addAll(paths(values, "testResources"));
    return new Build(
        file,
        Path.of(values.getProperty("workdir")),
        paths(values, "classpath"),
        Path.of(values.getProperty("classes")),
        Path.of(values.getProperty("testClasses")),
        paths(values, "sources"),
        paths(values, "testSources"),
        resources,
        values.getProperty("release"),
        words(values.getProperty("jvm.args", "")),
        system,
        Path.of(values.getProperty("launcher")),
        paths(values, "poms"));
  }

  /** The class path without the module's own class folders: what stays loaded between runs. */
  List<Path> libraries() {
    return classPath.stream()
        .filter(path -> !path.equals(classes) && !path.equals(testClasses))
        .toList();
  }

  /**
   * Names the build and the runner a daemon was started with: the time the export was written,
   * and the time of the runner's own JAR, so that a new export or a new runner starts a new one.
   */
  static String stamp(Path export) throws IOException {
    Path self;
    try {
      self = Path.of(
          Build.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (java.net.URISyntaxException e) {
      throw new IOException(e);
    }
    return Files.getLastModifiedTime(export).toMillis() + "-"
        + Files.getLastModifiedTime(self).toMillis();
  }

  /** Whether a build file changed after the export, so that the export is out of date. */
  boolean stale() {
    try {
      var exported = Files.getLastModifiedTime(file);
      for (var pom : poms) {
        if (!Files.exists(pom) || Files.getLastModifiedTime(pom).compareTo(exported) > 0) {
          return true;
        }
      }
      return false;
    } catch (IOException e) {
      return true;
    }
  }

  private static List<Path> paths(Properties values, String key) {
    var text = values.getProperty(key, "");
    if (text.isBlank()) {
      return List.of();
    }
    return Arrays.stream(text.split(File.pathSeparator)).map(Path::of).toList();
  }

  /** Splits an {@code argLine} into options, as a shell would: at spaces outside quotes. */
  static List<String> words(String line) {
    var words = new ArrayList<String>();
    var word = new StringBuilder();
    char quote = 0;
    boolean inWord = false;
    for (char c : line.toCharArray()) {
      if (quote != 0) {
        if (c == quote) {
          quote = 0;
        } else {
          word.append(c);
        }
      } else if (c == '"' || c == '\'') {
        quote = c;
        inWord = true;
      } else if (Character.isWhitespace(c)) {
        if (inWord) {
          words.add(word.toString());
          word.setLength(0);
          inWord = false;
        }
      } else {
        word.append(c);
        inWord = true;
      }
    }
    if (inWord) {
      words.add(word.toString());
    }
    return words;
  }
}
