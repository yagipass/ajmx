package io.github.yagipass.ajmx.core;

import java.util.List;

public enum Op implements Token {
  PING(false),

  SEARCH(false, "pattern"),

  DESCRIBE(false, "mbean"),

  READ(false, "mbean", "attributes"),

  WRITE(true, "mbean", "attribute", "value"),

  INVOKE(true, "mbean", "operation", "args", "signature");

  private final boolean mutating;

  @SuppressWarnings("ImmutableEnumChecker")
  private final List<String> fields;

  Op(boolean mutating, String... fields) {
    this.mutating = mutating;
    this.fields = List.of(fields);
  }

  public boolean mutating() {
    return mutating;
  }

  List<String> fields() {
    return fields;
  }
}
