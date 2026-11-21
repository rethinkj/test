package greet;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LazyFirstTest {

  @Test
  void fillsTheCache() {
    assertTrue(Lazy.get() > 0);
  }
}
