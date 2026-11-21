package shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class CartTest {

  /** Aborts the class after its tests ran, which must not count them again. */
  @AfterAll
  static void closeShop() {
    assumeTrue(false, "shop closed");
  }

  @Test
  void emptyCartCostsNothing() {
    assertEquals(0, new Cart().total());
  }

  @Test
  void totalAddsPrices() {
    System.out.println("adding 10 and 20");
    assertEquals(30, new Cart().add(10).add(20).total()); // fails here
  }

  @Test
  void checkoutNeedsPayment() {
    new Cart().add(10).checkout();
  }

  @Test
  void onlyOnSundays() {
    assumeTrue(false, "not Sunday");
  }

  @Test
  @Disabled("coupons are not built")
  void couponsLowerTheTotal() {}
}
