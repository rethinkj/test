package com.rethinkj.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // The class file of a class the source no longer declares must not keep the test compiling.
    edit(project, greeter, "public static class Shout {}", "");
    report = new StringWriter();
    assertEquals(2, runner.run(List.of(), report));
    assertTrue(
        report.toString().contains("\"at\":\"src/test/java/greet/GreeterTest.java:"),
        report.toString());
  }

  @Test
  void runsOnlyTheTestClassesAChangeCanAffect(@TempDir Path project) throws IOException {
    copy(Path.of("src/test/resources/projects/warm"), project);
    // Test classes in the order of their names, so that InitTest initializes Settings and
    // LazyFirstTest fills the cache of Lazy.
    Files.createDirectories(project.resolve("target/test-classes"));
    Files.writeString(
        project.resolve("target/test-classes/junit-platform.properties"),
        "junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$ClassName\n");
    var runner = new WarmRunner(build(project), () -> {});
    assertSelection(
        runner,
        List.of(),
        0,
        "\"classes\":6,\"leftOut\":0,\"leftOutTests\":0,\"why\":[\"no record yet\"]");

    edit(project, "src/main/java/greet/Counter.java", "++count", "count += 2");
    var report = assertSelection(
        runner,
        List.of(),
        1,
        "\"classes\":1,\"leftOut\":5,\"leftOutTests\":7,\"why\":[\"changed greet.Counter\"]");
    assertTrue(report.contains("\"test\":\"greet.CounterTest#countsFromOne\""), report);

    // The class that failed runs again; a comment changes no bytecode but the lines, which the
    // hashes leave out.
    edit(project, "src/main/java/greet/Counter.java", "count += 2", "++count");
    edit(
        project,
        "src/main/java/greet/Greeter.java",
        "public class Greeter {",
        "/** Greets. */\npublic class Greeter {");
    assertSelection(
        runner,
        List.of(),
        0,
        "\"classes\":1,\"leftOut\":5,\"leftOutTests\":7,\"why\":[\"failed greet.CounterTest\"]");

    // What a static initializer ran counts for every test class that uses its class, also one that
    // did not initialize it.
    edit(project, "src/main/java/greet/Source.java", "return 1;", "return 2;");
    report = assertSelection(
        runner,
        List.of(),
        1,
        "\"classes\":4,\"leftOut\":2,\"leftOutTests\":4,\"why\":[\"changed greet.Source\"]");
    assertTrue(report.contains("\"test\":\"greet.ValueTest#readsTheSetting\""), report);
    // The same for a cache that an earlier test class filled.
    assertTrue(report.contains("\"test\":\"greet.LazySecondTest#readsTheCache\""), report);

    edit(project, "src/main/java/greet/Source.java", "return 2;", "return 1;");
    assertSelection(
        runner,
        List.of("--all"),
        0,
        "\"classes\":6,\"leftOut\":0,\"leftOutTests\":0,\"why\":[\"--all\"]");
    assertSelection(
        runner,
        List.of(),
        0,
        "\"classes\":0,\"leftOut\":6,\"leftOutTests\":8,\"why\":[\"nothing changed\"]");
  }

  /** Runs, checks the exit code and the selection line, and returns the report. */
  private static String assertSelection(
      WarmRunner runner, List<String> selectors, int code, String selection) throws IOException {
    var report = new StringWriter();
    assertEquals(code, runner.run(selectors, report), report.toString());
    assertTrue(report.toString().contains(selection), report.toString());
    return report.toString();
  }

  @Test
  void selectsTheClassesSurefireSelects() {
    assertTrue("com.example.CartTest".matches(WarmRunner.regex("**/*Test.*")));
    assertTrue("com.example.CartTest".matches(WarmRunner.regex("*Test.java")));
    assertTrue("com.example.Cart$Item".matches(WarmRunner.regex("**/*$*")));
    assertFalse("com.example.CartTests".matches(WarmRunner.regex("**/*Test.java")));
    assertTrue("com.example.unit.CartTest".matches(WarmRunner.regex("unit/*Test.*")));
    assertFalse("com.other.CartIT".matches(WarmRunner.regex("com/example/**/*IT.java")));
  }

  private static Build build(Path project) {
    var classes = project.resolve("target/classes");
    var testClasses = project.resolve("target/test-classes");
    var classPath = new ArrayList<>(Project.junit());
    classPath.addAll(List.of(classes, testClasses));
    return new Build(
        project.resolve(Build.EXPORT),
        project,
        classPath,
        List.of(classes),
        classes,
        testClasses,
        List.of(project.resolve("src/main/java")),
        List.of(project.resolve("src/test/java")),
        List.of(),
        "25",
        "25",
        false,
        false,
        false,
        true,
        List.of(),
        Map.of(),
        Project.junit().getFirst(),
        List.of(),
        Build.Selection.DEFAULT,
        Build.FORMAT);
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
