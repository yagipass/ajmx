package io.github.yagipass.ajmx.connection;

public sealed interface Target {
    record Local(long pid) implements Target {
    }

    record Remote(String url, Credentials credentials) implements Target {
    }
}
