package io.github.yagipass.ajmx.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class Version {
  private static final String RESOURCE = "version.txt";

  private Version() {}

  static Map<String, Object> json() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", read());
    return result;
  }

  private static String read() {
    try (InputStream in = Version.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
