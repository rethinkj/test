package greet;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InitTest {

  @Test
  void initializesTheSettings() {
    assertTrue(Settings.VALUE > 0);
  }
}
