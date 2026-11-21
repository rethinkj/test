package greet;

public class Lazy {

  private static Integer value;

  /** Computed by whichever test class asks first, then kept. */
  public static Integer get() {
    if (value == null) {
      value = Maker.make();
    }
    return value;
  }
}
