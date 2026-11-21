package com.rethinkj.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

class AgentReportTest {

  @Test
  void writesOneLinePerFailureAndTheCounts(@TempDir Path temp) throws Exception {
    var project = Project.compile("report", temp.resolve("classes"));
    var report = temp.resolve("report.jsonl");
    run(project, report);

    var lines = Files.readAllLines(report);
    assertEquals(3, lines.size(), String.join("\n", lines));
    assertContains(
        lines.get(0),
        "{\"v\":1,\"event\":\"failed\",\"test\":\"shop.CartTest#checkoutNeedsPayment\"",
        "\"at\":\"shop/Cart.java:" + project.line("src/main/java/shop/Cart.java", "// throws here")
            + "\"",
        "\"error\":\"java.lang.IllegalStateException\",\"message\":\"no payment method\"",
        "\"stack\":[\"shop.Cart.checkout(Cart.java:",
        "\"shop.CartTest.checkoutNeedsPayment(CartTest.java:",
        "\"cause\":\"java.lang.NumberFormatException: For input string: \\\"none\\\"\"");
    assertContains(
        lines.get(1),
        "\"test\":\"shop.CartTest#totalAddsPrices\"",
        "\"at\":\"shop/CartTest.java:"
            + project.line("src/test/java/shop/CartTest.java", "// fails here") + "\"",
        "\"error\":\"org.opentest4j.AssertionFailedError\"",
        "\"expected\":\"30\",\"actual\":\"20\"",
        "\"output\":\"adding 10 and 20"
            + System.lineSeparator().replace("\r", "\\r").replace("\n", "\\n")
            + "\"");
    assertTrue(
        lines
            .get(2)
            .matches(
                "\\{\"v\":1,\"event\":\"summary\",\"passed\":1,\"failed\":2,\"aborted\":1,\"skipped\":1,\"ms\":\\d+}"),
        lines.get(2));
  }

  /** Runs the tests of the project with the report on, failures in the order of their names. */
  private static void run(Project project, Path report) throws Exception {
    var thread = Thread.currentThread();
    var previous = thread.getContextClassLoader();
    try (var loader = project.loader()) {
      thread.setContextClassLoader(loader);
      var request = LauncherDiscoveryRequestBuilder.request()
          .selectors(selectClass(loader.loadClass("shop.CartTest")))
          .configurationParameter(AgentReport.FILE, report.toString())
          .configurationParameter("junit.platform.output.capture.stdout", "true")
          .configurationParameter(
              "junit.jupiter.testmethod.order.default",
              "org.junit.jupiter.api.MethodOrderer$MethodName")
          .build();
      LauncherFactory.create().execute(request);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  private static void assertContains(String line, String... parts) {
    for (var part : List.of(parts)) {
      assertTrue(line.contains(part), "no " + part + " in " + line);
    }
  }
}
