package greet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CounterTest {

  @Test
  void countsFromOne() {
    assertEquals(1, new Counter().next());
  }
}
