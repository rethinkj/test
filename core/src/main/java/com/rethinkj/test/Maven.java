package com.rethinkj.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs Maven in a module, for what only Maven can do: the export and copying resources. */
final class Maven {

  private Maven() {}

  /** Runs Maven with the arguments given, its output into the runner's log of the module. */
  static void run(Path workdir, String... arguments) {
    var windows = System.getProperty("os.name").startsWith("Windows");
    var command = new ArrayList<>(List.of(windows ? "mvn.cmd" : "mvn", "-B", "-q"));
    command.addAll(List.of(arguments));
    var log = workdir.resolve("target/rethinkj-test/maven.log");
    try {
      Files.createDirectories(log.getParent());
      var process = new ProcessBuilder(command)
          .directory(workdir.toFile())
          .redirectErrorStream(true)
          .redirectOutput(log.toFile())
          .start();
      int exit = process.waitFor();
      if (exit != 0) {
        throw new IllegalStateException(String.join(" ", command) + " failed with " + exit
            + "; its output is in " + workdir.relativize(log));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot run " + command.getFirst(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while running Maven", e);
    }
  }

  /** Copies the module's main and test resources into its class folders, filtered as Maven does. */
  static void copyResources(Path workdir) {
    run(workdir, "-o", "resources:resources", "resources:testResources");
  }

  /** Writes {@link Build#EXPORT} with this version of the Maven plugin. */
  static void export(Path workdir, String version) {
    run(workdir, "com.rethinkj.test:rethinkj-test-maven-plugin:" + version + ":export");
  }
}
