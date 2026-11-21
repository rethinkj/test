package com.rethinkj.test;

import java.util.List;

/** One JSON object on one line, written field by field. */
final class Json {

  private final StringBuilder text = new StringBuilder("{");

  Json field(String name, long value) {
    name(name).append(value);
    return this;
  }

  Json field(String name, String value) {
    name(name);
    quote(value);
    return this;
  }

  Json field(String name, List<String> values) {
    name(name).append('[');
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) {
        text.append(',');
      }
      quote(values.get(i));
    }
    text.append(']');
    return this;
  }

  @Override
  public String toString() {
    return text + "}";
  }

  private StringBuilder name(String name) {
    if (text.length() > 1) {
      text.append(',');
    }
    quote(name);
    return text.append(':');
  }

  private void quote(String value) {
    text.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> text.append("\\\"");
        case '\\' -> text.append("\\\\");
        case '\n' -> text.append("\\n");
        case '\r' -> text.append("\\r");
        case '\t' -> text.append("\\t");
        default -> {
          if (c < 0x20) {
            text.append(String.format("\\u%04x", (int) c));
          } else {
            text.append(c);
          }
        }
      }
    }
    text.append('"');
  }
}
