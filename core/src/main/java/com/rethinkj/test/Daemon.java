package com.rethinkj.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The JVM that stays running for a module and runs its tests when {@link Main} asks.
 *
 * <p>It is started with the class path of the module's libraries and the options of its test JVM.
 * It reads one request per connection, a line of tab-separated words ({@code run}, the time of the
 * export it was started for, then the selectors), and answers with the report's lines and a last
 * line {@code exit <code>}, or with {@code restart} when the export changed. It ends after a
 * time without requests, and when its runs leak so that it should be replaced.
 */
public final class Daemon {

  /** How long it waits for a request before it ends. */
  static final long IDLE_MINUTES = 30;

  private Daemon() {}

  /**
   * @param args the export file and the socket, or the export file, {@code --once} and the
   *     selectors for a single run in this JVM
   */
  public static void main(String[] args) throws IOException {
    var build = Build.read(Path.of(args[0]));
    var runner = new WarmRunner(build, () -> Maven.copyResources(build.workdir()));
    if (args[1].equals("--once")) {
      var out = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
      int code = run(runner, Arrays.asList(args).subList(2, args.length), out);
      out.flush();
      System.exit(code);
    }
    serve(build, runner, Path.of(args[1]));
  }

  private static void serve(Build build, WarmRunner runner, Path socket) throws IOException {
    var stamp = Build.stamp(build.file());
    var lastUsed = new AtomicLong(System.nanoTime());
    Thread.ofVirtual().name("rethinkj-test idle").start(() -> {
      while (true) {
        try {
          Thread.sleep(TimeUnit.MINUTES.toMillis(1));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        if (System.nanoTime() - lastUsed.get() > TimeUnit.MINUTES.toNanos(IDLE_MINUTES)) {
          System.exit(0);
        }
      }
    });
    // A socket left by a daemon that ended; not deleted on exit, where it may be the next one's.
    Files.deleteIfExists(socket);
    try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(socket));
      while (true) {
        try (var channel = server.accept()) {
          var in = new BufferedReader(Channels.newReader(channel, StandardCharsets.UTF_8));
          var out = Channels.newWriter(channel, StandardCharsets.UTF_8);
          var line = in.readLine();
          if (line == null) {
            continue;
          }
          var words = List.of(line.split("\t", -1));
          if (words.size() < 2 || !words.get(0).equals("run")) {
            continue;
          }
          if (!words.get(1).equals(stamp)) {
            out.write("restart\n");
            out.flush();
            return;
          }
          int code = run(
              runner,
              words.subList(2, words.size()).stream()
                  .filter(word -> !word.isEmpty())
                  .toList(),
              out);
          out.write("exit " + code + "\n");
          out.flush();
        } catch (IOException e) {
          // The client went away during the run; the next one connects anew.
        }
        lastUsed.set(System.nanoTime());
        if (runner.worn()) {
          System.out.println("Replaced after " + runner.leaked() + " leaked class loaders");
          return;
        }
      }
    }
  }

  private static int run(WarmRunner runner, List<String> selectors, Writer out) throws IOException {
    try {
      return runner.run(selectors, out);
    } catch (RuntimeException | IOException | Error e) {
      var trace = new StringWriter();
      e.printStackTrace(new PrintWriter(trace));
      out.write(new Json()
              .field("v", AgentReport.VERSION)
              .field("event", "error")
              .field("message", String.valueOf(e.getMessage()))
              .field("trace", trace.toString())
          + "\n");
      out.flush();
      return 2;
    }
  }
}
