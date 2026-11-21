package com.rethinkj.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HookTest {

  @Test
  void findsTheModuleOfTheFileTheToolEdited(@TempDir Path folder) throws IOException {
    var module = Files.createDirectories(folder.resolve("shop core"));
    Files.writeString(module.resolve("pom.xml"), "<project/>");
    var source = module.resolve("src/main/java/shop/Cart.java");
    var input = input(source);

    assertEquals(source, Hook.file(input));
    assertEquals(module, Hook.module(source));
    // Outside the sources, as a build output or the README, nothing runs.
    assertNull(Hook.module(module.resolve("target/classes/shop/Cart.class")));
    assertNull(Hook.module(module.resolve("README.md")));
    assertNull(Hook.file("{\"tool_name\":\"Bash\",\"tool_input\":{\"command\":\"ls\"}}"));
    assertEquals("", Hook.answer(input(module.resolve("README.md"))));
  }

  /** The input of an Edit, with the path as JSON escapes it, also one of Windows. */
  private static String input(Path file) {
    return "{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\""
        + file.toString().replace("\\", "\\\\")
        + "\",\"old_string\":\"a \\\"b\\\"\"}}";
  }
}
