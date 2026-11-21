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
 * @param compileClassPath the class path the main sources compile against
 * @param classes the folder of the main classes
 * @param testClasses the folder of the test classes
 * @param sources the folders of the main sources
 * @param testSources the folders of the test sources
 * @param resources the folders of the main and test resources, and the files they are filtered
 *     with
 * @param release the Java release to compile for, or null
 * @param testRelease the Java release to compile the tests for, or null for {@code release}
 * @param parameters whether javac keeps the names of parameters ({@code -parameters}) of the
 *     main classes
 * @param testParameters the same for the test classes
 * @param processors whether the build runs annotation processors, which the runner does not
 * @param assertions whether the test JVM enables Java's assertions, as Surefire does by default
 * @param jvmArgs the options of the test JVM
 * @param systemProperties the system properties of the test JVM
 * @param launcher the JUnit Platform Launcher JAR matching the module's JUnit
 * @param poms the build files whose change calls for a new export
 * @param selection which tests a run without selectors runs
 * @param format the version of the file's content
 */
record Build(
    Path file,
    Path workdir,
    List<Path> classPath,
    List<Path> compileClassPath,
    Path classes,
    Path testClasses,
    List<Path> sources,
    List<Path> testSources,
    List<Path> resources,
    String release,
    String testRelease,
    boolean parameters,
    boolean testParameters,
    boolean processors,
    boolean assertions,
    List<String> jvmArgs,
    Map<String, String> systemProperties,
    Path launcher,
    List<Path> poms,
    Selection selection,
    String format) {

  /**
   * Which tests a run without selectors runs, as Surefire selects them.
   *
   * @param includes Surefire's patterns of the test classes, or empty for its default ones
   * @param excludes Surefire's patterns of the classes left out, or empty for its default one
   * @param groups the tag expressions a test has to match, or null
   * @param excludedGroups the tag expressions a test must not match, or null
   */
  record Selection(
      List<String> includes, List<String> excludes, String groups, String excludedGroups) {

    static final Selection DEFAULT = new Selection(List.of(), List.of(), null, null);
  }

  /** The version of the file's content this runner reads. */
  static final String FORMAT = "1";

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
    resources.addAll(paths(values, "filters"));
    return new Build(
        file,
        Path.of(values.getProperty("workdir")),
        paths(values, "classpath"),
        paths(values, "compileClasspath"),
        Path.of(values.getProperty("classes")),
        Path.of(values.getProperty("testClasses")),
        paths(values, "sources"),
        paths(values, "testSources"),
        resources,
        values.getProperty("release"),
        values.getProperty("testRelease", values.getProperty("release")),
        Boolean.parseBoolean(values.getProperty("parameters")),
        Boolean.parseBoolean(values.getProperty("testParameters")),
        Boolean.parseBoolean(values.getProperty("processors")),
        !"false".equals(values.getProperty("assertions")),
        words(values.getProperty("jvm.args", "")),
        system,
        Path.of(values.getProperty("launcher")),
        paths(values, "poms"),
        new Selection(
            items(values, "test.includes"),
            items(values, "test.excludes"),
            values.getProperty("test.groups"),
            values.getProperty("test.excludedGroups")),
        values.getProperty("format"));
  }

  private static List<String> items(Properties values, String key) {
    var text = values.getProperty(key, "");
    return text.isBlank() ? List.of() : List.of(text.strip().split("\\s*,\\s*"));
  }

  /** The class path without the module's own class folders: what stays loaded between runs. */
  List<Path> libraries() {
    return classPath.stream()
        .filter(path -> !path.equals(classes) && !path.equals(testClasses))
        .toList();
  }

  /**
   * Names the build and the runner a daemon was started with: the time the export was written,
   * the time of the runner's own JAR and those of the libraries. A new export, a new runner or a
   * library installed anew starts a new daemon.
   */
  String stamp() throws IOException {
    Path self;
    try {
      self = Path.of(
          Build.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (java.net.URISyntaxException e) {
      throw new IOException(e);
    }
    return (Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : 0)
        + "-"
        + Files.getLastModifiedTime(self).toMillis()
        + "-"
        + libraryStamp();
  }

  /** The libraries by path and time, so that one installed anew at the same path counts. */
  String libraryStamp() throws IOException {
    var libraries = new StringBuilder();
    for (var library : libraries()) {
      libraries.append(library).append(File.pathSeparator);
      libraries.append(
          Files.exists(library) ? Files.getLastModifiedTime(library).toMillis() : 0);
    }
    return Integer.toHexString(libraries.toString().hashCode());
  }

  /**
   * Whether the export is out of date: a build file changed after it, or an older adapter wrote
   * it.
   */
  boolean stale() {
    if (!FORMAT.equals(format)) {
      return true;
    }
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
