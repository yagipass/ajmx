package io.github.yagipass.ajmx.error;

public enum Execution {
    NOT_EXECUTED, EXECUTED, UNKNOWN;

    Object toJson() {
        return switch (this) {
            case NOT_EXECUTED -> null;
            case EXECUTED -> true;
            case UNKNOWN -> "unknown";
        };
    }
}
