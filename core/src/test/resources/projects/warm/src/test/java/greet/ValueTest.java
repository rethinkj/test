package greet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Runs after InitTest, so Settings is already initialized when it reads it. */
class ValueTest {

  @Test
  void readsTheSetting() {
    assertEquals(1, Settings.VALUE);
  }
}
