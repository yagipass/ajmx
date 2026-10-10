package io.github.yagipass.ajmx.core;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public interface Token {
  String name();

  default String token() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  static <T extends Token> Optional<T> find(T[] values, String token) {
    return Arrays.stream(values).filter(v -> v.token().equals(token)).findFirst();
  }

  static List<String> tokens(Token[] values) {
    return Arrays.stream(values).map(Token::token).toList();
  }
}
