package greet;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

/** Run by Failsafe, not by Surefire, so not by a run of all tests either. */
class GreeterIT {

  @Test
  void runsOnlyInFailsafe() {
    fail("Surefire does not run *IT classes");
  }
}
