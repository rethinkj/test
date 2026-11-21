package greet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreeterTest {

  @Test
  void greetsByName() {
    assertEquals(Greeter.GREETING + ", Ada", new Greeter().greet("Ada"));
  }
}
