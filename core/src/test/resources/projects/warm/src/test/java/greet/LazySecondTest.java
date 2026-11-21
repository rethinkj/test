package greet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Runs after LazyFirstTest, so it reads what that one computed. */
class LazySecondTest {

  @Test
  void readsTheCache() {
    assertEquals(1, Lazy.get());
  }
}
