package com.rethinkj.test;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathRoots;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Runs a module's tests again and again in this JVM. The libraries and JUnit stay loaded and
 * compiled by the JIT; the module's own classes are compiled when they changed and loaded fresh
 * for every run, by a class loader of the run's own.
 */
final class WarmRunner {

  /** How many earlier runs' class loaders may stay in memory before the JVM is replaced. */
  static final int LEAK_LIMIT = 8;

  /** How many runs one JVM does before it is replaced, however clean it stays. */
  static final int RUN_LIMIT = 500;

  private final Build build;
  private final Compilation compilation;
  private final Impact impact;
  private final List<WeakReference<ClassLoader>> loaders = new ArrayList<>();
  private int runs;

  WarmRunner(Build build, Runnable copyResources) {
    this.build = build;
    this.compilation = new Compilation(build, copyResources);
    this.impact = new Impact(build);
  }

  /**
   * Compiles what changed and runs the tests selected, writing the report to {@code out}.
   *
   * @param selectors classes ({@code a.BTest}), methods ({@code a.BTest#c}) or packages ({@code
   *     a}); none for the test classes a change since their last run can affect, or {@code --all}
   *     alone for all of them
   * @return 0 when every test passed, 1 when one failed, 2 when the code did not compile
   */
  int run(List<String> selectors, Writer out) throws IOException {
    impact.load();
    var compiled = compilation.update();
    // What changed now is not in the bytecode: forget what the tests used, so that the next run
    // without selectors runs all, also when this one runs some.
    if (compiled.anew() || compiled.resourcesCopied()) {
      impact.forget();
      impact.save();
    }
    if (!compiled.errors().isEmpty()) {
      for (var error : compiled.errors()) {
        out.write(new Json()
                .field("v", AgentReport.VERSION)
                .field("event", "compile-error")
                .field("at", error.file() + ":" + error.line())
                .field("message", error.message())
            + "\n");
      }
      out.flush();
      return 2;
    }
    var report = new AgentReport(out);
    var failure = new AtomicReference<Throwable>();
    var loader = new RunLoader(
        "run-" + ++runs,
        new URL[] {url(build.testClasses()), url(build.classes())},
        WarmRunner.class.getClassLoader(),
        impact);
    var matched = new AtomicReference<>(true);
    boolean all = selectors.contains("--all");
    var names = selectors.stream().filter(name -> !name.equals("--all")).toList();
    // A thread of its own, so that thread locals of the run do not keep its classes alive.
    var thread = Thread.ofPlatform().name("rethinkj-test run " + runs).unstarted(() -> {
      try {
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors(names, loader))
            .configurationParameter("junit.platform.output.capture.stdout", "true")
            .configurationParameter("junit.platform.output.capture.stderr", "true");
        filter(request, names.isEmpty());
        var launcher = LauncherFactory.create();
        var plan = launcher.discover(request.build());
        if (!names.isEmpty() && !plan.containsTests()) {
          matched.set(false);
          return;
        }
        // Classes that run in parallel cannot tell their probes' hits apart.
        boolean parallel = plan.getConfigurationParameters()
            .getBoolean("junit.jupiter.execution.parallel.enabled")
            .orElse(false);
        if (names.isEmpty()) {
          var tests = testClasses(plan);
          var classes = tests.keySet();
          String why = all
              ? "--all"
              : parallel
                  ? "parallel execution"
                  : !impact.usable(build.stamp())
                      ? "no record yet"
                      : compiled.anew()
                          ? "compiled anew"
                          : compiled.resourcesCopied() ? "resources changed" : null;
          if (why != null) {
            selection(out, classes.size(), 0, 0, List.of(why));
          } else {
            var affected = impact.affected(classes);
            long leftOut = tests.entrySet().stream()
                .filter(test -> !affected.containsKey(test.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
            selection(
                out,
                affected.size(),
                classes.size() - affected.size(),
                leftOut,
                affected.isEmpty() ? List.of("nothing changed") : reasons(affected));
            if (affected.size() < classes.size()) {
              var quoted = affected.keySet().stream().map(Pattern::quote).toList();
              request.filters(ClassNameFilter.includeClassNamePatterns(
                  quoted.isEmpty() ? new String[] {"(?!)"} : quoted.toArray(String[]::new)));
              plan = launcher.discover(request.build());
            }
          }
        }
        // Only a run of whole classes tells what each of them uses.
        if (names.isEmpty() && !parallel) {
          launcher.execute(plan, report, new Recorder());
          impact.save();
        } else {
          launcher.execute(plan, report);
        }
      } catch (Throwable e) {
        failure.set(e);
      }
    });
    thread.setContextClassLoader(loader);
    // What tests commonly change of the JVM, put back so that the next run starts as a fresh one.
    var properties = (Properties) System.getProperties().clone();
    var locale = Locale.getDefault();
    var display = Locale.getDefault(Locale.Category.DISPLAY);
    var format = Locale.getDefault(Locale.Category.FORMAT);
    var zone = TimeZone.getDefault();
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted", e);
    } finally {
      System.setProperties(properties);
      Locale.setDefault(locale);
      Locale.setDefault(Locale.Category.DISPLAY, display);
      Locale.setDefault(Locale.Category.FORMAT, format);
      TimeZone.setDefault(zone);
      // The probes' record holds the run's classes, which have to go with its loader.
      Probe.end();
      loader.close();
    }
    if (failure.get() != null) {
      throw new IOException("The run failed", failure.get());
    }
    loaders.add(new WeakReference<>(loader));
    if (!matched.get()) {
      out.write(new Json()
              .field("v", AgentReport.VERSION)
              .field("event", "error")
              .field("message", "No test matches " + String.join(" ", selectors))
          + "\n");
      out.flush();
      return 2;
    }
    return report.failed() > 0 ? 1 : 0;
  }

  /** The test classes of a plan, the classes right below an engine, with their tests. */
  private static Map<String, Long> testClasses(TestPlan plan) {
    var classes = new TreeMap<String, Long>();
    for (var engine : plan.getRoots()) {
      for (var child : plan.getChildren(engine)) {
        if (child.getSource().orElse(null) instanceof ClassSource source) {
          long tests =
              plan.getDescendants(child).stream().filter(TestIdentifier::isTest).count();
          classes.merge(source.getClassName(), tests, Long::sum);
        }
      }
    }
    return classes;
  }

  /** Says which test classes run and why, before they do. */
  private static void selection(
      Writer out, int classes, int leftOut, long leftOutTests, List<String> why)
      throws IOException {
    out.write(new Json()
            .field("v", AgentReport.VERSION)
            .field("event", "selection")
            .field("classes", classes)
            .field("leftOut", leftOut)
            .field("leftOutTests", leftOutTests)
            .field("why", why)
        + "\n");
    out.flush();
  }

  /** The first reasons for the test classes affected: what changed, or that they are new. */
  private static List<String> reasons(Map<String, String> affected) {
    var reasons = new LinkedHashSet<String>();
    affected.forEach((test, reason) -> reasons.add(
        switch (reason) {
          case "new" -> "new " + test;
          case "failed" -> "failed " + test;
          default -> "changed " + reason;
        }));
    return reasons.stream().limit(5).toList();
  }

  /** Tells {@link Impact} what each test class used, from the probes, as the class ends. */
  private final class Recorder implements TestExecutionListener {

    private final Set<String> failed = new HashSet<>();

    @Override
    public void executionStarted(TestIdentifier test) {
      if (testClass(test) != null) {
        Probe.restart();
      }
    }

    @Override
    public void executionFinished(TestIdentifier test, TestExecutionResult result) {
      if (result.getStatus() == TestExecutionResult.Status.FAILED) {
        failed.add(outer(test));
      }
      var name = testClass(test);
      if (name != null) {
        try {
          impact.ran(name, Probe.restart(), Probe.initialized(), !failed.contains(name));
        } catch (RuntimeException e) {
          // JUnit would only log it; a record kept from before could leave the class out.
          impact.drop(name);
          System.err.println("Cannot record what " + name + " used: " + e);
        }
      }
    }

    /** The name of a test class, for a class right below an engine, or null. */
    private String testClass(TestIdentifier test) {
      return test.getSource().orElse(null) instanceof ClassSource source
              && !source.getClassName().contains("$")
          ? source.getClassName()
          : null;
    }

    private String outer(TestIdentifier test) {
      var source = test.getSource().orElse(null);
      var name = source instanceof MethodSource method
          ? method.getClassName()
          : source instanceof ClassSource type ? type.getClassName() : "";
      return name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
    }
  }

  /** Surefire's default patterns of test classes, for a module that configures none. */
  static final List<String> INCLUDES =
      List.of("**/Test*.java", "**/*Test.java", "**/*Tests.java", "**/*TestCase.java");

  /** Surefire's default pattern of classes it leaves out: nested ones. */
  static final List<String> EXCLUDES = List.of("**/*$*");

  /**
   * Selects the tests Surefire would run: its groups always, as with {@code -Dtest}, and its
   * patterns of classes when no selector names the tests.
   */
  private void filter(LauncherDiscoveryRequestBuilder request, boolean all) {
    var selection = build.selection();
    if (all) {
      var includes = selection.includes().isEmpty() ? INCLUDES : selection.includes();
      var excludes = selection.excludes().isEmpty() ? EXCLUDES : selection.excludes();
      request.filters(
          ClassNameFilter.includeClassNamePatterns(
              includes.stream().map(WarmRunner::regex).toArray(String[]::new)),
          ClassNameFilter.excludeClassNamePatterns(
              excludes.stream().map(WarmRunner::regex).toArray(String[]::new)));
    }
    if (selection.groups() != null) {
      request.filters(TagFilter.includeTags(selection.groups().split(",")));
    }
    if (selection.excludedGroups() != null) {
      request.filters(TagFilter.excludeTags(selection.excludedGroups().split(",")));
    }
  }

  /**
   * A Surefire pattern of class files ({@code **}{@code /*Test.java}) as a regular expression of
   * class names ({@code com.example.CartTest}).
   */
  // shortcut: no %regex[...] patterns; add them when a module of ours uses one.
  static String regex(String pattern) {
    // Surefire matches the patterns against class files, so a suffix of .java, .class or .* is the
    // file's extension, not part of the class name.
    var text = pattern.strip().replaceAll("\\.(java|class|\\*)$", "");
    // Surefire matches every pattern in any folder, also one that names folders.
    if (!text.startsWith("**")) {
      text = "**/" + text;
    }
    var regex = new StringBuilder("^");
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (text.startsWith("**/", i)) {
        regex.append("(?:.*\\.)?");
        i += 2;
      } else if (text.startsWith("**", i)) {
        regex.append(".*");
        i++;
      } else if (c == '*') {
        regex.append("[^.]*");
      } else if (c == '?') {
        regex.append("[^.]");
      } else if (c == '/' || c == '.') {
        regex.append("\\.");
      } else if (Character.isLetterOrDigit(c) || c == '_') {
        regex.append(c);
      } else {
        regex.append('\\').append(c);
      }
    }
    return regex.append('$').toString();
  }

  /**
   * How many class loaders of earlier runs are still in memory, after a garbage collection. A
   * class loader that stays keeps all classes of its run, so their number grows with every run.
   */
  int leaked() {
    System.gc();
    loaders.removeIf(reference -> reference.get() == null);
    return Math.max(0, loaders.size() - 1);
  }

  /** Whether this JVM should be replaced by a fresh one. */
  boolean worn() {
    return runs >= RUN_LIMIT || leaked() > LEAK_LIMIT;
  }

  private DiscoverySelector[] selectors(List<String> names, ClassLoader loader) {
    if (names.isEmpty()) {
      return selectClasspathRoots(Set.of(build.testClasses())).toArray(DiscoverySelector[]::new);
    }
    return names.stream()
        .flatMap(name -> {
          if (name.contains("#")) {
            return methods(name, loader);
          }
          try {
            return Stream.of(selectClass(Class.forName(name, false, loader)));
          } catch (ClassNotFoundException e) {
            return Stream.of(selectPackage(name));
          }
        })
        .toArray(DiscoverySelector[]::new);
  }

  /**
   * The methods {@code a.BTest#c} names. Without parameter types it names every method of that
   * name, as Surefire's {@code -Dtest} does, also those that take parameters.
   */
  private static Stream<DiscoverySelector> methods(String name, ClassLoader loader) {
    if (!name.contains("(")) {
      try {
        var type = Class.forName(name.substring(0, name.indexOf('#')), false, loader);
        var method = name.substring(name.indexOf('#') + 1);
        var found = ReflectionSupport.findMethods(
            type, candidate -> candidate.getName().equals(method), HierarchyTraversalMode.TOP_DOWN);
        if (!found.isEmpty()) {
          return found.stream().map(candidate -> selectMethod(type, candidate));
        }
      } catch (ClassNotFoundException e) {
        // Selected as given, so that discovery reports that nothing matches.
      }
    }
    return Stream.of(selectMethod(loader, name));
  }

  private static URL url(java.nio.file.Path folder) {
    try {
      return folder.toUri().toURL();
    } catch (MalformedURLException e) {
      throw new UncheckedIOException(e);
    }
  }
}
