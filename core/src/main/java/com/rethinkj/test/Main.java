package com.rethinkj.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the tests of the module in the working directory, in a JVM that stays running between
 * runs: {@code java -jar rethinkj-test-core.jar [--fresh] [selectors]}.
 *
 * <p>Selectors are classes ({@code a.BTest}), methods ({@code a.BTest#c}) or packages ({@code
 * a}); without one, all tests run. It prints the report's lines and exits with 0 when every test
 * passed, 1 when one failed and 2 when the code did not compile or the run could not start. With
 * {@code --fresh} it runs once in a new JVM.
 */
public final class Main {

  /** How long it waits for a new daemon to listen. */
  static final long START_MILLIS = 60_000;

  private Main() {}

  public static void main(String[] args) throws Exception {
    System.exit(run(List.of(args)));
  }

  static int run(List<String> args) throws Exception {
    var fresh = args.contains("--fresh");
    var selectors = args.stream().filter(arg -> !arg.startsWith("--")).toList();
    var workdir = Path.of("").toAbsolutePath();
    var export = workdir.resolve(Build.FILE);
    if (!Files.exists(export) || Build.read(export).stale()) {
      Maven.export(workdir, version());
    }
    var build = Build.read(export);
    if (fresh) {
      var once = new ArrayList<>(List.of("--once"));
      once.addAll(selectors);
      return jvm(build, once).inheritIO().start().waitFor();
    }
    var socket = socket(workdir);
    var request = "run\t" + Build.stamp(export) + "\t" + String.join("\t", selectors) + "\n";
    for (int attempt = 0; attempt < 2; attempt++) {
      var running = connect(socket);
      if (running == null) {
        start(build, socket);
      }
      try (var channel = running != null ? running : await(socket)) {
        var code = talk(channel, request);
        if (code >= 0) {
          return code;
        }
      }
    }
    throw new IllegalStateException("The daemon of " + workdir + " keeps asking for a restart");
  }

  /** Sends the request and prints the answer; the exit code, or -1 when it asks for a restart. */
  private static int talk(SocketChannel channel, String request) throws IOException {
    var out = Channels.newWriter(channel, StandardCharsets.UTF_8);
    out.write(request);
    out.flush();
    var in = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
    for (var line = in.readLine(); line != null; line = in.readLine()) {
      if (line.equals("restart")) {
        return -1;
      }
      if (line.startsWith("exit ")) {
        return Integer.parseInt(line.substring("exit ".length()));
      }
      System.out.println(line);
    }
    throw new IOException(
        "The daemon ended during the run; its log is target/rethinkj-test/daemon.log");
  }

  // shortcut: two clients that start a daemon at once start two; the second's socket wins and the
  // first idles out. Add a lock file when that bites.
  private static void start(Build build, Path socket) throws IOException {
    var log = build.workdir().resolve("target/rethinkj-test/daemon.log");
    Files.createDirectories(log.getParent());
    jvm(build, List.of(socket.toString()))
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
        .start();
  }

  /** A JVM like the test JVM Maven would start, running the daemon of this module. */
  static ProcessBuilder jvm(Build build, List<String> args) {
    var java = ProcessHandle.current()
        .info()
        .command()
        .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    var command = new ArrayList<>(List.of(java));
    command.addAll(build.jvmArgs());
    build.systemProperties().forEach((name, value) -> command.add("-D" + name + "=" + value));
    var classPath = new ArrayList<String>();
    classPath.add(self().toString());
    classPath.add(build.launcher().toString());
    build.libraries().forEach(library -> classPath.add(library.toString()));
    command.addAll(List.of("-cp", String.join(File.pathSeparator, classPath)));
    // A name, not Daemon.class: the client runs without JUnit, which the daemon's classes need.
    command.add("com.rethinkj.test.Daemon");
    command.add(build.file().toString());
    command.addAll(args);
    return new ProcessBuilder(command).directory(build.workdir().toFile());
  }

  /** The socket of a module's daemon, in the temporary folder: socket paths are short. */
  static Path socket(Path workdir) {
    var name = "rethinkj-test-" + Integer.toHexString(workdir.toString().hashCode()) + ".sock";
    return Path.of(System.getProperty("java.io.tmpdir"), name);
  }

  private static SocketChannel connect(Path socket) {
    if (!Files.exists(socket)) {
      return null;
    }
    try {
      return SocketChannel.open(UnixDomainSocketAddress.of(socket));
    } catch (IOException e) {
      return null;
    }
  }

  private static SocketChannel await(Path socket) throws InterruptedException {
    var deadline = System.currentTimeMillis() + START_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      var channel = connect(socket);
      if (channel != null) {
        return channel;
      }
      Thread.sleep(20);
    }
    throw new IllegalStateException(
        "The daemon did not start; its log is target/rethinkj-test/daemon.log");
  }

  /** This library's JAR, or its class folder when it runs from one. */
  private static Path self() {
    try {
      return Path.of(
          Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String version() {
    var version = Main.class.getPackage().getImplementationVersion();
    if (version == null) {
      throw new IllegalStateException(
          "Run from the JAR, which knows its version, or run the export with Maven first");
    }
    return version;
  }
}
