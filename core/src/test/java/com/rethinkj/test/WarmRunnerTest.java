package com.rethinkj.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WarmRunnerTest {

  @Test
  void compilesWhatChangedAndRunsTheTestsAgain(@TempDir Path project) throws IOException {
    copy(Path.of("src/test/resources/projects/warm"), project);
    var runner = new WarmRunner(build(project), () -> {});
    var greeter = "src/main/java/greet/Greeter.java";

    assertEquals(0, runner.run(List.of(), new StringWriter()));

    // javac copied the constant into the test, so the test has to be compiled again.
    edit(project, greeter, "\"Hello\"", "\"Hi\"");
    assertEquals(0, runner.run(List.of(), new StringWriter()));

    edit(project, greeter, "GREETING + \", \" + name", "GREETING + \" \" + name");
    var report = new StringWriter();
    assertEquals(1, runner.run(List.of("greet.GreeterTest#greetsByName"), report));
    assertTrue(
        report.toString().contains("\"expected\":\"Hi, Ada\",\"actual\":\"Hi Ada\""),
        report.toString());

    edit(project, greeter, "return GREETING", "return GREETING +");
    report = new StringWriter();
    assertEquals(2, runner.run(List.of(), report));
    assertTrue(
        report
            .toString()
            .startsWith("{\"v\":1,\"event\":\"compile-error\",\"at\":\"" + greeter + ":8\""),
        report.toString());

    // The file that did not compile is compiled again, with both mistakes fixed.
    edit(project, greeter, "GREETING + + \" \" + name", "GREETING + \", \" + name");
    assertEquals(0, runner.run(List.of(), new StringWriter()));
  }

  private static Build build(Path project) {
    var classes = project.resolve("target/classes");
    var testClasses = project.resolve("target/test-classes");
    var classPath = new ArrayList<>(Project.junit());
    classPath.addAll(List.of(classes, testClasses));
    return new Build(
        project.resolve(Build.FILE),
        project,
        classPath,
        classes,
        testClasses,
        List.of(project.resolve("src/main/java")),
        List.of(project.resolve("src/test/java")),
        List.of(),
        "25",
        List.of(),
        Map.of(),
        Project.junit().getFirst(),
        List.of());
  }

  private static void edit(Path project, String file, String from, String to) throws IOException {
    var path = project.resolve(file);
    var text = Files.readString(path);
    assertTrue(text.contains(from), from + " is not in " + file);
    Files.writeString(path, text.replace(from, to));
  }

  private static void copy(Path from, Path to) throws IOException {
    try (var files = Files.walk(from)) {
      for (var file : files.toList()) {
        var target = to.resolve(from.relativize(file).toString());
        if (Files.isDirectory(file)) {
          Files.createDirectories(target);
        } else {
          Files.copy(file, target);
        }
      }
    }
  }
}
