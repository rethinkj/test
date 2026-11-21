package com.rethinkj.test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Runs the tests an edit can affect, as a {@code PostToolUse} hook of Claude Code: {@code java -jar
 * rethinkj-test-core.jar --hook}, after the tools that edit files.
 *
 * <p>It reads the hook's input, finds the Maven module of the file edited, and runs there what a
 * run without selectors runs. When the tests pass, or the file is in no module's {@code src}, it
 * says nothing. Otherwise it hands the report to the agent as context of the edit, the way a
 * compile error would come, and never fails the edit itself.
 */
final class Hook {

  private static final Pattern FILE =
      Pattern.compile("\"file_path\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  private Hook() {}

  /** The hook's answer to its input: empty, or the JSON that gives the agent the report. */
  static String answer(String input) {
    var file = file(input);
    var module = file == null ? null : module(file);
    if (module == null) {
      return "";
    }
    var report = new ByteArrayOutputStream();
    var console = System.out;
    int code;
    try {
      System.setOut(new PrintStream(report, true, StandardCharsets.UTF_8));
      code = Main.run(module, List.of());
    } catch (Exception e) {
      code = 2;
      System.out.println(new Json()
          .field("v", AgentReport.VERSION)
          .field("event", "error")
          .field("message", String.valueOf(e.getMessage())));
    } finally {
      System.setOut(console);
    }
    if (code == 0) {
      return "";
    }
    var text = "rethinkj-test ran the tests this edit can affect in " + module
        + ", and they do not pass:\n" + report.toString(StandardCharsets.UTF_8);
    return "{\"hookSpecificOutput\":"
        + new Json().field("hookEventName", "PostToolUse").field("additionalContext", text)
        + "}";
  }

  /** The file the tool edited, from {@code tool_input.file_path}, or null. */
  static Path file(String input) {
    var match = FILE.matcher(input);
    return match.find() ? Path.of(unescape(match.group(1))) : null;
  }

  /** The module whose sources hold the file: its nearest folder with a POM, or null. */
  static Path module(Path file) {
    for (var folder = file.toAbsolutePath().getParent();
        folder != null;
        folder = folder.getParent()) {
      if (Files.isRegularFile(folder.resolve("pom.xml"))) {
        return file.toAbsolutePath().startsWith(folder.resolve("src")) ? folder : null;
      }
    }
    return null;
  }

  /** A JSON string's content as text. */
  private static String unescape(String text) {
    var out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c != '\\' || i + 1 == text.length()) {
        out.append(c);
        continue;
      }
      char next = text.charAt(++i);
      switch (next) {
        case 'n' -> out.append('\n');
        case 't' -> out.append('\t');
        case 'r' -> out.append('\r');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'u' -> {
          out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
          i += 4;
        }
        default -> out.append(next);
      }
    }
    return out.toString();
  }
}
