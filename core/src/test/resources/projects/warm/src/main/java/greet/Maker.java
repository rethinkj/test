package greet;

/** Between Lazy and Source, so that Lazy does not name Source. */
class Maker {

  static Integer make() {
    return Source.value();
  }
}
