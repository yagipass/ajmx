package io.github.yagipass.ajmx.error;

import org.jspecify.annotations.Nullable;

public enum Execution {
    NOT_EXECUTED, EXECUTED, UNKNOWN;

    @Nullable
    Object toJson() {
        return switch (this) {
            case NOT_EXECUTED -> null;
            case EXECUTED -> true;
            case UNKNOWN -> "unknown";
        };
    }
}
