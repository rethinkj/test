package com.rethinkj.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.opentest4j.AssertionFailedError;

/**
 * Writes a report of a test run for coding agents: one JSON line per failure as it happens, and a
 * line with the counts at the end. A run where everything passes writes only that last line.
 *
 * <p>The JUnit Platform registers it by itself when this library is on the test class path. It
 * stays silent unless the configuration parameter {@value #FILE} names the file to write, for
 * example {@code mvn test -Drethinkj.test.report=target/test-report.jsonl}. A relative path is
 * resolved against the working directory of the test JVM, which Maven sets to the module.
 *
 * <p>Every line has {@code "v":1}. Within that version fields are added, never renamed, removed or
 * given a new meaning.
 */
public final class AgentReport implements TestExecutionListener {

  /** The configuration parameter that names the file to write. */
  public static final String FILE = "rethinkj.test.report";

  static final int VERSION = 1;

  /** The most characters of a test's output a failure carries; the end is kept. */
  static final int OUTPUT_LIMIT = 2000;

  /** The most stack frames a failure carries. */
  static final int FRAME_LIMIT = 20;

  /** The files written in this JVM: a second run into the same file appends to it. */
  private static final Set<Path> STARTED = new HashSet<>();

  private Writer out;
  private TestPlan plan;
  private long startedAt;
  private int passed;
  private int failed;
  private int aborted;
  private int skipped;
  private final Map<String, StringBuilder> output = new HashMap<>();
  private final Set<String> counted = new HashSet<>();

  @Override
  public synchronized void testPlanExecutionStarted(TestPlan testPlan) {
    var file = testPlan.getConfigurationParameters().get(FILE);
    if (file.isEmpty() || file.get().isBlank()) {
      return;
    }
    plan = testPlan;
    startedAt = System.nanoTime();
    passed = failed = aborted = skipped = 0;
    output.clear();
    counted.clear();
    var path = Path.of(file.get()).toAbsolutePath().normalize();
    try {
      Files.createDirectories(path.getParent());
      boolean first;
      synchronized (STARTED) {
        first = STARTED.add(path);
      }
      out = Files.newBufferedWriter(
          path,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE,
          first ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot write the test report to " + path, e);
    }
  }

  @Override
  public synchronized void reportingEntryPublished(TestIdentifier test, ReportEntry entry) {
    if (out == null) {
      return;
    }
    // Present when the project turns on junit.platform.output.capture.stdout or .stderr.
    for (var key : List.of("stdout", "stderr")) {
      var text = entry.getKeyValuePairs().get(key);
      if (text != null) {
        var kept = output
            .computeIfAbsent(test.getUniqueId(), id -> new StringBuilder())
            .append(text);
        // One character more than the limit, so that the report still sees it was cut.
        kept.delete(0, Math.max(0, kept.length() - OUTPUT_LIMIT - 1));
      }
    }
  }

  @Override
  public synchronized void executionSkipped(TestIdentifier test, String reason) {
    if (out == null) {
      return;
    }
    skipped += tests(test);
  }

  @Override
  public synchronized void executionFinished(TestIdentifier test, TestExecutionResult result) {
    if (out == null) {
      return;
    }
    switch (result.getStatus()) {
      case SUCCESSFUL -> passed += test.isTest() ? tests(test) : 0;
      // A class whose assumption fails in @BeforeAll aborts those of its tests that did not run.
      case ABORTED -> aborted += tests(test);
      case FAILED -> {
        failed++;
        tests(test);
        write(failure(test, result.getThrowable().orElse(null)));
      }
    }
    output.remove(test.getUniqueId());
  }

  @Override
  public synchronized void testPlanExecutionFinished(TestPlan testPlan) {
    if (out == null) {
      return;
    }
    var line = new Json()
        .field("v", VERSION)
        .field("event", "summary")
        .field("passed", passed)
        .field("failed", failed)
        .field("aborted", aborted)
        .field("skipped", skipped)
        .field("ms", (System.nanoTime() - startedAt) / 1_000_000);
    write(line);
    try {
      out.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      out = null;
      plan = null;
    }
  }

  /** The tests in what was skipped or aborted: the test itself, or those of a class. */
  private int tests(TestIdentifier test) {
    return test.isTest()
        ? 1
        : (int)
            plan.getDescendants(test).stream().filter(TestIdentifier::isTest).count();
  }

  private Json failure(TestIdentifier test, Throwable error) {
    var json = new Json()
        .field("v", VERSION)
        .field("event", "failed")
        .field("test", selector(test))
        .field("name", test.getDisplayName())
        .field("id", test.getUniqueId());
    if (error != null) {
      var frames = ownFrames(error);
      if (!frames.isEmpty()) {
        json.field("at", location(frames.getFirst()));
      }
      json.field("error", error.getClass().getName());
      if (error.getMessage() != null) {
        json.field("message", error.getMessage());
      }
      if (error instanceof AssertionFailedError assertion) {
        if (assertion.isExpectedDefined()) {
          json.field("expected", assertion.getExpected().getStringRepresentation());
        }
        if (assertion.isActualDefined()) {
          json.field("actual", assertion.getActual().getStringRepresentation());
        }
      }
      // Not StackTraceElement.toString(): it puts the module and its version in front.
      json.field(
          "stack",
          frames.stream()
              .map(frame -> frame.getClassName() + "." + frame.getMethodName() + "("
                  + frame.getFileName() + ":" + frame.getLineNumber() + ")")
              .toList());
      var seen = Collections.<Throwable>newSetFromMap(new IdentityHashMap<>());
      var root = error;
      while (seen.add(root) && root.getCause() != null && !seen.contains(root.getCause())) {
        root = root.getCause();
      }
      if (root != error) {
        json.field("cause", root.toString());
      }
    }
    var text = output.get(test.getUniqueId());
    if (text != null) {
      json.field(
          "output",
          text.length() <= OUTPUT_LIMIT
              ? text.toString()
              : "…" + text.substring(text.length() - OUTPUT_LIMIT));
    }
    return json;
  }

  /** What Surefire's {@code -Dtest} and the Launcher's selectors take to run this test again. */
  static String selector(TestIdentifier test) {
    TestSource source = test.getSource().orElse(null);
    if (source instanceof MethodSource method) {
      return method.getClassName() + "#" + method.getMethodName();
    }
    if (source instanceof ClassSource type) {
      return type.getClassName();
    }
    return test.getUniqueId();
  }

  /** The frames of the project's own code, from the top, at most {@link #FRAME_LIMIT}. */
  static List<StackTraceElement> ownFrames(Throwable error) {
    var frames = new ArrayList<StackTraceElement>();
    for (var frame : error.getStackTrace()) {
      if (frames.size() == FRAME_LIMIT) {
        break;
      }
      if (own(frame.getClassName())) {
        frames.add(frame);
      }
    }
    return frames;
  }

  /**
   * Whether a class is the project's own: its class file is in a folder, such as Maven's
   * {@code target/classes}. The JDK, JUnit, the build tool and libraries come from JARs.
   */
  static boolean own(String className) {
    var loader = Thread.currentThread().getContextClassLoader();
    var file = (loader != null ? loader : ClassLoader.getSystemClassLoader())
        .getResource(className.replace('.', '/') + ".class");
    return file != null && file.getProtocol().equals("file");
  }

  /** The source file of a frame relative to its source folder, and the line: {@code a/B.java:3}. */
  static String location(StackTraceElement frame) {
    var type = frame.getClassName();
    var folder =
        type.contains(".") ? type.substring(0, type.lastIndexOf('.')).replace('.', '/') + "/" : "";
    return folder + frame.getFileName() + ":" + frame.getLineNumber();
  }

  private void write(Json line) {
    try {
      out.write(line.toString());
      out.write('\n');
      out.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
