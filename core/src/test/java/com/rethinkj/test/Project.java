package com.rethinkj.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/** A project under {@code src/test/resources/projects}, compiled into a folder of its own. */
record Project(Path source, Path classes) {

  /** Compiles the main and test code of the project named, against JUnit Jupiter. */
  static Project compile(String name, Path into) {
    var source = Path.of("src/test/resources/projects", name);
    var classPath = junit().stream().map(Path::toString).toList();
    var options = new ArrayList<>(List.of(
        "-proc:none",
        "--release",
        "25",
        "-cp",
        String.join(File.pathSeparator, classPath),
        "-d",
        into.toString()));
    try (var files = Files.walk(source.resolve("src"))) {
      files
          .filter(file -> file.toString().endsWith(".java"))
          .map(Path::toString)
          .forEach(options::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    int exit =
        ToolProvider.getSystemJavaCompiler().run(null, null, null, options.toArray(String[]::new));
    assertEquals(0, exit, "javac failed on " + source);
    return new Project(source, into);
  }

  /** A class loader for the project's classes, with JUnit from the loader of this test. */
  URLClassLoader loader() {
    try {
      return new URLClassLoader(
          new URL[] {classes.toUri().toURL()}, Project.class.getClassLoader());
    } catch (MalformedURLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The number of the line of a source file that contains the text given. */
  int line(String file, String text) throws IOException {
    var lines = Files.readAllLines(source.resolve(file));
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(text)) {
        return i + 1;
      }
    }
    throw new AssertionError(text + " is not in " + file);
  }

  /** The JARs a project's tests compile against: JUnit Jupiter's API and what it needs. */
  static List<Path> junit() {
    return Stream.of(
            org.junit.jupiter.api.Test.class,
            org.opentest4j.AssertionFailedError.class,
            org.apiguardian.api.API.class)
        .map(Project::jar)
        .toList();
  }

  private static Path jar(Class<?> type) {
    try {
      return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
