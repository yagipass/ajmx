package io.github.yagipass.ajmx.cli;

import java.util.List;

import io.github.yagipass.ajmx.core.Request;
import io.github.yagipass.ajmx.core.Token;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;

enum Command implements Token {
    PS(0, 0, "", "List local JVMs (pid, mainClass, displayName)"),

    PING(0, 0, "", "Check the JMX connection"),

    SEARCH(0, 1, "[pattern]",
            "List MBean ObjectNames matching a pattern (default " + Request.DEFAULT_SEARCH_PATTERN + ")"),

    DESCRIBE(1, 1, "<mbean>", "Show attributes and operations of an MBean"),

    READ(2, Integer.MAX_VALUE, "<mbean> <attribute>...", "Read one or more attributes"),

    WRITE(2, 2, "<mbean> <attribute>=<value>", "Write an attribute"),

    INVOKE(2, 2, "<mbean> <operation>", "Invoke an operation"),

    BATCH(0, 0, "", "Run requests from stdin, one {\"id\", \"op\", ...} per line, over one connection"),

    HELP(0, Integer.MAX_VALUE, "", "Print this usage"),

    VERSION(0, 0, "", "Print the ajmx version");

    private final int minArguments;
    private final int maxArguments;
    private final String argumentSyntax;
    private final String description;

    Command(int minArguments, int maxArguments, String argumentSyntax, String description) {
        this.minArguments = minArguments;
        this.maxArguments = maxArguments;
        this.argumentSyntax = argumentSyntax;
        this.description = description;
    }

    String description() {
        return description;
    }

    String usage() {
        return argumentSyntax.isEmpty() ? token() : token() + " " + argumentSyntax;
    }

    void requireArgumentCount(int count) {
        if (count < minArguments || count > maxArguments) {
            throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Wrong number of arguments")
                    .with("command", token()).with("argumentCount", count);
        }
    }

    static List<String> tokens() {
        return Token.tokens(values());
    }
}
