package io.github.yagipass.ajmx.cli;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.github.yagipass.ajmx.concurrent.Timeouts;
import io.github.yagipass.ajmx.connection.Credentials;
import io.github.yagipass.ajmx.error.AjmxException;
import io.github.yagipass.ajmx.error.ErrorCode;
import io.github.yagipass.ajmx.json.Json;

final class Inputs {
    private Inputs() {
    }

    static String readStdin(InputStream in, long timeoutMs) {
        return Timeouts.call(() -> new String(in.readAllBytes(), StandardCharsets.UTF_8), timeoutMs,
                () -> new AjmxException(ErrorCode.INVALID_ARGUMENT, "Timed out waiting for input on stdin"),
                Inputs::stdinFailure);
    }

    private static AjmxException stdinFailure(Throwable t) {
        if (t instanceof AjmxException e) {
            return e;
        }
        if (t instanceof Exception) {
            return new AjmxException(ErrorCode.INVALID_ARGUMENT, "Unable to read stdin", t);
        }
        return AjmxException.wrap(t);
    }

    static @Nullable Object parseJson(String text, String source) {
        try {
            return Json.parse(text);
        } catch (Json.ParseException e) {
            throw new AjmxException(ErrorCode.INVALID_ARGUMENT, "Invalid JSON")
                    .with("source", source).with("reason", e.getMessage());
        }
    }

    static Credentials readCredentialsFromStdin(InputStream in, long timeoutMs) {
        Object doc = parseJson(readStdin(in, timeoutMs), "stdin");
        if (doc instanceof Map<?, ?> m && m.get("username") instanceof String username) {
            Object password = m.get("password");
            if (password == null || password instanceof String) {
                return new Credentials(username, Objects.toString(password, ""));
            }
        }
        throw new AjmxException(ErrorCode.INVALID_ARGUMENT,
                "Credentials on stdin must be {\"username\": \"...\", \"password\": \"...\"}");
    }

    static @Nullable Credentials readCredentialsFromEnv(Map<String, String> env) {
        String username = env.get("JMX_USERNAME");
        if (username == null || username.isEmpty()) {
            return null;
        }
        return new Credentials(username, env.getOrDefault("JMX_PASSWORD", ""));
    }
}
