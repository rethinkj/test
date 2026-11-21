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
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.platform.commons.support.HierarchyTraversalMode;
import org.junit.platform.commons.support.ReflectionSupport;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.launcher.TagFilter;
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
  private final List<WeakReference<ClassLoader>> loaders = new ArrayList<>();
  private int runs;

  WarmRunner(Build build, Runnable copyResources) {
    this.build = build;
    this.compilation = new Compilation(build, copyResources);
  }

  /**
   * Compiles what changed and runs the tests selected, writing the report to {@code out}.
   *
   * @param selectors classes ({@code a.BTest}), methods ({@code a.BTest#c}) or packages ({@code
   *     a}); none for all tests of the module
   * @return 0 when every test passed, 1 when one failed, 2 when the code did not compile
   */
  int run(List<String> selectors, Writer out) throws IOException {
    var compiled = compilation.update();
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
    // Maven's order: test classes, classes, libraries. A test resource wins over a main one of the
    // same name, and both over one in a library; the classes of the libraries and JUnit stay
    // shared.
    var loader =
        new URLClassLoader(
            "run-" + ++runs,
            new URL[] {url(build.testClasses()), url(build.classes())},
            WarmRunner.class.getClassLoader()) {
          @Override
          public URL getResource(String name) {
            var own = findResource(name);
            return own != null ? own : super.getResource(name);
          }

          @Override
          public Enumeration<URL> getResources(String name) throws IOException {
            var all = new ArrayList<>(Collections.list(findResources(name)));
            all.addAll(Collections.list(getParent().getResources(name)));
            return Collections.enumeration(all);
          }
        };
    var matched = new AtomicReference<>(true);
    // A thread of its own, so that thread locals of the run do not keep its classes alive.
    var thread = Thread.ofPlatform().name("rethinkj-test run " + runs).unstarted(() -> {
      try {
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors(selectors, loader))
            .configurationParameter("junit.platform.output.capture.stdout", "true")
            .configurationParameter("junit.platform.output.capture.stderr", "true");
        filter(request, selectors.isEmpty());
        var launcher = LauncherFactory.create();
        var plan = launcher.discover(request.build());
        if (!selectors.isEmpty() && !plan.containsTests()) {
          matched.set(false);
          return;
        }
        launcher.execute(plan, report);
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
