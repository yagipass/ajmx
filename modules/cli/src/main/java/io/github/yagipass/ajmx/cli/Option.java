package io.github.yagipass.ajmx.cli;

import java.util.Optional;

import io.github.yagipass.ajmx.core.Token;

enum Option implements Token {
    PID("<pid>", "Local JVM (Attach API)"),

    URL("<jmx-service-url>", "Remote JVM (JSR-160); credentials from JMX_USERNAME/JMX_PASSWORD"),

    CREDENTIALS_STDIN("", "Read {\"username\", \"password\"} from stdin (with --url)"),

    TIMEOUT("<duration>", "Attach, connect and per-operation timeout (default " + Options.DEFAULT_TIMEOUT + ")"),

    LIMIT("<n>", "Max list items and collection elements (default " + Options.DEFAULT_LIMIT + ")"),

    MAX_BYTES("<n>", "Max stdout bytes (default " + Options.DEFAULT_MAX_BYTES + ")"),

    ARGS("<json-array>", "invoke arguments"),

    SIGNATURE("<type,...>", "invoke parameter types, to pick an overload"),

    DEBUG("", "Print stack traces to stderr"),

    HELP("", "Print this usage"),

    VERSION("", "Print the ajmx version");

    private static final String PREFIX = "--";

    private final String valueSyntax;
    private final String description;

    Option(String valueSyntax, String description) {
        this.valueSyntax = valueSyntax;
        this.description = description;
    }

    String description() {
        return description;
    }

    String flag() {
        return PREFIX + token();
    }

    boolean takesValue() {
        return !valueSyntax.isEmpty();
    }

    String usage() {
        return takesValue() ? flag() + " " + valueSyntax : flag();
    }

    static Optional<Option> find(String flag) {
        return flag.startsWith(PREFIX) ? Token.find(values(), flag.substring(PREFIX.length())) : Optional.empty();
    }
}
