package greet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GreeterTest {

  @Test
  void greetsByName() {
    assertEquals(Greeter.GREETING + ", Ada", new Greeter().greet("Ada"));
  }

  /** Passes in a fresh JVM only, which every warm run has to look like. */
  @Test
  void ownsItsProperty() {
    assertNull(System.getProperty("greet.seen"));
    System.setProperty("greet.seen", "yes");
  }

  @Test
  void shouts() {
    new Greeter.Shout();
  }
}
