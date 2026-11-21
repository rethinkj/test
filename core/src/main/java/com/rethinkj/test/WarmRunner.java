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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.platform.engine.DiscoverySelector;
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
    var loader = new URLClassLoader(
        "run-" + ++runs,
        new URL[] {url(build.classes()), url(build.testClasses())},
        WarmRunner.class.getClassLoader());
    // A thread of its own, so that thread locals of the run do not keep its classes alive.
    var thread = Thread.ofPlatform().name("rethinkj-test run " + runs).unstarted(() -> {
      try {
        var request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors(selectors, loader))
            .configurationParameter("junit.platform.output.capture.stdout", "true")
            .configurationParameter("junit.platform.output.capture.stderr", "true")
            .build();
        LauncherFactory.create().execute(request, report);
      } catch (Throwable e) {
        failure.set(e);
      }
    });
    thread.setContextClassLoader(loader);
    thread.start();
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted", e);
    } finally {
      loader.close();
    }
    if (failure.get() != null) {
      throw new IOException("The run failed", failure.get());
    }
    loaders.add(new WeakReference<>(loader));
    return report.failed() > 0 ? 1 : 0;
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
        .map(name -> {
          if (name.contains("#")) {
            return selectMethod(loader, name);
          }
          var simple = name.substring(name.lastIndexOf('.') + 1);
          return simple.isEmpty() || Character.isLowerCase(simple.charAt(0))
              ? selectPackage(name)
              : selectClass(loader, name);
        })
        .toArray(DiscoverySelector[]::new);
  }

  private static URL url(java.nio.file.Path folder) {
    try {
      return folder.toUri().toURL();
    } catch (MalformedURLException e) {
      throw new UncheckedIOException(e);
    }
  }
}
