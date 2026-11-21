package com.rethinkj.test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
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

  public static void main(String[] args) {
    try {
      System.exit(run(List.of(args)));
    } catch (Exception e) {
      System.out.println(new Json()
          .field("v", AgentReport.VERSION)
          .field("event", "error")
          .field("message", String.valueOf(e.getMessage())));
      System.exit(2);
    }
  }

  static int run(List<String> args) throws Exception {
    var fresh = args.contains("--fresh");
    var selectors = args.stream().filter(arg -> !arg.equals("--fresh")).toList();
    var workdir = Path.of("").toAbsolutePath();
    var export = workdir.resolve(Build.EXPORT);
    if (!Files.exists(export) || Build.read(export).stale()) {
      Maven.export(workdir, version());
    }
    // Read again when an export ran meanwhile, so that the stamp names the build that was read.
    Build build;
    FileTime written;
    do {
      written = Files.getLastModifiedTime(export);
      build = Build.read(export);
    } while (!written.equals(Files.getLastModifiedTime(export)));
    var stamp = build.stamp();
    if (fresh) {
      var once = new ArrayList<>(List.of("--once"));
      once.addAll(selectors);
      var process =
          jvm(build, once).redirectError(ProcessBuilder.Redirect.INHERIT).start();
      var in = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      // Read to the end, so that output after the result cannot block the JVM as it ends.
      Integer code = null;
      for (var line = in.readLine(); line != null; line = in.readLine()) {
        if (code == null && line.startsWith("exit ")) {
          code = Integer.parseInt(line.substring("exit ".length()));
        } else {
          System.out.println(line);
        }
      }
      int ended = process.waitFor();
      if (code == null) {
        // A test ended the JVM, which Surefire counts as a failure too.
        throw new IllegalStateException(
            "The test JVM ended with " + ended + " before the run was complete");
      }
      return code;
    }
    var socket = socket(workdir);
    var request = "run\t" + stamp + "\t" + String.join("\t", selectors) + "\n";
    for (int attempt = 0; attempt < 2; attempt++) {
      var running = connect(socket);
      var started = running == null ? start(build, socket, stamp) : null;
      try (var channel = running != null ? running : await(socket, started)) {
        var code = talk(channel, request);
        if (code >= 0) {
          return code;
        }
      }
    }
    throw new IllegalStateException(
        "Cannot reach the daemon of " + workdir + "; its log is target/rethinkj-test/daemon.log");
  }

  /**
   * Sends the request and prints the answer; the exit code, or -1 when it asks for a restart or
   * goes away before it answers, as a daemon that is being replaced does.
   */
  private static int talk(SocketChannel channel, String request) throws IOException {
    boolean answered = false;
    try {
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
        answered = true;
        System.out.println(line);
      }
    } catch (IOException e) {
      if (answered) {
        throw e;
      }
      return -1;
    }
    if (!answered) {
      return -1;
    }
    throw new IOException(
        "The daemon ended during the run; its log is target/rethinkj-test/daemon.log");
  }

  // shortcut: two clients that start a daemon at once start two; the second's socket wins and the
  // first idles out. Add a lock file when that bites.
  private static Process start(Build build, Path socket, String stamp) throws IOException {
    var log = build.workdir().resolve("target/rethinkj-test/daemon.log");
    Files.createDirectories(log.getParent());
    return jvm(build, List.of(socket.toString(), stamp))
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
    if (build.assertions()) {
      command.add("-ea");
    }
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

  /** The socket of a module's daemon, in a folder of the user's own. */
  static Path socket(Path workdir) throws IOException {
    return folder().resolve(Integer.toHexString(workdir.toString().hashCode()) + ".sock");
  }

  /**
   * A folder that only this user can access, with a short path, as sockets need: in the user's
   * runtime folder, or else in the temporary folder. Anyone who could create a socket there could
   * answer in the daemon's place.
   */
  static Path folder() throws IOException {
    var user = System.getProperty("user.name");
    var runtime = System.getenv("XDG_RUNTIME_DIR");
    var folder = runtime != null && !runtime.isBlank()
        ? Path.of(runtime, "rethinkj-test")
        : Path.of(System.getProperty("java.io.tmpdir"), "rethinkj-test-" + user);
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      // Windows: the temporary folder is in the user's profile, which only they can access.
      return Files.createDirectories(folder);
    }
    var ownerOnly = PosixFilePermissions.fromString("rwx------");
    try {
      Files.createDirectory(folder, PosixFilePermissions.asFileAttribute(ownerOnly));
    } catch (FileAlreadyExistsException e) {
      // Checked below, like a folder created now.
    }
    var attributes =
        Files.readAttributes(folder, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isDirectory()
        || !attributes.owner().getName().equals(user)
        || !attributes.permissions().equals(ownerOnly)) {
      throw new IOException(folder + " has to be a folder that only " + user + " can access");
    }
    return folder;
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

  private static SocketChannel await(Path socket, Process daemon) throws InterruptedException {
    var deadline = System.currentTimeMillis() + START_MILLIS;
    while (System.currentTimeMillis() < deadline) {
      var channel = connect(socket);
      if (channel != null) {
        return channel;
      }
      if (daemon != null && !daemon.isAlive()) {
        throw new IllegalStateException("The daemon ended with " + daemon.exitValue()
            + " as it started; its log is target/rethinkj-test/daemon.log");
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
