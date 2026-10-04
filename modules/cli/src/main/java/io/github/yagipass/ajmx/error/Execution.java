package io.github.yagipass.ajmx.error;

import java.util.Optional;

public enum Execution {
    NOT_EXECUTED, EXECUTED, UNKNOWN;

    Optional<Object> toJson() {
        return switch (this) {
            case NOT_EXECUTED -> Optional.empty();
            case EXECUTED -> Optional.of(true);
            case UNKNOWN -> Optional.of("unknown");
        };
    }
}
