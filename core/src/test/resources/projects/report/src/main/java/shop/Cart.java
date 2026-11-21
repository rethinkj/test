package shop;

import java.util.ArrayList;
import java.util.List;

public class Cart {

  private final List<Integer> prices = new ArrayList<>();

  public Cart add(int price) {
    prices.add(price);
    return this;
  }

  public int total() {
    return prices.isEmpty() ? 0 : prices.getFirst() * prices.size();
  }

  public void checkout() {
    try {
      Integer.parseInt("none");
    } catch (NumberFormatException e) {
      throw new IllegalStateException("no payment method", e); // throws here
    }
  }
}
