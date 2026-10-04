package io.github.yagipass.ajmx.connection;

import org.jspecify.annotations.Nullable;

public sealed interface Target {
    record Local(long pid) implements Target {
    }

    record Remote(String url, @Nullable Credentials credentials) implements Target {
    }
}
