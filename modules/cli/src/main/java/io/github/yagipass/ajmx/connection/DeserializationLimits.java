package io.github.yagipass.ajmx.connection;

import java.io.ObjectInputFilter;

public final class DeserializationLimits {
  static final int MAX_DEPTH = 500;
  static final int MAX_ARRAY_LENGTH = 16_777_216;

  private DeserializationLimits() {}

  public static void install() {
    ObjectInputFilter.Config.setSerialFilter(
        ObjectInputFilter.Config.createFilter(
            "maxdepth=" + MAX_DEPTH + ";maxarray=" + MAX_ARRAY_LENGTH));
  }
}
